import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import en from '../i18n/en'
import es from '../i18n/es'
import fr from '../i18n/fr'

/*
 * Sign-up security and email preferences, as a customer meets them: the marketing box, the
 * human check (and its absence until it is set up), the verify-your-email banner and page,
 * and the guest points line.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', async (original) => ({ ...(await original<typeof import('../lib/api')>()), api }))

const auth = vi.hoisted(() => ({
  account: null as null | Record<string, unknown>,
  login: vi.fn(), register: vi.fn(), logout: vi.fn(), refreshAccount: vi.fn(),
}))
vi.mock('../state/AuthContext', () => ({ useAuth: () => ({ ...auth, loading: false }) }))
vi.mock('../components/Atmosphere', () => ({ Atmosphere: () => null }))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { ApiError } = await import('../lib/api')
const { I18nProvider } = await import('../i18n')
const { resetSignupConfigForTests } = await import('../lib/signupConfig')
const { resetTurnstileForTests } = await import('../components/Turnstile')
const { default: Login } = await import('./Login')
const { default: ForgotPassword } = await import('./ForgotPassword')
const { default: VerifyEmail } = await import('./VerifyEmail')
const { VerifyEmailBanner } = await import('../components/VerifyEmailBanner')
const { verifiedText } = await import('./admin/AdminCustomers')

function at(path: string, element: React.ReactNode) {
  return render(
    <I18nProvider>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={path.split('?')[0]} element={element} />
          <Route path="/account" element={<p>account page</p>} />
        </Routes>
      </MemoryRouter>
    </I18nProvider>,
  )
}

/** What the server says the forms start with. */
function serverConfig(config: { prefillMarketingConsent: boolean; turnstileSiteKey: string | null }) {
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/auth/signup-config') return config
    if (path === '/api/v1/auth/providers') return { providers: [] }
    throw new Error(path)
  })
}

/** Turnstile's script, as loaded: renders, and hands back a token at once. */
function turnstileSolves(token = 'cf-token-1') {
  window.turnstile = {
    render: vi.fn((_el: HTMLElement, options: Record<string, unknown>) => {
      ;(options.callback as (t: string) => void)(token)
      return 'widget-1'
    }),
    remove: vi.fn(),
  }
}

async function fillRegister(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/^Email/), 'new@example.test')
  await user.type(screen.getByLabelText(/^Password/), 'correct horse battery')
  await user.click(screen.getByRole('checkbox', { name: /I accept the/ }))
}

beforeEach(() => {
  window.localStorage.clear()
  resetSignupConfigForTests()
  resetTurnstileForTests()
  auth.account = null
  auth.register.mockResolvedValue(undefined)
})

afterEach(() => {
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

describe('the marketing box at sign-up', () => {
  it('reads "Send me offers and news by email", starts unticked, and is its own grey control, apart from the terms', async () => {
    serverConfig({ prefillMarketingConsent: false, turnstileSiteKey: null })
    at('/register', <Login mode="register" />)
    const box = await screen.findByRole('checkbox', { name: /Send me offers and news by email/ })
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/v1/auth/signup-config'))
    expect(box).not.toBeChecked()
    expect(screen.getByTestId('marketing-consent')).toContainElement(box)
    expect(screen.getByTestId('marketing-consent'))
      .not.toContainElement(screen.getByRole('checkbox', { name: /I accept the/ }))
    expect(screen.getByText('Send me offers and news by email')).toHaveClass('text-chalk-muted')
  })

  it('starts ticked only when an admin has turned on "Pre-tick marketing consent", and sends what was submitted', async () => {
    serverConfig({ prefillMarketingConsent: true, turnstileSiteKey: null })
    const user = userEvent.setup()
    at('/register', <Login mode="register" />)
    const box = await screen.findByRole('checkbox', { name: /Send me offers and news by email/ })
    await waitFor(() => expect(box).toBeChecked())
    await user.click(box)
    await fillRegister(user)
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(auth.register).toHaveBeenCalledWith(expect.objectContaining({ marketingOptIn: false, language: 'en' }))
  })

  it('is in each language', () => {
    expect(en.auth.marketingOptIn).toBe('Send me offers and news by email')
    expect(es.auth.marketingOptIn).toBe('Envíame ofertas y novedades por correo')
    expect(fr.auth.marketingOptIn).toBe('Envoie-moi des offres et des nouveautés par e-mail')
  })
})

describe('the human check (Cloudflare Turnstile)', () => {
  it('not configured: no widget anywhere, and the forms send no token', async () => {
    serverConfig({ prefillMarketingConsent: false, turnstileSiteKey: null })
    const user = userEvent.setup()
    at('/register', <Login mode="register" />)
    await screen.findByRole('checkbox', { name: /Send me offers/ })
    await fillRegister(user)
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(screen.queryByTestId('turnstile')).toBeNull()
    expect(auth.register).toHaveBeenCalledWith(expect.objectContaining({ humanToken: null }))
    expect(document.querySelector('script[src*="challenges.cloudflare.com"]')).toBeNull()
  })

  it('sign-up: shows the widget and sends its token', async () => {
    serverConfig({ prefillMarketingConsent: false, turnstileSiteKey: '0x4AAA-site' })
    turnstileSolves('cf-token-1')
    const user = userEvent.setup()
    at('/register', <Login mode="register" />)
    expect(await screen.findByTestId('turnstile')).toBeInTheDocument()
    await waitFor(() => expect(window.turnstile?.render).toHaveBeenCalledWith(expect.anything(),
      expect.objectContaining({ sitekey: '0x4AAA-site' })))
    await fillRegister(user)
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(auth.register).toHaveBeenCalledWith(expect.objectContaining({ humanToken: 'cf-token-1' }))
  })

  it('sign-up: not solved yet, nothing is sent', async () => {
    serverConfig({ prefillMarketingConsent: false, turnstileSiteKey: '0x4AAA-site' })
    window.turnstile = { render: vi.fn(() => 'w'), remove: vi.fn() }
    const user = userEvent.setup()
    at('/register', <Login mode="register" />)
    await screen.findByTestId('turnstile')
    await fillRegister(user)
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(screen.getByText('Please complete the check below to continue.')).toBeInTheDocument()
    expect(auth.register).not.toHaveBeenCalled()
  })

  it('sign-in: no widget at first; after three failed tries it appears and its token goes with the next', async () => {
    serverConfig({ prefillMarketingConsent: false, turnstileSiteKey: '0x4AAA-site' })
    turnstileSolves('cf-token-2')
    auth.login.mockRejectedValue(new ApiError(400, { error: 'invalid_credentials', message: 'x' }))
    const user = userEvent.setup()
    at('/login', <Login mode="login" />)
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/v1/auth/signup-config'))
    await user.type(screen.getByLabelText(/^Email/), 'ada@example.test')
    await user.type(screen.getByLabelText(/^Password/), 'wrong password')
    for (let i = 0; i < 3; i++) {
      expect(screen.queryByTestId('turnstile')).toBeNull()
      await user.click(screen.getByRole('button', { name: 'Sign in' }))
      await screen.findByText('Email or password is incorrect.')
    }
    expect(auth.login.mock.calls.map((c) => c[3])).toEqual([null, null, null])
    expect(await screen.findByTestId('turnstile')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(auth.login).toHaveBeenLastCalledWith('ada@example.test', 'wrong password', false, 'cf-token-2')
  })

  it('sign-in: when the server asks for the check, the widget appears', async () => {
    serverConfig({ prefillMarketingConsent: false, turnstileSiteKey: '0x4AAA-site' })
    turnstileSolves()
    auth.login.mockRejectedValue(new ApiError(400, { error: 'human_check_required', message: 'x' }))
    const user = userEvent.setup()
    at('/login', <Login mode="login" />)
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/v1/auth/signup-config'))
    await user.type(screen.getByLabelText(/^Email/), 'ada@example.test')
    await user.type(screen.getByLabelText(/^Password/), 'whatever it is')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Please complete the check below to continue.')).toBeInTheDocument()
    expect(screen.getByTestId('turnstile')).toBeInTheDocument()
  })

  it('forgot password: the widget, and its token with the request', async () => {
    serverConfig({ prefillMarketingConsent: false, turnstileSiteKey: '0x4AAA-site' })
    turnstileSolves('cf-token-3')
    api.post.mockResolvedValue({ message: 'x' })
    const user = userEvent.setup()
    at('/forgot-password', <ForgotPassword />)
    expect(await screen.findByTestId('turnstile')).toBeInTheDocument()
    await user.type(screen.getByLabelText(/Email/), 'ada@example.test')
    await user.click(screen.getByRole('button', { name: 'Send reset link' }))
    expect(api.post).toHaveBeenCalledWith('/api/v1/auth/password/forgot',
      { email: 'ada@example.test', language: 'en', humanToken: 'cf-token-3' })
  })
})

describe('sign-up errors in the customer\'s language', () => {
  it('a refused sign-up is the one translated sentence, never the server\'s English', async () => {
    window.localStorage.setItem('gfs.language', 'es')
    serverConfig({ prefillMarketingConsent: false, turnstileSiteKey: null })
    auth.register.mockRejectedValue(new ApiError(409, { error: 'registration_failed',
      message: 'We could not create that account. If you already have one, try signing in.' }))
    const user = userEvent.setup()
    at('/register', <Login mode="register" />)
    await user.type(screen.getByLabelText(/^Correo|^Email/), 'new@example.test')
    await user.type(screen.getByLabelText(/^Contraseña/), 'correct horse battery')
    await user.click(screen.getByRole('checkbox', { name: new RegExp(es.auth.acceptTerms.slice(0, 8)) }))
    await user.click(screen.getByRole('button', { name: es.auth.createAccount }))
    expect(await screen.findByText(es.auth.registerFailed)).toBeInTheDocument()
  })

  it('the server\'s field errors are shown in the customer\'s words', async () => {
    window.localStorage.setItem('gfs.language', 'fr')
    serverConfig({ prefillMarketingConsent: false, turnstileSiteKey: null })
    auth.register.mockRejectedValue(new ApiError(400, { error: 'validation_failed', message: 'Invalid',
      details: { displayName: ['size must be between 0 and 80'] } }))
    const user = userEvent.setup()
    at('/register', <Login mode="register" />)
    await user.type(screen.getByLabelText(/^E-mail|^Email/), 'new@example.test')
    await user.type(screen.getByLabelText(/^Mot de passe/), 'correct horse battery')
    await user.click(screen.getByRole('checkbox', { name: new RegExp(fr.auth.acceptTerms.slice(0, 8)) }))
    await user.click(screen.getByRole('button', { name: fr.auth.createAccount }))
    expect(await screen.findByText(fr.auth.displayNameTooLong)).toBeInTheDocument()
    expect(screen.queryByText('size must be between 0 and 80')).toBeNull()
  })

  it('every new sentence is in each language', () => {
    for (const d of [en, es, fr]) {
      for (const k of ['registerFailed', 'passwordTooLong', 'displayNameTooLong', 'termsRequired'] as const) expect(d.auth[k]).toBeTruthy()
    }
    expect(es.auth.registerFailed).not.toBe(en.auth.registerFailed)
    expect(fr.auth.registerFailed).not.toBe(en.auth.registerFailed)
  })
})

describe('the human check on a narrow phone', () => {
  it('uses the compact widget when the normal one (300px) would not fit', async () => {
    serverConfig({ prefillMarketingConsent: false, turnstileSiteKey: '0x4AAA-site' })
    turnstileSolves()
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(280)
    at('/register', <Login mode="register" />)
    await waitFor(() => expect(window.turnstile?.render).toHaveBeenCalledWith(expect.anything(),
      expect.objectContaining({ size: 'compact' })))
  })

  it('and the normal one where it fits', async () => {
    serverConfig({ prefillMarketingConsent: false, turnstileSiteKey: '0x4AAA-site' })
    turnstileSolves()
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(400)
    at('/register', <Login mode="register" />)
    await waitFor(() => expect(window.turnstile?.render).toHaveBeenCalledWith(expect.anything(),
      expect.objectContaining({ size: 'normal' })))
  })
})

describe('please verify your email', () => {
  const unverified = { publicId: 'acc_1', email: 'new@example.test', role: 'CUSTOMER', emailVerified: false }

  it('a banner with a Resend button, for an unverified customer only', async () => {
    auth.account = unverified
    api.post.mockResolvedValue({ verified: false })
    const user = userEvent.setup()
    const { unmount } = at('/', <VerifyEmailBanner />)
    expect(screen.getByTestId('verify-email-banner')).toHaveTextContent('Please verify your email')
    expect(screen.getByTestId('verify-email-banner')).toHaveTextContent('new@example.test')
    await user.click(screen.getByRole('button', { name: 'Resend email' }))
    expect(api.post).toHaveBeenCalledWith('/api/v1/auth/email/resend', { language: 'en' })
    expect(await screen.findByText('Sent — check your inbox.')).toBeInTheDocument()
    unmount()

    for (const account of [{ ...unverified, emailVerified: true }, { ...unverified, role: 'ADMIN' }, null]) {
      auth.account = account
      const r = at('/', <VerifyEmailBanner />)
      expect(screen.queryByTestId('verify-email-banner')).toBeNull()
      r.unmount()
    }
  })

  it('Resend says to wait when it has been used too often', async () => {
    auth.account = unverified
    api.post.mockRejectedValue(new ApiError(429, { error: 'too_many_attempts', message: 'x' }))
    const user = userEvent.setup()
    at('/', <VerifyEmailBanner />)
    await user.click(screen.getByRole('button', { name: 'Resend email' }))
    expect(await screen.findByText(/sent a few links already/)).toBeInTheDocument()
  })

  it('the link: verifies once as the page opens, then says so', async () => {
    api.post.mockResolvedValue({ verified: true })
    at('/confirm-email?token=abc', <VerifyEmail />)
    expect(await screen.findByText('Your email is verified. Thank you!')).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(api.post).toHaveBeenCalledWith('/api/v1/auth/email/confirm', { token: 'abc' })
    expect(auth.refreshAccount).toHaveBeenCalled()
  })

  it('a used or out-of-date link says how to get a new one', async () => {
    api.post.mockRejectedValue(new ApiError(400, { error: 'verify_link_invalid', message: 'x' }))
    at('/confirm-email?token=old', <VerifyEmail />)
    expect(await screen.findByText(/expired or has already been used/)).toBeInTheDocument()
  })
})

describe('Admin > Customers', () => {
  it('shows verified yes or no, and nothing for a guest', () => {
    const base = { key: 'a-1', name: 'A', email: 'a@x.test', orders: 0, joinedAt: '2026-10-01T00:00:00Z',
      status: 'ACTIVE' as const, discordConnected: false }
    expect(verifiedText({ ...base, kind: 'ACCOUNT', emailVerified: true })).toBe('Yes')
    expect(verifiedText({ ...base, kind: 'ACCOUNT', emailVerified: false })).toBe('No')
    expect(verifiedText({ ...base, kind: 'GUEST', emailVerified: null })).toBe('—')
  })
})

describe('guest checkout and points', () => {
  /** Every page and component, as text: the three checkouts carry the line. */
  const SOURCES = import.meta.glob<string>(['/src/pages/*.tsx', '!/src/pages/*.test.tsx'],
    { query: '?raw', import: 'default', eager: true })

  it('the coin, boosting and coaching checkouts say an account collects points, in each language', () => {
    expect(en.order.guestPoints).toBe('Create an account to collect points on this order.')
    expect(es.order.guestPoints).toBeTruthy()
    expect(fr.order.guestPoints).toBeTruthy()
    for (const page of ['/src/pages/Order.tsx', '/src/pages/BoostingCheckout.tsx', '/src/pages/CoachingBook.tsx']) {
      expect(SOURCES[page]).toContain('t.order.guestPoints')
    }
  })
})
