import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogCoin, CatalogOption } from '../lib/types'

/*
 * The coin slider reads the chosen platform's price structure, as the admin set it on the
 * Coin rates page: PC has its own; PlayStation and Xbox share one. Its range, its step, its
 * quick picks and its prices all come from there -- none of it is written into the page --
 * and the rate shown follows the volume bracket the amount reaches.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
const AUTH = {
  account: { publicId: 'acc_player', email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER',
    pointsBalance: 0, pointsValueMinor: 0, pointsValueFormatted: '$0.00', firstOrder: true, referredByCode: null },
  loading: false,
}
vi.mock('../state/AuthContext', () => ({ useAuth: () => AUTH }))

const rate = (fromK: number, perMillionMinor: number) => ({
  fromK, perMillionMinor, perMillionFormatted: `$${(perMillionMinor / 100).toFixed(2)}`,
  per100kFormatted: `$${(perMillionMinor / 1000).toFixed(2)}`,
})

/** PC: 50K to 1M in 10K steps, its own quick picks, cheaper from 500K. */
const PC: CatalogCoin = {
  market: 'PC', marketLabel: 'PC', minK: 50, maxK: 1000, stepK: 10, quickPicksK: [50, 100, 500, 730, 1000],
  rates: [rate(0, 14_300), rate(500, 13_000)],
}
/** PlayStation + Xbox: one market, a different slider, one price, no brackets. */
const CONSOLE: CatalogCoin = {
  market: 'CONSOLE', marketLabel: 'PlayStation + Xbox', minK: 100, maxK: 2000, stepK: 20,
  quickPicksK: [100, 200, 1000, 2000], rates: [rate(0, 15_000)],
}

function coins(platform: string, label: string, coin: CatalogCoin | null): CatalogOption {
  const structure = coin ?? PC
  return {
    platform, variant: null, label, unitPriceMinor: structure.rates[0]!.perMillionMinor,
    unitPriceFormatted: structure.rates[0]!.perMillionFormatted,
    minQuantity: String(structure.minK / 1000), maxQuantity: String(structure.maxK / 1000),
    stepQuantity: String(structure.stepK / 1000), coin,
  }
}

let options: CatalogOption[] = []
const refresh = vi.fn()
let pricedVersion: number | null = null
// One object, as the real context gives: a catalogue rebuilt on every render would change
// every memo keyed on it.
const CATALOG = {
  catalog: {
    season: 'FC26', currency: 'USD', availableCurrencies: ['USD'],
    services: [{ sku: 'TRADING_SERVICE', displayName: 'Coins', sellable: true, priceUnit: 'PER_MILLION',
      marketTaxApplies: true, mayRequireCredentials: true, get options() { return options } }],
  },
  policy: { onlinePaymentsEnabled: false, maxWalletRedemptionBps: 0, pointValueMinor: 100 },
  loading: false,
  error: null,
  currency: 'USD',
  setCurrency: () => {},
  refresh,
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

function open() {
  render(
    <MemoryRouter initialEntries={['/order']}>
      <Routes><Route path="/order" element={<Order />} /></Routes>
    </MemoryRouter>,
  )
}

const posted = () => api.post.mock.calls.filter(([p]) => p === '/api/v1/quotes').map(([, body]) => body)
const lastQuote = () => posted()[posted().length - 1]

async function pick(name: RegExp) {
  const picker = await screen.findByRole('group', { name: 'Platform' })
  await userEvent.click(within(picker).getByRole('button', { name }))
}

const slider = () => screen.getByRole('slider', { name: 'Coin amount in millions' })
const exact = () => screen.getByRole('spinbutton', { name: 'Or type an exact amount' })
const chips = () => ['50K', '100K', '200K', '250K', '500K', '730K', '1M', '2M']
  .filter((label) => screen.queryByRole('button', { name: label, pressed: false })
    ?? screen.queryByRole('button', { name: label, pressed: true }))

beforeEach(() => {
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  Element.prototype.scrollIntoView = vi.fn()
  options = [coins('PC', 'PC', PC), coins('PLAYSTATION', 'PlayStation', CONSOLE), coins('XBOX', 'Xbox', CONSOLE)]
  api.get.mockReset()
  api.post.mockReset()
  refresh.mockReset()
  pricedVersion = null
  api.get.mockResolvedValue([])
  api.post.mockImplementation(async (path: string, body: { sku?: string; platform?: string; quantity?: string }) => (
    path === '/api/v1/quotes'
      ? { quoteId: 'q1', season: 'FC26', sku: body.sku, platform: body.platform ?? null, variant: null,
          quantity: body.quantity, currency: 'USD', lines: [], subtotalMinor: 90, totalMinor: 90, totalFormatted: '$0.90',
          pointsRedeemed: 0, pointsEarned: 0, referralCode: null, couponCode: null, couponMessage: null,
          issuedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 600_000).toISOString(), signature: 'x',
          priceVersion: pricedVersion }
      : {}))
})

describe('PC: its own structure', () => {
  it('the range, the step and the quick picks are the ones the admin set for PC', async () => {
    open()
    await pick(/^PC/)
    expect(slider()).toHaveAttribute('min', '0.05')
    expect(slider()).toHaveAttribute('max', '1')
    expect(slider()).toHaveAttribute('step', '0.01')
    // 730K is no round number: it is on screen because the admin put it there.
    expect(chips()).toEqual(['50K', '100K', '500K', '730K', '1M'])
  })

  it('the rate follows the bracket the amount reaches, for every coin in the order', async () => {
    open()
    await pick(/^PC/)

    expect(screen.getByTestId('applied-rate')).toHaveTextContent('$143.00')
    const panel = screen.getByTestId('volume-pricing')
    const [base, bulk] = within(panel).getAllByRole('listitem')
    expect(base).toHaveTextContent('50K – 490K')
    expect(base).toHaveTextContent('$143.00 / million')
    expect(base).toHaveAttribute('aria-current', 'true')
    expect(bulk).toHaveTextContent('500K and more')
    expect(bulk).toHaveTextContent('$130.00 / million')
    expect(panel).toHaveTextContent('From 500K, every coin in the order is $130.00 / million.')

    await userEvent.click(screen.getByRole('button', { name: '500K' }))
    expect(screen.getByTestId('applied-rate')).toHaveTextContent('$130.00')
    expect(bulk).toHaveAttribute('aria-current', 'true')
    expect(base).not.toHaveAttribute('aria-current')
    expect(panel).not.toHaveTextContent('From 500K')
    // And the price is asked for, for that platform and that amount.
    await waitFor(() => expect(lastQuote()).toMatchObject({ platform: 'PC', quantity: '0.5' }))
  })
})

describe('PlayStation and Xbox: one shared structure', () => {
  it('both read the same slider, quick picks and price; with no brackets there is no volume panel', async () => {
    open()
    for (const name of [/PlayStation/, /Xbox/]) {
      await pick(name)
      expect(slider()).toHaveAttribute('min', '0.1')
      expect(slider()).toHaveAttribute('max', '2')
      expect(slider()).toHaveAttribute('step', '0.02')
      expect(chips()).toEqual(['100K', '200K', '1M', '2M'])
      expect(screen.getByTestId('applied-rate')).toHaveTextContent('$150.00')
      expect(screen.queryByTestId('volume-pricing')).toBeNull()
    }
    await waitFor(() => expect(lastQuote()).toMatchObject({ platform: 'XBOX' }))
  })

  it('switching from PC fits the amount to the shared structure and asks for its price straight away', async () => {
    open()
    await pick(/^PC/)
    await userEvent.click(screen.getByRole('button', { name: '730K' }))
    await waitFor(() => expect(lastQuote()).toMatchObject({ platform: 'PC', quantity: '0.73' }))

    await pick(/PlayStation/)
    // 730K is not on PlayStation + Xbox's 20K steps from 100K: the nearest one is 740K.
    await waitFor(() => expect(exact()).toHaveValue(740))
    await waitFor(() => expect(lastQuote()).toMatchObject({ platform: 'PLAYSTATION', quantity: '0.74' }))
  })
})

describe('prices changed since the page loaded them', () => {
  it('a quote priced from a newer version makes the page fetch its prices again, once', async () => {
    options = options.map((o) => ({ ...o, coin: o.coin && { ...o.coin, version: 7 } }))
    pricedVersion = 8
    open()
    await pick(/^PC/)
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
    // More quotes from the same newer version do not ask again.
    await userEvent.click(screen.getByRole('button', { name: '500K' }))
    await waitFor(() => expect(lastQuote()).toMatchObject({ quantity: '0.5' }))
    await new Promise((r) => setTimeout(r, 50))
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('a quote from the version on screen asks for nothing', async () => {
    options = options.map((o) => ({ ...o, coin: o.coin && { ...o.coin, version: 7 } }))
    pricedVersion = 7
    open()
    await pick(/^PC/)
    await waitFor(() => expect(posted().length).toBeGreaterThan(0))
    await new Promise((r) => setTimeout(r, 50))
    expect(refresh).not.toHaveBeenCalled()
  })
})

describe('a server without structures', () => {
  it('still gives a working slider, from the option’s own range', async () => {
    options = options.map((o) => ({ ...o, coin: null }))
    open()
    await pick(/^PC/)
    expect(slider()).toHaveAttribute('min', '0.05')
    expect(screen.getByTestId('applied-rate')).toHaveTextContent('$143.00')
    expect(screen.queryByTestId('volume-pricing')).toBeNull()
    expect(chips()).toContain('100K')
  })
})
