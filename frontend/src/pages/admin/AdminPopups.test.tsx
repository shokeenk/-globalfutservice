import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminPopup } from './popups/types'

/* The Popup Manager: the list, creating a draft, publishing, images and the preview. */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), upload: vi.fn(), baseUrl: '' }))
vi.mock('../../lib/api', async (original) => ({ ...(await original<typeof import('../../lib/api')>()), api }))

const { default: AdminPopups } = await import('./AdminPopups')
const { inputFrom, formFrom } = await import('./popups/PopupEditor')

const STATS = { views: 40, closes: 10, clicks: 5, views30d: 12, closes30d: 3, clicks30d: 2 }

function popup(over: Partial<AdminPopup> = {}): AdminPopup {
  return {
    id: 'pop_1', name: 'Coins week', type: 'CENTER_MODAL', status: 'DRAFT', enabled: false, version: 3,
    desktopImage: { id: 7, url: '/api/v1/popups/images/7/abc.png', contentType: 'image/png', width: 800, height: 400, sizeBytes: 90_000 },
    mobileImage: null, copy: { en: { headline: '10% off coins', body: 'This week only.' } },
    buttons: [{ label: { en: 'Buy coins' }, destination: '/order' }], targetPages: ['HOME', 'COINS'], targetPaths: ['/help'],
    triggerType: 'PAGE_LOAD', frequency: 'SESSION', priority: 0, live: false,
    createdAt: '2026-10-21T10:00:00Z', updatedAt: '2026-10-21T10:00:00Z', stats: STATS, ...over,
  }
}

function page(path = '/admin/popups') {
  return render(<MemoryRouter initialEntries={[path]}><AdminPopups /></MemoryRouter>)
}

beforeEach(() => {
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/admin/popups') return [popup(), popup({ id: 'pop_2', name: 'Champs card', type: 'BOTTOM_RIGHT', status: 'PUBLISHED', enabled: true, live: true })]
    if (path === '/api/v1/admin/popups/options') return { pages: [], steps: ['coins:continue', 'champs:checkout'] }
    if (path.endsWith('/changes')) return [{ action: 'CREATED', detail: 'Draft, switched off', adminEmail: 'owner@example.test', at: '2026-10-21T10:00:00Z' }]
    throw new Error(path)
  })
  api.post.mockResolvedValue(popup())
  api.put.mockResolvedValue(popup())
})

afterEach(() => vi.clearAllMocks())

describe('the list', () => {
  it('shows each popup with its status, pages, schedule and stats', async () => {
    page()
    const row = await screen.findByTestId('popup-row-pop_1')
    expect(within(row).getByText('Draft')).toBeInTheDocument()
    expect(within(row).getByText('Home, Coins, /help')).toBeInTheDocument()
    expect(within(row).getByText('Always')).toBeInTheDocument()
    expect(within(row).getByText('12')).toBeInTheDocument()
    expect(within(screen.getByTestId('popup-row-pop_2')).getByText('Live')).toBeInTheDocument()
  })

  it('publish, switch on and duplicate are each their own button', async () => {
    const user = userEvent.setup()
    page()
    const row = await screen.findByTestId('popup-row-pop_1')
    await user.click(within(row).getByRole('button', { name: 'Publish' }))
    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/popups/pop_1/publish')
    await user.click(within(row).getByRole('button', { name: 'Switch on' }))
    expect(api.put).toHaveBeenCalledWith('/api/v1/admin/popups/pop_1/enabled', { enabled: true })
    await user.click(within(row).getByRole('button', { name: 'Duplicate' }))
    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/popups/pop_1/duplicate')
    await user.click(within(screen.getByTestId('popup-row-pop_2')).getByRole('button', { name: 'Unpublish' }))
    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/popups/pop_2/unpublish')
  })

  it('says why publishing was refused', async () => {
    const { ApiError } = await import('../../lib/api')
    api.post.mockRejectedValueOnce(new ApiError(400, { error: 'validation_failed', message: 'x',
      details: { desktopImage: ['Upload the desktop image before publishing.'] } }))
    const user = userEvent.setup()
    page()
    await user.click(within(await screen.findByTestId('popup-row-pop_1')).getByRole('button', { name: 'Publish' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Upload the desktop image before publishing.')
  })
})

describe('the editor', () => {
  it('creates a draft from the form', async () => {
    const user = userEvent.setup()
    page('/admin/popups?edit=new')
    await screen.findByTestId('popup-editor')
    expect(screen.getByText('Save the popup first, then add its images.')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Name (only staff see it)'), 'Rewards push')
    await user.type(screen.getByLabelText('Headline'), 'Double points')
    await user.click(screen.getByRole('checkbox', { name: 'Rewards' }))
    await user.click(screen.getByRole('button', { name: 'Create draft' }))
    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/popups', expect.objectContaining({
      name: 'Rewards push', targetPages: ['REWARDS'], copy: expect.objectContaining({ en: expect.objectContaining({ headline: 'Double points' }), es: null }),
    }))
  })

  it('uploads an image as a file, and previews at desktop and phone size', async () => {
    const user = userEvent.setup()
    api.upload.mockResolvedValue(popup())
    page('/admin/popups?edit=pop_1')
    await screen.findByTestId('popup-editor')
    expect(screen.getByAltText('desktop preview')).toHaveAttribute('src', '/api/v1/popups/images/7/abc.png')
    const file = new File([new Uint8Array([0x89, 0x50])], 'banner.png', { type: 'image/png' })
    await user.upload(screen.getByLabelText('Replace desktop image'), file)
    expect(api.upload).toHaveBeenCalledWith('/api/v1/admin/popups/pop_1/images/desktop', expect.any(FormData))

    const preview = screen.getByTestId('popup-preview')
    expect(within(preview).getByText('10% off coins')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Phone' }))
    expect(preview).toHaveAttribute('data-device', 'phone')
    await user.click(screen.getByRole('button', { name: 'ES' }))
    // No Spanish yet: the preview falls back to English.
    expect(within(preview).getByText('10% off coins')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText(/owner@example.test/)).toBeInTheDocument())
  })

  it('offers no button on the guide popups that have none', async () => {
    api.get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/admin/popups') return [popup({ id: 'pop_guide_coins', preset: 'COINS_OFFER', buttons: [] })]
      if (path === '/api/v1/admin/popups/options') return { pages: [], steps: [] }
      return []
    })
    page('/admin/popups?edit=pop_guide_coins')
    await screen.findByTestId('popup-editor')
    expect(screen.getByText('The guide sets this popup out with no button.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add button' })).toBeNull()
  })

  it('keeps "after seconds or scroll" and "never during checkout" through the form', () => {
    const p = popup({ triggerType: 'DELAY_OR_SCROLL', triggerValue: 8, triggerScroll: 40, excludeCheckout: true })
    expect(inputFrom(formFrom(p))).toMatchObject({ triggerType: 'DELAY_OR_SCROLL', triggerValue: 8, triggerScroll: 40,
      excludeCheckout: true })
  })

  it('round-trips a popup through the form unchanged', () => {
    const p = popup({ triggerType: 'DELAY', triggerValue: 5, frequency: 'DAYS', frequencyDays: 7 })
    const back = inputFrom(formFrom(p))
    expect(back).toMatchObject({ name: p.name, type: p.type, targetPages: p.targetPages, targetPaths: p.targetPaths,
      triggerType: 'DELAY', triggerValue: 5, frequency: 'DAYS', frequencyDays: 7, buttons: [{ destination: '/order' }] })
  })
})
