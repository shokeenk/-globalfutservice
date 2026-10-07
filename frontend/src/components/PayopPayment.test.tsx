import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

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
const { api, ApiError } = mocks

const OPTIONS = {
  currency: 'EUR',
  netMinor: 9000,
  netFormatted: '€90.00',
  feeLabel: 'Payment processing fee',
  unavailable: null,
  claimsBlockedUntil: null,
  methods: [
    { methodId: 381, name: 'Bank transfer', type: 'bank_transfer', feeMinor: 407, feeFormatted: '€4.07',
      totalMinor: 9407, totalFormatted: '€94.07' },
    { methodId: 700001, name: 'Wallet', type: 'ewallet', feeMinor: 485, feeFormatted: '€4.85',
      totalMinor: 9485, totalFormatted: '€94.85' },
  ],
}

const MANUAL = [
  { method: 'PAYPAL', destination: 'pay@example.com', accountName: 'GFS', referenceName: 'transaction ID', link: null },
  { method: 'CRYPTO', destination: 'TWALLET', accountName: null, referenceName: 'TXID', link: null },
]

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
  render(<PayopPayment publicRef="GFS-26-EUR00001" email="buyer@example.com" onUnavailable={onUnavailable} />)
  return onUnavailable
}

describe('Payop at checkout', () => {
  it('preselects the guessed country, shows each method with its fee, then the price, fee and total', async () => {
    api.post.mockResolvedValue(OPTIONS)
    payop()

    expect(await screen.findByRole('button', { name: /Bank transfer/ })).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledWith('/api/v1/payments/payop/options',
      { order: 'GFS-26-EUR00001', email: 'buyer@example.com', country: 'DE' })
    expect(screen.getByRole('combobox')).toHaveValue('DE')
    expect(screen.getByText('Payment processing fee: €4.07')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Wallet/ }))
    expect(screen.getByText('€90.00')).toBeInTheDocument()
    expect(screen.getByText('€4.85')).toBeInTheDocument()
    expect(screen.getByText('€94.85')).toBeInTheDocument()
  })

  it('sends only the method, the country and the total it showed, and goes where the server says', async () => {
    api.post.mockImplementation(async (path: string) => (path.endsWith('/options') ? OPTIONS
      : { redirectUrl: 'https://checkout.payop.com/en/payment/invoice-preprocessing/inv-1', invoiceId: 'inv-1',
        totalMinor: 9407, totalFormatted: '€94.07', payableUntil: '2026-10-05T12:00:00Z' }))
    payop()
    await userEvent.click(await screen.findByRole('button', { name: /Bank transfer/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Continue to pay €94.07' }))

    await waitFor(() => expect(assign).toHaveBeenCalledWith(
      'https://checkout.payop.com/en/payment/invoice-preprocessing/inv-1'))
    expect(api.post).toHaveBeenLastCalledWith('/api/v1/payments/payop/invoices', {
      order: 'GFS-26-EUR00001', email: 'buyer@example.com', methodId: 381, country: 'DE',
      expectedTotalMinor: 9407, language: 'en',
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

  it('a total that moved on the server: says so, asks again, and does not leave the page', async () => {
    api.post.mockImplementation(async (path: string) => {
      if (path.endsWith('/options')) return OPTIONS
      throw new ApiError(409, 'price_changed')
    })
    payop()
    await userEvent.click(await screen.findByRole('button', { name: /Bank transfer/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Continue to pay €94.07' }))

    expect(await screen.findByText(/The total for this method has changed/)).toBeInTheDocument()
    expect(assign).not.toHaveBeenCalled()
    await waitFor(() => expect(api.post.mock.calls.filter(([p]) => p.endsWith('/options'))).toHaveLength(2))
  })

  it('a redirect that is not https is never followed', async () => {
    api.post.mockImplementation(async (path: string) => (path.endsWith('/options') ? OPTIONS
      : { redirectUrl: 'javascript:alert(1)', invoiceId: 'x', totalMinor: 9407, totalFormatted: '€94.07',
        payableUntil: '2026-10-05T12:00:00Z' }))
    payop()
    await userEvent.click(await screen.findByRole('button', { name: /Bank transfer/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Continue to pay €94.07' }))
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
  async function openInternational(currency: string) {
    render(<ManualPayment publicRef="GFS-26-EUR00001" email="buyer@example.com" sku="TRADING_SERVICE"
      totalFormatted="€92.25" currency={currency} />)
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
    render(<ManualPayment publicRef="GFS-26-EUR00001" email="buyer@example.com" sku="TRADING_SERVICE"
      totalFormatted="€92.25" currency="EUR" />)
    await userEvent.type(await screen.findByLabelText(/reference/i), 'ABCD1234')
    const file = new File(['x'], 'proof.png', { type: 'image/png' })
    await userEvent.upload(screen.getByLabelText(/screenshot/i), file)
    await userEvent.click(screen.getByRole('button', { name: /send|submit|paid/i }))
    expect(await screen.findByText(/You started a payment with a local method/)).toBeInTheDocument()
    expect(screen.queryByText('server sentence')).not.toBeInTheDocument()
  })
})
