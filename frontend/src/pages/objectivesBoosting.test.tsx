import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogListing, CatalogOption, ServiceGroup } from '../lib/types'

/*
 * The Boosting page's four categories: Champs wins, Rivals divisions, Objectives and
 * Tournaments in one row, the selected one in red; every card drawn by the one shared card;
 * Objectives and Tournaments listed from the catalogue -- only what the server has on sale,
 * in its order, at its price in the currency shown -- with their artwork when the team
 * chose to show it; a clean empty state; and the Champions success banner on Champs only.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), baseUrl: 'https://api.example.test' }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))

const state = vi.hoisted(() => ({ services: [] as ServiceGroup[], currency: 'INR' }))
vi.mock('../state/CatalogContext', () => ({
  useCatalog: () => ({
    catalog: { season: 'FC27', currency: state.currency, availableCurrencies: ['INR', 'USD'], services: state.services },
    policy: { guaranteeCashBps: 5000, guaranteeCreditBps: 10000 },
    loading: false,
    error: null,
  }),
}))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: Boosting } = await import('./Boosting')

function listing(id: number, patch: Partial<CatalogListing> = {}): CatalogListing {
  return {
    id, title: `Listing ${id}`, subtitle: null, imageUrl: null, imageWidth: null, imageHeight: null, showImage: true,
    pricingType: 'FIXED', minHours: null, maxHours: null, overview: null, requirements: [], requiredGames: null,
    estimatedTime: null, schedulingNote: null, reward: null, customerInstructions: null, ...patch,
  }
}

function option(l: CatalogListing, minor: number, formatted: string): CatalogOption {
  return {
    platform: null, variant: `LISTING_${l.id}`, label: l.title, unitPriceMinor: minor, unitPriceFormatted: formatted,
    minQuantity: null, maxQuantity: null, stepQuantity: null, successRateBps: null, bestValue: false, listing: l,
  }
}

function group(sku: string, options: CatalogOption[]): ServiceGroup {
  return { sku, displayName: sku, sellable: options.length > 0, priceUnit: 'FLAT', marketTaxApplies: false,
    mayRequireCredentials: true, options } as ServiceGroup
}

const CHAMPS = ['WINS_13', 'WINS_14', 'WINS_15'].map((variant, i): CatalogOption => ({
  platform: null, variant, label: variant, unitPriceMinor: 260000 + i * 50000, unitPriceFormatted: `₹${2600 + i * 500}.00`,
  minQuantity: null, maxQuantity: null, stepQuantity: null, successRateBps: 9200, bestValue: i === 2,
}))

const CLAIRE = listing(12, {
  title: 'Claire Hutton Objective', subtitle: '84 CDM · Special Card',
  imageUrl: '/api/v1/catalog/listing-images/7', imageWidth: 400, imageHeight: 500,
})
const SCORE = listing(14, { title: 'Score in 3 Objective', subtitle: '150 SP Reward', showImage: false,
  imageUrl: '/api/v1/catalog/listing-images/8' })
const GALLERY = listing(20, { title: 'Gallery Tokens', subtitle: 'Paid per hour · 100% manual', pricingType: 'PER_HOUR',
  minHours: 1, maxHours: 50 })

function open(path = '/boosting') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes><Route path="/boosting" element={<Boosting />} /></Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  state.currency = 'INR'
  state.services = [
    group('BOOST_CHAMPS', CHAMPS),
    group('BOOST_RIVALS', []),
    // As the server sends them: active only, in display order.
    group('BOOST_OBJECTIVES', [option(CLAIRE, 120000, '₹1,200.00'), option(SCORE, 65000, '₹650.00'),
      option(GALLERY, 65000, '₹650.00')]),
    group('BOOST_TOURNAMENTS', []),
  ]
})

describe('the category row', () => {
  it('shows Champs wins, Rivals divisions, Objectives and Tournaments in one row, Champs chosen in red', () => {
    open()
    const row = screen.getByRole('group', { name: 'Boosting services' })
    const buttons = within(row).getAllByRole('button')
    expect(buttons.map((b) => b.textContent)).toEqual(['Champs wins', 'Rivals divisions', 'Objectives', 'Tournaments'])
    expect(buttons[0]).toHaveAttribute('aria-pressed', 'true')
    expect(buttons[0]!.className).toContain('bg-brand-500')
    expect(buttons[2]).toHaveAttribute('aria-pressed', 'false')
  })

  it('switches to Objectives, which then wears the red', () => {
    open()
    fireEvent.click(screen.getByRole('button', { name: 'Objectives' }))
    expect(screen.getByRole('button', { name: 'Objectives' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Objectives' }).className).toContain('bg-brand-500')
    expect(screen.getByText('Claire Hutton Objective')).toBeInTheDocument()
  })
})

describe('the Objectives listing', () => {
  it('one card per listing, in the order the server sent, each opening checkout by its id', () => {
    open('/boosting?category=objectives')
    const grid = screen.getByTestId('boosting-grid')
    const cards = within(grid).getAllByRole('link')
    expect(cards.map((c) => c.getAttribute('href'))).toEqual([
      '/boosting/checkout?listing=12', '/boosting/checkout?listing=14', '/boosting/checkout?listing=20'])
    expect(cards[0]).toHaveTextContent('Claire Hutton Objective')
    expect(cards[0]).toHaveTextContent('84 CDM · Special Card')
    expect(cards[0]).toHaveTextContent('₹1,200.00')
    expect(cards[0]).toHaveTextContent('Choose')
  })

  it('shows the artwork whole and unstretched, only where the team chose to show it', () => {
    open('/boosting?category=objectives')
    const [claire, score] = within(screen.getByTestId('boosting-grid')).getAllByRole('link')
    const art = within(claire!).getByTestId('listing-artwork').querySelector('img')!
    expect(art).toHaveAttribute('src', 'https://api.example.test/api/v1/catalog/listing-images/7')
    expect(art.className).toContain('object-contain')
    expect(art).toHaveAttribute('width', '400')
    expect(art).toHaveAttribute('height', '500')
    expect(within(score!).queryByTestId('listing-artwork')).toBeNull()
  })

  it('a listing sold by the hour says so on its price', () => {
    open('/boosting?category=objectives')
    const gallery = within(screen.getByTestId('boosting-grid')).getAllByRole('link')[2]!
    expect(gallery).toHaveTextContent('₹650.00 / hour')
  })

  it('uses the same card as Champs: one component, not a page of its own', () => {
    open()
    const tier = within(screen.getByTestId('boosting-grid')).getAllByRole('link')[0]!
    fireEvent.click(screen.getByRole('button', { name: 'Objectives' }))
    const objective = within(screen.getByTestId('boosting-grid')).getAllByRole('link')[0]!
    const base = 'group relative flex h-full flex-col overflow-hidden rounded-panel border p-6'
    expect(tier.className).toContain(base)
    expect(objective.className).toContain(base)
  })

  it('the Champions success banner is on Champs only', () => {
    open()
    expect(screen.getByText(/13–15 wins reached in 92% of eligible orders/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Objectives' }))
    expect(screen.queryByText(/reached in .* of eligible orders/)).toBeNull()
  })

  it('shows each card at its price in the currency being shown', () => {
    state.currency = 'USD'
    state.services = [group('BOOST_OBJECTIVES', [option(CLAIRE, 1450, '$14.50')])]
    open('/boosting?category=objectives')
    expect(within(screen.getByTestId('boosting-grid')).getByRole('link')).toHaveTextContent('$14.50')
  })

  it('with nothing on sale: a clean empty state, never a broken page', () => {
    state.services = [group('BOOST_OBJECTIVES', [])]
    open('/boosting?category=objectives')
    const empty = screen.getByTestId('boosting-empty')
    expect(empty).toHaveTextContent('No objectives are on sale right now')
    expect(within(empty).getByRole('link', { name: 'Ask on live chat' })).toHaveAttribute('href', '/support')
  })
})

describe('Tournaments', () => {
  it('with none active: the empty state', () => {
    open('/boosting?category=tournaments')
    expect(screen.getByTestId('boosting-empty')).toHaveTextContent('No tournaments are on sale right now')
  })

  it('with one active: the same card and the same checkout as Objectives', () => {
    state.services = [group('BOOST_TOURNAMENTS', [option(listing(31, { title: 'Weekend Cup' }), 150000, '₹1,500.00')])]
    open('/boosting?category=tournaments')
    const card = within(screen.getByTestId('boosting-grid')).getByRole('link')
    expect(card).toHaveTextContent('Weekend Cup')
    expect(card).toHaveAttribute('href', '/boosting/checkout?listing=31')
  })
})
