import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * GFS Store Credit at the coaching checkout, and the balance on the account pages: offered
 * only when the customer holds credit in the order's currency, combined with a coupon, the
 * payment fee charged only on what is left to pay with money, and an order the credit covers
 * in full going straight to its confirmation with no payment step.
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
  ] }] },
  policy: { onlinePaymentsEnabled: false },
}))
vi.mock('../state/CatalogContext', () => ({ useCatalog: () => CATALOG }))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))
vi.mock('../components/ManualPayment', () => ({ ManualPayment: () => <p data-testid="panel">pay</p> }))

const { default: CoachingBook } = await import('./CoachingBook')
const { StoreCreditBalance } = await import('../components/StoreCredit')

const SLOT = '2026-10-05T13:30:00.000Z'
const usd = (minor: number) => `${minor < 0 ? '-' : ''}$${(Math.abs(minor) / 100).toFixed(2)}`
let held = 500

/** The server's pricing: 10% SAVE10 off, then the credit, then 2.5% of what is left to pay. */
function quote(code: string | null, useCredit: boolean) {
  const base = 2000
  const discount = code === 'SAVE10' ? -200 : 0
  const credit = useCredit ? -Math.min(held, base + discount) : 0
  const fee = Math.round((base + discount + credit) * 0.025)
  const lines = [
    { code: 'BASE', label: 'Coaching', amountMinor: base, amountFormatted: usd(base) },
    ...(discount ? [{ code: 'COUPON_DISCOUNT', label: 'Coupon SAVE10 (10% off)', amountMinor: discount,
      amountFormatted: usd(discount) }] : []),
    ...(credit ? [{ code: 'STORE_CREDIT', label: 'GFS Store Credit', amountMinor: credit, amountFormatted: usd(credit) }] : []),
    ...(fee ? [{ code: 'GATEWAY_FEE', label: 'Payment processing (2.5%)', amountMinor: fee, amountFormatted: usd(fee) }] : []),
  ]
  const total = base + discount + credit + fee
  return { quoteId: 'q1', sku: 'COACHING', variant: 'SINGLE_SESSION', currency: 'USD', lines, totalMinor: total,
    totalFormatted: usd(total), couponCode: discount ? 'SAVE10' : null, couponMessage: null,
    expiresAt: '2026-10-01T07:00:00Z', signature: 'x', storeCreditMinor: -credit,
    storeCreditAvailableMinor: held || null, storeCreditAvailableFormatted: held ? usd(held) : null }
}

async function toReview() {
  render(
    <MemoryRouter initialEntries={['/coaching/book?step=details&variant=SINGLE_SESSION']}>
      <Routes><Route path="/coaching/book" element={<CoachingBook />} /></Routes>
    </MemoryRouter>,
  )
  await userEvent.type(await screen.findByPlaceholderText(/in-game ID/i), 'VinayFC10')
  await userEvent.click(screen.getByRole('radio', { name: /PlayStation/ }))
  await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
  const time = new Date(SLOT).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  await userEvent.click(await screen.findByRole('radio', { name: time }))
  await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
  await screen.findByTestId('summary-total')
}

const summary = () => screen.getByText('Order summary').parentElement as HTMLElement

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-01T06:00:00Z'))
  window.scrollTo = vi.fn()
  held = 500
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
    if (path === '/api/v1/orders/GFS-26-C1') {
      return { publicRef: 'GFS-26-C1', status: 'READY_FOR_DELIVERY', sku: 'COACHING', serviceLabel: 'Coaching',
        totalMinor: 0, totalFormatted: '$0.00', currency: 'USD', coachingPlatform: 'PLAYSTATION', lines: [], timeline: [],
        createdAt: '2026-10-01T06:00:00Z' }
    }
    if (path === '/api/v1/account/store-credit') {
      return { balances: [{ currency: 'INR', minor: 47500, formatted: '₹475.00' },
        { currency: 'EUR', minor: 1250, formatted: '€12.50' }], statement: [] }
    }
    return []
  })
  api.post.mockImplementation(async (path: string, body: { couponCode: string | null; useStoreCredit?: boolean }) => {
    if (path === '/api/v1/quotes') return quote(body.couponCode, !!body.useStoreCredit)
    return { publicRef: 'GFS-26-C1', status: 'AWAITING_PAYMENT', totalMinor: 1845, totalFormatted: '$18.45',
      currency: 'USD', payment: { provider: 'STUB' } }
  })
})
afterEach(() => { vi.useRealTimers() })

describe('GFS Store Credit at the coaching checkout', () => {
  it('not offered to a customer with none in this currency', async () => {
    held = 0
    await toReview()
    expect(within(summary()).queryByTestId('store-credit')).toBeNull()
  })

  it('with a coupon: both come off, and the 2.5% is on what is left to pay', async () => {
    await toReview()
    const field = within(summary()).getByLabelText('Coupon code')
    await userEvent.type(field, 'SAVE10')
    await userEvent.click(within(summary()).getByRole('button', { name: 'Apply' }))
    await within(summary()).findByText('SAVE10 applied.')

    await userEvent.click(within(within(summary()).getByTestId('store-credit')).getByRole('checkbox'))
    await waitFor(() => expect(within(summary()).getByTestId('summary-total')).toHaveTextContent('$13.33'))
    const last = api.post.mock.calls.filter(([p]) => p === '/api/v1/quotes').pop()
    expect(last?.[1]).toMatchObject({ couponCode: 'SAVE10', useStoreCredit: true })
    expect(summary()).toHaveTextContent('Coupon SAVE10 (10% off)-$2.00')
    expect(summary()).toHaveTextContent('GFS Store Credit-$5.00')
    // 2.5% of $13.00, the part paid with money: never of the part credit covers.
    expect(summary()).toHaveTextContent('Payment processing (2.5%)$0.33')
  })

  it('credit that covers the whole order: no payment step, straight to the confirmation', async () => {
    held = 5000
    api.post.mockImplementation(async (path: string, body: { couponCode: string | null; useStoreCredit?: boolean }) => {
      if (path === '/api/v1/quotes') return quote(body.couponCode, !!body.useStoreCredit)
      return { publicRef: 'GFS-26-C1', status: 'READY_FOR_DELIVERY', totalMinor: 0, totalFormatted: '$0.00',
        currency: 'USD', payment: null }
    })
    await toReview()
    await userEvent.click(within(within(summary()).getByTestId('store-credit')).getByRole('checkbox'))
    await waitFor(() => expect(within(summary()).getByTestId('summary-total')).toHaveTextContent('$0.00'))
    expect(within(summary()).getByTestId('store-credit'))
      .toHaveTextContent('Your store credit covers this order. There is nothing to pay.')

    // The terms box: the other checkbox is the store credit one.
    const terms = screen.getAllByRole('checkbox').find((box) => !box.closest('[data-testid="store-credit"]'))!
    await userEvent.click(terms)
    await userEvent.click(screen.getByRole('button', { name: /Pay now/ }))

    expect(await screen.findByRole('heading', { name: 'Payment successful!' })).toBeInTheDocument()
    expect(screen.queryByTestId('panel')).toBeNull()
  })
})

describe('the balance on the account pages', () => {
  it('each currency held, and what credit is for', async () => {
    render(<StoreCreditBalance />)
    const box = await screen.findByTestId('store-credit-balance')
    expect(box).toHaveTextContent('GFS Store Credit')
    expect(box).toHaveTextContent('₹475.00')
    expect(box).toHaveTextContent('€12.50')
    expect(box).toHaveTextContent('It does not expire, and it cannot be withdrawn as cash.')
  })

  it('says so when there is none', async () => {
    api.get.mockResolvedValue({ balances: [], statement: [] })
    render(<StoreCreditBalance />)
    expect(await screen.findByText('You have no store credit.')).toBeInTheDocument()
  })
})
