import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RecordedResult, ResultView } from './champs'

/*
 * The Champions controls on the admin order page: Wins Achieved in the Actions, entered once
 * and kept in step with the Champions card; Mark delivered blocked until it is set; below the
 * lowest tier an amount by hand, capped, with a note; Paid only through a confirmation with
 * the refund's reference; and everything locked once settled. Method, status and proof are an
 * admin's.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), upload: vi.fn(), blobUrl: vi.fn() }))
vi.mock('../../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, body: { message: string }) {
      super(body.message)
    }
  },
}))
const auth = vi.hoisted(() => ({ role: 'ADMIN' as 'ADMIN' | 'OPERATOR' }))
vi.mock('../../../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'staff@example.test', role: auth.role } }),
}))

const { default: AdminOrder } = await import('../AdminOrder')

const REF = 'GFS-26-592VBQ2Z'

const OPTIONS = [9, 10, 11, 12, 13, 14, 15].map((level) => {
  const prices: Record<number, number> = { 9: 120000, 10: 155000, 11: 190000, 12: 210000, 13: 260000, 14: 350000, 15: 400000 }
  const price = prices[level]!
  const diff = 400000 - price
  const settlement = Math.round(diff * 380000 / 400000)
  const fmt = (m: number) => `₹${(m / 100).toLocaleString('en-IN', { minimumFractionDigits: 2 })}`
  return { level, achievedPriceMinor: price, achievedPriceFormatted: fmt(price), differenceFormatted: fmt(diff),
    settlementMinor: settlement, settlementFormatted: fmt(settlement), refundAllowed: true }
})

function view(result: RecordedResult | null = null): ResultView {
  return {
    applies: true, why: null, kind: 'CHAMPS_WINS', currency: 'INR', targetLevel: 15, lowestLevel: 9, options: OPTIONS,
    orderedPriceMinor: 400000, orderedPriceFormatted: '₹4,000.00', servicePaidMinor: 380000,
    servicePaidFormatted: '₹3,800.00', storeCreditUsedMinor: 0, result,
  }
}

function recorded(patch: Partial<RecordedResult> = {}): RecordedResult {
  return {
    achievedLevel: 14, belowLowest: false, achievedPriceMinor: 350000, achievedPriceFormatted: '₹3,500.00',
    settlementMinor: 47500, settlementFormatted: '₹475.00', method: 'REFUND', methodSetBy: 'CUSTOMER',
    methodSetAt: '2026-10-14T05:00:00Z', status: 'PENDING', chooseBy: null, recordedAt: '2026-10-13T05:00:00Z',
    settledAt: null, settledBy: null, refundMethod: null, refundReference: null, pointsRemoved: 0,
    refundAllowed: true, locked: false, proof: null, ...patch,
  }
}

function order(status: string, timeline: unknown[] = []) {
  return {
    publicRef: REF, status, statusLabel: status, nextAction: 'NONE', sku: 'BOOST_CHAMPS',
    serviceLabel: 'Champs Boosting — 15 wins · Elite I (PlayStation)', platform: 'PLAYSTATION', variant: 'WINS_15',
    quantity: '1', deliveryMethod: 'COMFORT_TRADE', currency: 'INR', totalMinor: 389500, totalFormatted: '₹3,895.00',
    lines: [], pointsRedeemed: 0, pointsEarned: 20, createdAt: '2026-10-08T02:55:00Z', deliveredAt: null,
    guaranteeExpiresAt: null, credentialsRequired: false, credentialsSubmitted: false, timeline,
  }
}

function stub(o: ReturnType<typeof order>, v: ResultView, transitions: string[]) {
  api.get.mockImplementation(async (path: string) => {
    if (path === `/api/v1/admin/orders/${REF}`) return o
    if (path.startsWith('/api/v1/admin/orders?search=')) return [{ publicRef: REF, availableTransitions: transitions }]
    if (path === `/api/v1/admin/orders/${REF}/result`) return v
    if (path.endsWith('/payment')) return { current: null, currentBreakdown: null, attempts: [] }
    throw new Error('not here')
  })
}

function open() {
  render(
    <MemoryRouter initialEntries={[`/admin/orders/${REF}`]}>
      <Routes><Route path="/admin/orders/:publicRef" element={<AdminOrder />} /></Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  auth.role = 'ADMIN'
  for (const fn of Object.values(api)) fn.mockReset()
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
})

describe('Wins Achieved before delivery', () => {
  it('Mark delivered is blocked until Wins Achieved is set, and the warning stays', async () => {
    stub(order('IN_PROGRESS'), view(), ['DELIVERED', 'ON_HOLD', 'REFUNDED'])
    open()
    const actions = await screen.findByTestId('wins-actions')
    expect(screen.getByText('Delivering is final')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Mark delivered' })).toBeDisabled()
    expect(within(actions).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Select wins', '9 Wins', '10 Wins', '11 Wins', '12 Wins', '13 Wins', '14 Wins', '15 Wins', 'Below lowest tier'])
  })

  it('one value: chosen in the Actions it shows on the card, and the other way round', async () => {
    stub(order('IN_PROGRESS'), view(), ['DELIVERED'])
    open()
    const actions = await screen.findByTestId('wins-actions')
    const card = screen.getByTestId('wins-card')
    fireEvent.change(actions, { target: { value: '14' } })
    expect(card).toHaveValue('14')
    expect(screen.getByTestId('settlement-preview')).toHaveValue('₹475.00')
    expect(screen.getByTestId('calculation')).toHaveTextContent(
      '(₹4,000.00 − ₹3,500.00) × (₹3,800.00 ÷ ₹4,000.00) = ₹475.00. The payment fee is not refunded.')
    expect(screen.getByText('Auto-calculated based on achieved wins')).toBeInTheDocument()

    fireEvent.change(card, { target: { value: '13' } })
    expect(actions).toHaveValue('13')
  })

  it('delivering sends Wins Achieved with it, through the result endpoint, never a bare transition', async () => {
    stub(order('IN_PROGRESS'), view(), ['DELIVERED'])
    api.post.mockResolvedValue({})
    open()
    fireEvent.change(await screen.findByTestId('wins-actions'), { target: { value: '14' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Mark delivered' })) })
    expect(api.post).toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/result/deliver`, {
      result: { achievedLevel: 14, belowLowest: false, manualAmountMinor: null, note: null }, reason: null,
    })
    expect(api.post).not.toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/transition`, expect.anything())
  })

  it('below the lowest tier: the actual wins and an amount by hand, capped, and a note required', async () => {
    stub(order('IN_PROGRESS'), view(), ['DELIVERED'])
    api.post.mockResolvedValue({})
    open()
    fireEvent.change(await screen.findByTestId('wins-actions'), { target: { value: 'BELOW' } })
    fireEvent.change(screen.getByTestId('actual-wins'), { target: { value: '7' } })
    fireEvent.change(screen.getByTestId('manual-amount'), { target: { value: '3900' } })
    const deliver = screen.getByRole('button', { name: 'Mark delivered' })

    fireEvent.click(deliver)
    expect(await screen.findByText(/at most ₹3,800.00/)).toBeInTheDocument()

    fireEvent.change(screen.getByTestId('manual-amount'), { target: { value: '1500' } })
    fireEvent.click(deliver)
    expect(await screen.findByText('Below the lowest tier, say why in the admin note.')).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()

    fireEvent.change(screen.getByTestId('admin-note'), { target: { value: 'Seven wins; agreed with the client.' } })
    expect(screen.getByText('35/500')).toBeInTheDocument()
    await act(async () => { fireEvent.click(deliver) })
    expect(api.post).toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/result/deliver`, {
      result: { achievedLevel: 7, belowLowest: true, manualAmountMinor: 150000,
        note: 'Seven wins; agreed with the client.' },
      reason: null,
    })
  })

  it('method, status and proof wait for delivery', async () => {
    stub(order('IN_PROGRESS'), view(), ['DELIVERED'])
    open()
    await screen.findByTestId('wins-actions')
    expect(screen.getByTestId('settlement-method')).toBeDisabled()
    expect(screen.getByTestId('settlement-status')).toBeDisabled()
    expect(screen.getByTestId('proof-input')).toBeDisabled()
  })
})

describe('settling', () => {
  it('Paid only through a confirmation with the refund’s reference', async () => {
    stub(order('DELIVERED'), view(recorded()), ['COMPLETED', 'DISPUTED'])
    api.post.mockResolvedValue(view(recorded({ status: 'PAID', locked: true, settledAt: '2026-10-15T05:00:00Z',
      settledBy: 'Vinay', refundMethod: 'PAYOP', refundReference: 'RF-9001' })))
    open()
    expect(screen.queryByTestId('wins-actions')).toBeNull()
    fireEvent.change(await screen.findByTestId('settlement-status'), { target: { value: 'PAID' } })
    const dialog = screen.getByTestId('settle-dialog')
    expect(dialog).toHaveTextContent('Confirm the ₹475.00 refund has actually been sent')

    fireEvent.click(within(dialog).getByTestId('settle-confirm'))
    expect(await within(dialog).findByText('Tick the box to confirm the refund was sent.')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('checkbox'))
    fireEvent.click(within(dialog).getByTestId('settle-confirm'))
    expect(await within(dialog).findByText(/reference is required/)).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()

    fireEvent.change(within(dialog).getByTestId('refund-rail'), { target: { value: 'PAYOP' } })
    fireEvent.change(within(dialog).getByTestId('refund-reference'), { target: { value: 'RF-9001' } })
    await act(async () => { fireEvent.click(within(dialog).getByTestId('settle-confirm')) })
    expect(api.post).toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/result/paid`,
      { method: 'PAYOP', reference: 'RF-9001', confirmed: true })
  })

  it('Issued is confirmed too, and asks for nothing else', async () => {
    stub(order('DELIVERED'), view(recorded({ method: 'STORE_CREDIT' })), ['COMPLETED'])
    api.post.mockResolvedValue(view(recorded({ method: 'STORE_CREDIT', status: 'ISSUED', locked: true })))
    open()
    fireEvent.change(await screen.findByTestId('settlement-status'), { target: { value: 'ISSUED' } })
    const dialog = screen.getByTestId('settle-dialog')
    expect(dialog).toHaveTextContent('Add ₹475.00 GFS Store Credit to the customer’s account now')
    fireEvent.click(within(dialog).getByRole('checkbox'))
    await act(async () => { fireEvent.click(within(dialog).getByTestId('settle-confirm')) })
    expect(api.post).toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/result/issue`, { confirmed: true })
  })

  it('locked once Paid: nothing on the card can change', async () => {
    stub(order('DELIVERED'), view(recorded({ status: 'PAID', locked: true, settledAt: '2026-10-15T05:00:00Z',
      settledBy: 'Vinay', refundMethod: 'PAYOP', refundReference: 'RF-9001' })), ['COMPLETED'])
    open()
    const line = await screen.findByTestId('settled-line')
    expect(line).toHaveTextContent('Refund sent by Payop (reference RF-9001). Marked Paid by Vinay')
    expect(line).toHaveTextContent('Locked.')
    expect(screen.getByTestId('wins-card')).toBeDisabled()
    expect(screen.getByTestId('settlement-method')).toBeDisabled()
    expect(screen.getByTestId('settlement-status')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save result' })).toBeNull()
  })

  it('an operator can correct the wins but not set the method, the status or the proof', async () => {
    auth.role = 'OPERATOR'
    stub(order('DELIVERED'), view(recorded()), ['COMPLETED'])
    api.put.mockResolvedValue(view(recorded({ achievedLevel: 13, settlementMinor: 133000 })))
    open()
    const wins = await screen.findByTestId('wins-card')
    expect(wins).toBeEnabled()
    expect(screen.getByTestId('settlement-method')).toBeDisabled()
    expect(screen.getByTestId('settlement-status')).toBeDisabled()
    expect(screen.getByTestId('proof-input')).toBeDisabled()

    fireEvent.change(wins, { target: { value: '13' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Save result' })) })
    expect(api.put).toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/result`,
      { achievedLevel: 13, belowLowest: false, manualAmountMinor: null, note: null })
  })
})

describe('the history', () => {
  it('marks a staff-only note as never shown to the customer', async () => {
    stub(order('DELIVERED', [
      { fromStatus: 'DELIVERED', toStatus: 'DELIVERED', actorType: 'OPERATOR', actorLabel: 'acc_admin',
        reason: 'Customer asked on chat; refund by UPI.', at: '2026-10-14T05:00:00Z', kind: 'ADMIN_NOTE', staffOnly: true },
    ]), view(recorded()), ['COMPLETED'])
    open()
    await screen.findByText('Customer asked on chat; refund by UPI.')
    expect(screen.getByText(/Staff only · not shown to the customer/)).toBeInTheDocument()
    await waitFor(() => expect(api.get).toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/result`))
  })
})
