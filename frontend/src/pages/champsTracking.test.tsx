import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Account, OrderEvent, OrderResult } from '../lib/types'
import { I18nProvider } from '../i18n'

/*
 * The "Champions Order Results" card on the tracking page: the wins, what is owed and how it
 * is settled, in every state; the owner's one choice, confirmed; nothing to choose for anyone
 * else; "All wins achieved" with no settlement at all; the history rows in the customer's
 * words; and the page bringing itself up to date when an admin saves.
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
  useCatalog: () => ({ catalog: null, policy: { champsChoiceDays: 5 }, loading: false, error: null, currency: 'INR',
    setCurrency: () => {} }),
}))
vi.mock('../components/support/SupportChat', () => ({ SupportChat: () => null }))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: Track, OrderView } = await import('./Track')
const { api } = mocks

const OWNER: Account = {
  publicId: 'acc_owner', email: 'rahul@example.test', displayName: 'Rahul', role: 'CUSTOMER',
  pointsBalance: 0, pointsValueMinor: 0, pointsValueFormatted: '₹0.00', firstOrder: false, referredByCode: null,
}
const REF = 'GFS-26-CHAMPS01'
const CHOOSE_BY = '2026-10-18T05:00:00Z'

function result(patch: Partial<OrderResult> = {}): OrderResult {
  return {
    kind: 'CHAMPS_WINS', targetLevel: 15, achievedLevel: 14, belowLowest: false, allAchieved: false,
    settlementMinor: 47500, settlementFormatted: '₹475.00', method: null, status: 'PENDING',
    chooseBy: CHOOSE_BY, refundAllowed: true, defaultMethod: 'STORE_CREDIT', ...patch,
  }
}

function order(r: OrderResult | null, patch: Record<string, unknown> = {}) {
  return {
    publicRef: REF, status: 'DELIVERED', statusLabel: 'Completed', nextAction: 'NONE', sku: 'BOOST_CHAMPS',
    serviceLabel: 'Champs Boosting — 15 wins · Elite I', platform: 'PLAYSTATION', variant: 'WINS_15', quantity: '1',
    deliveryMethod: 'COMFORT_TRADE', currency: 'INR', totalMinor: 389500, totalFormatted: '₹3,895.00',
    lines: [{ code: 'BASE', label: 'Champs Boosting — 15 wins', amountMinor: 400000, amountFormatted: '₹4,000.00' }],
    pointsRedeemed: 0, pointsEarned: 0, createdAt: '2026-10-08T02:55:00Z', deliveredAt: '2026-10-13T05:00:00Z',
    guaranteeExpiresAt: null, credentialsRequired: false, credentialsSubmitted: false, discordAccess: null,
    deliveredCoins: null, orderedCoins: null, transferStarted: false, customerAction: null,
    timeline: [
      { fromStatus: null, toStatus: 'DRAFT', actorType: 'CUSTOMER', actorLabel: null, reason: 'Order created',
        at: '2026-10-08T02:55:00Z' },
      { fromStatus: 'IN_PROGRESS', toStatus: 'DELIVERED', actorType: 'OPERATOR', actorLabel: 'acc_staff',
        reason: null, at: '2026-10-13T05:00:00Z', kind: 'RESULT_DELIVERED', detail: '14' },
    ] as OrderEvent[],
    result: r, ...patch,
  }
}

function openAsOwner(first: unknown, ...later: unknown[]) {
  auth.account = OWNER
  const queue = [first, ...later]
  api.get.mockImplementation(async (url: string) => {
    if (url === `/api/v1/orders/${REF}`) return queue.length > 1 ? queue.shift() : queue[0]
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

afterEach(() => {
  vi.useRealTimers()
  window.localStorage.clear()
})

describe('the Champions Order Results card', () => {
  it('owed and not chosen: the wins, the amount, a red Pending, the two choices and the date', async () => {
    openAsOwner(order(result()))
    const card = await screen.findByTestId('champs-result')
    expect(screen.getByText('Champions order details')).toBeInTheDocument()
    expect(card).toHaveTextContent('Champions Order Results')
    expect(within(card).getByTestId('wins-ordered')).toHaveTextContent('15 Wins')
    expect(within(card).getByTestId('wins-achieved')).toHaveTextContent('14 Wins')
    expect(card).toHaveTextContent('Settlement Amount')
    expect(within(card).getByTestId('settlement-amount')).toHaveTextContent('₹475.00')
    const pill = within(card).getByTestId('settlement-status')
    expect(pill).toHaveTextContent('Pending')
    expect(pill.className).toContain('text-brand-500')
    expect(within(card).getByRole('button', { name: 'Get a refund' })).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Get GFS Store Credit' })).toBeInTheDocument()
    expect(within(card).getByTestId('choose-by')).toHaveTextContent(/^Choose by .*2026.*\. If you don't choose, you'll receive GFS Store Credit\.$/)
    expect(card).toHaveTextContent('Your final results and settlement details have also been sent to your registered email.')
  })

  it('sits after the order’s particulars and before the breakdown', async () => {
    openAsOwner(order(result()))
    const card = await screen.findByTestId('champs-result')
    const delivery = screen.getByText('Delivery method')
    const breakdown = screen.getByText('Breakdown')
    expect(delivery.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(card.compareDocumentPosition(breakdown) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('the owner chooses once, after confirming: the card then shows the method and no buttons', async () => {
    openAsOwner(order(result()))
    const card = await screen.findByTestId('champs-result')
    fireEvent.click(within(card).getByRole('button', { name: 'Get a refund' }))
    expect(card).toHaveTextContent('Get a refund of ₹475.00? You can’t change this choice later.'.replace('’', "'"))
    expect(api.post).not.toHaveBeenCalled()

    api.post.mockResolvedValue(order(result({ method: 'REFUND', chooseBy: null })))
    await act(async () => { fireEvent.click(within(card).getByRole('button', { name: 'Confirm' })) })
    expect(api.post).toHaveBeenCalledWith(`/api/v1/orders/${REF}/result/choice`, { method: 'REFUND' })

    const after = screen.getByTestId('champs-result')
    expect(after).toHaveTextContent('Refund Amount')
    expect(within(after).getByTestId('settlement-status')).toHaveTextContent('Refund Pending')
    expect(within(after).getByTestId('settlement-method')).toHaveTextContent('Settlement Method: Refund')
    expect(within(after).queryByRole('button', { name: 'Get a refund' })).toBeNull()
  })

  it('cancelling the confirmation sends nothing', async () => {
    openAsOwner(order(result()))
    const card = await screen.findByTestId('champs-result')
    fireEvent.click(within(card).getByRole('button', { name: 'Get GFS Store Credit' }))
    expect(card).toHaveTextContent("Get ₹475.00 as GFS Store Credit? You can't change this choice later.")
    fireEvent.click(within(card).getByRole('button', { name: 'Cancel' }))
    expect(api.post).not.toHaveBeenCalled()
    expect(within(card).getByRole('button', { name: 'Get a refund' })).toBeInTheDocument()
  })

  it('anyone but the signed-in owner sees the result and no choice', async () => {
    render(
      <MemoryRouter>
        <OrderView order={order(result()) as never} signedIn={false} />
      </MemoryRouter>,
    )
    const card = screen.getByTestId('champs-result')
    expect(within(card).getByTestId('settlement-amount')).toHaveTextContent('₹475.00')
    expect(within(card).queryByRole('button', { name: 'Get a refund' })).toBeNull()
    expect(within(card).queryByTestId('settlement-choice')).toBeNull()
  })

  it('a refund paid: Refund Amount and Paid, in neutral styling, with no tick', async () => {
    openAsOwner(order(result({ method: 'REFUND', status: 'PAID', chooseBy: null })))
    const card = await screen.findByTestId('champs-result')
    expect(card).toHaveTextContent('Refund Amount')
    const pill = within(card).getByTestId('settlement-status')
    expect(pill).toHaveTextContent(/^Paid$/)
    expect(pill.className).not.toContain('text-ok')
    expect(pill.querySelector('svg')).toBeNull()
    expect(within(card).queryByRole('button')).toBeNull()
  })

  it('store credit pending and issued', async () => {
    openAsOwner(order(result({ method: 'STORE_CREDIT', status: 'PENDING', chooseBy: null })))
    const card = await screen.findByTestId('champs-result')
    expect(card).toHaveTextContent('Store Credit Amount')
    expect(within(card).getByTestId('settlement-status')).toHaveTextContent('Store Credit Pending')
    expect(within(card).getByTestId('settlement-method')).toHaveTextContent('GFS Store Credit')
  })

  it('store credit issued', async () => {
    openAsOwner(order(result({ method: 'STORE_CREDIT', status: 'ISSUED', chooseBy: null })))
    const card = await screen.findByTestId('champs-result')
    expect(within(card).getByTestId('settlement-status')).toHaveTextContent(/^Issued$/)
  })

  it('every win achieved: both numbers and "All wins achieved", and nothing about a settlement', async () => {
    openAsOwner(order(result({ achievedLevel: 15, allAchieved: true, settlementMinor: null,
      settlementFormatted: null, status: 'NONE', chooseBy: null })))
    const card = await screen.findByTestId('champs-result')
    expect(within(card).getByTestId('wins-achieved')).toHaveTextContent('15 Wins')
    expect(within(card).getByTestId('all-achieved')).toHaveTextContent('All wins achieved')
    expect(within(card).queryByTestId('settlement-amount')).toBeNull()
    expect(within(card).queryByTestId('settlement-status')).toBeNull()
    expect(within(card).queryByRole('button')).toBeNull()
  })

  it('paid partly with store credit: only store credit can be chosen', async () => {
    openAsOwner(order(result({ refundAllowed: false })))
    const card = await screen.findByTestId('champs-result')
    expect(within(card).queryByRole('button', { name: 'Get a refund' })).toBeNull()
    expect(within(card).getByRole('button', { name: 'Get GFS Store Credit' })).toBeInTheDocument()
    expect(card).toHaveTextContent('the difference is returned as store credit')
  })

  it('no card before Wins Achieved is recorded', async () => {
    openAsOwner(order(null, { status: 'IN_PROGRESS', timeline: [] }))
    await screen.findByText('Breakdown')
    expect(screen.queryByTestId('champs-result')).toBeNull()
  })
})

describe('the history', () => {
  it('the delivery row carries the wins, and settlement changes are rows of their own', async () => {
    const o = order(result({ method: 'REFUND', status: 'PAID', chooseBy: null }))
    o.timeline.push(
      { fromStatus: 'DELIVERED', toStatus: 'DELIVERED', actorType: 'CUSTOMER', actorLabel: null,
        reason: 'Customer chose a refund.', at: '2026-10-14T05:00:00Z', kind: 'SETTLEMENT_CHOSEN', detail: 'REFUND' },
      { fromStatus: 'DELIVERED', toStatus: 'DELIVERED', actorType: 'OPERATOR', actorLabel: 'acc_admin',
        reason: 'Refund of ₹475.00 paid by UPI, reference 412345678901.', at: '2026-10-15T05:00:00Z',
        kind: 'SETTLEMENT_PAID', detail: '₹475.00' },
    )
    openAsOwner(o)
    expect(await screen.findByText('DELIVERED — 14 Wins Achieved')).toBeInTheDocument()
    expect(screen.getByText('You chose a refund')).toBeInTheDocument()
    expect(screen.getByText('Refund of ₹475.00 paid')).toBeInTheDocument()
  })

  it('in Spanish', async () => {
    window.localStorage.setItem('gfs.language', 'es')
    const o = order(result({ method: 'STORE_CREDIT', status: 'ISSUED', chooseBy: null }))
    o.timeline.push({ fromStatus: 'DELIVERED', toStatus: 'DELIVERED', actorType: 'SYSTEM', actorLabel: 'GFS',
      reason: 'GFS Store Credit of ₹475.00 issued automatically after 5 days.', at: '2026-10-18T05:00:00Z',
      kind: 'SETTLEMENT_ISSUED_AUTO', detail: '₹475.00' })
    render(
      <I18nProvider>
        <MemoryRouter><OrderView order={o as never} signedIn={false} /></MemoryRouter>
      </I18nProvider>,
    )
    expect(screen.getByText('DELIVERED — 14 victorias conseguidas')).toBeInTheDocument()
    expect(screen.getByText('Crédito de tienda GFS de ₹475.00 emitido automáticamente tras 5 días')).toBeInTheDocument()
    const card = screen.getByTestId('champs-result')
    expect(card).toHaveTextContent('Resultados del pedido de Champions')
    expect(card).toHaveTextContent('Importe del crédito de tienda')
    expect(within(card).getByTestId('settlement-status')).toHaveTextContent('Emitido')
  })
})

describe('the page brings itself up to date', () => {
  it('when an admin marks the refund Paid, the card says so without a reload', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    openAsOwner(
      order(result({ method: 'REFUND', chooseBy: null })),
      order(result({ method: 'REFUND', status: 'PAID', chooseBy: null })),
    )
    const card = await screen.findByTestId('champs-result')
    expect(within(card).getByTestId('settlement-status')).toHaveTextContent('Refund Pending')
    await act(async () => { vi.advanceTimersByTime(31_000) })
    expect(await screen.findByText(/^Paid$/)).toBeInTheDocument()
  })

  it('keeps refreshing a completed order while its settlement is owed', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    openAsOwner(
      order(result({ method: 'STORE_CREDIT', chooseBy: null }), { status: 'COMPLETED' }),
      order(result({ method: 'STORE_CREDIT', status: 'ISSUED', chooseBy: null }), { status: 'COMPLETED' }),
    )
    await screen.findByText('Store Credit Pending')
    await act(async () => { vi.advanceTimersByTime(31_000) })
    expect(await screen.findByText(/^Issued$/)).toBeInTheDocument()
  })
})
