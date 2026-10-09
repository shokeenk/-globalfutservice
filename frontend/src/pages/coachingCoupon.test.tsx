import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * A coupon at the coaching checkout: the coin checkout's own field, in the order summary on
 * the review step. The server prices it with the same rules as everywhere else -- coupons
 * have no service restriction -- and says why when a code does not apply.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
vi.mock('../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER' }, loading: false }),
}))
const CATALOG = vi.hoisted(() => ({
  catalog: { currency: 'USD', services: [{ sku: 'COACHING', options: [
    { platform: null, variant: 'SINGLE_SESSION', label: 'Single session · 1 hour', unitPriceMinor: 2000,
      unitPriceFormatted: '$20.00', minQuantity: null, maxQuantity: null, stepQuantity: null },
    { platform: null, variant: 'MONTHLY_6_SESSIONS', label: 'Monthly package · 6 sessions', unitPriceMinor: 10000,
      unitPriceFormatted: '$100.00', minQuantity: null, maxQuantity: null, stepQuantity: null },
  ] }] },
  policy: { onlinePaymentsEnabled: false },
}))
vi.mock('../state/CatalogContext', () => ({ useCatalog: () => CATALOG }))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))
vi.mock('../components/ManualPayment', () => ({ ManualPayment: () => <p data-testid="panel">pay</p> }))

const { default: CoachingBook } = await import('./CoachingBook')

const SLOT = '2026-10-05T13:30:00.000Z'
const PRICES: Record<string, number> = { SINGLE_SESSION: 2000, MONTHLY_6_SESSIONS: 10000 }
const usd = (minor: number) => `${minor < 0 ? '-' : ''}$${(Math.abs(minor) / 100).toFixed(2)}`

/** What the server says about each code, as CouponService words it. */
const REFUSED: Record<string, string> = {
  NOPE10: 'That code is not valid.',
  OLD10: 'That code has expired.',
  FULL10: 'That code has been fully claimed.',
  ONCE10: 'You have already used that code.',
}

/** The quote as the server prices it: a 10% SAVE10 off the price, then 2.5% of what is left. */
function quote(variant: string, code: string | null) {
  const base = PRICES[variant]!
  const applies = code === 'SAVE10'
  const discount = applies ? -Math.round(base * 0.1) : 0
  const fee = Math.round((base + discount) * 0.025)
  const lines = [
    { code: 'BASE', label: 'Coaching', amountMinor: base, amountFormatted: usd(base) },
    ...(applies ? [{ code: 'COUPON_DISCOUNT', label: 'Coupon SAVE10 (10% off)', amountMinor: discount,
      amountFormatted: usd(discount) }] : []),
    { code: 'GATEWAY_FEE', label: 'Payment processing (2.5%)', amountMinor: fee, amountFormatted: usd(fee) },
  ]
  return { quoteId: 'q1', sku: 'COACHING', variant, currency: 'USD', lines, totalMinor: base + discount + fee,
    totalFormatted: usd(base + discount + fee), couponCode: applies ? 'SAVE10' : null,
    couponMessage: code && !applies ? REFUSED[code] ?? null : null,
    expiresAt: '2026-10-01T07:00:00Z', signature: 'x' }
}

async function toReview(variant = 'SINGLE_SESSION') {
  render(
    <MemoryRouter initialEntries={[`/coaching/book?step=details&variant=${variant}`]}>
      <Routes><Route path="/coaching/book" element={<CoachingBook />} /></Routes>
    </MemoryRouter>,
  )
  await userEvent.type(await screen.findByPlaceholderText(/in-game ID/i), 'VinayFC10')
  await userEvent.click(screen.getByRole('radio', { name: /PlayStation/ }))
  await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
  const time = new Date(SLOT).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  await userEvent.click(await screen.findByRole('radio', { name: time }))
  await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
  await screen.findByText(/Held for you until your payment is verified/)
  await screen.findByTestId('summary-total')
}

const summary = () => screen.getByText('Order summary').parentElement as HTMLElement
const quoteCalls = () => api.post.mock.calls.filter(([path]) => path === '/api/v1/quotes')

async function apply(code: string) {
  const field = within(summary()).getByLabelText('Coupon code')
  await userEvent.clear(field)
  await userEvent.type(field, code)
  await userEvent.click(within(summary()).getByRole('button', { name: 'Apply' }))
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-01T06:00:00Z'))
  window.scrollTo = vi.fn()
  api.get.mockReset()
  api.post.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/payments/methods')) {
      return [{ method: 'UPI', destination: 'services@upi', accountName: 'GFS', link: null, referenceName: 'UTR' }]
    }
    if (path === '/api/v1/coaching/coaches') {
      return [{ id: 'vinay', displayName: 'Vinay', headline: null, bio: null, avatarUrl: null, languages: null,
        timezone: 'Asia/Kolkata' }]
    }
    if (path.includes('/slots')) return { coachId: 'vinay', coachTimezone: 'Asia/Kolkata', sessionMinutes: 60, slots: [SLOT] }
    return []
  })
  api.post.mockImplementation(async (path: string, body: { variant: string; couponCode: string | null }) => {
    if (path === '/api/v1/quotes') return quote(body.variant, body.couponCode)
    return { publicRef: 'GFS-26-C1', status: 'AWAITING_PAYMENT', totalMinor: 1845, totalFormatted: '$18.45',
      currency: 'USD', payment: { provider: 'STUB' } }
  })
})
afterEach(() => { vi.useRealTimers() })

describe('a coupon at the coaching checkout', () => {
  it.each([
    ['SINGLE_SESSION', '$20.00', '-$2.00', '$0.45', '$18.45'],
    ['MONTHLY_6_SESSIONS', '$100.00', '-$10.00', '$2.25', '$92.25'],
  ])('%s: the code is priced by the server, shown in the summary, and the fee is on the price after it',
    async (variant, price, discount, fee, total) => {
      await toReview(variant)
      expect(within(summary()).getByText('Have a discount code? Enter it here.')).toBeInTheDocument()

      await apply('save10')

      await waitFor(() => expect(within(summary()).getByText('SAVE10 applied.')).toBeInTheDocument())
      expect(quoteCalls()[quoteCalls().length - 1]?.[1]).toMatchObject({ sku: 'COACHING', variant, couponCode: 'SAVE10' })
      expect(summary()).toHaveTextContent(`Coaching${price}`)
      expect(summary()).toHaveTextContent(`Coupon SAVE10 (10% off)${discount}`)
      expect(summary()).toHaveTextContent(`Payment processing (2.5%)${fee}`)
      expect(within(summary()).getByTestId('summary-total')).toHaveTextContent(total)
    })

  it.each(Object.entries(REFUSED))('%s: no discount, and the server says why', async (code, reason) => {
    await toReview()
    await apply(code)

    expect(await within(summary()).findByText(reason)).toBeInTheDocument()
    expect(summary()).not.toHaveTextContent('(10% off)')
    expect(within(summary()).getByTestId('summary-total')).toHaveTextContent('$20.50')
  })

  it('the order is placed from the quote that carries the coupon', async () => {
    await toReview()
    await apply('SAVE10')
    await within(summary()).findByText('SAVE10 applied.')
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(screen.getByRole('button', { name: /Pay now/ }))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/orders', expect.objectContaining({
      quote: expect.objectContaining({ couponCode: 'SAVE10', totalFormatted: '$18.45' }),
    })))
  })
})
