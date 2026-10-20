import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Order } from '../lib/types'

/*
 * The order page's way to us is the order's live chat, and nothing else: the signed-in owner
 * gets the order's support page, a guest the guest chat with the reference filled in. No
 * Discord step for any order -- whatever an older API still says about Discord -- and the
 * one rule about the chat said under it: the EA password and backup codes never go in it.
 */

const api = vi.hoisted(() => ({ get: vi.fn(async () => ({ creditBalance: 0, upcoming: [] })), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
vi.mock('../state/AuthContext', () => ({ useAuth: () => ({ account: null, loading: false }) }))
vi.mock('../state/CatalogContext', () => ({
  useCatalog: () => ({ catalog: null, policy: null, loading: false, error: null, currency: 'INR', setCurrency: () => {} }),
}))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { OrderView } = await import('./Track')

function order(sku: string, status: string): Order {
  return {
    publicRef: 'GFS-26-70C4DPWH', status, statusLabel: 'Being delivered', nextAction: 'NONE',
    serviceLabel: 'A service', sku, platform: 'PC', quantity: 1, deliveryMethod: 'PLAYER_AUCTION',
    credentialsRequired: false, credentialsSubmitted: false, currency: 'INR', totalMinor: 100000,
    totalFormatted: '₹1,000.00', lines: [], pointsRedeemed: 0, pointsEarned: 0, createdAt: '2026-10-01T10:00:00Z',
    timeline: [],
    // What an older API still sends: never shown.
    discordAccess: { mode: 'DIRECT', channelUrl: 'https://discord.com/channels/1/2', inviteUrl: 'https://discord.gg/x' },
  } as unknown as Order
}

function view(o: Order, signedIn: boolean, guestEmail?: string) {
  return render(<MemoryRouter><OrderView order={o} signedIn={signedIn} guestEmail={guestEmail} /></MemoryRouter>)
}

/** Nothing on the page takes a customer to Discord: no server invite, no channel, no direct message. */
function expectNoDiscord() {
  const links = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href') ?? '')
  expect(links.filter((href) => /discord/i.test(href))).toEqual([])
  expect(document.body.textContent).not.toMatch(/discord|\/verify|run this command/i)
}

describe('the order page\'s support card', () => {
  it.each([
    ['BOOST_CHAMPS', 'Connect to booster', '/orders/GFS-26-70C4DPWH/support'],
    ['BOOST_RIVALS', 'Connect to booster', '/orders/GFS-26-70C4DPWH/support'],
    ['BOOST_OBJECTIVES', 'Connect to booster', '/orders/GFS-26-70C4DPWH/support'],
    ['BOOST_TOURNAMENTS', 'Connect to booster', '/orders/GFS-26-70C4DPWH/support'],
    ['TRADING_SERVICE', 'Create Order Support', '/orders/GFS-26-70C4DPWH/support'],
  ])('%s, signed in: the live chat, the rule about it, and no Discord', (sku, cta, path) => {
    view(order(sku, 'IN_PROGRESS'), true)
    expect(screen.getByRole('link', { name: cta })).toHaveAttribute('href', path)
    expect(screen.getByTestId('never-in-chat'))
      .toHaveTextContent('Never share your EA password or backup codes in chat. Use the secure form above.')
    expectNoDiscord()
  })

  it('COACHING, signed in: the coach, and no Discord -- no sign-in, so no rule about one', () => {
    view(order('COACHING', 'IN_PROGRESS'), true)
    expect(screen.getByRole('link', { name: 'Connect with Coach' }))
      .toHaveAttribute('href', '/coaching/GFS-26-70C4DPWH/support')
    expect(screen.queryByTestId('never-in-chat')).toBeNull()
    expectNoDiscord()
  })

  it('a guest order: the live chat too, with the reference in the address and the email out of it', async () => {
    function Landed() {
      const location = useLocation()
      return <p data-testid="landed">{location.pathname + location.search} {JSON.stringify(location.state)}</p>
    }
    render(
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<OrderView order={order('BOOST_CHAMPS', 'IN_PROGRESS')} signedIn={false}
                                              guestEmail="guest@example.test" />} />
          <Route path="/support/chat" element={<Landed />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(screen.getByTestId('guest-support-card')).toBeInTheDocument()
    expect(screen.queryByTestId('support-card')).toBeNull()
    expectNoDiscord()

    const chat = screen.getByRole('link', { name: 'Chat with us' })
    expect(chat).toHaveAttribute('href', '/support/chat?ref=GFS-26-70C4DPWH')
    expect(chat.getAttribute('href')).not.toContain('guest@example.test')
    await userEvent.click(chat)
    expect(screen.getByTestId('landed')).toHaveTextContent('/support/chat?ref=GFS-26-70C4DPWH {"email":"guest@example.test"}')
  })

  it('before payment and after the work is done, no card', () => {
    view(order('BOOST_CHAMPS', 'AWAITING_PAYMENT'), true)
    expect(screen.queryByTestId('support-card')).toBeNull()
    view(order('BOOST_CHAMPS', 'COMPLETED'), true)
    expect(screen.queryByTestId('support-card')).toBeNull()
    expectNoDiscord()
  })
})
