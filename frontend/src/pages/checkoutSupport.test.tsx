import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Order } from '../lib/types'

/*
 * After paying, the boosting and coaching checkouts point to the order's support page on
 * this site. Boosting keeps its Discord ticket as well: it is where the EA login is handed
 * over, as the checkout says before payment. Coaching has no Discord step any more.
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
  useCatalog: () => ({
    catalog: { currency: 'INR', services: [] },
    policy: { onlinePaymentsEnabled: false, customerEmailsEnabled: false },
  }),
}))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))

const { default: BoostingCheckout } = await import('./BoostingCheckout')
const { default: CoachingBook } = await import('./CoachingBook')

function paid(sku: string, publicRef: string): Order {
  return {
    publicRef, status: 'PAID', statusLabel: 'Queued', nextAction: 'NONE', serviceLabel: 'A service', sku,
    platform: 'PLAYSTATION', quantity: 1, currency: 'INR', totalMinor: 100000, totalFormatted: '₹1,000.00',
    lines: [], pointsRedeemed: 0, pointsEarned: 0, createdAt: '2026-10-01T10:00:00Z', timeline: [],
    // A customer whose Discord account we do not know: the confirmation asks them to message us.
    discordAccess: { mode: 'VERIFY', channelUrl: null, inviteUrl: 'https://discord.gg/x' },
  } as unknown as Order
}

beforeEach(() => {
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  api.get.mockReset()
})

describe('after paying', () => {
  it('boosting: the confirmation offers the Discord ticket for the EA login, and the support page', async () => {
    api.get.mockImplementation(async (path: string) =>
      path.startsWith('/api/v1/orders/') ? paid('BOOST_CHAMPS', 'GFS-26-BOOST001') : [])
    render(
      <MemoryRouter initialEntries={['/boosting/checkout?service=BOOST_CHAMPS&order=GFS-26-BOOST001']}>
        <Routes><Route path="/boosting/checkout" element={<BoostingCheckout />} /></Routes>
      </MemoryRouter>,
    )

    const cta = await screen.findByRole('link', { name: 'Connect to booster' })
    expect(cta).toHaveAttribute('href', '/orders/GFS-26-BOOST001/support')
    expect(screen.getByText('Connect with GFS')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'contact support' })).toHaveAttribute('href', '/support')
    // Message us on Discord, with the reference to paste in: the site's own direct-message link.
    expect(screen.getByRole('link', { name: 'Message us on Discord' }))
      .toHaveAttribute('href', 'https://discord.com/users/1300551868174569595')
    expect(screen.getByText('globalfutservices')).toBeInTheDocument()
    expect(screen.getByText('Include your order reference:')).toBeInTheDocument()
    expect(screen.getByText('GFS-26-BOOST001', { selector: 'code' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy order reference GFS-26-BOOST001' })).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/\/verify|run this command/i)
    expect(screen.queryByRole('link', { name: /Join Our Discord/ })).toBeNull()
  })

  it('coaching: the step after the confirmation connects with the coach, and no Discord', async () => {
    api.get.mockImplementation(async (path: string) =>
      path.startsWith('/api/v1/orders/') ? paid('COACHING', 'GFS-26-COACH001') : [])
    render(
      <MemoryRouter initialEntries={['/coaching/book?order=GFS-26-COACH001']}>
        <Routes><Route path="/coaching/book" element={<CoachingBook />} /></Routes>
      </MemoryRouter>,
    )

    // The confirmation first: until the order has loaded, an earlier step's Continue shows.
    expect(await screen.findByText('Payment successful!')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
    expect(screen.getByText('Connect with Your Coach')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Connect with Coach' }))
      .toHaveAttribute('href', '/coaching/GFS-26-COACH001/support')
    expect(document.body.textContent).not.toMatch(/discord|\/verify/i)

    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
    expect(await screen.findByText('You’re all set!')).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/discord/i)
  })
})
