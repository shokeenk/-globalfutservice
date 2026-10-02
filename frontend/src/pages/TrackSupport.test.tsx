import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { Order } from '../lib/types'

/*
 * The order page offers the support card where the Discord panel used to be: the signed-in
 * owner gets the way to the order's support page, a guest the way to a support ticket.
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
    // The Discord access the server still sends: the page no longer shows it.
    discordAccess: { mode: 'VERIFY', channelUrl: null, inviteUrl: 'https://discord.gg/x', command: '/verify ABC' },
  } as unknown as Order
}

function view(o: Order, signedIn: boolean) {
  return render(<MemoryRouter><OrderView order={o} signedIn={signedIn} /></MemoryRouter>)
}

describe('the order page\'s support card', () => {
  it.each([
    ['BOOST_CHAMPS', 'Create Order Ticket', '/orders/GFS-26-70C4DPWH/support'],
    ['TRADING_SERVICE', 'Create Order Support', '/orders/GFS-26-70C4DPWH/support'],
    ['COACHING', 'Connect with Coach', '/coaching/GFS-26-70C4DPWH/support'],
  ])('%s, signed in: the support card, and no Discord', (sku, cta, path) => {
    view(order(sku, 'IN_PROGRESS'), true)
    expect(screen.getByRole('link', { name: cta })).toHaveAttribute('href', path)
    expect(document.body.textContent).not.toMatch(/discord|\/verify/i)
  })

  it('a guest order: the way to a support ticket, not the chat', () => {
    view(order('BOOST_CHAMPS', 'IN_PROGRESS'), false)
    expect(screen.getByTestId('guest-support-card')).toBeInTheDocument()
    expect(screen.queryByTestId('support-card')).toBeNull()
  })

  it('before payment and after the work is done, no card, as the Discord panel before it', () => {
    view(order('BOOST_CHAMPS', 'AWAITING_PAYMENT'), true)
    expect(screen.queryByTestId('support-card')).toBeNull()
    view(order('BOOST_CHAMPS', 'COMPLETED'), true)
    expect(screen.queryByTestId('support-card')).toBeNull()
  })
})
