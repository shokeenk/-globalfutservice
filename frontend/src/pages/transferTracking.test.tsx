import { fireEvent, render, screen, within } from '@testing-library/react'
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
    expect(box).toHaveTextContent('200K of 500K delivered')
    const bar = within(box).getByRole('progressbar')
    expect(bar).toHaveAttribute('aria-valuenow', '200')
    expect(bar).toHaveAttribute('aria-valuemax', '500')
    expect(box).toHaveTextContent('This page updates by itself.')
  })

  it('just started, nothing reported yet: "Your coin transfer has started", and nothing delivered', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: null, orderedCoins: 500 }))
    const box = await screen.findByTestId('transfer-progress')
    expect(within(box).getByTestId('transfer-stage')).toHaveTextContent('Your coin transfer has started')
    expect(box).toHaveTextContent('0K of 500K delivered')
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

describe("the partner's live tracking page, on the owner's own coin order", () => {
  const PARTNER = 'https://transferstatus.top/abc12345?shop=gfs&lang=de'

  it('"Track order" opens the link exactly as stored, in a new tab, with the note under it', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 120, orderedCoins: 250, partnerTrackingUrl: PARTNER }))

    const box = await screen.findByTestId('partner-tracking')
    const link = within(box).getByRole('link', { name: /Track order/ })
    expect(link).toHaveAttribute('href', PARTNER)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(box).toHaveTextContent('Live delivery status from our transfer partner. If your EA login details need '
      + 'fixing, you can update them here. Don\u2019t share this link.')
  })

  it('no link on the order -- not yet stored, or not the owner -- no button and no note', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 120, orderedCoins: 250 }))
    await screen.findByTestId('transfer-progress')
    expect(screen.queryByTestId('partner-tracking')).toBeNull()
    expect(screen.queryByRole('link', { name: /Track order/ })).toBeNull()
  })

  it.each(['http://transferstatus.top/abc12345', 'javascript:alert(1)', 'not a url'])(
    'anything but an https address is not shown: %s', async (bad) => {
      openOrder(order({ transferStarted: true, deliveredCoins: 120, orderedCoins: 250, partnerTrackingUrl: bad }))
      await screen.findByTestId('transfer-progress')
      expect(screen.queryByTestId('partner-tracking')).toBeNull()
    })
})

describe('a refused EA sign-in, on the order page', () => {
  const held = (action: string) => order({ status: 'ON_HOLD', statusLabel: 'On hold', nextAction: 'RESUBMIT_CREDENTIALS',
    customerAction: action, transferStarted: true, deliveredCoins: 30, orderedCoins: 500 })

  it('the email or password: said plainly, with the form right there, and said once', async () => {
    openOrder(held('RESUBMIT_SIGN_IN'))
    const box = await screen.findByTestId('signin-retry')
    expect(box).toHaveTextContent('Your EA details need fixing')
    expect(box).toHaveTextContent('Your EA email or password was not accepted.')
    expect(within(box).getByLabelText(/EA password/)).toBeInTheDocument()
    expect(within(box).getByTestId('cred-check-note')).toHaveTextContent('We check your EA details a few minutes after your order starts. If anything is wrong, you\u2019ll see it here and by email.')
    // Not repeated in the progress card above.
    expect(screen.getAllByText(/was not accepted/)).toHaveLength(1)
  })

  it('the backup codes: said as the backup codes', async () => {
    openOrder(held('NEW_BACKUP_CODES'))
    const box = await screen.findByTestId('signin-retry')
    expect(box).toHaveTextContent('Your EA backup codes were not accepted.')
    expect(box).not.toHaveTextContent('email or password')
  })

  it('looked up without signing in: what is wrong, and to sign in to fix it -- no form', async () => {
    api.post.mockResolvedValue(held('RESUBMIT_SIGN_IN'))
    render(<MemoryRouter initialEntries={[`/track?ref=${REF}`]}><Track /></MemoryRouter>)
    fireEvent.change(await screen.findByPlaceholderText('you@example.com'), { target: { value: 'rahul@example.test' } })
    fireEvent.click(screen.getByRole('button', { name: /Find my order/ }))
    expect(await screen.findByText(/Sign in to your account to enter them again\./)).toBeInTheDocument()
    expect(screen.queryByLabelText(/EA password/)).toBeNull()
  })
})

describe('the partner tracking link, when there is none to give', () => {
  it('coming: "Live tracking link coming shortly", with our progress', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 120, orderedCoins: 250, partnerLinkState: 'PENDING' }))
    const box = await screen.findByTestId('transfer-progress')
    expect(within(box).getByTestId('partner-link-pending')).toHaveTextContent('Live tracking link coming shortly.')
    expect(box).toHaveTextContent('120K of 250K delivered')
    expect(within(box).queryByRole('link', { name: /Track order/ })).toBeNull()
  })

  it('unavailable: a plain message, and our progress stays', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 120, orderedCoins: 250, partnerLinkState: 'UNAVAILABLE' }))
    const box = await screen.findByTestId('transfer-progress')
    expect(within(box).getByTestId('partner-link-unavailable'))
      .toHaveTextContent('A live tracking link isn\u2019t available for this order.')
    expect(box).toHaveTextContent('120K of 250K delivered')
  })

  it('hidden from customers (no state, no link): nothing about it at all', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 120, orderedCoins: 250 }))
    const box = await screen.findByTestId('transfer-progress')
    expect(box).not.toHaveTextContent(/tracking link/i)
  })
})

describe('while our staff check the order', () => {
  const CHECKING = 'We’re checking your order, nothing for you to do.'

  it('the progress keeps showing, with the neutral line -- and never a reason', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 110, orderedCoins: 500, transferUnderReview: true }))
    const box = await screen.findByTestId('transfer-progress')
    expect(within(box).getByTestId('transfer-checking')).toHaveTextContent(CHECKING)
    expect(box).toHaveTextContent('110K of 500K delivered')
    expect(box).not.toHaveTextContent(/review|unknown|status code/i)
  })

  it('not under review, or already delivered: no such line', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 110, orderedCoins: 500, transferUnderReview: false }))
    await screen.findByTestId('transfer-progress')
    expect(screen.queryByTestId('transfer-checking')).toBeNull()
  })

  it('delivered: no checking line even if the flag lingers', async () => {
    openOrder(order({ status: 'DELIVERED', transferStarted: true, deliveredCoins: 500, orderedCoins: 500,
      transferUnderReview: true }))
    await screen.findByTestId('transfer-progress')
    expect(screen.queryByTestId('transfer-checking')).toBeNull()
  })
})

describe('the simple status, and delivered of ordered in coins', () => {
  it.each<[string, Record<string, unknown>, string]>([
    ['nothing reported yet', { deliveredCoins: null }, 'Processing'],
    ['coins arriving', { deliveredCoins: 120 }, 'Delivering'],
    ['every coin reported, but the order is not finished', { deliveredCoins: 250 }, 'Delivering'],
    ['the order delivered', { status: 'DELIVERED', deliveredCoins: 250 }, 'Delivered'],
    ['the customer has something to fix', { status: 'ON_HOLD', customerAction: 'RESUBMIT_SIGN_IN', deliveredCoins: 40 },
      'Needs attention'],
  ])('%s: %s', async (_name, patch, label) => {
    openOrder(order({ transferStarted: true, orderedCoins: 250, ...patch }))
    expect(await screen.findByTestId('transfer-status')).toHaveTextContent(label)
  })

  it('amounts are in thousands: 120 of 250 is "120K of 250K delivered", 1500 is 1.5M', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 120, orderedCoins: 250 }))
    expect(await screen.findByTestId('transfer-progress')).toHaveTextContent('120K of 250K delivered')
  })

  it('a million and a half reads as 1.5M', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 1000, orderedCoins: 1500 }))
    expect(await screen.findByTestId('transfer-progress')).toHaveTextContent('1M of 1.5M delivered')
  })

  it('no vendor wording reaches the customer: the partner is never named, nor its codes', async () => {
    openOrder(order({ transferStarted: true, deliveredCoins: 120, orderedCoins: 250,
      partnerTrackingUrl: 'https://transferstatus.top/abc12345' }))
    const box = await screen.findByTestId('transfer-progress')
    expect(box).not.toHaveTextContent(/fut ?transfer|accountCheck|economyState|simplified/i)
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
  const OWN_URL = `/api/v1/orders/${REF}`

  /** Someone else's order: the owner endpoint answers this account with a not-found. */
  function openAsStaff(role: 'ADMIN' | 'OPERATOR', o: Record<string, unknown>) {
    auth.account = staff(role)
    api.get.mockImplementation(async (url: string) => {
      if (url === OWN_URL) throw new mocks.ApiError(404, 'No such order.')
      return url === STAFF_URL ? o : new Promise(() => {})
    })
    render(<MemoryRouter initialEntries={[`/track?ref=${REF}`]}><Track /></MemoryRouter>)
  }

  it.each(['ADMIN', 'OPERATOR'] as const)('%s: the order as its customer sees it, from the staff endpoint, read-only', async (role) => {
    openAsStaff(role, order({ transferStarted: true, deliveredCoins: 200, orderedCoins: 500 }))

    expect(await screen.findByTestId('transfer-progress')).toHaveTextContent('200K of 500K delivered')
    expect(screen.getByTestId('staff-view')).toHaveTextContent('Staff view')
    // Asked once, as itself, whether the order is its own (it is not); then only the staff
    // endpoint. Looking does not stand in for the customer.
    expect(api.get.mock.calls.map(([url]) => url)).toEqual([OWN_URL, STAFF_URL])
    expect(api.post).not.toHaveBeenCalled()
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.queryByRole('link', { name: /Track order/ })).toBeNull()
  })

  it.each(['ADMIN', 'OPERATOR'] as const)("%s opening its OWN order: the owner's page, with the Track order button", async (role) => {
    const PARTNER = 'https://transferstatus.top/abc12345'
    auth.account = staff(role)
    const own = order({ transferStarted: true, deliveredCoins: 120, orderedCoins: 250, partnerTrackingUrl: PARTNER })
    api.get.mockImplementation(async (url: string) => {
      if (url === OWN_URL) return own
      if (url === '/api/v1/orders') return []
      return new Promise(() => {})
    })
    render(<MemoryRouter initialEntries={[`/track?ref=${REF}`]}><Track /></MemoryRouter>)

    const link = await screen.findByRole('link', { name: /Track order/ })
    expect(link).toHaveAttribute('href', PARTNER)
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.queryByTestId('staff-view')).toBeNull()
    expect(api.get).not.toHaveBeenCalledWith(STAFF_URL)
  })

  it("the owner check failing for any other reason: the staff view, never the owner's page", async () => {
    auth.account = staff('ADMIN')
    api.get.mockImplementation(async (url: string) => {
      if (url === OWN_URL) throw new Error('network')
      return url === STAFF_URL ? order({ transferStarted: true, deliveredCoins: 200, orderedCoins: 500 })
        : new Promise(() => {})
    })
    render(<MemoryRouter initialEntries={[`/track?ref=${REF}`]}><Track /></MemoryRouter>)
    expect(await screen.findByTestId('staff-view')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Track order/ })).toBeNull()
  })

  it.each([
    ['their EA sign-in', { status: 'READY_FOR_DELIVERY', nextAction: 'SUBMIT_CREDENTIALS', credentialsSubmitted: false }],
    ['a refused EA sign-in', { status: 'ON_HOLD', nextAction: 'RESUBMIT_CREDENTIALS', customerAction: 'RESUBMIT_SIGN_IN' }],
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
