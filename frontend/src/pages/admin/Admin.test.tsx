import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminOrderOverview, AdminOrderRow } from '../../lib/types'

// ---- the API, mocked: these tests are about the page, not the server ---------------
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), del: vi.fn(), blobUrl: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  // Shaped like the real one, so the page reads the server's message off it.
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, body: { message: string }) {
      super(body.message)
    }
  },
}))

const auth = vi.hoisted(() => ({ role: 'ADMIN' as 'ADMIN' | 'OPERATOR' }))
vi.mock('../../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'staff@example.test', role: auth.role } }),
}))

// Imported after the mocks are declared, so the page picks up the mocked modules.
const { default: Admin } = await import('./Admin')
const { releaseQuestion } = await import('./orders/confirmations')

const OVERVIEW: AdminOrderOverview = {
  counts: [
    { sku: 'COACHING', status: 'AWAITING_PAYMENT', count: 1 },
    { sku: 'TRADING_SERVICE', status: 'READY_FOR_DELIVERY', count: 2 },
    { sku: 'BOOST_CHAMPS', status: 'IN_PROGRESS', count: 1 },
    { sku: 'TRADING_SERVICE', status: 'CREDENTIALS_PENDING', count: 3 },
  ],
  paymentsToCheck: 2, signInsToWork: 3, disputed: 1, awaitingSignIn: 3,
  deliveredToday: 24, deliveredYesterdaySoFar: 20, credentialsHeld: 6,
}

function row(patch: Partial<AdminOrderRow>): AdminOrderRow {
  return {
    publicRef: 'GFS-26-CN43SP05', status: 'AWAITING_PAYMENT', sku: 'COACHING',
    serviceLabel: 'FUT Classes — Single session · 1 hour', variant: 'SINGLE_SESSION', quantity: 1,
    platform: 'PLAYSTATION', deliveryMethod: 'SCHEDULED_SESSION', credentialsHeld: false, withPartner: false,
    customerName: 'Rahul_07', customerEmail: 'rahul07@example.test', paymentState: 'SUBMITTED',
    paymentMethod: 'UPI', paymentReference: '412345678901', eaHandle: null, totalMinor: 102500,
    totalFormatted: '₹1,025.00', currency: 'INR', createdAt: '2026-09-26T10:04:00Z', deliveredAt: null,
    availableTransitions: [],
    ...patch,
  }
}

const ROWS = [
  row({}),
  row({
    publicRef: 'GFS-26-70C4DPPW', status: 'READY_FOR_DELIVERY', sku: 'TRADING_SERVICE',
    serviceLabel: 'Buy Coins — 500K (PlayStation)', credentialsHeld: true, paymentState: 'VERIFIED',
    customerName: null, customerEmail: 'sharvin@example.test',
  }),
  row({
    publicRef: 'GFS-26-N84UVQ28', status: 'CREDENTIALS_PENDING', sku: 'BOOST_CHAMPS',
    serviceLabel: 'Champs Boosting — 14 wins · Elite II', paymentState: 'VERIFIED',
  }),
]

/** How Approve would send it: the public pool now, and the last attempt from our own senders. */
const PREVIEW = { orderMode: 'PUBLIC_POOL', lastAttemptMode: 'OWN_SENDERS' }

function stubApi({ search = ROWS, overview = OVERVIEW as AdminOrderOverview | Error } = {}) {
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/admin/orders/overview') {
      if (overview instanceof Error) throw overview
      return overview
    }
    if (path.startsWith('/api/v1/admin/orders/search')) return { items: search, total: search.length, page: 0, size: 25 }
    if (path === '/api/v1/admin/payment-claims') return []
    if (path.startsWith('/api/v1/admin/saved-views')) return []
    if (path === '/api/v1/admin/vendor/needs-review') return []
    if (path === '/api/v1/admin/vendor/control') return { paused: false, pausedAt: null, reason: null, resumedAt: null }
    if (path.endsWith('/release-preview')) return PREVIEW
    throw new Error(`unexpected GET ${path}`)
  })
}

function renderAt(path = '/admin/orders') {
  return render(<MemoryRouter initialEntries={[path]}><Admin /></MemoryRouter>)
}

const searchCalls = () => api.get.mock.calls.map(([p]) => p as string).filter((p) => p.includes('/orders/search'))
const lastSearch = () => searchCalls()[searchCalls().length - 1]

describe('Orders page', () => {
  beforeEach(() => {
    auth.role = 'ADMIN'
    api.get.mockReset()
    api.post.mockReset()
    api.blobUrl.mockReset()
    stubApi()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the six cards from real counts, with no money in them', async () => {
    renderAt()

    const cards = await screen.findByText('2 Payments • 3 Sign-ins • 1 Disputed')
    expect(cards).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Needs Attention 6' })).toBeInTheDocument()
    expect(screen.getByText('3 Sign-ins')).toBeInTheDocument()
    expect(screen.getByText('1 Boosting')).toBeInTheDocument()
    expect(screen.getByText('No orders on hold')).toBeInTheDocument()
    expect(screen.getByText('20%')).toBeInTheDocument()
    // The Orders page never asks for revenue.
    expect(api.get).not.toHaveBeenCalledWith('/api/v1/admin/analytics/revenue')
  })

  it('draws each row with its customer, service, status and next action', async () => {
    renderAt()

    const first = (await screen.findByText('#GFS-26-CN43SP05')).closest('tr')!
    expect(within(first).getByText('Rahul_07')).toBeInTheDocument()
    expect(within(first).getByText('Coaching')).toBeInTheDocument()
    expect(within(first).getByText('Single session · 1 hour')).toBeInTheDocument()
    expect(within(first).getByText('Awaiting payment')).toBeInTheDocument()
    expect(within(first).getByRole('button', { name: 'Verify Payment, order GFS-26-CN43SP05' })).toBeInTheDocument()
    expect(within(first).getByText('PlayStation')).toBeInTheDocument()

    const second = screen.getByText('#GFS-26-70C4DPPW').closest('tr')!
    expect(within(second).getByText('Sign-in held')).toBeInTheDocument()
    expect(within(second).getByRole('button', { name: /Start Order/ })).toBeInTheDocument()

    expect(screen.getByText('Showing 1–3 of 3 orders')).toBeInTheDocument()
  })

  it('offers Export to an admin', async () => {
    renderAt()
    expect(await screen.findByRole('button', { name: 'Export' })).toBeInTheDocument()
  })

  it('does not offer Export to an operator, whose request would be refused', async () => {
    auth.role = 'OPERATOR'
    renderAt()
    await screen.findByText('#GFS-26-CN43SP05')
    expect(screen.queryByRole('button', { name: 'Export' })).toBeNull()
    // Everything else on the page is theirs.
    expect(screen.getByRole('button', { name: 'Needs Attention 6' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Saved Views/ })).toBeInTheDocument()
  })

  it('says the table could not load, and offers to try again, rather than showing it empty', async () => {
    api.get.mockImplementation(async (path: string) => {
      if (path.startsWith('/api/v1/admin/orders/search')) throw new Error('offline')
      if (path === '/api/v1/admin/orders/overview') return OVERVIEW
      return []
    })
    renderAt()

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load orders')
    expect(screen.queryByText('No orders yet')).toBeNull()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('shows dashes, not zeros, when the counts could not be read', async () => {
    stubApi({ overview: new Error('offline') })
    renderAt()

    await screen.findByText('#GFS-26-CN43SP05')
    expect(screen.getAllByText('Could not load').length).toBeGreaterThanOrEqual(6)
  })

  it('names the filter when nothing matches, and offers to clear it', async () => {
    stubApi({ search: [] })
    renderAt('/admin/orders?status=DISPUTED')

    expect(await screen.findByText('No orders match these filters')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Clear the filters' }))
    await waitFor(() => expect(lastSearch()).toBe('/api/v1/admin/orders/search?page=0&size=25'))
  })

  it('sends the real statuses behind a tab, and Boosting as both boosting services', async () => {
    renderAt()
    await screen.findByText('#GFS-26-CN43SP05')

    fireEvent.click(screen.getByRole('button', { name: /^Completed/ }))
    await waitFor(() => expect(lastSearch()).toContain('status=DELIVERED%2CCOMPLETED'))

    fireEvent.click(screen.getByRole('button', { name: /^Boosting/ }))
    await waitFor(() => expect(lastSearch()).toContain('service=BOOSTING'))
  })

  it('starts from the top bar’s search', async () => {
    renderAt('/admin/orders?search=rahul')
    await screen.findByText('#GFS-26-CN43SP05')
    expect(searchCalls()[0]).toContain('search=rahul')
    expect(screen.getByLabelText(/Search orders by reference/)).toHaveValue('rahul')
  })

  it('Needs Attention filters the table and opens the payments to check', async () => {
    renderAt()
    await screen.findByText('#GFS-26-CN43SP05')

    fireEvent.click(screen.getByRole('button', { name: 'Needs Attention 6' }))

    await waitFor(() => expect(lastSearch()).toContain('attention=true'))
    expect(await screen.findByRole('heading', { name: 'Payments to check' })).toBeInTheDocument()
    expect(api.get).toHaveBeenCalledWith('/api/v1/admin/payment-claims')
  })

  it('Verify Payment opens that order’s claim, not a new way to mark it paid', async () => {
    renderAt()
    fireEvent.click(await screen.findByRole('button', { name: 'Verify Payment, order GFS-26-CN43SP05' }))

    expect(await screen.findByRole('heading', { name: 'Payments to check' })).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()
  })

  it('Start Order on a coin order asks the order page’s release question, then releases it', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    api.post.mockResolvedValue({})
    renderAt()

    fireEvent.click(await screen.findByRole('button', { name: 'Start Order, order GFS-26-70C4DPPW' }))

    await waitFor(() => expect(confirm).toHaveBeenCalledWith(releaseQuestion('GFS-26-70C4DPPW', PREVIEW)))
    expect(api.get).toHaveBeenCalledWith('/api/v1/admin/orders/GFS-26-70C4DPPW/release-preview')
    // A retry that changes how the order is placed says so before anything is sent.
    expect(confirm.mock.calls[0]?.[0]).toContain("the public pool — coins bought from FUT Transfer's sellers")
    expect(confirm.mock.calls[0]?.[0]).toContain('The last attempt used own senders')
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/admin/orders/GFS-26-70C4DPPW/approve-fulfilment'))
    expect(await screen.findByRole('status')).toHaveTextContent('released to the fulfilment partner')
  })

  it('Request Sign-in asks first, then sends the reminder', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderAt()

    const button = await screen.findByRole('button', { name: 'Request Sign-in, order GFS-26-N84UVQ28' })
    fireEvent.click(button)
    expect(confirm).toHaveBeenCalled()
    expect(api.post).not.toHaveBeenCalled()

    confirm.mockReturnValue(true)
    api.post.mockResolvedValue({ sentAt: '2026-09-27T06:00:00Z' })
    fireEvent.click(button)
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/admin/orders/GFS-26-N84UVQ28/credentials/remind'))
    expect(await screen.findByRole('status')).toHaveTextContent('Reminder sent to rahul07@example.test')
  })

  it('shows the server’s reason when an action is refused', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { ApiError } = await import('../../lib/api')
    api.post.mockRejectedValue(new ApiError(409, { error: 'reminded_recently', message: 'A reminder went out recently.' }))
    renderAt()

    fireEvent.click(await screen.findByRole('button', { name: 'Request Sign-in, order GFS-26-N84UVQ28' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('A reminder went out recently.')
  })
})
