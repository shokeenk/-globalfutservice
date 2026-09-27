import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// ---- the API and the app's contexts, mocked: this is about the checkout's steps --------
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const ApiErrorClass = vi.hoisted(() => class ApiError extends Error {
  code: string
  constructor(message: string, code: string) {
    super(message)
    this.code = code
  }
})
vi.mock('../lib/api', () => ({ api, ApiError: ApiErrorClass }))

vi.mock('../state/AuthContext', () => ({
  useAuth: () => ({
    account: { email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER' },
    loading: false,
  }),
}))

const OPTIONS = [
  { platform: null, variant: 'SINGLE_SESSION', label: 'Single session · 1 hour',
    unitPriceMinor: 100000, unitPriceFormatted: '₹1,000.00', minQuantity: null,
    maxQuantity: null, stepQuantity: null },
  { platform: null, variant: 'MONTHLY_6_SESSIONS', label: '6 sessions × 40 minutes',
    unitPriceMinor: 405000, unitPriceFormatted: '₹4,050.00', minQuantity: null,
    maxQuantity: null, stepQuantity: null },
]
vi.mock('../state/CatalogContext', () => ({
  useCatalog: () => ({
    catalog: { currency: 'INR', services: [{ sku: 'COACHING', options: OPTIONS }] },
    policy: { onlinePaymentsEnabled: false },
  }),
}))

vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))
vi.mock('../components/ManualPayment', () => ({ ManualPayment: () => <p>manual-payment</p> }))

// Imported after the mocks are declared, so the page picks up the mocked modules.
const { default: CoachingBook } = await import('./CoachingBook')

const COACH = { id: 'vinay', displayName: 'Vinay', headline: null, bio: null, avatarUrl: null,
  languages: null, timezone: 'Asia/Kolkata' }
const SLOTS = ['2026-10-05T13:30:00.000Z', '2026-10-05T14:00:00.000Z']
const QUOTE = { quoteId: 'q1', expiresAt: '2026-10-01T07:00:00Z', lines: [],
  totalFormatted: '₹1,050.00', signature: 'x' }

function localTime(iso: string) {
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

function renderCheckout() {
  return render(
    <MemoryRouter initialEntries={['/coaching/book?step=details']}>
      <Routes>
        <Route path="/coaching/book" element={<CoachingBook />} />
      </Routes>
    </MemoryRouter>,
  )
}

/** Details filled in, then on to the Schedule step. */
async function throughDetails() {
  await userEvent.type(await screen.findByPlaceholderText(/in-game ID/i), 'VinayFC10')
  await userEvent.click(screen.getByRole('radio', { name: /PlayStation/ }))
  await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
}

describe('Coaching checkout: the Schedule step', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-01T06:00:00Z'))
    window.scrollTo = vi.fn()
    api.get.mockReset()
    api.post.mockReset()
    api.get.mockImplementation(async (path: string) => {
      if (path.startsWith('/api/v1/payments/methods')) {
        return [{ method: 'UPI', destination: 'services@bank', label: 'UPI' }]
      }
      if (path === '/api/v1/coaching/coaches') return [COACH]
      if (path.includes('/slots')) {
        return { coachId: 'vinay', coachTimezone: 'Asia/Kolkata', sessionMinutes: 60, slots: SLOTS }
      }
      return []
    })
  })
  afterEach(() => { vi.useRealTimers() })

  it('runs Service, Details, Schedule, Payment -- and Schedule comes before paying', async () => {
    renderCheckout()
    await throughDetails()

    expect(await screen.findByRole('heading', { name: 'Pick your session time' })).toBeInTheDocument()
    expect(screen.getByText('Schedule')).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalledWith('/api/v1/orders', expect.anything())
  })

  it('sends the chosen slot with the order, and shows it on the Payment step', async () => {
    api.post.mockImplementation(async (path: string) => {
      if (path === '/api/v1/quotes') return QUOTE
      return { publicRef: 'GFS-26-C1', status: 'AWAITING_PAYMENT', totalMinor: 105000,
        totalFormatted: '₹1,050.00', currency: 'INR', payment: { provider: 'STUB' } }
    })
    renderCheckout()
    await throughDetails()
    await userEvent.click(await screen.findByRole('radio', { name: localTime(SLOTS[0]!) }))
    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))

    // Payment step: the session is in the summary, marked as held.
    expect(await screen.findByText(/Held for you until your payment is verified/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(await screen.findByRole('button', { name: /Pay now/ }))

    const order = api.post.mock.calls.find((c) => c[0] === '/api/v1/orders')?.[1] as Record<string, unknown>
    expect(order).toMatchObject({
      coachingCoachId: 'vinay',
      coachingStartsAt: SLOTS[0],
      coachingTimezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      eaPlatformHandle: 'VinayFC10',
    })
  })

  it('a slot taken meanwhile sends the customer back to pick again, with the message', async () => {
    api.post.mockImplementation(async (path: string) => {
      if (path === '/api/v1/quotes') return QUOTE
      throw new ApiErrorClass('That slot was just taken, please pick another.', 'slot_unavailable')
    })
    renderCheckout()
    await throughDetails()
    await userEvent.click(await screen.findByRole('radio', { name: localTime(SLOTS[0]!) }))
    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
    await userEvent.click(await screen.findByRole('checkbox'))
    await userEvent.click(await screen.findByRole('button', { name: /Pay now/ }))

    expect(await screen.findByRole('heading', { name: 'Pick your session time' })).toBeInTheDocument()
    expect(screen.getByText('That slot was just taken, please pick another.')).toBeInTheDocument()
    // The pick is cleared and the calendar re-read, so the dead slot is not offered again.
    expect(screen.getByRole('radio', { name: localTime(SLOTS[0]!) })).toHaveAttribute('aria-checked', 'false')
    const slotReads = api.get.mock.calls.filter((c) => String(c[0]).includes('/slots')).length
    expect(slotReads).toBeGreaterThanOrEqual(2)
  })
})
