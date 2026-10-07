import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogOption } from '../lib/types'

/*
 * No checkout picks a platform for the customer.
 *
 * Client testing found the coin checkout arriving with the first platform already
 * selected, so an order could be placed -- and delivered -- on a platform nobody chose.
 * On every checkout PC, PlayStation and Xbox now start unselected, and Continue without
 * one shows the same red required-field error as an empty text field, and goes nowhere.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
const AUTH = {
  account: { publicId: 'acc_player', email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER',
    pointsBalance: 0, pointsValueMinor: 0, pointsValueFormatted: '$0.00', firstOrder: true, referredByCode: null },
  loading: false,
}
vi.mock('../state/AuthContext', () => ({ useAuth: () => AUTH }))

function coins(platform: string, label: string): CatalogOption {
  return { platform, variant: null, label, unitPriceMinor: 900, unitPriceFormatted: '$9.00',
    minQuantity: '0.01', maxQuantity: '1', stepQuantity: '0.01' }
}
const flat = (variant: string): CatalogOption => ({ platform: null, variant, label: variant, unitPriceMinor: 1000,
  unitPriceFormatted: '$10.00', minQuantity: null, maxQuantity: null, stepQuantity: null })

// One object, as the real context gives: a catalogue rebuilt on every render would
// change every memo keyed on it, and boosting's effects would re-run forever.
const CATALOG = {
  catalog: {
    season: 'FC26', currency: 'USD', availableCurrencies: ['USD'],
    services: [
      { sku: 'TRADING_SERVICE', displayName: 'Coins', sellable: true, priceUnit: 'PER_MILLION',
        marketTaxApplies: true, mayRequireCredentials: true,
        options: [coins('PC', 'PC'), coins('PLAYSTATION', 'PlayStation'), coins('XBOX', 'Xbox')] },
      { sku: 'BOOST_CHAMPS', displayName: 'Champs', sellable: true, priceUnit: 'FLAT',
        marketTaxApplies: false, mayRequireCredentials: false, options: [flat('CHAMPS_15_WINS')] },
      { sku: 'COACHING', displayName: 'Coaching', sellable: true, priceUnit: 'FLAT',
        marketTaxApplies: false, mayRequireCredentials: false, options: [flat('SINGLE_SESSION')] },
    ],
  },
  policy: { onlinePaymentsEnabled: false, maxWalletRedemptionBps: 0, pointValueMinor: 100 },
  loading: false,
  error: null,
  currency: 'USD',
  setCurrency: () => {},
}
vi.mock('../state/CatalogContext', () => ({ useCatalog: () => CATALOG }))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))
vi.mock('../components/ManualPayment', () => ({ ManualPayment: () => <p>manual-payment</p> }))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: Order } = await import('./Order')
const { default: BoostingCheckout } = await import('./BoostingCheckout')
const { default: CoachingBook } = await import('./CoachingBook')

function renderAt(path: string, element: React.ReactElement) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes><Route path={path.split('?')[0]} element={element} /></Routes>
    </MemoryRouter>,
  )
}

/** Longer than the coin page's quote debounce, so a request it would make has been made. */
const pastDebounce = () => new Promise((resolve) => setTimeout(resolve, 400))

const posted = (path: string) => api.post.mock.calls.filter(([p]) => p === path).map(([, body]) => body)

/** The required-field error: announced, and the same red as the site's other required fields. */
function expectRequiredError(text: string) {
  const error = screen.getByText(text)
  expect(error).toHaveAttribute('role', 'alert')
  expect(error).toHaveClass('text-brand-400')
  return error
}

const scrolled = vi.fn()

beforeEach(() => {
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  Element.prototype.scrollIntoView = scrolled
  scrolled.mockReset()
  api.get.mockReset()
  api.post.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/coaching/coaches') {
      return [{ id: 'vinay', displayName: 'Vinay', headline: null, bio: null, avatarUrl: null, languages: null,
        timezone: 'Asia/Kolkata' }]
    }
    if (path.includes('/slots')) {
      return { coachId: 'vinay', coachTimezone: 'Asia/Kolkata', sessionMinutes: 60, slots: ['2026-10-05T13:30:00.000Z'] }
    }
    return []
  })
  api.post.mockImplementation(async (path: string, body: { sku?: string; platform?: string | null }) => {
    if (path === '/api/v1/quotes') {
      return { quoteId: 'q1', season: 'FC26', sku: body.sku, platform: body.platform ?? null, variant: null,
        quantity: '0.1', currency: 'USD', lines: [], subtotalMinor: 90, totalMinor: 90, totalFormatted: '$0.90',
        pointsRedeemed: 0, pointsEarned: 0, referralCode: null, couponCode: null, couponMessage: null,
        issuedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 600_000).toISOString(), signature: 'x' }
    }
    return { publicRef: 'GFS-26-PLAT0001', status: 'AWAITING_PAYMENT', totalMinor: 90, totalFormatted: '$0.90',
      currency: 'USD', payment: { provider: 'STUB' } }
  })
})

describe('coins checkout', () => {
  it('starts with PC, PlayStation and Xbox all unselected, and prices nothing until one is picked', async () => {
    renderAt('/order', <Order />)

    const picker = await screen.findByRole('group', { name: 'Platform' })
    const cards = within(picker).getAllByRole('button')
    expect(cards.map((c) => c.textContent)).toEqual([
      expect.stringContaining('PC'), expect.stringContaining('PlayStation'), expect.stringContaining('Xbox'),
    ])
    for (const card of cards) expect(card).toHaveAttribute('aria-pressed', 'false')

    await pastDebounce()
    expect(posted('/api/v1/quotes')).toHaveLength(0)
    expect(screen.getByText('Choose your platform to see your price.')).toBeInTheDocument()
    // No error before the customer has tried to move on.
    expect(screen.queryByText('Choose the platform you play on.')).toBeNull()
  })

  it('Continue without a platform shows the red required error at the picker, and stays put', async () => {
    renderAt('/order', <Order />)
    const picker = await screen.findByRole('group', { name: 'Platform' })

    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    const error = expectRequiredError('Choose the platform you play on.')
    expect(picker).toHaveAttribute('aria-describedby', error.id)
    expect(scrolled).toHaveBeenCalled()
    // Still on the first step: the picker is there and nothing was priced or ordered.
    expect(screen.getByRole('group', { name: 'Platform' })).toBeInTheDocument()
    expect(posted('/api/v1/quotes')).toHaveLength(0)
    expect(posted('/api/v1/orders')).toHaveLength(0)
  })

  it('once picked, the error goes, and the price is asked for that platform and no other', async () => {
    renderAt('/order', <Order />)
    await screen.findByRole('group', { name: 'Platform' })
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    await userEvent.click(screen.getByRole('button', { name: /Xbox/ }))

    expect(screen.queryByText('Choose the platform you play on.')).toBeNull()
    expect(screen.getByRole('button', { name: /Xbox/ })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(posted('/api/v1/quotes').length).toBeGreaterThan(0))
    for (const body of posted('/api/v1/quotes')) expect(body).toMatchObject({ platform: 'XBOX' })

    // And the step can now be left.
    await userEvent.click(await screen.findByRole('button', { name: 'Continue' }))
    await waitFor(() => expect(screen.queryByRole('group', { name: 'Platform' })).toBeNull())
  })
})

describe('boosting checkout', () => {
  const continueButton = () => screen.getByRole('button', { name: /Continue to Payment/ })

  it('starts with no platform selected', async () => {
    renderAt('/boosting/checkout?service=BOOST_CHAMPS', <BoostingCheckout />)

    const picker = await screen.findByRole('group', { name: /platform/i })
    const cards = within(picker).getAllByRole('button')
    expect(cards).toHaveLength(2)
    for (const card of cards) expect(card).toHaveAttribute('aria-pressed', 'false')
    expect(screen.queryByText('Choose a platform to continue.')).toBeNull()
  })

  it('Continue is pressable, and without a platform shows the red required error and places nothing', async () => {
    renderAt('/boosting/checkout?service=BOOST_CHAMPS', <BoostingCheckout />)
    const picker = await screen.findByRole('group', { name: /platform/i })
    await waitFor(() => expect(continueButton()).toBeEnabled())

    await userEvent.click(continueButton())

    const error = expectRequiredError('Choose a platform to continue.')
    expect(picker).toHaveAttribute('aria-describedby', error.id)
    expect(scrolled).toHaveBeenCalled()
    expect(posted('/api/v1/orders')).toHaveLength(0)
  })

  it('PC also needs a launcher, with the same error; the order then carries what was picked', async () => {
    renderAt('/boosting/checkout?service=BOOST_CHAMPS', <BoostingCheckout />)
    await screen.findByRole('group', { name: /platform/i })
    await waitFor(() => expect(continueButton()).toBeEnabled())

    await userEvent.click(screen.getByRole('button', { name: /^PC/ }))
    await userEvent.click(continueButton())
    expectRequiredError('Choose where you play on PC to continue.')
    expect(screen.queryByText('Choose a platform to continue.')).toBeNull()
    expect(posted('/api/v1/orders')).toHaveLength(0)

    await userEvent.click(screen.getByRole('button', { name: /^Steam/ }))
    await userEvent.click(continueButton())
    await waitFor(() => expect(posted('/api/v1/orders')).toHaveLength(1))
    expect(posted('/api/v1/orders')[0]).toMatchObject({ boostPlatform: 'PC', pcLauncher: 'STEAM' })
  })
})

describe('coaching checkout', () => {
  it('starts with PlayStation, Xbox and PC all unchecked', async () => {
    renderAt('/coaching/book?step=details', <CoachingBook />)

    const group = await screen.findByRole('radiogroup', { name: 'Platform' })
    const radios = within(group).getAllByRole('radio')
    expect(radios).toHaveLength(3)
    for (const radio of radios) expect(radio).toHaveAttribute('aria-checked', 'false')
  })

  it('Continue without a platform shows the red required error and stays on the details step', async () => {
    renderAt('/coaching/book?step=details', <CoachingBook />)
    await userEvent.type(await screen.findByPlaceholderText(/in-game ID/i), 'VinayFC10')

    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))

    const error = expectRequiredError('Choose the platform you play on.')
    const group = screen.getByRole('radiogroup', { name: 'Platform' })
    expect(group).toHaveAttribute('aria-describedby', error.id)
    expect(group).toHaveAttribute('aria-invalid', 'true')

    await userEvent.click(within(group).getByRole('radio', { name: /PlayStation/ }))
    expect(screen.queryByText('Choose the platform you play on.')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
    await waitFor(() => expect(screen.queryByRole('radiogroup', { name: 'Platform' })).toBeNull())
  })
})
