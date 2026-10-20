import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Order } from '../lib/types'

/*
 * After paying, the boosting and coaching checkouts point to the order's live chat on this
 * site, and nowhere else: no Discord, on any checkout or confirmation. Boosting, Objectives
 * and Tournaments ask for the EA login in the encrypted form, right on the confirmation,
 * and say it never goes in the chat.
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

function paid(sku: string, publicRef: string, patch: Partial<Order> = {}): Order {
  return {
    publicRef, status: 'PAID', statusLabel: 'Queued', nextAction: 'NONE', serviceLabel: 'A service', sku,
    platform: 'PLAYSTATION', quantity: 1, currency: 'INR', totalMinor: 100000, totalFormatted: '₹1,000.00',
    lines: [], pointsRedeemed: 0, pointsEarned: 0, createdAt: '2026-10-01T10:00:00Z', timeline: [],
    // What an older API still sends: the page must not act on it.
    discordAccess: { mode: 'VERIFY', channelUrl: null, inviteUrl: 'https://discord.gg/x' },
    ...patch,
  } as unknown as Order
}

/** Nothing on the page takes a customer to Discord: no server invite, no direct message. */
function expectNoDiscord() {
  const links = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href') ?? '')
  expect(links.filter((href) => /discord/i.test(href))).toEqual([])
  expect(document.body.textContent).not.toMatch(/discord|\/verify|run this command/i)
}

function confirmation(sku: string, ref: string, order: Order) {
  api.get.mockImplementation(async (path: string) => (path.startsWith('/api/v1/orders/') ? order : []))
  render(
    <MemoryRouter initialEntries={[`/boosting/checkout?service=${sku}&order=${ref}`]}>
      <Routes><Route path="/boosting/checkout" element={<BoostingCheckout />} /></Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  api.get.mockReset()
})

describe('after paying', () => {
  it('boosting: the encrypted EA login form right there, the live chat, and no Discord', async () => {
    confirmation('BOOST_CHAMPS', 'GFS-26-BOOST001', paid('BOOST_CHAMPS', 'GFS-26-BOOST001', {
      status: 'CREDENTIALS_PENDING', nextAction: 'SUBMIT_CREDENTIALS', credentialsSubmitted: false,
    } as Partial<Order>))

    const cta = await screen.findByRole('link', { name: 'Connect to booster' })
    expect(cta).toHaveAttribute('href', '/orders/GFS-26-BOOST001/support')
    expect(screen.getByText('Connect with GFS')).toBeInTheDocument()
    // The EA login goes in the secure form, not the chat -- and the page says so.
    expect(screen.getByTestId('confirmation-signin')).toBeInTheDocument()
    expect(screen.getByTestId('never-in-chat'))
      .toHaveTextContent('Never share your EA password or backup codes in chat. Use the secure form above.')
    expectNoDiscord()
  })

  it('boosting, sign-in already given: no form again, the live chat, no Discord', async () => {
    confirmation('BOOST_RIVALS', 'GFS-26-BOOST002', paid('BOOST_RIVALS', 'GFS-26-BOOST002', {
      status: 'CREDENTIALS_PENDING', nextAction: 'SUBMIT_CREDENTIALS', credentialsSubmitted: true,
    } as Partial<Order>))

    expect(await screen.findByRole('link', { name: 'Connect to booster' })).toBeInTheDocument()
    expect(screen.queryByTestId('confirmation-signin')).toBeNull()
    expectNoDiscord()
  })

  it.each([['BOOST_OBJECTIVES', 'GFS-26-OBJ00001'], ['BOOST_TOURNAMENTS', 'GFS-26-TOUR0001']])(
    '%s: the same form, the live chat and no Discord', async (sku, ref) => {
      confirmation(sku, ref, paid(sku, ref, {
        status: 'CREDENTIALS_PENDING', nextAction: 'SUBMIT_CREDENTIALS', credentialsSubmitted: false, platform: null,
      } as Partial<Order>))
      expect(await screen.findByRole('link', { name: 'Connect to booster' }))
        .toHaveAttribute('href', `/orders/${ref}/support`)
      expect(screen.getByTestId('confirmation-signin')).toBeInTheDocument()
      expectNoDiscord()
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
    expectNoDiscord()

    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
    expect(await screen.findByText('You’re all set!')).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/discord/i)
  })
})
