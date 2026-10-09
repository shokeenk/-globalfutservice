import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { expect, it, vi } from 'vitest'
import type { CatalogOption } from '../lib/types'

/*
 * The coin checkout's coupon field, unchanged now that coaching shares it: in the summary's
 * Discount tab, the code goes to the server with the quote, and the summary shows what the
 * server says.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
vi.mock('../state/AuthContext', () => ({
  useAuth: () => ({ account: { publicId: 'acc_player', email: 'player@example.test', displayName: 'Player',
    role: 'CUSTOMER', pointsBalance: 0, pointsValueMinor: 0, pointsValueFormatted: '$0.00', firstOrder: true,
    referredByCode: null }, loading: false }),
}))
const coins = (platform: string): CatalogOption => ({ platform, variant: null, label: platform, unitPriceMinor: 900,
  unitPriceFormatted: '$9.00', minQuantity: '0.1', maxQuantity: '1', stepQuantity: '0.1' })
const CATALOG = vi.hoisted(() => ({
  catalog: null as unknown,
  policy: { onlinePaymentsEnabled: false, maxWalletRedemptionBps: 0, pointValueMinor: 100, backupCodesRequired: 1 },
  loading: false, error: null, currency: 'USD', setCurrency: () => {},
}))
CATALOG.catalog = { season: 'FC26', currency: 'USD', availableCurrencies: ['USD'], services: [
  { sku: 'TRADING_SERVICE', displayName: 'Coins', sellable: true, priceUnit: 'PER_MILLION', marketTaxApplies: true,
    mayRequireCredentials: true, options: [coins('PC'), coins('PLAYSTATION')] }] }
vi.mock('../state/CatalogContext', () => ({ useCatalog: () => CATALOG }))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))
window.matchMedia = ((query: string) => ({
  matches: false, media: query, onchange: null, addEventListener: () => {}, removeEventListener: () => {},
  addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: Order } = await import('./Order')

it('a coupon on coins: sent with the quote, and the server\'s answer shown under the field', async () => {
  api.get.mockResolvedValue([])
  api.post.mockImplementation(async (_path: string, body: { platform?: string; couponCode?: string | null }) => ({
    quoteId: 'q1', season: 'FC26', sku: 'TRADING_SERVICE', platform: body.platform, variant: null, quantity: '0.1',
    currency: 'USD', lines: [], subtotalMinor: 90, totalMinor: 90, totalFormatted: '$0.90', pointsRedeemed: 0,
    pointsEarned: 0, referralCode: null, couponCode: body.couponCode === 'SAVE10' ? 'SAVE10' : null,
    couponMessage: body.couponCode === 'OLD10' ? 'That code has expired.' : null,
    issuedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 600_000).toISOString(), signature: 'x' }))

  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  try {
    render(<MemoryRouter initialEntries={['/order']}><Routes><Route path="/order" element={<Order />} /></Routes></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: /PLAYSTATION/ }))
    await act(() => vi.runOnlyPendingTimersAsync())

    const field = screen.getByLabelText('Coupon code')
    fireEvent.change(field, { target: { value: 'old10' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await act(() => vi.runOnlyPendingTimersAsync())
  } finally {
    vi.useRealTimers()
  }
  await waitFor(() => expect(api.post.mock.calls[api.post.mock.calls.length - 1]?.[1]).toMatchObject({ couponCode: 'OLD10' }))
  expect(await screen.findByText('That code has expired.')).toBeInTheDocument()

  fireEvent.change(screen.getByLabelText('Coupon code'), { target: { value: 'SAVE10' } })
  fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
  expect(await screen.findByText('SAVE10 applied.')).toBeInTheDocument()
  expect(within(screen.getByRole('tablist', { name: 'Your order' })).getByRole('tab', { name: /Discount/ }))
    .toHaveAttribute('aria-selected', 'true')
})
