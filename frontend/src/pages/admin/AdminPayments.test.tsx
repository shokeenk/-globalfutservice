import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminPayment, AdminPaymentOverview } from '../../lib/types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), blobUrl: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, body: { message: string }) { super(body.message) }
  },
}))

const auth = vi.hoisted(() => ({ role: 'ADMIN' as 'ADMIN' | 'OPERATOR' }))
vi.mock('../../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'staff@example.test', role: auth.role } }),
}))

const { default: Payments } = await import('./AdminPayments')
const { formatMinor, otherCurrencies, sum } = await import('./payments/totals')

function payment(patch: Partial<AdminPayment>): AdminPayment {
  return {
    claimId: 1, publicRef: 'GFS-26-CN43SP05', customerName: 'Rahul Sharma', email: 'rahul07@example.test',
    method: 'UPI', reference: '412345678901', destination: '9166172359@ybl', status: 'SUCCESS',
    orderStatus: 'READY_FOR_DELIVERY', amountMinor: 825000, amountFormatted: '₹8,250.00', currency: 'INR',
    submittedAt: '2026-09-26T10:12:00Z', hasProof: false, ...patch,
  }
}

const ROWS: AdminPayment[] = [
  payment({}),
  payment({ claimId: 2, publicRef: 'GFS-26-7XPENDNG', status: 'PENDING', method: 'CRYPTO', customerName: null, reference: '0xabc' }),
  payment({ claimId: 3, publicRef: 'GFS-26-REFUNDED', status: 'REFUNDED', method: 'PAYPAL',
    refund: { amountMinor: 825000, amountFormatted: '₹8,250.00', method: 'UPI', reference: 'RF-1', reason: 'Could not deliver', at: '2026-09-26T12:00:00Z', by: 'Vinay' } }),
]

const OVERVIEW: AdminPaymentOverview = {
  thisMonth: [
    { status: 'SUCCESS', currency: 'INR', count: 132, minor: 31842000, formatted: '₹3,18,420.00' },
    { status: 'PENDING', currency: 'INR', count: 5, minor: 1243000, formatted: '₹12,430.00' },
    { status: 'FAILED', currency: 'INR', count: 3, minor: 425000, formatted: '₹4,250.00' },
    { status: 'REFUNDED', currency: 'INR', count: 2, minor: 765000, formatted: '₹7,650.00' },
    { status: 'SUCCESS', currency: 'GBP', count: 1, minor: 2500, formatted: '£25.00' },
  ],
  lastMonthSoFar: [{ status: 'SUCCESS', currency: 'INR', count: 100, minor: 28325000, formatted: '₹2,83,250.00' }],
  allTime: { SUCCESS: 140, PENDING: 5, FAILED: 3, REFUNDED: 2 },
}

/** What an operator is sent: counts, no amounts. */
function countsOnly(o: AdminPaymentOverview): AdminPaymentOverview {
  const strip = (list: AdminPaymentOverview['thisMonth']) => list.map(({ status, currency, count }) => ({ status, currency, count }))
  return { ...o, thisMonth: strip(o.thisMonth), lastMonthSoFar: strip(o.lastMonthSoFar) }
}

function stub() {
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/admin/payments/overview') return auth.role === 'ADMIN' ? OVERVIEW : countsOnly(OVERVIEW)
    if (path.startsWith('/api/v1/admin/payments?')) return { items: ROWS, total: ROWS.length, page: 0, size: 25 }
    throw new Error(`unexpected GET ${path}`)
  })
}

const renderAt = (path = '/admin/payments') => render(<MemoryRouter initialEntries={[path]}><Payments /></MemoryRouter>)
const listCalls = () => api.get.mock.calls.map(([p]) => p as string).filter((p) => p.startsWith('/api/v1/admin/payments?'))

describe('Payments page', () => {
  beforeEach(() => {
    auth.role = 'ADMIN'
    api.get.mockReset()
    api.post.mockReset()
    stub()
  })
  afterEach(() => vi.restoreAllMocks())

  it('gives an admin this month in rupees, other currencies beside, against last month', async () => {
    renderAt()
    // 3,18,420 + 12,430 + 4,250 + 7,650 in rupees; the pounds are listed, not added.
    expect(await screen.findByText('₹3,42,750.00')).toBeInTheDocument()
    expect(screen.getByText(/143 payments · \+ £25\.00/)).toBeInTheDocument()
    expect(screen.getByText('₹3,18,420.00')).toBeInTheDocument()
    expect(screen.getByText('21%')).toBeInTheDocument() // 3,42,750 vs 2,83,250
    expect(screen.getByRole('button', { name: 'Record Refund' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Export' })).toBeInTheDocument()
  })

  it('writes a rise from nothing last month as money, not as a count of paise', async () => {
    api.get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/admin/payments/overview') return { ...OVERVIEW, lastMonthSoFar: [] }
      return { items: ROWS, total: ROWS.length, page: 0, size: 25 }
    })
    renderAt()
    expect(await screen.findByText('₹3,42,750.00 more')).toBeInTheDocument()
  })

  it('gives an operator counts, no money, and no refund or export', async () => {
    auth.role = 'OPERATOR'
    renderAt()
    expect(await screen.findByText('143')).toBeInTheDocument()
    expect(screen.queryByText(/₹3,42,750/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Record Refund' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Export' })).toBeNull()
  })

  it('lists each payment with its method and status in the one set of words', async () => {
    renderAt()
    const first = (await screen.findByText('#GFS-26-CN43SP05')).closest('tr')!
    expect(within(first).getByText('Verified')).toBeInTheDocument()
    expect(within(first).getByText('UPI')).toBeInTheDocument()
    const pending = screen.getByText('#GFS-26-7XPENDNG').closest('tr')!
    expect(within(pending).getByText('Pending')).toBeInTheDocument()
    expect(within(pending).getByText('USDT (TRON)')).toBeInTheDocument()
    const refunded = screen.getByText('#GFS-26-REFUNDED').closest('tr')!
    expect(within(refunded).getByRole('button', { name: /View refund/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^All Transactions/ })).toHaveTextContent('150')
  })

  it('filters on the server by tab and method', async () => {
    renderAt()
    await screen.findByText('#GFS-26-CN43SP05')
    fireEvent.click(screen.getByRole('button', { name: /^Rejected/ }))
    await waitFor(() => expect(listCalls()[listCalls().length - 1]).toContain('status=FAILED'))
    fireEvent.change(screen.getByLabelText('Payment Method'), { target: { value: 'PAYPAL' } })
    await waitFor(() => expect(listCalls()[listCalls().length - 1]).toContain('method=PAYPAL'))
  })

  it('verifies a pending payment from its panel, asking the queue’s question', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    api.post.mockResolvedValue({})
    renderAt('/admin/payments?payment=2')

    const panel = await screen.findByRole('complementary', { name: 'Payment details' })
    fireEvent.click(within(panel).getByRole('button', { name: 'Verify' }))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/admin/payment-claims/2/verify', { note: null }))
    expect(confirm.mock.calls[0]![0]).toContain('Confirm ₹8,250.00 arrived at 9166172359@ybl with reference 0xabc?')
  })

  it('shows the refund record on a refunded payment', async () => {
    renderAt('/admin/payments?payment=3')
    const panel = await screen.findByRole('complementary', { name: 'Payment details' })
    expect(within(panel).getByText('RF-1')).toBeInTheDocument()
    expect(within(panel).getByText('Could not deliver')).toBeInTheDocument()
  })

  it('records a refund for the full total, after saying what it does', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    api.post.mockResolvedValue({ publicRef: 'GFS-26-CN43SP05', amountMinor: 825000, amountFormatted: '₹8,250.00',
      method: 'UPI', reference: 'UTR-BACK-1', at: '2026-09-27T06:00:00Z' })
    renderAt('/admin/payments?payment=1')

    const panel = await screen.findByRole('complementary', { name: 'Payment details' })
    fireEvent.click(within(panel).getByRole('button', { name: 'Record Refund' }))
    const dialog = await screen.findByRole('dialog', { name: 'Record Refund' })
    expect(within(dialog).getByLabelText('Order')).toHaveValue('GFS-26-CN43SP05')
    fireEvent.change(within(dialog).getByLabelText(/Reference of the money/), { target: { value: 'UTR-BACK-1' } })
    fireEvent.change(within(dialog).getByLabelText(/Reason/), { target: { value: 'Could not deliver' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Record Refund' }))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/admin/payments/refunds', {
      publicRef: 'GFS-26-CN43SP05', method: 'UPI', reference: 'UTR-BACK-1', reason: 'Could not deliver',
    }))
    expect(confirm.mock.calls[0]![0]).toContain('points spent are returned')
    expect(await screen.findByRole('status')).toHaveTextContent('Refund of ₹8,250.00 recorded on GFS-26-CN43SP05')
  })

  it('says the list could not load rather than showing it empty', async () => {
    api.get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/admin/payments/overview') return OVERVIEW
      throw new Error('offline')
    })
    renderAt()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load payments')
  })
})

describe('payment totals', () => {
  it('writes rupees in lakhs and other currencies in thousands, as the server does', () => {
    expect(formatMinor(34285000, 'INR')).toBe('₹3,42,850.00')
    expect(formatMinor(102500, 'INR')).toBe('₹1,025.00')
    expect(formatMinor(99, 'INR')).toBe('₹0.99')
    expect(formatMinor(125000000, 'USD')).toBe('$1,250,000.00')
  })

  it('adds statuses within a currency and never across currencies', () => {
    expect(sum(OVERVIEW.thisMonth, ['SUCCESS', 'PENDING'])).toEqual({ count: 138, minor: 33085000 })
    expect(otherCurrencies(OVERVIEW.thisMonth, ['SUCCESS'])).toEqual(['£25.00'])
    expect(otherCurrencies(OVERVIEW.thisMonth, ['FAILED'])).toEqual([])
  })
})
