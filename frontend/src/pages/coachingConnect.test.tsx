import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

/*
 * The Coaching page's booking panel sends a customer to Connect with Coach on their
 * coaching order, on this site. It used to send them to Discord.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
vi.mock('../state/AuthContext', () => ({
  useAuth: () => ({
    account: { email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER' },
    loading: false,
  }),
}))
vi.mock('../state/CatalogContext', () => ({
  useCatalog: () => ({ catalog: { currency: 'INR', services: [] }, policy: null }),
}))

// jsdom has no matchMedia; the storefront's reveal animations ask it about reduced motion.
window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: Coaching } = await import('./Coaching')

const POLICY = { sessionMinutes: 60, blockSessionMinutes: 40, changeCutoffHours: 24, maxReschedules: 2,
  minLeadTimeHours: 12, creditValidityDays: 90, maxAdvanceDays: 30 }

describe('the Coaching page, for a customer with sessions to book', () => {
  it('points to Connect with Coach on their coaching order, and no longer to Discord', async () => {
    api.get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/coaching/coaches') {
        return [{ id: 'vinay', displayName: 'Vinay', headline: null, bio: null, avatarUrl: null, languages: null,
          credentials: null, timezone: 'Asia/Kolkata' }]
      }
      if (path === '/api/v1/coaching/me') {
        return { creditBalance: 2, creditsExpireAt: null, upcoming: [], policy: POLICY }
      }
      if (path.includes('/slots')) return { coachId: 'vinay', coachTimezone: 'Asia/Kolkata', sessionMinutes: 60, slots: [] }
      return []
    })
    render(<MemoryRouter initialEntries={['/coaching']}><Coaching /></MemoryRouter>)

    const line = await screen.findByTestId('coach-connect')
    expect(line).toHaveTextContent(
      'Need help with your coaching order or session? Connect with your coach and continue the conversation '
      + 'directly from your GFS account. Choose Connect with Coach on your coaching order:')
    expect(within(line).getByRole('link', { name: 'My Orders' })).toHaveAttribute('href', '/track')
    expect(line.textContent).not.toMatch(/discord/i)
    expect(screen.queryByRole('link', { name: /discord/i })).toBeNull()
  })
})
