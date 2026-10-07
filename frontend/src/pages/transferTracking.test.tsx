import { render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Account } from '../lib/types'

/*
 * Once FUT Transfer has a coin order, the customer can follow it: "Track your order" on their
 * order list, the order's support page and the page Payop returns them to, and on the tracking
 * page a live bar -- delivered of ordered -- under a plain sentence of where it has got to.
 * Before that, none of it shows: the status is all there is.
 */

const mocks = vi.hoisted(() => {
  class ApiError extends Error {
    status: number
    constructor(status: number, message = 'error') {
      super(message)
      this.status = status
    }
  }
  return { api: { get: vi.fn(), post: vi.fn(), put: vi.fn() }, ApiError }
})
vi.mock('../lib/api', () => ({ api: mocks.api, ApiError: mocks.ApiError }))
const auth = vi.hoisted(() => ({ account: null as Account | null }))
vi.mock('../state/AuthContext', () => ({ useAuth: () => ({ account: auth.account, loading: false }) }))
vi.mock('../state/CatalogContext', () => ({
  useCatalog: () => ({ catalog: null, policy: null, loading: false, error: null, currency: 'INR', setCurrency: () => {} }),
}))
vi.mock('../components/support/SupportChat', () => ({ SupportChat: () => null }))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: Track } = await import('./Track')
const { default: OrderSupport } = await import('./OrderSupport')
const { default: PayopReturn } = await import('./PayopReturn')
const { api } = mocks

const OWNER: Account = {
  publicId: 'acc_owner', email: 'rahul@example.test', displayName: 'Rahul', role: 'CUSTOMER',
  pointsBalance: 0, pointsValueMinor: 0, pointsValueFormatted: '₹0.00', firstOrder: false, referredByCode: null,
}
const REF = 'GFS-26-TRACK001'

function order(patch: Record<string, unknown>) {
  return {
    publicRef: REF, status: 'IN_PROGRESS', statusLabel: 'Being delivered', nextAction: 'WAIT', sku: 'TRADING_SERVICE',
    serviceLabel: 'Buy Coins — 500K (PlayStation)', platform: 'PLAYSTATION', quantity: 0.5, currency: 'INR',
    totalMinor: 825000, totalFormatted: '₹8,250.00', lines: [], pointsRedeemed: 0, pointsEarned: 0,
    createdAt: '2026-10-07T10:00:00Z', timeline: [], credentialsRequired: true, credentialsSubmitted: true,
    discordAccess: null, deliveredCoins: null, orderedCoins: null, transferStarted: false, ...patch,
  }
}

function row(ref: string, transferStarted: boolean) {
  return {
    publicRef: ref, status: transferStarted ? 'IN_PROGRESS' : 'READY_FOR_DELIVERY', sku: 'TRADING_SERVICE',
    statusLabel: transferStarted ? 'Being delivered' : 'Queued', serviceLabel: 'Buy Coins — 500K', platform: 'PS',
    quantity: '0.5', deliveryMethod: 'PLAYER_AUCTION', credentialsHeld: true, customerEmail: 'rahul@example.test',
    totalMinor: 825000, totalFormatted: '₹8,250.00', currency: 'INR', createdAt: '2026-10-07T10:00:00Z',
    deliveredAt: null, availableTransitions: [], paymentState: null, transferStarted,
  }
}

function openOrder(o: Record<string, unknown>) {
  auth.account = OWNER
  api.get.mockImplementation(async (url: string) => {
    if (url === `/api/v1/orders/${REF}`) return o
    if (url === '/api/v1/orders') return []
    return new Promise(() => {})
  })
  render(<MemoryRouter initialEntries={[`/track?ref=${REF}`]}><Track /></MemoryRouter>)
}

beforeEach(() => {
  api.get.mockReset()
  api.post.mockReset()
  auth.account = null
})

describe('the tracking page, for a coin order', () => {
  it('with the partner and coins arriving: the plain sentence, delivered of ordered, and that it updates', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 200, orderedCoins: 500 }))

    const box = await screen.findByTestId('transfer-progress')
    expect(within(box).getByTestId('transfer-stage')).toHaveTextContent('Your coins are being delivered')
    expect(box).toHaveTextContent('200K of 500K coins delivered')
    const bar = within(box).getByRole('progressbar')
    expect(bar).toHaveAttribute('aria-valuenow', '200')
    expect(bar).toHaveAttribute('aria-valuemax', '500')
    expect(box).toHaveTextContent('This page updates by itself.')
  })

  it('just started, nothing reported yet: "Your coin transfer has started", and nothing delivered', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: null, orderedCoins: 500 }))
    const box = await screen.findByTestId('transfer-progress')
    expect(within(box).getByTestId('transfer-stage')).toHaveTextContent('Your coin transfer has started')
    expect(box).toHaveTextContent('0K of 500K coins delivered')
  })

  it('all of it delivered: says so, and stops saying it updates', async () => {
    openOrder(order({ status: 'DELIVERED', transferStarted: true, deliveredCoins: 500, orderedCoins: 500 }))
    const box = await screen.findByTestId('transfer-progress')
    expect(within(box).getByTestId('transfer-stage')).toHaveTextContent('All your coins have been delivered')
    expect(box).not.toHaveTextContent('updates by itself')
  })

  it('before the partner has it: no progress and no transfer sentence -- the status as now', async () => {
    openOrder(order({ status: 'READY_FOR_DELIVERY', statusLabel: 'Queued', transferStarted: false }))
    expect(await screen.findAllByText('Queued')).not.toHaveLength(0)
    expect(screen.queryByTestId('transfer-progress')).toBeNull()
  })
})

describe('"Track your order", only once the partner has the order', () => {
  it('My Orders: on the order being transferred, not on the one still queued', async () => {
    auth.account = OWNER
    api.get.mockImplementation(async (url: string) => (url === '/api/v1/orders'
      ? [row('GFS-26-STARTED1', true), row('GFS-26-QUEUED01', false)] : new Promise(() => {})))
    render(<MemoryRouter initialEntries={['/track']}><Track /></MemoryRouter>)

    const links = await screen.findAllByRole('link', { name: 'Track your order' })
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveAttribute('href', '/track?ref=GFS-26-STARTED1')
  })

  it.each([true, false])('the order\'s support page (started: %s)', async (started) => {
    auth.account = OWNER
    api.get.mockResolvedValue({
      mode: 'COINS',
      summary: { reference: REF, service: 'Buy Coins — 500K', platform: 'PlayStation',
        status: started ? 'Being delivered' : 'Queued', coins: '500K', transferStarted: started },
      chat: { name: 'Rahul', email: 'rahul@example.test', hash: null, attributes: {} },
    })
    render(
      <MemoryRouter initialEntries={[`/orders/${REF}/support`]}>
        <Routes><Route path="/orders/:ref/support" element={<OrderSupport />} /></Routes>
      </MemoryRouter>,
    )
    await screen.findByTestId('support-summary')
    const link = screen.queryByRole('link', { name: 'Track your order' })
    if (started) expect(link).toHaveAttribute('href', `/track?ref=${REF}`)
    else expect(link).toBeNull()
  })

  it('the page Payop returns to: "Track your order" once the transfer has started, "View your order" before', async () => {
    const status = (transferStarted: boolean) => ({ payment: 'PAID', order: 'IN_PROGRESS', totalMinor: 9407,
      totalFormatted: '€94.07', method: 'Bank transfer', transferStarted })
    api.get.mockResolvedValueOnce(status(false)).mockResolvedValue(status(true))
    render(
      <MemoryRouter initialEntries={[`/payment/payop/return?ref=${REF}&invoice=inv-1`]}>
        <Routes><Route path="/payment/payop/return" element={<PayopReturn />} /></Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByRole('link', { name: 'View your order' })).toBeInTheDocument()
    // Paid but not yet with the partner: it keeps asking, and the button changes when it is.
    const track = await screen.findByRole('link', { name: 'Track your order' }, { timeout: 8000 })
    expect(track).toHaveAttribute('href', `/track?ref=${REF}`)
  }, 15000)
})

describe("staff opening the customer's tracking link", () => {
  const staff = (role: 'ADMIN' | 'OPERATOR'): Account => ({ ...OWNER, publicId: 'acc_staff', email: 'ops@example.test', role })
  const STAFF_URL = `/api/v1/admin/orders/${REF}/customer-view`

  function openAsStaff(role: 'ADMIN' | 'OPERATOR', o: Record<string, unknown>) {
    auth.account = staff(role)
    api.get.mockImplementation(async (url: string) => (url === STAFF_URL ? o : new Promise(() => {})))
    render(<MemoryRouter initialEntries={[`/track?ref=${REF}`]}><Track /></MemoryRouter>)
  }

  it.each(['ADMIN', 'OPERATOR'] as const)('%s: the order as its customer sees it, from the staff endpoint, read-only', async (role) => {
    openAsStaff(role, order({ transferStarted: true, deliveredCoins: 200, orderedCoins: 500 }))

    expect(await screen.findByTestId('transfer-progress')).toHaveTextContent('200K of 500K coins delivered')
    expect(screen.getByTestId('staff-view')).toHaveTextContent('Staff view')
    // Never the customer's own endpoints: looking does not stand in for the customer.
    expect(api.get.mock.calls.map(([url]) => url)).toEqual([STAFF_URL])
    expect(api.post).not.toHaveBeenCalled()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it.each([
    ['their EA sign-in', { status: 'READY_FOR_DELIVERY', nextAction: 'SUBMIT_CREDENTIALS', credentialsSubmitted: false }],
    ['paying', { status: 'AWAITING_PAYMENT', nextAction: 'PAY' }],
  ])('where the customer has a form (%s), staff are told there is one instead of getting it', async (_step, patch) => {
    openAsStaff('OPERATOR', order(patch))
    expect(await screen.findByText('Here the customer sees a form for this step. It is hidden in the staff view.'))
      .toBeInTheDocument()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
    expect(api.post).not.toHaveBeenCalled()
  })

  it('the customer opening the same link still gets their own page, from their own endpoint', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 200, orderedCoins: 500 }))
    await screen.findByTestId('transfer-progress')
    expect(screen.queryByTestId('staff-view')).toBeNull()
    expect(api.get).toHaveBeenCalledWith(`/api/v1/orders/${REF}`)
    expect(api.get).not.toHaveBeenCalledWith(STAFF_URL)
  })
})
