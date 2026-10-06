import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Order, OrderPaymentView } from '../lib/types'

/*
 * "Complete your payment": the same order, at its frozen price in its own currency, paid
 * through the checkout's own payment step on the signed-in owner's routes.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), upload: vi.fn() }))
vi.mock('../lib/api', async (original) => ({ ...(await original<typeof import('../lib/api')>()), api }))
vi.mock('../state/CatalogContext', () => ({
  // The display currency is USD; the orders here are not, and must not be shown in it.
  useCatalog: () => ({ catalog: null, policy: null, loading: false, error: null, currency: 'USD', setCurrency: () => {} }),
}))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { ApiError } = await import('../lib/api')
const { CompletePayment } = await import('./CompletePayment')
const { ownerManualRoutes } = await import('./ManualPayment')
const { PayopPayment, ownerPayopRoutes } = await import('./PayopPayment')
const { OrderView } = await import('../pages/Track')

const REF = 'GFS-26-AFXZAZ1M'
const BASE = `/api/v1/orders/${REF}/payment`

function order(patch: Partial<Order> = {}): Order {
  return {
    publicRef: REF, status: 'AWAITING_PAYMENT', statusLabel: 'Waiting for payment', nextAction: 'PAY',
    serviceLabel: 'FUT Classes — Single session', sku: 'COACHING', platform: null, variant: 'SINGLE_SESSION',
    quantity: 1, deliveryMethod: 'SCHEDULED_SESSION', credentialsRequired: false, credentialsSubmitted: false,
    currency: 'EUR', totalMinor: 9225, totalFormatted: '€92.25', lines: [], pointsRedeemed: 0, pointsEarned: 0,
    createdAt: '2026-10-05T10:00:00Z', timeline: [], discordAccess: null,
    paymentState: 'UNPAID', payBy: '2026-10-07T10:00:00Z',
    ...patch,
  } as unknown as Order
}

function view(patch: Partial<OrderPaymentView> = {}): OrderPaymentView {
  return {
    publicRef: REF, status: 'AWAITING_PAYMENT', paymentState: 'UNPAID', payBy: '2026-10-07T10:00:00Z',
    currency: 'EUR', amountDueMinor: 9225, amountDueFormatted: '€92.25',
    manual: { lines: [], totalMinor: 9225, totalFormatted: '€92.25' },
    payopOffered: true, payableInvoice: null, manualBlockedUntil: null, claimSubmittedAt: null,
    coaching: null, signInNeeded: false,
    ...patch,
  }
}

const METHODS = [
  { method: 'UPI', destination: 'gfs@upi', accountName: 'GFS', link: null, referenceName: 'UTR' },
  { method: 'PAYPAL', destination: 'pay@gfs.test', accountName: null, link: null, referenceName: 'transaction ID' },
]

/** The API, with `payment` as the order's payment step. */
function answer(payment: OrderPaymentView | (() => OrderPaymentView)) {
  api.get.mockImplementation(async (url: string) => {
    if (url.startsWith(`${BASE}?`)) return typeof payment === 'function' ? payment() : payment
    if (url.startsWith('/api/v1/payments/methods')) return METHODS
    if (url === '/api/v1/payments/payop/country') return { country: 'DE' }
    if (url === '/api/v1/coaching/coaches') return []
    if (url === `/api/v1/orders/${REF}`) return order({ paymentState: 'SUBMITTED' })
    return new Promise(() => {})
  })
}

function show(o: Order, signedIn = true) {
  return render(<MemoryRouter><CompletePayment order={o} signedIn={signedIn} /></MemoryRouter>)
}

async function open() {
  fireEvent.click(screen.getByRole('button', { name: 'Complete your payment' }))
  await waitFor(() => expect(api.get).toHaveBeenCalledWith(`${BASE}?lang=en`))
}

beforeEach(() => {
  api.get.mockReset()
  api.post.mockReset()
  api.upload.mockReset()
  // Anything not answered by a test stays loading, rather than resolving to nothing.
  api.get.mockImplementation(() => new Promise(() => {}))
  api.post.mockImplementation(() => new Promise(() => {}))
})

describe('an unpaid order', () => {
  it('says so in the client\'s words, with the amount due in the order\'s own currency and when to pay by', () => {
    show(order())
    const panel = screen.getByTestId('complete-payment')
    expect(panel).toHaveTextContent(
      'This order hasn’t been paid yet. Complete your payment to confirm it. Your order reference stays the same.')
    // The order's frozen total, in EUR: the site's display currency (USD) never re-prices it.
    expect(screen.getByTestId('amount-due')).toHaveTextContent('€92.25')
    expect(screen.getByTestId('pay-by')).toHaveTextContent(/^Pay by .*2026/)
    expect(screen.getByRole('button', { name: 'Complete your payment' })).toBeInTheDocument()
    expect(api.get).not.toHaveBeenCalled()
  })

  it('signed out: the way to pay is to sign in first, back to this order', () => {
    show(order(), false)
    expect(screen.getByRole('link', { name: 'Sign in to complete your payment' })).toHaveAttribute('href', '/login')
    expect(screen.queryByRole('button', { name: 'Complete your payment' })).toBeNull()
  })

  it('opens the checkout\'s own payment step for the same order, nothing placed again', async () => {
    answer(view())
    show(order({ sku: 'TRADING_SERVICE' }))
    await open()
    expect(await screen.findByRole('tab', { name: 'UPI' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'International' })).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalledWith('/api/v1/orders', expect.anything())
  })

  it('while a local-method invoice is open, manual methods say so, with the way back to it', async () => {
    answer(view({
      manualBlockedUntil: '2026-10-06T20:00:00Z',
      payableInvoice: { invoiceId: 'i-1', methodName: 'Bank transfer', totalMinor: 9407, currency: 'EUR',
        payableUntil: '2026-10-06T20:00:00Z', url: 'https://checkout.payop.com/en/payment/invoice-preprocessing/i-1' },
    }))
    show(order({ sku: 'TRADING_SERVICE' }))
    await open()
    const blocked = await screen.findByTestId('local-method-open')
    expect(blocked).toHaveTextContent(/^You’ve started a payment with a local method\. Complete it here, or you can choose another method after .*\.$/)
    expect(within(blocked).getByRole('link', { name: 'Complete it here' }))
      .toHaveAttribute('href', 'https://checkout.payop.com/en/payment/invoice-preprocessing/i-1')
  })

  it('an INR order is never offered Payop', async () => {
    answer(view({ currency: 'INR', payopOffered: false }))
    show(order({ sku: 'TRADING_SERVICE', currency: 'INR', totalFormatted: '₹8,250.00' }))
    await open()
    fireEvent.click(await screen.findByRole('tab', { name: 'International' }))
    expect(api.post).not.toHaveBeenCalledWith(expect.stringContaining('payop'), expect.anything())
  })
})

describe('before paying', () => {
  it('coaching whose hold ran out: the same slot is checked again and kept when still free', async () => {
    let held = false
    answer(() => view({
      coaching: held
        ? { state: 'HELD', coachId: 'coach_vinay', coachName: 'Vinay', startsAt: '2026-10-09T13:30:00Z',
            endsAt: null, timezone: 'Asia/Kolkata', holdExpiresAt: '2026-10-06T14:00:00Z', variant: 'SINGLE_SESSION' }
        : { state: 'EXPIRED', coachId: 'coach_vinay', coachName: 'Vinay', startsAt: '2026-10-09T13:30:00Z',
            endsAt: null, timezone: 'Asia/Kolkata', holdExpiresAt: null, variant: 'SINGLE_SESSION' },
    }))
    api.post.mockImplementation(async (url: string) => {
      if (url === `${BASE}/coaching-slot`) {
        held = true
        return {}
      }
      return new Promise(() => {})
    })
    show(order())
    await open()

    expect(await screen.findByTestId('slot-held')).toHaveTextContent(/^Your session: /)
    expect(api.post).toHaveBeenCalledWith(`${BASE}/coaching-slot`, {})
    expect(screen.getByRole('tab', { name: 'UPI' })).toBeInTheDocument()
  })

  it('taken in the meantime: a new time is picked before paying', async () => {
    answer(view({
      coaching: { state: 'EXPIRED', coachId: 'coach_vinay', coachName: 'Vinay', startsAt: '2026-10-09T13:30:00Z',
        endsAt: null, timezone: 'Asia/Kolkata', holdExpiresAt: null, variant: 'SINGLE_SESSION' },
    }))
    api.post.mockRejectedValue(new ApiError(409, { error: 'slot_unavailable', message: 'That slot was just taken.' }))
    show(order())
    await open()

    const repick = await screen.findByTestId('slot-repick')
    expect(repick).toHaveTextContent('That time has been taken since. Pick a new time for your session, then pay.')
    expect(screen.queryByRole('tab', { name: 'UPI' })).toBeNull()
  })

  it('coins whose sign-in is gone: asked for it again, before anything to pay', async () => {
    answer(view({ signInNeeded: true }))
    show(order({ sku: 'TRADING_SERVICE', deliveryMethod: 'COMFORT_TRADE', credentialsRequired: true }))
    await open()

    const step = await screen.findByTestId('sign-in-again')
    expect(step).toHaveTextContent('Before you pay, we need your EA sign-in again.')
    expect(screen.queryByRole('tab', { name: 'UPI' })).toBeNull()
  })
})

describe('the owner\'s routes send no amount', () => {
  it('a claim is the method and the reference; the screenshot is the file alone', async () => {
    api.post.mockResolvedValue({})
    api.upload.mockResolvedValue({})
    const routes = ownerManualRoutes(REF)
    await routes.claim('UPI', 'UTR12345678')
    await routes.proof(new File(['x'], 'shot.png', { type: 'image/png' }))

    expect(api.post).toHaveBeenCalledWith(`${BASE}/claims`, { method: 'UPI', reference: 'UTR12345678' })
    const form = api.upload.mock.calls[0]?.[1] as FormData
    expect(api.upload.mock.calls[0]?.[0]).toBe(`${BASE}/claims/proof`)
    expect([...form.keys()]).toEqual(['file'])
  })

  it('a Payop payment starts from the server\'s token and nothing else', async () => {
    api.post.mockImplementation(async (url: string) => (url.endsWith('/options') ? {
      currency: 'EUR', netMinor: 9000, netFormatted: '€90.00', unavailable: null, manualBlockedUntil: null,
      methods: [{ methodId: 381, name: 'Bank transfer', type: 'bank_transfer', feeMinor: 407, feeFormatted: '€4.07',
        breakdown: { totalMinor: 9407, totalFormatted: '€94.07' }, token: 'sealed-381' }],
    } : { redirectUrl: 'https://checkout.payop.com/x', invoiceId: 'i-1', totalMinor: 9407,
      totalFormatted: '€94.07', payableUntil: '2026-10-07T10:00:00Z' }))
    const routes = ownerPayopRoutes(REF)

    const options = await routes.options('DE')
    expect(options.methods[0]).toMatchObject({ methodId: 381, totalFormatted: '€94.07', feeFormatted: '€4.07' })
    await routes.start(options.methods[0]!, 'DE', 'fr')

    expect(api.post).toHaveBeenLastCalledWith(`${BASE}/payop/invoices`, { token: 'sealed-381', language: 'fr' })
  })

  it('too many new invoices: the customer is told when another can be started', async () => {
    api.get.mockResolvedValue({ country: 'DE' })
    const routes = {
      options: async () => ({
        currency: 'EUR', netMinor: 9000, netFormatted: '€90.00', feeLabel: '', unavailable: null,
        claimsBlockedUntil: null,
        methods: [{ methodId: 381, name: 'Bank transfer', type: 'bank_transfer', feeMinor: 407, feeFormatted: '€4.07',
          totalMinor: 9407, totalFormatted: '€94.07', token: 't' }],
      }),
      start: async () => {
        throw new ApiError(429, { error: 'payment_attempts_order', message: 'Try later.',
          details: { retryAt: ['2026-10-06T13:00:00Z'] } })
      },
    }
    render(<MemoryRouter><PayopPayment publicRef={REF} onUnavailable={() => {}} routes={routes} /></MemoryRouter>)

    fireEvent.click(await screen.findByText('Bank transfer'))
    fireEvent.click(screen.getByRole('button', { name: 'Continue to pay €94.07' }))

    expect(await screen.findByText(/^This payment has been started several times\. .* start another after .*2026/))
      .toBeInTheDocument()
  })
})

describe('on the order page', () => {
  function page(o: Order) {
    return render(<MemoryRouter><OrderView order={o} signedIn /></MemoryRouter>)
  }

  it('payment details sent: being verified, and nothing to pay', () => {
    page(order({ paymentState: 'SUBMITTED' }))
    expect(screen.getByTestId('payment-submitted'))
      .toHaveTextContent('We’ve received your payment details and are verifying them.')
    expect(screen.queryByTestId('complete-payment')).toBeNull()
  })

  it('expired: says so, and points to a new order of the same kind', () => {
    page(order({ status: 'ABANDONED', statusLabel: 'Cancelled', nextAction: 'WAIT', paymentState: 'EXPIRED' }))
    expect(screen.getByText('This order has expired')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Place a new order' })).toHaveAttribute('href', '/coaching/book')
    expect(screen.queryByTestId('complete-payment')).toBeNull()
  })

  it('paid: the normal order view, no payment step', () => {
    page(order({ status: 'READY_FOR_DELIVERY', statusLabel: 'Queued', nextAction: 'WAIT', paymentState: null }))
    expect(screen.queryByTestId('complete-payment')).toBeNull()
    expect(screen.queryByTestId('payment-submitted')).toBeNull()
    expect(screen.queryByTestId('order-expired')).toBeNull()
  })

  it('unpaid: the payment step', () => {
    page(order())
    expect(screen.getByTestId('complete-payment')).toBeInTheDocument()
  })
})
