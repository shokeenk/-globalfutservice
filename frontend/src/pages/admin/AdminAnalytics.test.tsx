import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// ---- the API, mocked: these tests are about the page, not the server ---------------
const api = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {},
}))

// Imported after the mock is declared, so the page picks up the mocked module.
const { default: AdminAnalytics } = await import('./AdminAnalytics')

function renderPage() {
  return render(<MemoryRouter><AdminAnalytics /></MemoryRouter>)
}

describe('Analytics', () => {
  // Braces, not an expression body: a function returned from beforeEach is run as the
  // test's teardown, and mockReset returns the mock -- so api.get would be called once
  // more after every test, and in the failure test that call rejects.
  beforeEach(() => { api.get.mockReset() })

  it('shows the thirty-day revenue as its first card, with the note on what it counts', async () => {
    api.get.mockResolvedValue({ revenueLast30dMinor: 287000, revenueLast30dFormatted: '₹2,870.00' })
    renderPage()

    const card = screen.getByRole('region', { name: 'Revenue, last 30 days' })
    expect(await within(card).findByText('₹2,870.00')).toBeInTheDocument()
    expect(card).toHaveTextContent('Counts delivered and completed orders.')
    expect(card).toHaveTextContent(
      'Orders inside their guarantee window are included — the money is taken, but the '
        + 'loyalty points have not settled yet.')
    expect(api.get).toHaveBeenCalledWith('/api/v1/admin/analytics/revenue')
    // The first card on the page, directly under the header.
    expect(screen.getAllByRole('region')[0]).toBe(card)
  })

  it('says so when the figure cannot be loaded, rather than showing nothing', async () => {
    api.get.mockRejectedValue(new Error('network'))
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the revenue figure.')
  })

  it('is a real page, not the coming-soon placeholder', async () => {
    api.get.mockResolvedValue({ revenueLast30dMinor: 0, revenueLast30dFormatted: '₹0.00' })
    renderPage()

    expect(screen.getByRole('heading', { level: 1, name: 'Analytics' })).toBeInTheDocument()
    expect(screen.queryByText('Coming soon')).toBeNull()
    expect(await screen.findByText('₹0.00')).toBeInTheDocument()
  })
})
