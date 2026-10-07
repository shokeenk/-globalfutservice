import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { OrderPaymentStaffView } from '../../../lib/types'

/* Staff see how the customer is paying: the current method and its fee, and every attempt. */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))

const { PaymentPanel } = await import('./PaymentPanel')

const STAFF: OrderPaymentStaffView = {
  current: { kind: 'PAYOP', method: 'Wallet', status: 'OPEN', note: null, totalMinor: 9485, totalFormatted: '€94.85',
    feeMinor: 485, feeFormatted: '€4.85', at: '2026-10-06T11:50:00Z', payableUntil: '2026-10-07T11:50:00Z',
    superseded: false },
  currentBreakdown: {
    lines: [
      { code: 'BASE', label: 'FUT Classes — Single session', amountMinor: 9000, amountFormatted: '€90.00' },
      { code: 'PAYMENT_FEE', label: 'Payment processing fee', amountMinor: 485, amountFormatted: '€4.85' },
    ],
    totalMinor: 9485, totalFormatted: '€94.85',
  },
  attempts: [
    { kind: 'PAYOP', method: 'Wallet', status: 'OPEN', note: null, totalMinor: 9485, totalFormatted: '€94.85',
      feeMinor: 485, feeFormatted: '€4.85', at: '2026-10-06T11:50:00Z', payableUntil: '2026-10-07T11:50:00Z',
      superseded: false },
    { kind: 'PAYOP', method: 'Bank transfer', status: 'EXPIRED', note: 'REPLACED', totalMinor: 9407,
      totalFormatted: '€94.07', feeMinor: 407, feeFormatted: '€4.07', at: '2026-10-06T11:40:00Z',
      payableUntil: '2026-10-07T11:40:00Z', superseded: true },
  ],
}

describe('the admin order page\'s payment panel', () => {
  it('shows the current method with what it charges, and every attempt, superseded ones marked', async () => {
    api.get.mockResolvedValue(STAFF)
    render(<PaymentPanel publicRef="GFS-26-AFXZAZ1M" />)

    const panel = await screen.findByTestId('payment-panel')
    expect(api.get).toHaveBeenCalledWith('/api/v1/admin/orders/GFS-26-AFXZAZ1M/payment')
    expect(panel).toHaveTextContent('Current method')
    expect(panel).toHaveTextContent('Wallet')
    expect(panel).toHaveTextContent('Payment processing fee€4.85')
    expect(panel).toHaveTextContent('To pay with this method€94.85')

    const rows = screen.getAllByTestId('payment-attempt')
    expect(rows).toHaveLength(2)
    expect(rows[1]).toHaveTextContent('Superseded')
    expect(rows[1]).toHaveTextContent('Customer chose another method')
    expect(rows[0]).not.toHaveTextContent('Superseded')
  })

  it('Re-check Payop payment: asks the server, says what came of each invoice, and reloads the order', async () => {
    api.get.mockResolvedValue(STAFF)
    api.post.mockResolvedValue({ order: 'GFS-26-AFXZAZ1M', orderStatus: 'READY_FOR_DELIVERY', invoices: [
      { invoiceId: 'd024f697-ba2d-456f-910e-4d7fdfd338dd', statusBefore: 'OPEN', outcome: 'PAID' },
      { invoiceId: '81962ed0-a65c-4d1a-851b-b3dbf9750399', statusBefore: 'EXPIRED', outcome: 'PENDING' },
    ] })
    const changed = vi.fn()
    render(<PaymentPanel publicRef="GFS-26-AFXZAZ1M" onChanged={changed} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Re-check Payop payment' }))

    const result = await screen.findByTestId('payop-recheck')
    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/payop/orders/GFS-26-AFXZAZ1M/recheck')
    expect(result).toHaveTextContent('d024f697… paid: the order has moved on')
    expect(result).toHaveTextContent('81962ed0… no completed payment at Payop yet')
    expect(result).toHaveTextContent('Order is now READY_FOR_DELIVERY.')
    expect(changed).toHaveBeenCalled()
  })

  it('no re-check button for an order paid only by manual methods', async () => {
    api.get.mockResolvedValue({ ...STAFF, current: null, currentBreakdown: null, attempts: [
      { kind: 'MANUAL', method: 'UPI', status: 'SUBMITTED', note: null, totalMinor: 100, totalFormatted: '₹1.00',
        feeMinor: null, feeFormatted: null, at: '2026-10-06T11:50:00Z', payableUntil: null, superseded: false },
    ] })
    render(<PaymentPanel publicRef="GFS-26-UPIONLY1" />)
    await screen.findByTestId('payment-panel')
    expect(screen.queryByRole('button', { name: 'Re-check Payop payment' })).toBeNull()
  })

  it('shows nothing for an order with no payment attempt', async () => {
    api.get.mockResolvedValue({ current: null, currentBreakdown: null, attempts: [] })
    const { container } = render(<PaymentPanel publicRef="GFS-26-PAIDXX01" />)
    await vi.waitFor(() => expect(api.get).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })
})
