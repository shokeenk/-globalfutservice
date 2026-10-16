import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { VendorControlState, VendorOrderDetail, VendorSection } from '../../../lib/types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, body: { message: string }) {
      super(body.message)
    }
  },
}))

const { ApiError } = await import('../../../lib/api')
const { approveReplacedBy, historyLabel, vendorQuestion, vendorTimeline } = await import('./vendor')
const { VendorPanel } = await import('./VendorPanel')
const { VendorReview, resumeCallsQuestion } = await import('./VendorReview')
const { releaseQuestion } = await import('./confirmations')

const REF = 'GFS-26-VENDOR01'

function detail(patch: Partial<VendorOrderDetail> = {}): VendorOrderDetail {
  return {
    state: 'IN_DELIVERY', externalRef: REF, vendorOrderId: 'vid-1', amountOrderedK: 500, vendorAmountOrderedK: 500,
    deliveredK: 420, vendorStatus: 'partlyDelivered', vendorAccountCheck: 'finished',
    vendorEconomyState: 'transfersInProgress', aborted: false, coinsUsed: 135040, toPay: 12.5, attempts: 1,
    lastErrorCode: null, reviewReason: null, customerAction: null, missingPolls: 0,
    submittedAt: '2026-09-29T10:00:00Z', lastPolledAt: '2026-09-29T10:05:00Z', lastProgressAt: '2026-09-29T10:05:00Z',
    resubmittedAt: null, updatedAt: '2026-09-29T10:05:00Z',
    ...patch,
  }
}

function section(patch: Partial<VendorSection> = {}): VendorSection {
  return { enabled: true, paused: false, vendorOrder: detail(), available: [], calls: [], actions: [], ...patch }
}

describe('vendor wording', () => {
  it('says what was ordered and delivered, and what happens at FUT Transfer, before stop, resume and finish', () => {
    for (const action of ['STOP', 'RESUME', 'MARK_FINISHED'] as const) {
      const q = vendorQuestion(action, REF, section())
      expect(q).toContain('Ordered 500K')
      expect(q).toContain('delivered 420K')
      expect(q).toContain('FUT Transfer')
    }
    expect(vendorQuestion('STOP', REF, section())).toContain('FUT Transfer stops sending coins. What has been '
      + 'delivered stays delivered. The order stays stopped here until someone presses Resume at partner')
    expect(vendorQuestion('RESUME', REF, section())).toContain('FUT Transfer starts delivering the rest again, '
      + 'from where it stopped.')
    expect(vendorQuestion('MARK_FINISHED', REF, section())).toContain('FUT Transfer closes the order and delivers '
      + 'nothing more. This cannot be undone.')
  })

  it('asks for the dashboard check before allowing a new send, and names the note when resolving', () => {
    expect(vendorQuestion('RETRY', REF, section())).toContain('checked the FUT Transfer dashboard')
    expect(vendorQuestion('RESOLVE', REF, section(), { note: 'Refunded in full' })).toContain('Refunded in full')
    expect(vendorQuestion('SEND_SIGN_IN', REF, section())).toContain('No new order is created')
  })

  it('replaces Approve once the partner has the order, but not when nothing was created', () => {
    expect(approveReplacedBy(null)).toBeNull()
    expect(approveReplacedBy(section({ vendorOrder: null }))).toBeNull()
    expect(approveReplacedBy(section({ vendorOrder: detail({ state: 'FAILED' }) }))).toBeNull()
    expect(approveReplacedBy(section({ vendorOrder: detail({ state: 'AWAITING_CUSTOMER' }) })))
      .toContain('waiting for the customer')
    expect(approveReplacedBy(section({ vendorOrder: detail({ state: 'NEEDS_REVIEW' }) }))).toContain('Needs review')
  })

  it('puts calls and actions in one history, newest first', () => {
    const items = vendorTimeline(section({
      calls: [{ at: '2026-09-29T10:00:00Z', endpoint: '/orderAPI', domain: 'PRIMARY', httpStatus: 200, result: 'ACCEPTED',
        errorCode: null, vendorOrderId: 'vid-1', durationMs: 120 }],
      actions: [{ at: '2026-09-29T11:00:00Z', action: 'STOP', actorLabel: 'admin@example.test', outcome: 'DONE',
        code: null, detail: 'Stopped.' }],
    }))
    expect(items.map((i) => i.kind)).toEqual(['action', 'call'])
  })
})

describe('VendorPanel', () => {
  beforeEach(() => {
    api.post.mockReset()
    api.get.mockReset()
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  function renderPanel(s: VendorSection, onChanged = vi.fn()) {
    render(<VendorPanel publicRef={REF} section={s} onChanged={onChanged} />)
    return onChanged
  }

  it('shows what the partner reports, and its own figure for the cost once it gives one', () => {
    renderPanel(section())
    expect(screen.getByText('vid-1')).toBeInTheDocument()
    expect(screen.getByText('420K')).toBeInTheDocument()
    expect(screen.getByText('Actual cost (FUT Transfer)').nextSibling).toHaveTextContent(/^€12\.50$/)
    expect(screen.queryByText(/To pay/)).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()

    renderPanel(section({ vendorOrder: detail({ toPay: null }) }))
    expect(screen.getAllByText('Actual cost (FUT Transfer)')).toHaveLength(1)
  })

  it('shows how it was sent, with every partner figure labelled as unconfirmed', async () => {
    api.get.mockResolvedValue({ balance: 4321.5, available: true, currency: 'unconfirmed' })
    renderPanel(section({
      currentOrderMode: 'PUBLIC_POOL',
      vendorOrder: detail({ orderMode: 'PUBLIC_POOL', transferMethod: 'targetedSnipe', buyNowThresholdSent: 4.4,
        supplierRateSent: 4, thresholdMarginPct: 10, transferFeeAtSend: 0, estimatedCostEur: 20,
        thresholdSource: 'MARKET', balanceAtSend: 5000 }),
      customerPaid: '₹4,500.00',
    }))

    expect(screen.getByText('Order mode').nextSibling).toHaveTextContent('Public pool')
    expect(screen.getByText('Transfer method').nextSibling).toHaveTextContent('targetedSnipe')
    // What the ceiling was worked out from, and what it implies, beside what the customer paid.
    expect(screen.getByText('Supplier rate (€ per 100K)').nextSibling).toHaveTextContent(/^€4\.00$/)
    expect(screen.getByText('Margin').nextSibling).toHaveTextContent(/^10%$/)
    expect(screen.getByText('Price ceiling per 100K (buyNowThreshold) sent').nextSibling).toHaveTextContent(/^€4\.40$/)
    expect(screen.getByText('Estimated cost (FUT Transfer)').nextSibling).toHaveTextContent(/^€20\.00$/)
    expect(screen.getByText('Customer paid').nextSibling).toHaveTextContent('₹4,500.00')
    expect(screen.getByText('maxPrice sent').nextSibling).toHaveTextContent('Not sent')
    expect(screen.getByText('Actual cost (FUT Transfer)').nextSibling).toHaveTextContent('€12.50')
    // Worked out from the market: the cells say how, and no note is needed.
    expect(screen.queryByTestId('ceiling-note')).toBeNull()
    expect(screen.queryByText('Fixed')).toBeNull()
    expect(screen.queryByText('Not recorded')).toBeNull()
    expect(screen.getByText('Balance at send (currency unconfirmed)').nextSibling).toHaveTextContent('5,000')
    expect(await screen.findByText('4,321.5')).toBeInTheDocument()
    expect(screen.getByText(/FUT Transfer balance \(currency unconfirmed\)/)).toBeInTheDocument()
    expect(api.get).toHaveBeenCalledWith('/api/v1/admin/vendor/balance')
    expect(screen.queryByText(/approving it again would use/)).toBeNull()
  })

  it('an order sent before automatic pricing says its ceiling was the order size, a maximum, not the price', () => {
    renderPanel(section({
      currentOrderMode: 'PUBLIC_POOL',
      vendorOrder: detail({ orderMode: 'PUBLIC_POOL', transferMethod: 'targetedSnipe', amountOrderedK: 50,
        vendorAmountOrderedK: 50, deliveredK: 20, buyNowThresholdSent: 50, thresholdSource: null, toPay: null }),
    }))
    expect(screen.getByTestId('ceiling-note')).toHaveTextContent('Sent before automatic pricing: ceiling was the order '
      + 'size (€50.00 for 50K). This is a maximum per 100K, not the price charged.')
    expect(screen.getByText('Price ceiling per 100K (buyNowThreshold) sent').nextSibling).toHaveTextContent('€50.00')
    // No more "Fixed" or "Not recorded", and no estimate made up for it.
    expect(screen.queryByText('Fixed')).toBeNull()
    expect(screen.queryByText('Not recorded')).toBeNull()
    expect(screen.queryByText('Supplier rate (€ per 100K)')).toBeNull()
    expect(screen.queryByText('Estimated cost (FUT Transfer)')).toBeNull()
  })

  it('a fixed ceiling set on Render says so', () => {
    renderPanel(section({
      vendorOrder: detail({ orderMode: 'PUBLIC_POOL', buyNowThresholdSent: 4.1, thresholdSource: 'FIXED_OVERRIDE' }),
    }))
    expect(screen.getByTestId('ceiling-note')).toHaveTextContent('Fixed override from Render: €4.10.')
    expect(screen.queryByText('Margin')).toBeNull()
  })

  it('the red box shows while the order needs someone, with the latest report, and goes once it recovers', () => {
    const { rerender } = render(<VendorPanel publicRef={REF} onChanged={vi.fn()} section={section({
      vendorOrder: detail({ state: 'NEEDS_REVIEW', lastErrorCode: 'UNKNOWN_ECONOMY_STATE', deliveredK: 20,
        reviewReason: 'The partner reports status partlyDelivered; 20K of 500K delivered.' }),
    })} />)
    expect(screen.getByTestId('review-box')).toHaveTextContent('UNKNOWN_ECONOMY_STATE')
    expect(screen.getByTestId('review-latest')).toHaveTextContent(/^Latest report: 20 of 500 K delivered, /)

    // The next report: the box follows it.
    rerender(<VendorPanel publicRef={REF} onChanged={vi.fn()} section={section({
      vendorOrder: detail({ state: 'NEEDS_REVIEW', lastErrorCode: 'UNKNOWN_ECONOMY_STATE', deliveredK: 28,
        reviewReason: 'The partner reports status partlyDelivered; 28K of 500K delivered.' }),
    })} />)
    expect(screen.getByTestId('review-latest')).toHaveTextContent('28 of 500 K delivered')

    // Recovered: delivering again, the box is gone; the history stays in Calls and actions.
    rerender(<VendorPanel publicRef={REF} onChanged={vi.fn()} section={section({
      vendorOrder: detail({ state: 'IN_DELIVERY', lastErrorCode: 'UNKNOWN_ECONOMY_STATE', deliveredK: 31,
        reviewReason: 'The partner reports status partlyDelivered; 28K of 500K delivered.' }),
      actions: [{ at: '2026-10-15T16:20:00Z', action: 'RESUME', actorLabel: 'admin@example.test', outcome: 'DONE',
        code: null, detail: 'Resumed at the partner.' }],
    })} />)
    expect(screen.queryByTestId('review-box')).toBeNull()
    expect(screen.getByText('Calls and actions')).toBeInTheDocument()
  })

  it('a stopped order says who stopped it and when, and offers Resume', () => {
    renderPanel(section({
      vendorOrder: detail({ state: 'STOPPED', stoppedBy: 'admin@example.test', stoppedAt: '2026-10-15T16:21:00Z',
        lastErrorCode: 'STOPPED_BY_ADMIN' }),
      available: ['RESUME', 'MARK_FINISHED', 'UPDATE_SETTINGS', 'RESOLVE'],
    }))
    expect(screen.getAllByText('Stopped').length).toBeGreaterThan(0)
    expect(screen.getByTestId('review-box')).toHaveTextContent('Stopped at FUT Transfer by admin@example.test')
    expect(screen.getByTestId('review-box')).toHaveTextContent('It stays stopped until someone presses Resume at partner.')
    expect(screen.getByRole('button', { name: 'Resume at partner' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Stop at partner' })).toBeNull()
  })

  it('names the partner codes we do not know yet', () => {
    renderPanel(section({ vendorOrder: detail({ unknownWords: ['economyState:transfersomethingnew'] }) }))
    expect(screen.getByTestId('unknown-words')).toHaveTextContent('economyState:transfersomethingnew')
  })

  it('while a request runs every action button is held, and the section reloads after it, done or not', async () => {
    let finish: (v: unknown) => void = () => {}
    api.post.mockImplementation(() => new Promise((resolve) => { finish = resolve }))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const onChanged = renderPanel(section({ available: ['RESUME', 'STOP', 'MARK_FINISHED'] }))

    fireEvent.click(screen.getByRole('button', { name: 'Stop at partner' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Resume at partner' })).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Mark finished at partner' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Stop at partner/ })).toBeDisabled()
    // A second press while it runs sends nothing more.
    fireEvent.click(screen.getByRole('button', { name: 'Resume at partner' }))
    expect(api.post).toHaveBeenCalledTimes(1)

    finish({ status: 'DONE', message: 'Stopped at the partner.' })
    await screen.findByText('Stopped at the partner.')
    expect(onChanged).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Resume at partner' })).toBeEnabled())

    // A refusal reloads too, and says why.
    api.post.mockRejectedValue(new ApiError(409, { message: 'Resume is only for stopped or held orders; this order '
      + '(GFS-26-VENDOR01) is delivering. Nothing was sent.' } as never))
    fireEvent.click(screen.getByRole('button', { name: 'Resume at partner' }))
    expect(await screen.findByText(/Resume is only for stopped or held orders/)).toBeInTheDocument()
    expect(onChanged).toHaveBeenCalledTimes(2)
  })

  const POOL_SEND = {
    orderMode: 'PUBLIC_POOL', endpoint: '/buyCoinsAPI', transferMethod: 'targetedSnipe', buyNowThreshold: 4.4,
    buyNowThresholdSource: "FUT Transfer's supplier price + 10%, read at each send", maxPrice: null,
    topUpEnabled: 300, autoFinishCycle: 1, senderGroup: null, refusal: null, minTransferAmount: 0,
    pauseIfBelowMinTransfer: 0, riskLevel: 1, supplierRate: 4, marginPct: 10, limit: null, estimatedCostEur: 20.5,
    priceNote: 'supplier rate + margin',
  }

  it('before anything is sent, says what Approve will send, field by field, with the live price', () => {
    renderPanel(section({ vendorOrder: null, currentOrderMode: 'PUBLIC_POOL', nextSend: POOL_SEND }))
    const note = screen.getByRole('note', { name: 'What Approve sends' })
    expect(note).toHaveTextContent('Approve sends this order to')
    expect(note).toHaveTextContent('Public pool · /buyCoinsAPI')
    expect(note).toHaveTextContent('transferMethod targetedSnipe · price ceiling per 100K (buyNowThreshold) €4.40 · '
      + 'minTransferAmount 0 · pauseIfBelowMinTransfer 0 · topUpEnabled 300 · autoFinishCycle 1 · riskLevel 1 · '
      + 'no senderGroup, no supplierID · maxPrice not sent')
    expect(screen.getByTestId('live-price')).toHaveTextContent('FUT Transfer now: supplier rate €4.00/100K · margin '
      + '10% · limit none · estimated cost €20.50 (supplier rate + margin)')
    expect(note).not.toHaveTextContent("order's amount")
    expect(note).not.toHaveTextContent('own sender accounts')
  })

  it('a market above the limit: Approve would send nothing, with the price and the limit', () => {
    renderPanel(section({ vendorOrder: null, currentOrderMode: 'PUBLIC_POOL', nextSend: { ...POOL_SEND,
      buyNowThreshold: null, marginPct: null, limit: 3.5, estimatedCostEur: 2.05, priceNote: null,
      refusal: 'FUT Transfer price €4/100K is above your limit €3.5. Approve would send nothing.' } }))
    expect(screen.getByRole('note', { name: 'What Approve sends' }))
      .toHaveTextContent('Approve would send nothing: FUT Transfer price €4/100K is above your limit €3.5.')
    expect(screen.getByTestId('live-price')).toHaveTextContent('supplier rate €4.00/100K · limit €3.50/100K')
    expect(document.body.textContent).not.toContain('undefined')
  })

  it('own senders is said as a warning, with how to switch', () => {
    renderPanel(section({ vendorOrder: null, nextSend: { ...POOL_SEND, orderMode: 'OWN_SENDERS', endpoint: '/orderAPI',
      buyNowThreshold: null, buyNowThresholdSource: null, senderGroup: '-1' } }))
    const note = screen.getByRole('note', { name: 'What Approve sends' })
    expect(note).toHaveTextContent('Own senders · /orderAPI')
    expect(note).toHaveTextContent('senderGroup -1')
    expect(note).not.toHaveTextContent('buyNowThreshold')
    expect(note).toHaveTextContent('GFS_FUTTRANSFER_ORDER_MODE=PUBLIC_POOL')
  })

  it('a setting that stops the send is shown before Approve, as the reason', () => {
    renderPanel(section({ vendorOrder: null, nextSend: { ...POOL_SEND, buyNowThreshold: null, supplierRate: null,
      marginPct: null, estimatedCostEur: null, priceNote: null, refusal: 'GFS_FUTTRANSFER_METHOD is cycle.' } }))
    expect(screen.getByRole('note', { name: 'What Approve sends' }))
      .toHaveTextContent('Approve would send nothing: GFS_FUTTRANSFER_METHOD is cycle.')
  })

  it('once the partner has the order, Approve sends nothing, so the note is not shown', () => {
    renderPanel(section({ nextSend: POOL_SEND }))
    expect(screen.queryByRole('note', { name: 'What Approve sends' })).toBeNull()
    renderPanel(section({ vendorOrder: detail({ state: 'FAILED' }), nextSend: POOL_SEND }))
    expect(screen.getByRole('note', { name: 'What Approve sends' })).toBeInTheDocument()
  })

  it('says Unavailable, never zero, when the balance cannot be read', async () => {
    api.get.mockRejectedValue(new Error('offline'))
    renderPanel(section())
    expect(await screen.findByText('Unavailable')).toBeInTheDocument()

    api.get.mockResolvedValue({ available: false, currency: 'unconfirmed' })
    renderPanel(section())
    await waitFor(() => expect(screen.getAllByText('Unavailable')).toHaveLength(2))
    expect(document.body.textContent).not.toContain('undefined')
  })

  it('an order sent before modes were recorded says so, and warns that approving again would use the new one', () => {
    // The API omits the fields it has no value for.
    renderPanel(section({ currentOrderMode: 'PUBLIC_POOL', vendorOrder: detail({ orderMode: 'OWN_SENDERS' }) }))
    expect(screen.getByText('Transfer method').nextSibling).toHaveTextContent('Not recorded')
    expect(screen.getByText('Price ceiling per 100K (buyNowThreshold) sent').nextSibling).toHaveTextContent('Not sent')
    expect(screen.getByText(/Sent as Own senders\. Configured now: Public pool/)).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('undefined')
  })

  it('shows a dash, never "undefined", for what the server leaves out of its answer', () => {
    // The API omits empty fields rather than sending null.
    const sparse = { ...detail() } as Partial<VendorOrderDetail>
    delete sparse.deliveredK
    delete sparse.coinsUsed
    delete sparse.toPay
    delete sparse.submittedAt
    renderPanel(section({ vendorOrder: sparse as VendorOrderDetail }))
    expect(document.body.textContent).not.toContain('undefined')
    expect(screen.getByText('Delivered').nextSibling).toHaveTextContent('—')
  })

  it('offers only what the server says applies, asks first, and posts to that action', async () => {
    api.post.mockResolvedValue({ status: 'DONE', message: 'Stopped at the partner.' })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const onChanged = renderPanel(section({ available: ['STOP', 'MARK_FINISHED'] }))

    expect(screen.queryByRole('button', { name: 'Resume at partner' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Stop at partner' }))

    await screen.findByText('Stopped at the partner.')
    expect(confirm).toHaveBeenCalledWith(vendorQuestion('STOP', REF, section()))
    expect(api.post).toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/vendor/stop`, undefined)
    expect(onChanged).toHaveBeenCalled()
  })

  it('updates the settings at the partner: says it sends minTransferAmount 0 and nothing else, then posts', async () => {
    api.post.mockResolvedValue({ status: 'DONE', message: 'minTransferAmount 0 sent to the partner, and it applied it.' })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const s = section({ available: ['UPDATE_SETTINGS', 'STOP'] })
    renderPanel(s)

    fireEvent.click(screen.getByRole('button', { name: 'Update settings at partner' }))

    await screen.findByText(/minTransferAmount 0 sent to the partner/)
    const question = vendorQuestion('UPDATE_SETTINGS', REF, s)
    expect(confirm).toHaveBeenCalledWith(question)
    expect(question).toContain('minTransferAmount 0')
    expect(question).toContain('Nothing else about the order changes')
    expect(api.post).toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/vendor/update-settings`, undefined)
    expect(historyLabel('UPDATE_SETTINGS')).toBe('Update settings at partner')
  })

  it('sends nothing when the question is declined', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderPanel(section({ available: ['MARK_FINISHED'] }))
    fireEvent.click(screen.getByRole('button', { name: 'Mark finished at partner' }))
    expect(api.post).not.toHaveBeenCalled()
  })

  it('needs a note to resolve, and sends it', async () => {
    api.post.mockResolvedValue({ status: 'DONE', message: 'Resolved: Refunded in full' })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPanel(section({ vendorOrder: detail({ state: 'NEEDS_REVIEW' }), available: ['RESOLVE'] }))

    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }))
    expect(await screen.findByText(/Say how it was settled/)).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText(/How it was settled/), { target: { value: 'Refunded in full' } })
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/vendor/resolve`,
      { note: 'Refunded in full' }))
  })

  it('sends the confirmation with a retry, and the partner id with a link', async () => {
    api.post.mockResolvedValue({ status: 'DONE', message: 'Done.' })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPanel(section({ vendorOrder: detail({ state: 'NEEDS_REVIEW', vendorOrderId: null }), available: ['RETRY', 'LINK'] }))

    fireEvent.click(screen.getByRole('button', { name: 'Allow sending again' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/vendor/retry`,
      { confirmedAbsent: true }))

    fireEvent.change(screen.getByLabelText(/Partner order id/), { target: { value: ' vid-9 ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Link partner order' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith(`/api/v1/admin/orders/${REF}/vendor/link`,
      { vendorOrderId: 'vid-9' }))
  })

  it("shows the server's reason when an action is refused, and still reloads", async () => {
    api.post.mockRejectedValue(new ApiError(502, { message: 'The partner would not stop the order (TempBan). Nothing changed.' } as never))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const onChanged = renderPanel(section({ available: ['STOP'] }))

    fireEvent.click(screen.getByRole('button', { name: 'Stop at partner' }))

    expect(await screen.findByText(/TempBan/)).toBeInTheDocument()
    expect(onChanged).toHaveBeenCalled()
  })
})

describe('VendorReview', () => {
  const CONTROL: VendorControlState = { paused: false, pausedAt: null, reason: null, resumedAt: null }

  beforeEach(() => {
    api.get.mockReset()
    api.post.mockReset()
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  function stub(items: unknown[], control = CONTROL) {
    api.get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/admin/vendor/needs-review') return items
      if (path === '/api/v1/admin/vendor/control') return control
      throw new Error(`unexpected GET ${path}`)
    })
  }

  it('says nothing when nothing needs a decision', async () => {
    stub([])
    const { container } = render(<MemoryRouter><VendorReview /></MemoryRouter>)
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2))
    expect(container).toBeEmptyDOMElement()
  })

  it('lists what needs a decision, each opening its order', async () => {
    stub([{ externalRef: REF, state: 'PARTIALLY_DELIVERED', lastErrorCode: 'SHORT_DELIVERY',
      reviewReason: 'Finished short.', amountOrderedK: 500, deliveredK: 420, updatedAt: '2026-09-29T10:00:00Z' }])
    render(<MemoryRouter><VendorReview /></MemoryRouter>)

    const link = await screen.findByRole('link', { name: REF })
    expect(link).toHaveAttribute('href', `/admin/orders/${REF}`)
    expect(screen.getByText('Partly delivered')).toBeInTheDocument()
    expect(screen.getByText('420K of 500K')).toBeInTheDocument()
  })

  it('shows a dash, never "undefined", when nothing has been delivered yet', async () => {
    // As the API sends it: no deliveredK at all.
    stub([{ externalRef: REF, state: 'NEEDS_REVIEW', lastErrorCode: 'MISSING_FROM_POLL', amountOrderedK: 1000,
      updatedAt: '2026-09-29T10:00:00Z' }])
    render(<MemoryRouter><VendorReview /></MemoryRouter>)

    expect(await screen.findByText('— of 1000K')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('undefined')
  })

  it('shows a pause, and resumes calls only after asking', async () => {
    stub([], { paused: true, pausedAt: '2026-09-29T09:00:00Z', reason: 'HTTP_403 /orderAPI', resumedAt: null })
    api.post.mockResolvedValue(CONTROL)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<MemoryRouter><VendorReview /></MemoryRouter>)

    fireEvent.click(await screen.findByRole('button', { name: 'Resume calls' }))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/admin/vendor/resume'))
    expect(confirm).toHaveBeenCalledWith(resumeCallsQuestion())
  })
})

describe('release question', () => {
  it('says how the order will be placed', () => {
    const q = releaseQuestion(REF, { orderMode: 'PUBLIC_POOL' })
    expect(q).toContain("It will be placed through the public pool — coins bought from FUT Transfer's sellers.")
    expect(q).not.toContain('last attempt')
    expect(q).toContain('cannot be undone')
  })

  it('warns when a retry is not placed the way the last attempt was', () => {
    const q = releaseQuestion(REF, { orderMode: 'PUBLIC_POOL', lastAttemptMode: 'OWN_SENDERS' })
    expect(q).toContain('The last attempt used own senders — coins sent from our own sender accounts. This one will not.')
    expect(releaseQuestion(REF, { orderMode: 'OWN_SENDERS', lastAttemptMode: 'OWN_SENDERS' })).not.toContain('last attempt')
  })

  it('says so when it could not be checked, rather than guessing', () => {
    expect(releaseQuestion(REF, null)).toContain('How it will be placed could not be checked.')
  })
})
