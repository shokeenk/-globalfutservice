import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminListing, AdminListingsOverview, AdminServiceListing } from '../../lib/types'

/*
 * Objectives and Tournaments in Service Listings: their cards with an "N Active" count,
 * and the Add / Edit Listing panel -- every field the checkout shows, a real PNG at most
 * 2MB saved with the listing, a preview of the public card, Duplicate as an inactive draft,
 * Delete with a confirmation that recommends deactivating when orders name the listing,
 * and who changed it when.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn(), post: vi.fn(), del: vi.fn(), upload: vi.fn(), baseUrl: '' }))
vi.mock('../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, body: { message: string }) { super(body.message) }
  },
}))

const { default: Listings } = await import('./AdminListings')

function detail(id: number, patch: Partial<AdminServiceListing> = {}): AdminServiceListing {
  return {
    id, sku: 'BOOST_OBJECTIVES', variant: `LISTING_${id}`, title: `Listing ${id}`, subtitle: null, overview: null,
    requirements: null, requiredGames: null, estimatedTime: null, schedulingNote: null, reward: null,
    customerInstructions: null, pricingType: 'FIXED', maxHours: 50, showImage: true, image: null, active: true,
    displayOrder: id, prices: { INR: { minor: 120000, formatted: '₹1,200.00' } }, createdBy: 'Vinay',
    createdAt: '2026-10-13T05:00:00Z', updatedBy: 'Vinay', updatedAt: '2026-10-13T06:00:00Z', changes: [], ...patch,
  }
}

function asListing(d: AdminServiceListing): AdminListing {
  return { sku: d.sku, variant: d.variant, label: d.title, sortOrder: d.displayOrder, active: d.active, prices: d.prices,
    successRateBps: null, successRateSource: null, bestValue: false, detail: d }
}

const CLAIRE = detail(12, {
  title: 'Claire Hutton Objective', subtitle: '84 CDM · Special Card',
  image: { id: 7, url: '/api/v1/catalog/listing-images/7', width: 400, height: 500, sizeBytes: 90000 },
  requirements: 'Win 7 Squad Battles matches\nScore a finesse goal in 3 separate matches', requiredGames: 7,
  estimatedTime: '2–3 hours', prices: { INR: { minor: 120000, formatted: '₹1,200.00' }, USD: { minor: 1450, formatted: '$14.50' } },
})
const SCORE = detail(14, { title: 'Score in 3 Objective', subtitle: '150 SP Reward' })
const GALLERY = detail(20, { title: 'Gallery Tokens', active: false, pricingType: 'PER_HOUR',
  prices: { INR: { minor: 65000, formatted: '₹650.00' } } })

const DATA: AdminListingsOverview = {
  currencies: ['INR', 'USD', 'EUR', 'GBP'],
  categories: [
    { sku: 'BOOST_CHAMPS', name: 'Champs Wins', bestValueChoice: 'DEFAULT', listings: [] },
    { sku: 'BOOST_RIVALS', name: 'Rivals Divisions', bestValueChoice: 'DEFAULT', listings: [] },
    { sku: 'COACHING', name: 'Coaching', listings: [] },
    { sku: 'BOOST_OBJECTIVES', name: 'Objectives', listings: [CLAIRE, SCORE, GALLERY].map(asListing) },
    { sku: 'BOOST_TOURNAMENTS', name: 'Tournaments', listings: [] },
  ],
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/services/listings" element={<Listings />} />
        <Route path="/admin/services/listings/new" element={<Listings />} />
      </Routes>
    </MemoryRouter>,
  )
}

function png(bytes = 64): File {
  const data = new Uint8Array(bytes)
  data.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
  return new File([data], 'card.png', { type: 'image/png' })
}

beforeEach(() => {
  for (const fn of [api.get, api.put, api.post, api.del, api.upload]) fn.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/admin/listings') return DATA
    if (path === '/api/v1/admin/service-listings/12') {
      return { ...CLAIRE, changes: [{ action: 'UPDATED', detail: 'prices ₹1,200.00', by: 'Vinay', at: '2026-10-13T06:00:00Z' }] }
    }
    if (path.endsWith('/references')) return { orders: 3 }
    return {}
  })
})
afterEach(() => vi.restoreAllMocks())

describe('the Objectives category', () => {
  it('lists its cards with artwork, words and price, and counts the active ones', async () => {
    renderAt('/admin/services/listings?service=BOOST_OBJECTIVES')
    expect(await screen.findByRole('button', { name: /Objectives.*3 listings/ })).toBeInTheDocument()
    expect(screen.getByText('2 Active')).toBeInTheDocument()
    const claire = screen.getByRole('button', { name: 'Edit Claire Hutton Objective' })
    expect(claire).toHaveTextContent('84 CDM · Special Card')
    expect(claire).toHaveTextContent('₹1,200.00')
    expect(claire.querySelector('img')).toHaveAttribute('src', '/api/v1/catalog/listing-images/7')
    const gallery = screen.getByRole('button', { name: 'Edit Gallery Tokens (inactive)' })
    expect(gallery).toHaveTextContent('Inactive')
    expect(gallery).toHaveTextContent('Per hour')
    expect(gallery).toHaveTextContent('₹650.00 / hour')
  })
})

describe('Add / Edit Listing', () => {
  it('adds a listing: a blank form, every field, the PNG uploaded with it', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const created = detail(40, { title: 'Homegrown Pride Objective' })
    api.post.mockResolvedValue(created)
    api.upload.mockResolvedValue({ ...created, image: { id: 9, url: '/api/v1/catalog/listing-images/9', width: 400,
      height: 500, sizeBytes: 64 } })
    renderAt('/admin/services/listings/new?service=BOOST_OBJECTIVES')
    const editor = await screen.findByRole('complementary', { name: 'Add listing' })
    expect(within(editor).getByLabelText('Title')).toHaveValue('')
    expect(within(editor).getByText('PNG, transparent background recommended. Recommended size: 400 × 500 px'))
      .toBeInTheDocument()

    fireEvent.change(within(editor).getByLabelText('Title'), { target: { value: 'Homegrown Pride Objective' } })
    fireEvent.change(within(editor).getByLabelText('Subtitle'), { target: { value: '1 of 5 81+ Gold Player Pick' } })
    fireEvent.change(within(editor).getByLabelText('Price in INR'), { target: { value: '850' } })
    fireEvent.change(within(editor).getByLabelText('Price in GBP'), { target: { value: '8.50' } })
    fireEvent.click(within(editor).getByLabelText('Per hour'))
    fireEvent.change(within(editor).getByLabelText('Most hours per order'), { target: { value: '20' } })
    fireEvent.change(within(editor).getByLabelText(/Listing Details/), { target: { value: 'Task one\nTask two' } })
    fireEvent.change(within(editor).getByLabelText('Required games'), { target: { value: '7' } })
    fireEvent.change(within(editor).getByLabelText('Estimated completion time'), { target: { value: '2–3 hours' } })
    fireEvent.change(within(editor).getByLabelText(/^Overview/), { target: { value: 'Our own overview.' } })
    fireEvent.change(within(editor).getByTestId('image-input'), { target: { files: [png()] } })
    await waitFor(() => expect(within(editor).getByAltText('Card image preview')).toHaveAttribute('src',
      expect.stringMatching(/^data:image\/png/)))

    fireEvent.click(within(editor).getByRole('button', { name: 'Save Listing' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/admin/service-listings', expect.objectContaining({
      sku: 'BOOST_OBJECTIVES', title: 'Homegrown Pride Objective', subtitle: '1 of 5 81+ Gold Player Pick',
      pricingType: 'PER_HOUR', maxHours: 20, requirements: 'Task one\nTask two', requiredGames: 7,
      estimatedTime: '2–3 hours', overview: 'Our own overview.', active: true, displayOrder: null,
      prices: { INR: 85000, USD: null, EUR: null, GBP: 850 },
    })))
    await waitFor(() => expect(api.upload).toHaveBeenCalledWith('/api/v1/admin/service-listings/40/image',
      expect.any(FormData)))
    expect(await screen.findByRole('status')).toHaveTextContent('Added Homegrown Pride Objective. It is on the website now.')
  })

  it('refuses a file that is not a PNG, or over 2MB, before anything is sent', async () => {
    renderAt('/admin/services/listings/new?service=BOOST_OBJECTIVES')
    const editor = await screen.findByRole('complementary', { name: 'Add listing' })
    fireEvent.change(within(editor).getByTestId('image-input'), {
      target: { files: [new File(['not a picture'], 'card.png', { type: 'image/png' })] } })
    expect(await within(editor).findByRole('alert')).toHaveTextContent('That file is not a PNG image.')

    fireEvent.change(within(editor).getByTestId('image-input'), { target: { files: [png(2 * 1024 * 1024 + 1)] } })
    expect(await within(editor).findByRole('alert')).toHaveTextContent('larger than 2MB')
    expect(api.upload).not.toHaveBeenCalled()
  })

  it('will not make a listing active with no price', async () => {
    renderAt('/admin/services/listings/new?service=BOOST_TOURNAMENTS')
    const editor = await screen.findByRole('complementary', { name: 'Add listing' })
    expect(within(editor).getByLabelText('Listing Category')).toHaveValue('BOOST_TOURNAMENTS')
    fireEvent.change(within(editor).getByLabelText('Title'), { target: { value: 'Weekend Cup' } })
    fireEvent.click(within(editor).getByRole('button', { name: 'Save Listing' }))
    expect(await within(editor).findByRole('alert')).toHaveTextContent('at least one currency')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('previews the public card before saving', async () => {
    renderAt('/admin/services/listings/new?service=BOOST_OBJECTIVES')
    const editor = await screen.findByRole('complementary', { name: 'Add listing' })
    fireEvent.change(within(editor).getByLabelText('Title'), { target: { value: 'Assist 8 Objective' } })
    fireEvent.change(within(editor).getByLabelText('Subtitle'), { target: { value: '150 SP Reward' } })
    fireEvent.change(within(editor).getByLabelText('Price in INR'), { target: { value: '650' } })
    fireEvent.click(within(editor).getByRole('button', { name: 'Preview card' }))
    const preview = within(editor).getByTestId('card-preview')
    expect(preview).toHaveTextContent('Assist 8 Objective')
    expect(preview).toHaveTextContent('150 SP Reward')
    expect(preview).toHaveTextContent('₹650')
    expect(preview).toHaveTextContent('Choose')
  })

  it('offers every category, and Champs Wins opens the Champs form', async () => {
    renderAt('/admin/services/listings/new?service=BOOST_OBJECTIVES')
    const editor = await screen.findByRole('complementary', { name: 'Add listing' })
    const select = within(editor).getByLabelText('Listing Category')
    expect([...select.querySelectorAll('option')].map((o) => o.textContent))
      .toEqual(['Champs Wins', 'Rivals Divisions', 'Objectives', 'Tournaments'])
    fireEvent.change(select, { target: { value: 'BOOST_CHAMPS' } })
    const champs = await screen.findByRole('complementary', { name: 'Add listing' })
    expect(within(champs).getByRole('button', { name: 'Add Listing' })).toBeInTheDocument()
    expect(within(champs).getByLabelText('Category')).toHaveValue('BOOST_CHAMPS')
  })

  it('edits a listing, asking before its price changes, and shows who changed it', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    api.put.mockResolvedValue(CLAIRE)
    renderAt('/admin/services/listings?service=BOOST_OBJECTIVES&listing=LISTING_12')
    const editor = await screen.findByRole('complementary', { name: 'Edit listing' })
    expect(within(editor).getByLabelText('Title')).toHaveValue('Claire Hutton Objective')
    expect(within(editor).getByLabelText(/Listing Details/)).toHaveValue(
      'Win 7 Squad Battles matches\nScore a finesse goal in 3 separate matches')
    expect(await within(editor).findByTestId('listing-history')).toHaveTextContent('added by Vinay')

    fireEvent.change(within(editor).getByLabelText('Price in INR'), { target: { value: '1,250' } })
    fireEvent.click(within(editor).getByRole('switch', { name: 'Show image on listing card' }))
    fireEvent.click(within(editor).getByRole('button', { name: 'Save Listing' }))
    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/api/v1/admin/service-listings/12', expect.objectContaining({
      title: 'Claire Hutton Objective', showImage: false, prices: { INR: 125000, USD: 1450, EUR: null, GBP: null },
    })))
    expect(confirm.mock.calls[0]![0]).toContain('INR: ₹1,200.00 → ₹1250')
    expect(api.upload).not.toHaveBeenCalled()
  })

  it('removing the artwork waits for Save', async () => {
    api.put.mockResolvedValue(CLAIRE)
    api.del.mockResolvedValue({ ...CLAIRE, image: null })
    renderAt('/admin/services/listings?service=BOOST_OBJECTIVES&listing=LISTING_12')
    const editor = await screen.findByRole('complementary', { name: 'Edit listing' })
    fireEvent.click(within(editor).getByRole('button', { name: 'Remove' }))
    expect(api.del).not.toHaveBeenCalled()
    fireEvent.click(within(editor).getByRole('button', { name: 'Save Listing' }))
    await waitFor(() => expect(api.del).toHaveBeenCalledWith('/api/v1/admin/service-listings/12/image'))
  })

  it('duplicates as an inactive draft', async () => {
    api.post.mockResolvedValue(detail(41, { title: 'Copy of Claire Hutton Objective', active: false }))
    renderAt('/admin/services/listings?service=BOOST_OBJECTIVES&listing=LISTING_12')
    const editor = await screen.findByRole('complementary', { name: 'Edit listing' })
    fireEvent.click(within(editor).getByRole('button', { name: 'Duplicate Listing' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/admin/service-listings/12/duplicate'))
    expect(await screen.findByRole('status')).toHaveTextContent(
      'Copied as an inactive draft: Copy of Claire Hutton Objective')
  })

  it('delete asks first, and with orders placed recommends deactivating instead', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderAt('/admin/services/listings?service=BOOST_OBJECTIVES&listing=LISTING_12')
    const editor = await screen.findByRole('complementary', { name: 'Edit listing' })
    fireEvent.click(within(editor).getByRole('button', { name: /Delete Listing/ }))
    await waitFor(() => expect(confirm).toHaveBeenCalled())
    expect(confirm.mock.calls[0]![0]).toContain('3 orders were placed for "Claire Hutton Objective"')
    expect(confirm.mock.calls[0]![0]).toContain('We recommend deactivating it instead')
    expect(confirm.mock.calls[0]![0]).toContain('kept for those orders')
    expect(api.del).not.toHaveBeenCalled()

    confirm.mockReturnValue(true)
    api.del.mockResolvedValue(undefined)
    fireEvent.click(within(editor).getByRole('button', { name: /Delete Listing/ }))
    await waitFor(() => expect(api.del).toHaveBeenCalledWith('/api/v1/admin/service-listings/12'))
    expect(await screen.findByRole('status')).toHaveTextContent('Deleted Claire Hutton Objective.')
  })
})
