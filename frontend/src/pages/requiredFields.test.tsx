import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogOption } from '../lib/types'

/*
 * Every required answer in the order flow is asked for the same way.
 *
 * Pressing Continue, Pay or Send without one is never blocked by a greyed-out button: it
 * shows every missing answer at once, each in red under its own field, announced, and
 * brings the first into view. Each error goes as soon as its field is put right. These
 * forms used to disagree -- a Pay button disabled until the terms were ticked, a payment
 * submit disabled until a reference was typed, a sign-in form whose button stayed grey
 * without saying why, amber errors in some places and a single message at the bottom of
 * the form in others.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), upload: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))

const auth = vi.hoisted(() => ({ signedIn: true }))
const ACCOUNT = { publicId: 'acc_player', email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER',
  pointsBalance: 0, pointsValueMinor: 0, pointsValueFormatted: '$0.00', firstOrder: true, referredByCode: null }
const SIGNED_IN = { account: ACCOUNT, loading: false }
const GUEST = { account: null, loading: false }
vi.mock('../state/AuthContext', () => ({ useAuth: () => (auth.signedIn ? SIGNED_IN : GUEST) }))

const coins = (platform: string, label: string): CatalogOption => ({ platform, variant: null, label,
  unitPriceMinor: 900, unitPriceFormatted: '$9.00', minQuantity: '0.01', maxQuantity: '1', stepQuantity: '0.01' })
const flat = (variant: string): CatalogOption => ({ platform: null, variant, label: variant, unitPriceMinor: 1000,
  unitPriceFormatted: '$10.00', minQuantity: null, maxQuantity: null, stepQuantity: null })
// One object, as the real context gives: a catalogue rebuilt per render re-runs every effect keyed on it.
const CATALOG = {
  catalog: {
    season: 'FC26', currency: 'USD', availableCurrencies: ['USD'],
    services: [
      { sku: 'TRADING_SERVICE', displayName: 'Coins', sellable: true, priceUnit: 'PER_MILLION',
        marketTaxApplies: true, mayRequireCredentials: true, options: [coins('PC', 'PC'), coins('PLAYSTATION', 'PlayStation')] },
      { sku: 'COACHING', displayName: 'Coaching', sellable: true, priceUnit: 'FLAT',
        marketTaxApplies: false, mayRequireCredentials: false, options: [flat('SINGLE_SESSION')] },
    ],
  },
  policy: { onlinePaymentsEnabled: false, maxWalletRedemptionBps: 0, pointValueMinor: 100 },
  loading: false, error: null, currency: 'USD', setCurrency: () => {},
}
vi.mock('../state/CatalogContext', () => ({ useCatalog: () => CATALOG }))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: Order } = await import('./Order')
const { default: CoachingBook } = await import('./CoachingBook')
const { default: Track } = await import('./Track')
const { ManualPayment } = await import('../components/ManualPayment')
const { CredentialForm } = await import('../components/CredentialForm')

const SLOT = '2026-10-05T13:30:00.000Z'
const scrolled = vi.fn()

function renderAt(path: string, element: React.ReactElement) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes><Route path={path.split('?')[0]} element={element} /></Routes>
    </MemoryRouter>,
  )
}

const posted = (path: string) => api.post.mock.calls.filter(([p]) => p === path)

/*
 * A field filled in one change rather than typed key by key. What is under test is what
 * the form says about the value, not typing; and typing fifty characters into the coin
 * checkout, one re-render each, is slow enough to time out under a full parallel run.
 */
const fill = (field: HTMLElement, value: string) => fireEvent.change(field, { target: { value } })

/** The one way a missing answer is said: red, announced, under its field. */
function expectFieldError(text: string) {
  const error = screen.getByText(text)
  expect(error).toHaveAttribute('role', 'alert')
  expect(error).toHaveAttribute('data-field-error')
  expect(error).toHaveClass('text-brand-400')
  return error
}

beforeEach(() => {
  auth.signedIn = true
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  Element.prototype.scrollIntoView = scrolled
  scrolled.mockReset()
  api.get.mockReset()
  api.post.mockReset()
  api.upload.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/payments/methods')) {
      return [{ method: 'UPI', destination: 'gfs@upi', accountName: 'GFS', link: null, referenceName: 'UTR' }]
    }
    if (path === '/api/v1/coaching/coaches') {
      return [{ id: 'vinay', displayName: 'Vinay', headline: null, bio: null, avatarUrl: null, languages: null,
        timezone: 'Asia/Kolkata' }]
    }
    if (path.includes('/slots')) return { coachId: 'vinay', coachTimezone: 'Asia/Kolkata', sessionMinutes: 60, slots: [SLOT] }
    return []
  })
  api.post.mockImplementation(async (path: string, body: { sku?: string; platform?: string | null }) => {
    if (path === '/api/v1/quotes') {
      return { quoteId: 'q1', season: 'FC26', sku: body.sku, platform: body.platform ?? null, variant: null,
        quantity: '0.1', currency: 'USD', lines: [], subtotalMinor: 90, totalMinor: 90, totalFormatted: '$0.90',
        pointsRedeemed: 0, pointsEarned: 0, referralCode: null, couponCode: null, couponMessage: null,
        issuedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 600_000).toISOString(), signature: 'x' }
    }
    if (path === '/api/v1/orders') {
      return { publicRef: 'GFS-26-REQD0001', status: 'AWAITING_PAYMENT', totalMinor: 90, totalFormatted: '$0.90',
        currency: 'USD', payment: { provider: 'STUB' } }
    }
    return new Promise(() => {})
  })
})
afterEach(() => { vi.useRealTimers() })

describe('coin checkout: your details and the sign-in', () => {
  /*
   * Getting to Pay is setup, so it uses plain events and a fake clock for the quote. The
   * page waits 260 ms of quiet before pricing, and Continue only appears once a price has
   * arrived. Waiting that out in real time made findByRole re-query the whole page every
   * 50 ms and on every change, which is slow enough to near the timeout in a full run.
   */
  async function toDetails() {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      renderAt('/order', <Order />)
      fireEvent.click(screen.getByRole('button', { name: /PlayStation/ }))
      await act(() => vi.runOnlyPendingTimersAsync())
    } finally {
      vi.useRealTimers()
    }
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    return screen.getByRole('button', { name: /^Pay \$0\.90/ })
  }

  it('Pay is not greyed out; pressed empty, every missing answer shows under its field, and nothing is placed', async () => {
    const pay = await toDetails()
    expect(pay).toBeEnabled()

    await userEvent.click(pay)

    expectFieldError('Enter your EA account email.')
    expectFieldError('Enter your EA password.')
    expectFieldError('Backup code 1 is required.')
    expectFieldError('Please confirm your account is ready — it saves both of us a delay.')
    expectFieldError('Please accept the terms to place your order.')
    // Said under the fields, not as one message at the bottom of the form.
    expect(screen.queryByText('Please check the highlighted fields.')).toBeNull()
    await waitFor(() => expect(scrolled).toHaveBeenCalled())
    expect(posted('/api/v1/orders')).toHaveLength(0)
  })

  it('the email is checked like the rest, and each error goes as its field is put right', async () => {
    const pay = await toDetails()
    const email = screen.getByLabelText(/^Email/)
    fill(email, '')
    await userEvent.click(pay)
    expectFieldError('Enter your email address.')
    expect(email).toHaveAttribute('aria-invalid', 'true')

    fill(email, 'player@gmail')
    expectFieldError('Enter a valid email address, like you@example.com.')
    fill(email, 'player@gmail.com')
    expect(screen.queryByText(/valid email address/)).toBeNull()

    await userEvent.click(screen.getByRole('checkbox', { name: /I understand and agree/ }))
    expect(screen.queryByText('Please accept the terms to place your order.')).toBeNull()
  })

  it('with everything answered, the order is placed', async () => {
    const pay = await toDetails()
    fill(screen.getByLabelText(/EA account email/), 'ea@example.test')
    fill(screen.getByLabelText(/EA password/), 'correct-horse')
    for (const n of [1, 2, 3]) fill(screen.getByLabelText(new RegExp(`Backup code ${n}`, 'i')), '12345678')
    for (const box of screen.getAllByRole('checkbox')) fireEvent.click(box)

    await userEvent.click(pay)

    await waitFor(() => expect(posted('/api/v1/orders')).toHaveLength(1))
    expect(document.querySelector('[data-field-error]')).toBeNull()
  })
})

describe('the payment step: reference and screenshot', () => {
  it('submit is not greyed out; pressed empty, both are asked for in red, and no claim is sent', async () => {
    render(<ManualPayment publicRef="GFS-26-REQD0001" email="player@example.test" sku="TRADING_SERVICE"
                          totalFormatted="$0.90" currency="INR" initialMethod="UPI" />)
    const submit = await screen.findByRole('button', { name: 'I have paid — submit reference' })
    expect(submit).toBeEnabled()

    await userEvent.click(submit)

    expectFieldError('Enter the reference number from your payment before submitting.')
    expectFieldError('Attach a screenshot of the payment before submitting.')
    expect(screen.getByLabelText(/Payment screenshot/)).toHaveAttribute('aria-invalid', 'true')
    await waitFor(() => expect(scrolled).toHaveBeenCalled())
    expect(api.post).not.toHaveBeenCalled()
  })

  it('a file that is not an image is refused in the same red', async () => {
    render(<ManualPayment publicRef="GFS-26-REQD0001" email="player@example.test" sku="TRADING_SERVICE"
                          totalFormatted="$0.90" currency="INR" initialMethod="UPI" />)
    const input = await screen.findByLabelText(/Payment screenshot/)
    fireEvent.change(input, { target: { files: [new File(['%PDF'], 'receipt.pdf', { type: 'application/pdf' })] } })
    expectFieldError('That file is not an image. Attach a JPG, PNG or WebP screenshot.')
  })
})

describe('coaching: time and terms', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-01T06:00:00Z'))
  })

  async function toSchedule() {
    renderAt('/coaching/book?step=details', <CoachingBook />)
    fill(await screen.findByPlaceholderText(/in-game ID/i), 'VinayFC10')
    await userEvent.click(screen.getByRole('radio', { name: /PlayStation/ }))
    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
    await screen.findByRole('radio', { name: new Date(SLOT).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) })
  }

  it('no time picked: the red error, and the schedule step stays', async () => {
    await toSchedule()
    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
    expectFieldError('Pick a time to continue.')
    await waitFor(() => expect(scrolled).toHaveBeenCalled())
  })

  it('terms unticked at review: the red error under the box, and no order', async () => {
    await toSchedule()
    const time = new Date(SLOT).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
    await userEvent.click(screen.getByRole('radio', { name: time }))
    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
    const pay = await screen.findByRole('button', { name: /Pay now/ })
    await waitFor(() => expect(pay).toBeEnabled())

    await userEvent.click(pay)

    const error = expectFieldError('Please accept the terms to continue.')
    expect(screen.getByRole('checkbox')).toHaveAttribute('aria-describedby', error.id)
    expect(posted('/api/v1/orders')).toHaveLength(0)
  })
})

describe('sending the EA sign-in from the order page', () => {
  function show(onSubmitted = vi.fn()) {
    render(<MemoryRouter><CredentialForm publicRef="GFS-26-REQD0001" onSubmitted={onSubmitted} /></MemoryRouter>)
    return screen.getByRole('button', { name: 'Submit securely' })
  }

  it('the button is not greyed out; pressed empty, it says what is missing, field by field', async () => {
    const send = show()
    expect(send).toBeEnabled()

    await userEvent.click(send)

    expectFieldError('Enter your EA account email.')
    expectFieldError('Enter your EA password.')
    expectFieldError('Backup code 1 is required.')
    const boxes = screen.getAllByRole('checkbox')
    expect(boxes).toHaveLength(4)
    for (const box of boxes) expect(box).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getAllByText('Tick this to continue.')).toHaveLength(4)
    await waitFor(() => expect(scrolled).toHaveBeenCalled())
    expect(api.post).not.toHaveBeenCalled()
  })

  it('the same rules as the checkout: a short password and a malformed code are refused', async () => {
    const send = show()
    fill(screen.getByLabelText(/EA account email/), 'ea@example.test')
    fill(screen.getByLabelText(/EA password/), 'short')
    fill(screen.getByLabelText('Backup code 1'), '1234')
    await userEvent.click(send)

    expectFieldError('That looks too short — an EA password is at least 8 characters.')
    expectFieldError('Backup code 1 must be exactly 8 digits.')
    expect(screen.getByLabelText('Backup code 1')).toHaveAttribute('aria-invalid', 'true')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('complete, it is sent', async () => {
    const send = show()
    fill(screen.getByLabelText(/EA account email/), 'ea@example.test')
    fill(screen.getByLabelText(/EA password/), 'correct-horse')
    for (const n of [1, 2, 3]) fill(screen.getByLabelText(`Backup code ${n}`), '12345678')
    for (const box of screen.getAllByRole('checkbox')) await userEvent.click(box)

    await userEvent.click(send)

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/orders/GFS-26-REQD0001/credentials',
      expect.objectContaining({ eaEmail: 'ea@example.test', acknowledgedSignedOut: true, acceptedTerms: true })))
  })
})

describe('finding an order', () => {
  it('Find is not greyed out; pressed empty, both answers are asked for in red, and nothing is looked up', async () => {
    auth.signedIn = false
    renderAt('/track', <Track />)
    const find = await screen.findByRole('button', { name: 'Find my order' })
    expect(find).toBeEnabled()

    await userEvent.click(find)

    expectFieldError('Enter your order reference.')
    expectFieldError('Enter your email address.')
    expect(posted('/api/v1/orders/track')).toHaveLength(0)

    const form = find.closest('form') as HTMLFormElement
    expect(form).toHaveAttribute('novalidate')
    fill(within(form).getByLabelText(/Order reference/), 'GFS-26-REQD0001')
    fill(within(form).getByLabelText(/^Email/), 'player@example')
    await userEvent.click(find)
    expectFieldError('Enter a valid email address, like you@example.com.')
    expect(posted('/api/v1/orders/track')).toHaveLength(0)

    fill(within(form).getByLabelText(/^Email/), 'player@example.test')
    await userEvent.click(find)
    await waitFor(() => expect(posted('/api/v1/orders/track')).toHaveLength(1))
  })
})
