import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogOption } from '../lib/types'

/*
 * International (Payop) on the coin checkout's pay step. A Payop payment is charged only its
 * method's fee from the fee sheet: the order summary on the right drops the 2.5% card fee,
 * says "Select a payment method" until one is chosen, then shows that method's fee and the
 * total -- once on the page -- with the button that starts the payment under it. UPI, PayPal
 * and USDT keep the 2.5%, and moving between them and International swaps the fee line.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))

const AUTH = { account: { publicId: 'acc_player', email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER',
  pointsBalance: 0, pointsValueMinor: 0, pointsValueFormatted: '€0.00', firstOrder: true, referredByCode: null },
loading: false }
vi.mock('../state/AuthContext', () => ({ useAuth: () => AUTH }))

const coins = (platform: string): CatalogOption => ({ platform, variant: null, label: platform, unitPriceMinor: 90000,
  unitPriceFormatted: '€900.00', minQuantity: '0.1', maxQuantity: '1', stepQuantity: '0.1' })
const CATALOG = {
  catalog: { season: 'FC26', currency: 'EUR', availableCurrencies: ['EUR'], services: [
    { sku: 'TRADING_SERVICE', displayName: 'Coins', sellable: true, priceUnit: 'PER_MILLION', marketTaxApplies: true,
      mayRequireCredentials: true, options: [coins('PC'), coins('PLAYSTATION')] }] },
  policy: { onlinePaymentsEnabled: false, maxWalletRedemptionBps: 0, pointValueMinor: 100, backupCodesRequired: 1,
    gatewayFeeBps: 250 },
  loading: false, error: null, currency: 'EUR', setCurrency: () => {},
}
vi.mock('../state/CatalogContext', () => ({ useCatalog: () => CATALOG }))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))

window.matchMedia = ((query: string) => ({
  matches: false, media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: Order } = await import('./Order')

const fill = (field: HTMLElement, value: string) => fireEvent.change(field, { target: { value } })

/** The order as quoted and placed: €90.00 of coins, and €2.25 of card fee for UPI, PayPal and USDT. */
const QUOTE_LINES = [
  { code: 'BASE', label: 'Coins', amountMinor: 9000, amountFormatted: '€90.00' },
  { code: 'GATEWAY_FEE', label: 'Payment processing (2.5%)', amountMinor: 225, amountFormatted: '€2.25' },
]

beforeEach(() => {
  Element.prototype.scrollIntoView = () => {}
  Element.prototype.animate = () => ({}) as Animation
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  api.get.mockReset()
  api.post.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/payments/methods')) {
      return [
        { method: 'UPI', destination: 'gfs@upi', accountName: 'GFS', link: null, referenceName: 'UTR' },
        { method: 'PAYPAL', destination: 'pay@gfs.test', accountName: null, link: null, referenceName: 'id' },
      ]
    }
    if (path === '/api/v1/payments/payop/country') return { country: 'DE' }
    return []
  })
  api.post.mockImplementation(async (path: string, body: { platform?: string }) => {
    if (path === '/api/v1/quotes') {
      return { quoteId: 'q1', season: 'FC26', sku: 'TRADING_SERVICE', platform: body.platform, variant: null,
        quantity: '0.1', currency: 'EUR', lines: QUOTE_LINES, subtotalMinor: 9000, totalMinor: 9225,
        totalFormatted: '€92.25', pointsRedeemed: 0, pointsEarned: 0, referralCode: null, couponCode: null,
        couponMessage: null, issuedAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 600_000).toISOString(), signature: 'x' }
    }
    if (path === '/api/v1/orders') {
      return { publicRef: 'GFS-26-PAYOP001', status: 'AWAITING_PAYMENT', totalMinor: 9225, totalFormatted: '€92.25',
        currency: 'EUR', payment: { provider: 'STUB' } }
    }
    if (path.endsWith('/credentials')) return {}
    if (path === '/api/v1/payments/payop/options') {
      return { currency: 'EUR', netMinor: 9000, netFormatted: '€90.00', lines: QUOTE_LINES.slice(0, 1),
        unavailable: null, claimsBlockedUntil: null,
        methods: [{ methodId: 381, name: 'Bank transfer', type: 'bank_transfer', feeMinor: 407, feeFormatted: '€4.07',
          totalMinor: 9407, totalFormatted: '€94.07', token: 'sealed-381' }] }
    }
    return new Promise(() => {})
  })
})

/** Through configure and details to the pay step, with the order placed. */
async function toPayStep() {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  try {
    render(<MemoryRouter initialEntries={['/order']}><Routes><Route path="/order" element={<Order />} /></Routes></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: /PLAYSTATION/ }))
    await act(() => vi.runOnlyPendingTimersAsync())
  } finally {
    vi.useRealTimers()
  }
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
  fill(screen.getByLabelText(/EA account email/), 'ea@example.test')
  fill(screen.getByLabelText(/EA password/), 'correct-horse')
  fill(screen.getAllByLabelText(/^Backup code \d/)[0]!, '12345678')
  for (const box of screen.getAllByRole('checkbox')) fireEvent.click(box)
  fireEvent.click(screen.getByRole('button', { name: /^Pay €92\.25/ }))
  await screen.findByRole('tab', { name: 'UPI' })
}

/** The order summary: the card around the total. */
function summary() {
  return screen.getByTestId('summary-total').closest('.plate') as HTMLElement
}

describe('the coin checkout pays International without the 2.5% card fee', () => {
  it('the summary on the right follows the way to pay: card fee for UPI, the sheet fee for Payop, the total once', async () => {
    await toPayStep()

    // Beside the pay step, in the checkout's two columns: the summary is the right-hand one.
    const grid = summary().closest('.grid') as HTMLElement
    expect(grid.className).toContain('lg:grid-cols-[1.25fr_1fr]')
    expect(grid.lastElementChild).toContainElement(summary())
    expect(grid.firstElementChild).toContainElement(screen.getByRole('tab', { name: 'UPI' }))

    // UPI: the order as placed, 2.5% card fee included.
    expect(summary()).toHaveTextContent('Payment processing (2.5%)€2.25')
    expect(within(summary()).getByTestId('summary-total')).toHaveTextContent('€92.25')

    // International, before a method: no card fee, the fee line asks for one, and no total yet.
    fireEvent.click(screen.getByRole('tab', { name: 'International' }))
    const bank = await screen.findByRole('button', { name: /Bank transfer/ })
    await waitFor(() => expect(summary()).toHaveTextContent('Payment processing feeSelect a payment method'))
    expect(summary()).not.toHaveTextContent('€2.25')
    expect(summary()).not.toHaveTextContent('2.5%')
    expect(within(summary()).getByTestId('summary-total')).toHaveTextContent('—')
    expect(within(summary()).queryByRole('button', { name: 'Continue to payment' })).toBeNull()

    // A method: the sheet fee, named, the total -- once on the page -- and the button under it.
    fireEvent.click(bank)
    expect(summary()).toHaveTextContent('Payment processing fee (Bank transfer)€4.07')
    expect(within(summary()).getByTestId('summary-total')).toHaveTextContent('€94.07')
    expect(screen.getAllByText('€94.07')).toHaveLength(1)
    expect(within(summary()).getByRole('button', { name: 'Continue to payment' })).toBeInTheDocument()

    // Starting it sends the server's token, never an amount.
    fireEvent.click(within(summary()).getByRole('button', { name: 'Continue to payment' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/payments/payop/invoices',
      { order: 'GFS-26-PAYOP001', email: 'player@example.test', token: 'sealed-381', language: 'en' }))

    // Back to UPI: the card fee again, and the order's own total.
    fireEvent.click(screen.getByRole('tab', { name: 'UPI' }))
    expect(summary()).toHaveTextContent('Payment processing (2.5%)€2.25')
    expect(within(summary()).getByTestId('summary-total')).toHaveTextContent('€92.25')
    expect(summary()).not.toHaveTextContent('Bank transfer')
  })
})
