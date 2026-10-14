import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { ListingSnapshot, Order, OrderEvent } from '../lib/types'
import { I18nProvider } from '../i18n'

/*
 * An Objectives or Tournaments order on the tracking page: the service as it was sold --
 * the snapshot frozen with the order, never the listing as it reads now -- with the hours
 * and the acknowledgement of an hourly one, and its price line named as the team named it.
 */

vi.mock('../lib/api', () => ({ api: { get: vi.fn(), post: vi.fn(), baseUrl: '' }, ApiError: class extends Error {} }))
vi.mock('../state/AuthContext', () => ({ useAuth: () => ({ account: null, loading: false }) }))
vi.mock('../state/CatalogContext', () => ({
  useCatalog: () => ({ catalog: null, policy: { champsChoiceDays: 5 }, loading: false, error: null, currency: 'INR',
    setCurrency: () => {} }),
}))
vi.mock('../components/support/SupportChat', () => ({ SupportChat: () => null }))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { OrderView } = await import('./Track')

const SNAPSHOT: ListingSnapshot = {
  listingId: 20, category: 'BOOST_OBJECTIVES', categoryName: 'Objectives', title: 'Gallery Tokens',
  subtitle: 'Paid per hour · 100% manual', overview: null, requirements: ['Everything will be done 100% manually.'],
  requiredGames: null, estimatedTime: null, schedulingNote: null, reward: null,
  customerInstructions: 'We recommend having at least 400,000 Coins available in your Club.',
  pricingType: 'PER_HOUR', hours: 3, unitPriceMinor: 65000, unitPriceFormatted: '₹650.00', priceMinor: 195000,
  priceFormatted: '₹1,950.00', currency: 'INR', imageUrl: '/api/v1/catalog/listing-images/7',
  hourlyAcknowledgedAt: '2026-10-13T05:00:00Z',
  hourlyAcknowledgement: "I understand I'm paying for the manual service time, not for a guaranteed level or specific result.",
}

function order(listing: ListingSnapshot | null): Order {
  return {
    publicRef: 'GFS-27-OBJ00001', status: 'IN_PROGRESS', statusLabel: 'In progress', nextAction: 'NONE',
    sku: 'BOOST_OBJECTIVES', serviceLabel: 'Objectives — Gallery Tokens (3 hours)', platform: null,
    variant: 'LISTING_20', quantity: '3', deliveryMethod: 'COMFORT_TRADE', currency: 'INR', totalMinor: 199875,
    totalFormatted: '₹1,998.75',
    lines: [
      { code: 'BASE', label: 'Objectives — Gallery Tokens (3 hours)', amountMinor: 195000, amountFormatted: '₹1,950.00' },
      { code: 'GATEWAY_FEE', label: 'Payment processing (2.5%)', amountMinor: 4875, amountFormatted: '₹48.75' },
    ],
    pointsRedeemed: 0, pointsEarned: 0, createdAt: '2026-10-13T05:00:00Z', deliveredAt: null, guaranteeExpiresAt: null,
    credentialsRequired: false, credentialsSubmitted: false, discordAccess: null, deliveredCoins: null,
    orderedCoins: null, transferStarted: false, customerAction: null, timeline: [] as OrderEvent[], result: null,
    listing,
  } as unknown as Order
}

function show(o: Order) {
  render(
    <I18nProvider>
      <MemoryRouter><OrderView order={o} readOnly /></MemoryRouter>
    </I18nProvider>,
  )
}

describe('an Objectives order on the tracking page', () => {
  it('shows the service as it was sold: title, hours, price per hour, tasks, instructions, the acknowledgement', () => {
    show(order(SNAPSHOT))
    const card = screen.getByTestId('listing-snapshot')
    expect(card).toHaveTextContent('Your service')
    expect(card).toHaveTextContent('Objectives')
    expect(card).toHaveTextContent('Gallery Tokens')
    expect(card).toHaveTextContent('Paid per hour · 100% manual')
    expect(card).toHaveTextContent('Hours3 hours')
    expect(card).toHaveTextContent('Price per hour₹650.00')
    expect(within(card).getByRole('listitem')).toHaveTextContent('Everything will be done 100% manually.')
    expect(card).toHaveTextContent('We recommend having at least 400,000 Coins')
    expect(within(card).getByTestId('snapshot-acknowledged')).toHaveTextContent(
      'You confirmed you are paying for the service time, not for a guaranteed result.')
  })

  it('names its price line as the team named it, not by an internal code', () => {
    show(order(SNAPSHOT))
    expect(screen.getAllByText('Objectives — Gallery Tokens (3 hours)').length).toBeGreaterThan(0)
    expect(screen.queryByText(/LISTING_20|BOOST_OBJECTIVES/)).toBeNull()
  })

  it('an order of any other service has no such card', () => {
    show({ ...order(null), sku: 'BOOST_CHAMPS' } as Order)
    expect(screen.queryByTestId('listing-snapshot')).toBeNull()
  })
})
