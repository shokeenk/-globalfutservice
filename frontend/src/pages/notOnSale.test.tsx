import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Account, Catalog, CatalogOption, ServiceGroup } from '../lib/types'

/*
 * What the order pages do when the catalogue has nothing to sell.
 *
 * Production moved GFS_SEASON to FC27 with only FC26 prices in the database. The
 * catalogue answered 200 with every option empty, and the pages sat there: the coin
 * page never asked for a quote, the coaching option list was a spinner that never
 * stopped, and boosting checkout asked for a sign-in to show an empty total. Each now
 * says prices are unavailable -- except for an order already placed, which is priced by
 * its own quote and must still be payable.
 */

// ---- the API and the app's contexts, mocked, with the catalogue switchable per test ----
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const ApiErrorClass = vi.hoisted(() => class ApiError extends Error {
  code: string
  constructor(message: string, code: string) {
    super(message)
    this.code = code
  }
})
vi.mock('../lib/api', () => ({ api, ApiError: ApiErrorClass }))

const state = vi.hoisted(() => ({
  catalog: null as Catalog | null,
  error: null as string | null,
  account: null as Account | null,
}))
vi.mock('../state/CatalogContext', () => ({
  useCatalog: () => ({
    catalog: state.catalog,
    policy: { onlinePaymentsEnabled: false, maxWalletRedemptionBps: 0, pointValueMinor: 100 },
    loading: false,
    error: state.error,
    currency: 'INR',
    setCurrency: () => {},
  }),
}))
vi.mock('../state/AuthContext', () => ({
  useAuth: () => ({ account: state.account, loading: false }),
}))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))
vi.mock('../components/ManualPayment', () => ({ ManualPayment: () => <p>manual-payment</p> }))

// jsdom has no matchMedia; the storefront's reveal animations ask it about reduced motion.
window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

// Imported after the mocks are declared, so the pages pick up the mocked modules.
const { default: Order } = await import('./Order')
const { default: BoostingCheckout } = await import('./BoostingCheckout')
const { default: CoachingBook } = await import('./CoachingBook')
const { default: Coaching } = await import('./Coaching')

const SKUS = ['TRADING_SERVICE', 'BOOST_CHAMPS', 'BOOST_RIVALS', 'COACHING', 'CARDS']

/** One service as a season with no rate cards serves it: listed, not sellable, no options. */
function unpriced(sku: string): ServiceGroup {
  return {
    sku,
    displayName: sku,
    sellable: false,
    priceUnit: sku === 'TRADING_SERVICE' ? 'PER_MILLION' : 'FLAT',
    marketTaxApplies: false,
    mayRequireCredentials: false,
    options: [],
  }
}

/** The catalogue a season with no rate cards serves: every service, no options. */
function emptyCatalog(): Catalog {
  return { season: 'FC27', currency: 'INR', availableCurrencies: ['INR'], services: SKUS.map(unpriced) }
}

/** Coins on PlayStation, 10K to 1M in 10K steps. */
const PS_COINS: CatalogOption = {
  platform: 'PS',
  variant: null,
  label: null,
  unitPriceMinor: 90000,
  unitPriceFormatted: '₹900.00',
  minQuantity: '0.01',
  maxQuantity: '1',
  stepQuantity: '0.01',
}

/** Coins priced on one platform, the way a working season serves them. */
function tradingCatalog(): Catalog {
  const catalog = emptyCatalog()
  catalog.services = catalog.services.map((service) => service.sku === 'TRADING_SERVICE'
    ? { ...service, sellable: true, marketTaxApplies: true, options: [PS_COINS] }
    : service)
  return catalog
}

const SIGNED_IN: Account = {
  publicId: 'acc_player',
  email: 'player@example.test',
  displayName: 'Player',
  role: 'CUSTOMER',
  pointsBalance: 0,
  pointsValueMinor: 0,
  pointsValueFormatted: '₹0.00',
  firstOrder: true,
  referredByCode: null,
}
const PLACED = { publicRef: 'GFS-26-TESTREF1', status: 'AWAITING_PAYMENT', totalFormatted: '₹1,050.00' }

function renderAt(path: string, element: React.ReactElement) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={path.split('?')[0]} element={element} />
      </Routes>
    </MemoryRouter>,
  )
}

/** Longer than the order page's quote debounce, so a request it would make has been made. */
const pastDebounce = () => new Promise((resolve) => setTimeout(resolve, 400))

const quoteRequests = () => api.post.mock.calls.filter(([path]) => path === '/api/v1/quotes')

beforeEach(() => {
  state.catalog = emptyCatalog()
  state.error = null
  state.account = SIGNED_IN
  api.get.mockReset()
  api.post.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/orders/')) return PLACED
    if (path === '/api/v1/coaching/me') return { creditBalance: 0, upcoming: [] }
    return []
  })
})

describe('Coin order page', () => {
  it('says prices are unavailable when the catalogue has nothing to sell, and asks for no quote', async () => {
    renderAt('/order', <Order />)

    expect(await screen.findByText('Prices are unavailable')).toBeInTheDocument()
    expect(screen.getByText(/This service has no prices right now/)).toBeInTheDocument()
    expect(screen.queryByText('Apply')).not.toBeInTheDocument()
    await pastDebounce()
    expect(quoteRequests()).toHaveLength(0)
  })

  it('prices as before when there is something to sell, once the platform is picked', async () => {
    state.catalog = tradingCatalog()
    api.post.mockResolvedValue({ quoteId: 'q1', lines: [], expiresAt: '2026-10-01T07:00:00Z' })

    renderAt('/order', <Order />)
    // Nothing is picked for the customer, even when there is only one platform to sell.
    await pastDebounce()
    expect(quoteRequests()).toHaveLength(0)
    await userEvent.click(screen.getByRole('button', { name: /900/ }))

    await waitFor(() => expect(quoteRequests()).toHaveLength(1))
    expect(screen.queryByText('Prices are unavailable')).not.toBeInTheDocument()
  })

  it('shows the reason when a quote is refused', async () => {
    state.catalog = tradingCatalog()
    api.post.mockRejectedValue(new ApiErrorClass('That option is not available right now.', 'not_found'))

    renderAt('/order', <Order />)
    await userEvent.click(await screen.findByRole('button', { name: /900/ }))

    expect(await screen.findByText('That option is not available right now.')).toBeInTheDocument()
  })

  it('shows a plain message when a quote fails without one', async () => {
    state.catalog = tradingCatalog()
    api.post.mockRejectedValue(new TypeError('Failed to fetch'))

    renderAt('/order', <Order />)
    await userEvent.click(await screen.findByRole('button', { name: /900/ }))

    expect(await screen.findByText('We could not price that. Try again.')).toBeInTheDocument()
  })
})

describe('Boosting checkout', () => {
  it('says prices are unavailable before asking anyone to sign in', async () => {
    state.account = null

    renderAt('/boosting/checkout?service=BOOST_CHAMPS', <BoostingCheckout />)

    expect(await screen.findByText('Prices are unavailable')).toBeInTheDocument()
    expect(screen.queryByText(/Sign in/)).not.toBeInTheDocument()
  })

  it('sends no quote for a tier named in the link when nothing is priced', async () => {
    renderAt('/boosting/checkout?service=BOOST_CHAMPS&variant=CHAMPS_15_WINS', <BoostingCheckout />)

    expect(await screen.findByText('Prices are unavailable')).toBeInTheDocument()
    await pastDebounce()
    expect(quoteRequests()).toHaveLength(0)
  })

  it('shows the catalogue error when prices could not be loaded', async () => {
    state.catalog = null
    state.error = 'We could not load prices. Please refresh, or contact support if it persists.'

    renderAt('/boosting/checkout?service=BOOST_CHAMPS', <BoostingCheckout />)

    expect(await screen.findByText('Prices are unavailable')).toBeInTheDocument()
    expect(screen.getByText(/We could not load prices/)).toBeInTheDocument()
  })

  it('still lets a placed order be paid', async () => {
    renderAt(`/boosting/checkout?order=${PLACED.publicRef}`, <BoostingCheckout />)

    expect(await screen.findByText('manual-payment')).toBeInTheDocument()
    expect(screen.queryByText('Prices are unavailable')).not.toBeInTheDocument()
  })
})

describe('Coaching booking', () => {
  it('says prices are unavailable instead of an endless spinner and a Continue button', async () => {
    renderAt('/coaching/book', <CoachingBook />)

    expect(await screen.findByText('Prices are unavailable')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Continue/ })).not.toBeInTheDocument()
  })

  it('still lets a placed order be paid', async () => {
    renderAt(`/coaching/book?order=${PLACED.publicRef}`, <CoachingBook />)

    expect(await screen.findByText('manual-payment')).toBeInTheDocument()
    expect(screen.queryByText('Prices are unavailable')).not.toBeInTheDocument()
  })
})

describe('Coaching page', () => {
  it('says the prices are being updated where the price cards would be', async () => {
    renderAt('/coaching', <Coaching />)

    expect(await screen.findByText('Coaching prices are being updated.')).toBeInTheDocument()
  })
})
