import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogListing, CatalogOption } from '../lib/types'

/*
 * The checkout of an Objectives or Tournaments listing, loaded by its id: the left side is
 * the listing as the team wrote it -- tasks, games, time, instructions, each hidden when
 * empty, the overview theirs when they wrote one -- with the boosting checkout's own
 * after-payment note (EA login in the encrypted form, the live chat) and no platform question. By the hour, nothing is priced until the hours
 * are chosen, the sum is shown before Continue, and the acknowledgement must be ticked.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), baseUrl: '' }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
vi.mock('../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER' }, loading: false }),
}))

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

const CLAIRE = listing(12, {
  title: 'Claire Hutton Objective', subtitle: '84 CDM · Special Card', imageUrl: '/api/v1/catalog/listing-images/7',
  imageWidth: 400, imageHeight: 500,
  requirements: ['Win 7 Squad Battles matches (Semi-Pro or higher)', 'Score at least 1 finesse goal in 3 separate matches'],
  requiredGames: 7, estimatedTime: '2–3 hours', schedulingNote: 'After your scheduled slot begins.',
  reward: '84 CDM Special Card', customerInstructions: 'Please make sure the objective is available on your account.',
})
const BARE = listing(13, { title: 'Bare Objective' })
const GALLERY = listing(20, {
  title: 'Gallery Tokens', subtitle: 'Paid per hour · 100% manual', pricingType: 'PER_HOUR', minHours: 1, maxHours: 50,
  overview: "We'll work on your Club manually for the hours you book.",
  requirements: ['Everything will be done 100% manually.'],
  customerInstructions: 'We recommend having at least 400,000 Coins available in your Club.',
})
const CUP = listing(31, { title: 'Weekend Cup' })

const CATALOG = vi.hoisted(() => ({
  catalog: { currency: 'INR', services: [] as unknown[] },
  policy: { onlinePaymentsEnabled: false, customerEmailsEnabled: false, gatewayFeeBps: 250, champsSettlementEnabled: true },
}))
vi.mock('../state/CatalogContext', () => ({ useCatalog: () => CATALOG }))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))

const { default: BoostingCheckout } = await import('./BoostingCheckout')

function quoteFor(body: { variant: string; quantity: string }) {
  const hourly = body.variant === 'LISTING_20'
  const base = hourly ? 65000 * Number(body.quantity) : 120000
  const fee = Math.round(base * 0.025)
  return {
    quoteId: `q_${body.variant}_${body.quantity}`, sku: 'BOOST_OBJECTIVES', variant: body.variant,
    quantity: body.quantity, currency: 'INR', totalMinor: base + fee, totalFormatted: `₹${(base + fee) / 100}`,
    lines: [
      { code: 'BASE', label: 'Objectives', amountMinor: base, amountFormatted: `₹${base / 100}` },
      { code: 'GATEWAY_FEE', label: 'Payment processing (2.5%)', amountMinor: fee, amountFormatted: `₹${fee / 100}` },
    ],
    pointsEarned: 0, couponMessage: null, expiresAt: new Date(Date.now() + 600_000).toISOString(), signature: 'x',
  }
}

function open(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/boosting/checkout" element={<BoostingCheckout />} />
        <Route path="/boosting" element={<p>services page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  window.sessionStorage.clear()
  CATALOG.catalog.services = [
    { sku: 'BOOST_CHAMPS', displayName: 'Champs', options: [] },
    { sku: 'BOOST_OBJECTIVES', displayName: 'Objectives', options: [
      option(CLAIRE, 120000, '₹1,200.00'), option(BARE, 50000, '₹500.00'), option(GALLERY, 65000, '₹650.00')] },
    { sku: 'BOOST_TOURNAMENTS', displayName: 'Tournaments', options: [option(CUP, 150000, '₹1,500.00')] },
  ]
  api.get.mockReset()
  api.post.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/payments/methods')) {
      return [{ method: 'UPI', destination: 'gfs@upi', accountName: 'GFS', link: null, referenceName: 'UTR' }]
    }
    return []
  })
  api.post.mockImplementation(async (path: string, body: { variant: string; quantity: string }) => {
    if (path === '/api/v1/quotes') return quoteFor(body)
    return new Promise(() => {})
  })
})

describe('a fixed-price objective', () => {
  it('loads the listing by its id: its words, artwork, tasks, games, time and instructions', async () => {
    open('/boosting/checkout?listing=12')
    const details = await screen.findByTestId('listing-details')
    expect(within(details).getByRole('heading', { level: 1, name: 'Claire Hutton Objective' })).toBeInTheDocument()
    expect(details).toHaveTextContent('84 CDM · Special Card')
    const art = screen.getByTestId('checkout-artwork')
    expect(art).toHaveAttribute('src', '/api/v1/catalog/listing-images/7')
    expect(art.className).toContain('object-contain')

    // Built from the listing: what we do, and the reward it names.
    expect(screen.getByTestId('listing-overview')).toHaveTextContent(
      "We'll complete the required objectives on your account. 84 CDM Special Card will be available in your club after completion.")
    expect(within(screen.getByTestId('listing-tasks')).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Win 7 Squad Battles matches (Semi-Pro or higher)', 'Score at least 1 finesse goal in 3 separate matches'])
    expect(screen.getByTestId('listing-games')).toHaveTextContent('Required games7')
    expect(screen.getByTestId('listing-time')).toHaveTextContent('2–3 hours')
    expect(screen.getByTestId('listing-time')).toHaveTextContent('After your scheduled slot begins.')
    expect(screen.getByTestId('listing-time')).toHaveTextContent('An estimate, not a guarantee.')
    expect(screen.getByTestId('listing-instructions')).toHaveTextContent('Please make sure the objective is available')

    // The boosting checkout's own after-payment note, and no Discord; no platform question.
    expect(screen.getByRole('heading', { name: 'After payment' })).toBeInTheDocument()
    expect(screen.getByTestId('after-payment')).toHaveTextContent('enter your EA login on your order page')
    expect(document.body.innerHTML).not.toMatch(/discord/i)
    expect(screen.queryByRole('group', { name: 'Select Platform' })).toBeNull()
    expect(screen.queryByTestId('hours-picker')).toBeNull()
    expect(screen.queryByTestId('hourly-acknowledgement')).toBeNull()

    // The order panel: the category, the listing, its thumbnail and its price.
    await waitFor(() => expect(screen.getByTestId('summary-total')).toHaveTextContent('₹1230'))
    const panel = screen.getByTestId('summary-total').closest('aside') as HTMLElement
    expect(panel).toHaveTextContent('FC 27 · Objectives')
    expect(panel).toHaveTextContent('Claire Hutton Objective')
    expect(api.post).toHaveBeenCalledWith('/api/v1/quotes', expect.objectContaining({
      sku: 'BOOST_OBJECTIVES', variant: 'LISTING_12', quantity: '1' }))
  })

  it('hides every block the team left empty', async () => {
    open('/boosting/checkout?listing=13')
    await screen.findByTestId('listing-details')
    expect(screen.queryByTestId('listing-tasks')).toBeNull()
    expect(screen.queryByTestId('listing-games')).toBeNull()
    expect(screen.queryByTestId('listing-time')).toBeNull()
    expect(screen.queryByTestId('listing-instructions')).toBeNull()
    expect(screen.queryByTestId('checkout-artwork')).toBeNull()
    expect(screen.getByTestId('listing-overview')).toHaveTextContent(
      "We'll complete the required objectives on your account.")
  })

  it('places the order with no platform and no acknowledgement', async () => {
    open('/boosting/checkout?listing=12')
    await waitFor(() => expect(screen.getByRole('button', { name: /Continue to Payment/ })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: /Continue to Payment/ }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/orders', expect.objectContaining({
      boostPlatform: null, pcLauncher: null, hourlyAcknowledged: null })))
  })

  it('"Edit" goes back to the Objectives services', async () => {
    open('/boosting/checkout?listing=12')
    expect(await screen.findByRole('link', { name: 'Edit' })).toHaveAttribute('href', '/boosting?category=objectives')
  })
})

describe('an objective sold by the hour', () => {
  it('prices nothing until the hours are chosen, then shows the sum before Continue', async () => {
    open('/boosting/checkout?listing=20')
    await screen.findByTestId('hours-picker')
    expect(screen.getByLabelText('Hours')).toHaveValue(null)
    expect(screen.getByTestId('summary-total')).toHaveTextContent('Choose the hours to see your total.')
    expect(api.post).not.toHaveBeenCalledWith('/api/v1/quotes', expect.anything())
    // The team's overview, in place of the generated text.
    expect(screen.getByTestId('listing-overview')).toHaveTextContent("We'll work on your Club manually for the hours you book.")
    expect(screen.getByTestId('listing-overview')).not.toHaveTextContent('required objectives')

    fireEvent.change(screen.getByLabelText('Hours'), { target: { value: '3' } })
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/quotes', expect.objectContaining({
      variant: 'LISTING_20', quantity: '3' })))
    expect(await screen.findByTestId('hourly-calculation')).toHaveTextContent('₹650.00/hour × 3 hours = ₹1,950.00')
  })

  it('refuses hours outside one to the cap, and pressing Continue says what is missing', async () => {
    open('/boosting/checkout?listing=20')
    await screen.findByTestId('hours-picker')
    fireEvent.change(screen.getByLabelText('Hours'), { target: { value: '51' } })
    expect(await screen.findByText('Choose how many hours to continue.')).toBeInTheDocument()
    expect(screen.queryByTestId('hourly-calculation')).toBeNull()
    fireEvent.change(screen.getByLabelText('Hours'), { target: { value: '0' } })
    expect(screen.queryByTestId('hourly-calculation')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /Continue to Payment/ }))
    expect(api.post).not.toHaveBeenCalledWith('/api/v1/orders', expect.anything())
  })

  it('needs the acknowledgement ticked, with the notice above it, before the order is placed', async () => {
    open('/boosting/checkout?listing=20')
    const ack = await screen.findByTestId('hourly-acknowledgement')
    expect(ack).toHaveTextContent(
      'You are paying for the manual service/time, not for a guaranteed Level or specific result.')
    fireEvent.click(screen.getByRole('button', { name: 'One hour more' }))
    fireEvent.click(screen.getByRole('button', { name: 'One hour more' }))
    expect(screen.getByLabelText('Hours')).toHaveValue(2)
    await waitFor(() => expect(screen.getByRole('button', { name: /Continue to Payment/ })).toBeEnabled())

    fireEvent.click(screen.getByRole('button', { name: /Continue to Payment/ }))
    expect(await screen.findByText('Tick the box to confirm before you continue.')).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalledWith('/api/v1/orders', expect.anything())

    fireEvent.click(within(ack).getByRole('checkbox', {
      name: "I understand I'm paying for the manual service time, not for a guaranteed level or specific result." }))
    fireEvent.click(screen.getByRole('button', { name: /Continue to Payment/ }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/orders', expect.objectContaining({
      hourlyAcknowledged: true, boostPlatform: null,
      quote: expect.objectContaining({ variant: 'LISTING_20', quantity: '2' }) })))
  })

  it('keeps the hours chosen when the customer goes back to the services and returns', async () => {
    window.sessionStorage.setItem('gfs.listing-checkout.20', JSON.stringify({ hours: 4, coupon: '' }))
    open('/boosting/checkout?listing=20')
    expect(await screen.findByLabelText('Hours')).toHaveValue(4)
    expect(await screen.findByTestId('hourly-calculation')).toHaveTextContent('× 4 hours = ₹2,600.00')
  })

  it('does not bring back hours beyond the cap', async () => {
    window.sessionStorage.setItem('gfs.listing-checkout.20', JSON.stringify({ hours: 80 }))
    open('/boosting/checkout?listing=20')
    await screen.findByTestId('hours-picker')
    expect(screen.getByLabelText('Hours')).toHaveValue(null)
    expect(screen.getByTestId('summary-total')).toHaveTextContent('Choose the hours to see your total.')
  })
})

describe('Tournaments and listings off sale', () => {
  it('a tournament opens in the same checkout, with its own heading', async () => {
    open('/boosting/checkout?listing=31')
    await screen.findByTestId('listing-details')
    expect(screen.getByRole('heading', { name: 'Tournament Details' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Edit' })).toHaveAttribute('href', '/boosting?category=tournaments')
  })

  it('a listing not on sale -- off, deleted or unpriced here -- says so, with the way back', async () => {
    open('/boosting/checkout?listing=99')
    expect(await screen.findByText('This service is not on sale')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to services' })).toHaveAttribute('href', '/boosting?category=objectives')
    expect(api.post).not.toHaveBeenCalled()
  })
})
