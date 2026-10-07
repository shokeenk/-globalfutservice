import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * The coaching checkout's ways to pay: the same list as coins and boosting, International
 * included. Its contents follow the order's currency: "Pay with a local method" for USD,
 * the placeholder for INR.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
vi.mock('../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER' }, loading: false }),
}))

const catalog = vi.hoisted(() => ({ currency: 'USD' }))
vi.mock('../state/CatalogContext', () => ({
  useCatalog: () => ({
    catalog: { currency: catalog.currency, services: [{ sku: 'COACHING', options: [
      { platform: null, variant: 'SINGLE_SESSION', label: 'Single session · 1 hour', unitPriceMinor: 999,
        unitPriceFormatted: '$9.99', minQuantity: null, maxQuantity: null, stepQuantity: null },
    ] }] },
    policy: { onlinePaymentsEnabled: false },
  }),
}))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))
// The payment panel itself is the shared one; here it only shows what it was opened with.
vi.mock('../components/ManualPayment', () => ({
  ManualPayment: ({ initialMethod, currency }: { initialMethod?: string; currency?: string }) => (
    <p data-testid="panel">{`${initialMethod}|${currency}`}</p>
  ),
}))

const { default: CoachingBook } = await import('./CoachingBook')

const SLOT = '2026-10-05T13:30:00.000Z'

async function toPaymentStep() {
  render(
    <MemoryRouter initialEntries={['/coaching/book?step=details']}>
      <Routes><Route path="/coaching/book" element={<CoachingBook />} /></Routes>
    </MemoryRouter>,
  )
  await userEvent.type(await screen.findByPlaceholderText(/in-game ID/i), 'VinayFC10')
  await userEvent.click(screen.getByRole('radio', { name: /PlayStation/ }))
  await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
  const time = new Date(SLOT).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  await userEvent.click(await screen.findByRole('radio', { name: time }))
  await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
  await screen.findByText(/Held for you until your payment is verified/)
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-01T06:00:00Z'))
  window.scrollTo = vi.fn()
  api.get.mockReset()
  api.post.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/payments/methods')) {
      return [
        { method: 'UPI', destination: 'services@upi', accountName: 'GFS', link: null, referenceName: 'UTR' },
        { method: 'PAYPAL', destination: 'pay@gfs.test', accountName: null, link: null, referenceName: 'id' },
        { method: 'CRYPTO', destination: 'TWALLET', accountName: null, link: null, referenceName: 'TXID' },
      ]
    }
    if (path === '/api/v1/coaching/coaches') {
      return [{ id: 'vinay', displayName: 'Vinay', headline: null, bio: null, avatarUrl: null, languages: null,
        timezone: 'Asia/Kolkata' }]
    }
    if (path.includes('/slots')) return { coachId: 'vinay', coachTimezone: 'Asia/Kolkata', sessionMinutes: 60, slots: [SLOT] }
    return []
  })
  api.post.mockImplementation(async (path: string) => {
    if (path === '/api/v1/quotes') {
      return { quoteId: 'q1', expiresAt: '2026-10-01T07:00:00Z', lines: [], currency: catalog.currency,
        totalFormatted: catalog.currency === 'INR' ? '₹1,025.00' : '$10.24', signature: 'x' }
    }
    return { publicRef: 'GFS-26-C1', status: 'AWAITING_PAYMENT', totalMinor: 1024, totalFormatted: '$10.24',
      currency: catalog.currency, payment: { provider: 'STUB' } }
  })
})
afterEach(() => { vi.useRealTimers() })

describe('coaching checkout: the ways to pay', () => {
  it('USD: UPI, PayPal, crypto and International, with "Pay with a local method" in it', async () => {
    catalog.currency = 'USD'
    await toPaymentStep()
    const group = screen.getByRole('radiogroup', { name: 'Payment method' })
    const names = [...group.querySelectorAll('[role="radio"]')].map((r) => r.textContent ?? '')
    expect(names).toHaveLength(4)
    expect(names[0]).toMatch(/^UPI/)
    expect(names[1]).toMatch(/^PayPal/)
    expect(names[2]).toMatch(/^Crypto/)
    expect(names[3]).toBe('InternationalPay with a local method: bank transfer, cards or wallets in your country')
  })

  it('choosing International and paying opens the payment step on it, for this order\'s currency', async () => {
    catalog.currency = 'USD'
    await toPaymentStep()
    await userEvent.click(screen.getByRole('radio', { name: /^International/ }))
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(screen.getByRole('button', { name: /Pay now/ }))
    expect(await screen.findByTestId('panel')).toHaveTextContent('INTERNATIONAL|USD')
  })

  it('INR: International is listed too, without local methods (INR orders never see Payop)', async () => {
    catalog.currency = 'INR'
    await toPaymentStep()
    const international = screen.getByRole('radio', { name: /^International/ })
    expect(international).toHaveTextContent('International payment options are coming soon')
    expect(international).not.toHaveTextContent(/local method/)
  })
})
