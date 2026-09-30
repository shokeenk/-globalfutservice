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
const { approveReplacedBy, vendorQuestion, vendorTimeline } = await import('./vendor')
const { VendorPanel } = await import('./VendorPanel')
const { VendorReview, resumeCallsQuestion } = await import('./VendorReview')

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
  it('says what was ordered and delivered before anything that ends an order', () => {
    for (const action of ['STOP', 'MARK_FINISHED'] as const) {
      const q = vendorQuestion(action, REF, section())
      expect(q).toContain('Ordered 500K')
      expect(q).toContain('delivered 420K')
      expect(q).toContain('cannot be undone')
    }
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
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  function renderPanel(s: VendorSection, onChanged = vi.fn()) {
    render(<VendorPanel publicRef={REF} section={s} onChanged={onChanged} />)
    return onChanged
  }

  it('shows what the partner reports, with the cost labelled as unconfirmed', () => {
    renderPanel(section())
    expect(screen.getByText('vid-1')).toBeInTheDocument()
    expect(screen.getByText('420K')).toBeInTheDocument()
    expect(screen.getByText('To pay (currency unconfirmed)')).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
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
