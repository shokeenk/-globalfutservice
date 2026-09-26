import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Campaign } from '../../lib/types'

// ---- the API, mocked: these tests are about the page, not the server ---------------
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), upload: vi.fn(), blobUrl: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {},
}))

// Imported after the mock is declared, so the page picks up the mocked module.
const { default: AdminCampaigns } = await import('./AdminCampaigns')

function finished(publicId: string, status: Campaign['status'], failed: number): Campaign {
  return {
    publicId, title: `Campaign ${publicId}`, subject: 's', heading: 'h', body: 'b',
    promoCode: null, ctaText: null, ctaPath: null, hasBanner: false,
    audience: 'ALL_OPTED_IN', audienceLabel: 'Everyone opted in', status,
    scheduledAt: null, completedAt: '2026-09-26T08:30:00Z', updatedAt: '2026-09-26T08:30:00Z',
    stats: { total: 10, sent: 10 - failed, failed, opened: 0, clicked: 0, unsubscribed: 0 },
  }
}

async function openSentTab(list: Campaign[]) {
  api.get.mockImplementation(async (path: string) => {
    if (path.endsWith('/options')) return { audiences: [], ctas: [] }
    return path.endsWith('status=FINISHED') ? list : []
  })
  render(<MemoryRouter><AdminCampaigns view="history" /></MemoryRouter>)
  await userEvent.click(screen.getByRole('button', { name: 'Sent' }))
}

describe('Campaign History: retrying failed recipients', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.post.mockReset()
  })

  it('offers a retry only where somebody was not reached', async () => {
    await openSentTab([
      finished('camp_partial', 'SENT', 3),
      finished('camp_clean', 'SENT', 0),
      finished('camp_broke', 'FAILED', 0),
    ])

    expect(await screen.findByRole('button', { name: 'Retry 3 failed' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Resume send' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /Retry|Resume/ })).toHaveLength(2)
  })

  it('queues the retry and says it goes out shortly', async () => {
    api.post.mockResolvedValue(finished('camp_partial', 'SCHEDULED', 3))
    await openSentTab([finished('camp_partial', 'SENT', 3)])

    await userEvent.click(await screen.findByRole('button', { name: 'Retry 3 failed' }))

    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/campaigns/camp_partial/retry', {})
    expect(await screen.findByText('Retry queued. It goes out within about a minute.'))
      .toBeInTheDocument()
  })
})
