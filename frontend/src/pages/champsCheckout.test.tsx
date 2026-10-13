import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * The boosting checkout's Champions information card, and GFS Store Credit at checkout:
 * the card on Champions orders only, with the client's words and a link to live chat; the
 * store credit option only when the customer holds some; and an order the credit covers in
 * full skipping the payment step.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
vi.mock('../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER' }, loading: false }),
}))
const CATALOG = vi.hoisted(() => ({
  catalog: { currency: 'INR', services: [
    { sku: 'BOOST_CHAMPS', displayName: 'Champs', options: [
      { platform: null, variant: 'WINS_15', label: '15 wins', unitPriceMinor: 400000, unitPriceFormatted: '₹4,000.00',
        minQuantity: null, maxQuantity: null, stepQuantity: null }] },
    { sku: 'BOOST_RIVALS', displayName: 'Rivals', options: [
      { platform: null, variant: 'DIV_3_TO_2', label: 'Division 3 to 2', unitPriceMinor: 140000,
        unitPriceFormatted: '₹1,400.00', minQuantity: null, maxQuantity: null, stepQuantity: null }] },
  ] },
  policy: { onlinePaymentsEnabled: false, customerEmailsEnabled: false, gatewayFeeBps: 250,
    champsSettlementEnabled: true, champsChoiceDays: 5 },
}))
vi.mock('../state/CatalogContext', () => ({ useCatalog: () => CATALOG }))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))

const { default: BoostingCheckout } = await import('./BoostingCheckout')

const LINES = [
  { code: 'BASE', label: 'Champs · 15 wins', amountMinor: 400000, amountFormatted: '₹4,000.00' },
  { code: 'GATEWAY_FEE', label: 'Payment processing (2.5%)', amountMinor: 10000, amountFormatted: '₹100.00' },
]
const CREDIT_LINES = [
  { code: 'BASE', label: 'Champs · 15 wins', amountMinor: 400000, amountFormatted: '₹4,000.00' },
  { code: 'STORE_CREDIT', label: 'GFS Store Credit', amountMinor: -47500, amountFormatted: '-₹475.00' },
  { code: 'GATEWAY_FEE', label: 'Payment processing (2.5%)', amountMinor: 8813, amountFormatted: '₹88.13' },
]

let creditHeld: number | null = null

function quote(useStoreCredit: boolean) {
  const spend = useStoreCredit && creditHeld ? creditHeld : 0
  return {
    quoteId: 'q1', sku: 'BOOST_CHAMPS', variant: 'WINS_15', currency: 'INR',
    lines: spend ? CREDIT_LINES : LINES, totalMinor: spend ? 361313 : 410000,
    totalFormatted: spend ? '₹3,613.13' : '₹4,100.00', pointsEarned: 0, couponMessage: null,
    expiresAt: new Date(Date.now() + 600_000).toISOString(), signature: 'x',
    storeCreditMinor: spend, storeCreditAvailableMinor: creditHeld,
    storeCreditAvailableFormatted: creditHeld ? '₹475.00' : null,
  }
}

function open(service = 'BOOST_CHAMPS', variant = 'WINS_15') {
  render(
    <MemoryRouter initialEntries={[`/boosting/checkout?service=${service}&variant=${variant}`]}>
      <Routes><Route path="/boosting/checkout" element={<BoostingCheckout />} /></Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  creditHeld = null
  CATALOG.policy.champsSettlementEnabled = true
  api.get.mockReset()
  api.post.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/payments/methods')) {
      return [{ method: 'UPI', destination: 'gfs@upi', accountName: 'GFS', link: null, referenceName: 'UTR' }]
    }
    if (path === '/api/v1/orders/GFS-26-CREDIT01') {
      return { publicRef: 'GFS-26-CREDIT01', status: 'READY_FOR_DELIVERY', sku: 'BOOST_CHAMPS', serviceLabel: 'Champs',
        platform: 'PLAYSTATION', currency: 'INR', totalMinor: 0, totalFormatted: '₹0.00', lines: [], timeline: [],
        discordAccess: null }
    }
    return []
  })
  api.post.mockImplementation(async (path: string, body?: { useStoreCredit?: boolean }) => {
    if (path === '/api/v1/quotes') return quote(!!body?.useStoreCredit)
    return new Promise(() => {})
  })
})

describe('the Champions information card', () => {
  it('shows on a Champions order: the client’s words, between the platform and the Discord block', async () => {
    open()
    const card = await screen.findByTestId('champs-info')
    expect(card).toHaveTextContent('Champions Boosting Information')
    expect(card).toHaveTextContent(
      'We aim to achieve your selected FUT Champions wins. If we achieve fewer wins, you can choose an eligible '
      + 'refund or GFS Store Credit. Your final results and settlement details will be shared by email. For '
      + 'assistance, contact our Live Chat Support.')
    // No amount is promised here.
    expect(card).not.toHaveTextContent('₹')
    const link = within(card).getByRole('link', { name: 'Live Chat Support' })
    expect(link).toHaveAttribute('href', '/support')

    const platform = screen.getByRole('group', { name: 'Select Platform' })
    const discord = screen.getByRole('heading', { name: 'Account Details via Discord' })
    expect(platform.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(card.compareDocumentPosition(discord) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('is not on a Rivals order', async () => {
    open('BOOST_RIVALS', 'DIV_3_TO_2')
    await screen.findByRole('heading', { name: 'Account Details via Discord' })
    expect(screen.queryByTestId('champs-info')).toBeNull()
  })

  it('is not shown when Champions settlement is switched off', async () => {
    CATALOG.policy.champsSettlementEnabled = false
    open()
    await screen.findByRole('heading', { name: 'Account Details via Discord' })
    expect(screen.queryByTestId('champs-info')).toBeNull()
  })
})

describe('GFS Store Credit at the boosting checkout', () => {
  it('is not offered to a customer who holds none', async () => {
    open()
    await waitFor(() => expect(screen.getByTestId('summary-total')).toHaveTextContent('₹4,100.00'))
    expect(screen.queryByTestId('store-credit')).toBeNull()
  })

  it('is offered with the balance, and using it prices the order again with the credit taken off', async () => {
    creditHeld = 47500
    open()
    const row = await screen.findByTestId('store-credit')
    expect(row).toHaveTextContent('Use my GFS Store Credit')
    expect(row).toHaveTextContent('₹475.00 available')

    fireEvent.click(within(row).getByRole('checkbox'))
    await waitFor(() => expect(screen.getByTestId('summary-total')).toHaveTextContent('₹3,613.13'))
    expect(api.post).toHaveBeenLastCalledWith('/api/v1/quotes', expect.objectContaining({ useStoreCredit: true }))
    expect(screen.getByTestId('store-credit')).toHaveTextContent('₹475.00 applied to this order')
    const panel = screen.getByTestId('summary-total').closest('aside') as HTMLElement
    expect(panel).toHaveTextContent('GFS Store Credit-₹475.00')
  })

  it('an order the credit covers in full has no payment step: it goes straight to the confirmation', async () => {
    creditHeld = 500000
    api.post.mockImplementation(async (path: string, body?: { useStoreCredit?: boolean }) => {
      if (path === '/api/v1/quotes') return { ...quote(!!body?.useStoreCredit), totalMinor: 0, totalFormatted: '₹0.00' }
      if (path === '/api/v1/orders') {
        return { publicRef: 'GFS-26-CREDIT01', status: 'READY_FOR_DELIVERY', totalMinor: 0, totalFormatted: '₹0.00',
          currency: 'INR', payment: null }
      }
      return new Promise(() => {})
    })
    open()
    fireEvent.click(within(await screen.findByTestId('store-credit')).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: /PlayStation/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: /Continue to Payment/ })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: /Continue to Payment/ }))

    expect(await screen.findByRole('heading', { name: 'Order Confirmed!' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'UPI' })).toBeNull()
  })
})
