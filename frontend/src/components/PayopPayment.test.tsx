import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState, type ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { QuoteLine } from '../lib/types'

// ---- the API, mocked: these tests are about what the page sends and shows ------------
const mocks = vi.hoisted(() => {
  class ApiError extends Error {
    status: number
    code: string
    constructor(status: number, code: string, message = code) {
      super(message)
      this.status = status
      this.code = code
    }
  }
  return { api: { get: vi.fn(), post: vi.fn(), upload: vi.fn() }, ApiError }
})
vi.mock('../lib/api', () => ({ api: mocks.api, ApiError: mocks.ApiError }))

const { PayopPayment } = await import('./PayopPayment')
const { ManualPayment } = await import('./ManualPayment')
const {
  PaySummaryProvider, PayopPayButton, payopFeeLine, payopLines, payopTotal,
} = await import('./paySummary')
const { useT } = await import('../i18n')
const { api, ApiError } = mocks

/** The order as placed: 100.00, a 10.00 coupon, and 2.25 of card fee for UPI, PayPal and USDT. */
const ORDER_LINES: QuoteLine[] = [
  { code: 'BASE', label: 'Coins', amountMinor: 10000, amountFormatted: '€100.00' },
  { code: 'COUPON_DISCOUNT', label: 'Coupon', amountMinor: -1000, amountFormatted: '-€10.00' },
  { code: 'GATEWAY_FEE', label: 'Payment processing (2.5%)', amountMinor: 225, amountFormatted: '€2.25' },
]

const OPTIONS = {
  currency: 'EUR',
  netMinor: 9000,
  netFormatted: '€90.00',
  // The server's lines for a Payop payment: no card fee among them.
  lines: ORDER_LINES.slice(0, 2),
  unavailable: null,
  claimsBlockedUntil: null,
  methods: [
    { methodId: 381, name: 'Bank transfer', type: 'bank_transfer', feeMinor: 407, feeFormatted: '€4.07',
      totalMinor: 9407, totalFormatted: '€94.07', token: 'sealed-381' },
    { methodId: 700001, name: 'Wallet', type: 'ewallet', feeMinor: 485, feeFormatted: '€4.85',
      totalMinor: 9485, totalFormatted: '€94.85', token: 'sealed-700001' },
  ],
}

const MANUAL = [
  { method: 'PAYPAL', destination: 'pay@example.com', accountName: 'GFS', referenceName: 'transaction ID', link: null },
  { method: 'CRYPTO', destination: 'TWALLET', accountName: null, referenceName: 'TXID', link: null },
]

/**
 * A page around the pay step: the step on one side, the order summary on the other, which
 * shows what the step reports -- the way the checkouts' own summary cards do.
 */
function Page({ children }: { children: ReactNode }) {
  const t = useT()
  const [pay, setPay] = useState<Parameters<typeof PayopPayButton>[0]['selection'] | null>(null)
  return (
    <PaySummaryProvider publish={(s) => setPay(s.kind === 'payop' ? s : null)}>
      <main>{children}</main>
      <aside data-testid="summary">
        {(pay ? [...payopLines(pay, ORDER_LINES), payopFeeLine(pay, t.order.payopSelectMethod)] : ORDER_LINES)
          .map((l) => (
            <p key={l.code} data-testid={`line-${l.code}`}>{`${l.code}: ${l.amountFormatted}`}</p>
          ))}
        <p data-testid="total">{pay ? payopTotal(pay) : '€92.25'}</p>
        {pay && <PayopPayButton selection={pay} />}
      </aside>
    </PaySummaryProvider>
  )
}

let assign: ReturnType<typeof vi.fn>

beforeEach(() => {
  api.get.mockReset()
  api.post.mockReset()
  assign = vi.fn()
  // jsdom has no object URLs; the screenshot preview needs one.
  URL.createObjectURL = vi.fn(() => 'blob:proof')
  URL.revokeObjectURL = vi.fn()
  Object.defineProperty(window, 'location', { value: { ...window.location, assign }, writable: true })
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/payments/payop/country') return { country: 'DE' }
    if (path.startsWith('/api/v1/payments/methods')) return MANUAL
    throw new Error(`unexpected GET ${path}`)
  })
})

function payop(onUnavailable = vi.fn()) {
  render(<Page><PayopPayment publicRef="GFS-26-EUR00001" email="buyer@example.com" onUnavailable={onUnavailable} /></Page>)
  return onUnavailable
}

const summary = () => within(screen.getByTestId('summary'))

describe('Payop at checkout', () => {
  it('preselects the guessed country and shows each method with its fee; the summary has no 2.5% and no total yet', async () => {
    api.post.mockResolvedValue(OPTIONS)
    payop()

    expect(await screen.findByRole('button', { name: /Bank transfer/ })).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledWith('/api/v1/payments/payop/options',
      { order: 'GFS-26-EUR00001', email: 'buyer@example.com', country: 'DE' })
    expect(screen.getByRole('combobox')).toHaveValue('DE')
    expect(screen.getByText('Payment processing fee: €4.07')).toBeInTheDocument()

    // Before a method is chosen: the fee line says so, there is no total, and nothing to press.
    await waitFor(() => expect(summary().getByTestId('line-PAYMENT_FEE'))
      .toHaveTextContent('PAYMENT_FEE: Select a payment method'))
    expect(summary().queryByTestId('line-GATEWAY_FEE')).toBeNull()
    expect(summary().getByTestId('total')).toHaveTextContent('—')
    expect(summary().queryByRole('button')).toBeNull()
  })

  it('a method chosen: its sheet fee and the total, once, in the summary -- never on this side', async () => {
    api.post.mockResolvedValue(OPTIONS)
    payop()
    await userEvent.click(await screen.findByRole('button', { name: /Wallet/ }))

    expect(summary().getByTestId('line-PAYMENT_FEE')).toHaveTextContent('PAYMENT_FEE: €4.85')
    expect(summary().getByTestId('line-BASE')).toHaveTextContent('€100.00')
    expect(summary().getByTestId('line-COUPON_DISCOUNT')).toHaveTextContent('-€10.00')
    expect(summary().queryByTestId('line-GATEWAY_FEE')).toBeNull()
    expect(summary().getByTestId('total')).toHaveTextContent('€94.85')
    // The total appears once on the page: the method's own panel shows no second one.
    expect(screen.getAllByText('€94.85')).toHaveLength(1)
    expect(within(screen.getByRole('main')).queryByText(/€90\.00|€94\.85/)).toBeNull()
  })

  it('pays with the token the server sealed the price into -- no amount, no method id -- and goes where the server says', async () => {
    api.post.mockImplementation(async (path: string) => (path.endsWith('/options') ? OPTIONS
      : { redirectUrl: 'https://checkout.payop.com/en/payment/invoice-preprocessing/inv-1', invoiceId: 'inv-1',
        totalMinor: 9407, totalFormatted: '€94.07', payableUntil: '2026-10-05T12:00:00Z' }))
    payop()
    await userEvent.click(await screen.findByRole('button', { name: /Bank transfer/ }))
    await userEvent.click(summary().getByRole('button', { name: 'Continue to payment' }))

    await waitFor(() => expect(assign).toHaveBeenCalledWith(
      'https://checkout.payop.com/en/payment/invoice-preprocessing/inv-1'))
    expect(api.post).toHaveBeenLastCalledWith('/api/v1/payments/payop/invoices', {
      order: 'GFS-26-EUR00001', email: 'buyer@example.com', token: 'sealed-381', language: 'en',
    })
  })

  it('changing the country asks the server again; nothing is priced in the browser', async () => {
    api.post.mockResolvedValue(OPTIONS)
    payop()
    await screen.findByRole('button', { name: /Bank transfer/ })
    await userEvent.selectOptions(screen.getByRole('combobox'), 'AT')
    await waitFor(() => expect(api.post).toHaveBeenLastCalledWith('/api/v1/payments/payop/options',
      { order: 'GFS-26-EUR00001', email: 'buyer@example.com', country: 'AT' }))
  })

  it('a total that moved on the server: says so beside the button, asks again, and does not leave the page', async () => {
    api.post.mockImplementation(async (path: string) => {
      if (path.endsWith('/options')) return OPTIONS
      throw new ApiError(409, 'price_changed')
    })
    payop()
    await userEvent.click(await screen.findByRole('button', { name: /Bank transfer/ }))
    await userEvent.click(summary().getByRole('button', { name: 'Continue to payment' }))

    expect(await summary().findByText(/The total for this method has changed/)).toBeInTheDocument()
    expect(assign).not.toHaveBeenCalled()
    await waitFor(() => expect(api.post.mock.calls.filter(([p]) => p.endsWith('/options'))).toHaveLength(2))
  })

  it('a redirect that is not https is never followed', async () => {
    api.post.mockImplementation(async (path: string) => (path.endsWith('/options') ? OPTIONS
      : { redirectUrl: 'javascript:alert(1)', invoiceId: 'x', totalMinor: 9407, totalFormatted: '€94.07',
        payableUntil: '2026-10-05T12:00:00Z' }))
    payop()
    await userEvent.click(await screen.findByRole('button', { name: /Bank transfer/ }))
    await userEvent.click(summary().getByRole('button', { name: 'Continue to payment' }))
    expect(await screen.findByText(/We could not start the payment/)).toBeInTheDocument()
    expect(assign).not.toHaveBeenCalled()
  })

  it('says why nothing is offered, and when a country has no method', async () => {
    api.post.mockResolvedValueOnce({ ...OPTIONS, methods: [], unavailable: 'NO_RATE' })
    payop()
    expect(await screen.findByText('Temporarily unavailable.')).toBeInTheDocument()
    expect(screen.getByText('Please use PayPal or crypto.')).toBeInTheDocument()
  })

  it('Payop switched off (404): hands back to the placeholder', async () => {
    api.post.mockRejectedValue(new ApiError(404, 'not_found'))
    const onUnavailable = payop()
    await waitFor(() => expect(onUnavailable).toHaveBeenCalled())
  })
})

describe('the International tab', () => {
  function manual(currency: string) {
    render(<Page><ManualPayment publicRef="GFS-26-EUR00001" email="buyer@example.com" sku="TRADING_SERVICE"
      totalFormatted="€92.25" currency={currency} /></Page>)
  }

  async function openInternational(currency: string) {
    manual(currency)
    await userEvent.click(await screen.findByRole('tab', { name: 'International' }))
  }

  it('INR orders never see Payop: the placeholder, and no Payop request at all', async () => {
    await openInternational('INR')
    expect(screen.getByText('International payment options are coming soon')).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()
    expect(api.get).not.toHaveBeenCalledWith('/api/v1/payments/payop/country')
  })

  it('other currencies get Payop when it is on', async () => {
    api.post.mockResolvedValue(OPTIONS)
    await openInternational('EUR')
    expect(await screen.findByRole('button', { name: /Bank transfer/ })).toBeInTheDocument()
  })

  it('switching between PayPal and a Payop method swaps the fee line, both ways', async () => {
    api.post.mockResolvedValue(OPTIONS)
    manual('EUR')
    // PayPal: the order as placed, 2.5% card fee and all.
    await screen.findByRole('tab', { name: 'PayPal' })
    expect(summary().getByTestId('line-GATEWAY_FEE')).toHaveTextContent('€2.25')
    expect(summary().getByTestId('total')).toHaveTextContent('€92.25')

    await userEvent.click(screen.getByRole('tab', { name: 'International' }))
    await userEvent.click(await screen.findByRole('button', { name: /Bank transfer/ }))
    expect(summary().queryByTestId('line-GATEWAY_FEE')).toBeNull()
    expect(summary().getByTestId('line-PAYMENT_FEE')).toHaveTextContent('€4.07')
    expect(summary().getByTestId('total')).toHaveTextContent('€94.07')

    await userEvent.click(screen.getByRole('tab', { name: 'PayPal' }))
    expect(summary().queryByTestId('line-PAYMENT_FEE')).toBeNull()
    expect(summary().getByTestId('line-GATEWAY_FEE')).toHaveTextContent('€2.25')
    expect(summary().getByTestId('total')).toHaveTextContent('€92.25')
  })

  it('an order not in INR, with Payop off: says it is temporarily unavailable -- never hidden as "coming soon"',
    async () => {
      api.post.mockRejectedValue(new ApiError(404, 'not_found'))
      await openInternational('EUR')
      expect(await screen.findByText('Temporarily unavailable.')).toBeInTheDocument()
      expect(screen.getByText('Please use PayPal or crypto.')).toBeInTheDocument()
      expect(screen.queryByText('International payment options are coming soon')).toBeNull()
    })

  it('a manual claim refused because a Payop invoice is open says why, in the customer’s words', async () => {
    api.post.mockRejectedValue(new ApiError(409, 'payop_invoice_open', 'server sentence'))
    manual('EUR')
    await userEvent.type(await screen.findByLabelText(/reference/i), 'ABCD1234')
    const file = new File(['x'], 'proof.png', { type: 'image/png' })
    await userEvent.upload(screen.getByLabelText(/screenshot/i), file)
    await userEvent.click(screen.getByRole('button', { name: /send|submit|paid/i }))
    expect(await screen.findByText(/You started a payment with a local method/)).toBeInTheDocument()
    expect(screen.queryByText('server sentence')).not.toBeInTheDocument()
  })
})
