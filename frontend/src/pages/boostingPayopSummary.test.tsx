import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * International (Payop) on the boosting checkout's pay step: the order panel on the right
 * drops the 2.5% card fee for the chosen method's own fee from the fee sheet, and the total
 * appears there once, with the button that starts the payment. UPI keeps the 2.5%.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
vi.mock('../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER' }, loading: false }),
}))
// One object, as the real context gives: the page prices again whenever the catalogue changes.
const CATALOG = vi.hoisted(() => ({
  catalog: { currency: 'EUR', services: [{ sku: 'BOOST_CHAMPS', displayName: 'Champs', options: [
    { platform: null, variant: 'RANK_1', label: 'Rank 1', unitPriceMinor: 9000, unitPriceFormatted: '€90.00',
      minQuantity: null, maxQuantity: null, stepQuantity: null },
  ] }] },
  policy: { onlinePaymentsEnabled: false, customerEmailsEnabled: false, gatewayFeeBps: 250 },
}))
vi.mock('../state/CatalogContext', () => ({ useCatalog: () => CATALOG }))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))

const { default: BoostingCheckout } = await import('./BoostingCheckout')

const REF = 'GFS-26-BOOSTEUR'
const LINES = [
  { code: 'BASE', label: 'Champs · Rank 1', amountMinor: 9000, amountFormatted: '€90.00' },
  { code: 'GATEWAY_FEE', label: 'Payment processing (2.5%)', amountMinor: 225, amountFormatted: '€2.25' },
]

beforeEach(() => {
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  api.get.mockReset()
  api.post.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path === `/api/v1/orders/${REF}`) {
      return { publicRef: REF, status: 'AWAITING_PAYMENT', paymentState: 'UNPAID', sku: 'BOOST_CHAMPS',
        variant: 'RANK_1', currency: 'EUR', totalMinor: 9225, totalFormatted: '€92.25', lines: LINES }
    }
    if (path.startsWith('/api/v1/payments/methods')) {
      return [{ method: 'UPI', destination: 'gfs@upi', accountName: 'GFS', link: null, referenceName: 'UTR' }]
    }
    if (path === '/api/v1/payments/payop/country') return { country: 'DE' }
    return []
  })
  api.post.mockImplementation(async (path: string) => {
    if (path === '/api/v1/quotes') {
      return { quoteId: 'q1', sku: 'BOOST_CHAMPS', variant: 'RANK_1', currency: 'EUR', lines: LINES,
        totalMinor: 9225, totalFormatted: '€92.25', pointsEarned: 0, couponMessage: null,
        expiresAt: new Date(Date.now() + 600_000).toISOString(), signature: 'x' }
    }
    if (path === '/api/v1/payments/payop/options') {
      return { currency: 'EUR', netMinor: 9000, netFormatted: '€90.00', lines: LINES.slice(0, 1), unavailable: null,
        claimsBlockedUntil: null,
        methods: [{ methodId: 381, name: 'Bank transfer', type: 'bank_transfer', feeMinor: 407, feeFormatted: '€4.07',
          totalMinor: 9407, totalFormatted: '€94.07', token: 'sealed-381' }] }
    }
    return new Promise(() => {})
  })
})

describe('the boosting checkout pays International without the 2.5% card fee', () => {
  it('the order panel on the right: the card fee for UPI, the sheet fee and the total once for Payop', async () => {
    render(
      <MemoryRouter initialEntries={[`/boosting/checkout?service=BOOST_CHAMPS&variant=RANK_1&order=${REF}`]}>
        <Routes><Route path="/boosting/checkout" element={<BoostingCheckout />} /></Routes>
      </MemoryRouter>,
    )
    const upi = await screen.findByRole('tab', { name: 'UPI' })
    const panel = screen.getByTestId('summary-total').closest('aside') as HTMLElement
    const grid = panel.parentElement as HTMLElement
    expect(grid.className).toContain('lg:grid-cols-[1.35fr_1fr]')
    expect(grid.lastElementChild).toBe(panel)
    expect(grid.firstElementChild).toContainElement(upi)

    await waitFor(() => expect(within(panel).getByTestId('summary-total')).toHaveTextContent('€92.25'))
    expect(panel).toHaveTextContent('€2.25')

    fireEvent.click(screen.getByRole('tab', { name: 'International' }))
    fireEvent.click(await screen.findByRole('button', { name: /Bank transfer/ }))
    expect(panel).not.toHaveTextContent('€2.25')
    expect(panel).toHaveTextContent('Payment processing fee (Bank transfer)€4.07')
    expect(within(panel).getByTestId('summary-total')).toHaveTextContent('€94.07')
    expect(screen.getAllByText('€94.07')).toHaveLength(1)
    expect(within(panel).getByRole('button', { name: 'Continue to payment' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'UPI' }))
    expect(panel).toHaveTextContent('€2.25')
    expect(within(panel).getByTestId('summary-total')).toHaveTextContent('€92.25')
  })
})
