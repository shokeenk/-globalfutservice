import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * The sign-in page, "Forgot password?", the reset page and the password card in Account
 * settings, as a customer meets them.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', async (original) => ({ ...(await original<typeof import('../lib/api')>()), api }))

// The animated background draws on a canvas jsdom does not have; it is decoration only.
vi.mock('../components/Atmosphere', () => ({ Atmosphere: () => null }))

const auth = vi.hoisted(() => ({ login: vi.fn(), logout: vi.fn() }))
vi.mock('../state/AuthContext', () => ({
  useAuth: () => ({ account: null, loading: false, login: auth.login, register: vi.fn(), logout: auth.logout }),
}))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { ApiError } = await import('../lib/api')
const { I18nProvider } = await import('../i18n')
const { default: Login } = await import('./Login')
const { default: ForgotPassword } = await import('./ForgotPassword')
const { default: ResetPassword } = await import('./ResetPassword')
const { ChangePassword } = await import('../components/ChangePassword')

function at(path: string, element: React.ReactNode, route = path.split('?')[0]) {
  return render(
    <I18nProvider>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={route} element={element} />
          <Route path="/account" element={<p>account page</p>} />
        </Routes>
      </MemoryRouter>
    </I18nProvider>,
  )
}

function providers(list: string[]) {
  api.get.mockImplementation((path: string) =>
    path === '/api/v1/auth/providers' ? Promise.resolve({ providers: list }) : Promise.reject(new Error(path)))
}

beforeEach(() => {
  window.localStorage.clear()
  providers([])
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('sign in', () => {
  it('has the approved layout: welcome back, the fields, remember me, forgot password, sign in, create account', async () => {
    providers(['google', 'discord'])
    at('/login', <Login mode="login" />)

    expect(screen.getByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /Continue with Google/ })).toHaveAttribute(
      'href', '/oauth2/authorization/google')
    expect(screen.getByRole('link', { name: /Continue with Discord/ })).toBeInTheDocument()
    expect(screen.getByLabelText(/Email/)).toBeInTheDocument()
    expect(screen.getByLabelText(/^Password/)).toHaveAttribute('type', 'password')
    expect(screen.getByRole('checkbox', { name: 'Remember me' })).not.toBeChecked()
    expect(screen.getByRole('link', { name: 'Forgot password?' })).toHaveAttribute('href', '/forgot-password')
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create account' })).toHaveAttribute('href', '/register')
  })

  it('shows only the social sign-ins that are configured', async () => {
    providers(['discord'])
    at('/login', <Login mode="login" />)
    expect(await screen.findByRole('link', { name: /Continue with Discord/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Continue with Google/ })).not.toBeInTheDocument()
  })

  it('shows no social sign-in at all when none is configured', async () => {
    at('/login', <Login mode="login" />)
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/v1/auth/providers', expect.anything()))
    expect(screen.queryByRole('link', { name: /Continue with/ })).not.toBeInTheDocument()
  })

  it('asks for the missing fields before sending anything', async () => {
    const user = userEvent.setup()
    at('/login', <Login mode="login" />)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(screen.getByText('Enter your email.')).toBeInTheDocument()
    expect(screen.getByText('Enter your password.')).toBeInTheDocument()

    await user.type(screen.getByLabelText(/Email/), 'name@gmail')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(screen.getByText('That does not look like an email address.')).toBeInTheDocument()
    expect(auth.login).not.toHaveBeenCalled()
  })

  it('shows and hides the password', async () => {
    const user = userEvent.setup()
    at('/login', <Login mode="login" />)
    const field = screen.getByLabelText(/^Password/)
    await user.click(screen.getByRole('button', { name: 'Show password' }))
    expect(field).toHaveAttribute('type', 'text')
    await user.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(field).toHaveAttribute('type', 'password')
  })

  it('sends Remember me as ticked or not', async () => {
    const user = userEvent.setup()
    auth.login.mockResolvedValue(undefined)
    at('/login', <Login mode="login" />)
    await user.type(screen.getByLabelText(/Email/), 'ada@example.test')
    await user.type(screen.getByLabelText(/^Password/), 'correct horse battery')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(auth.login).toHaveBeenLastCalledWith('ada@example.test', 'correct horse battery', false)
  })

  it('sends Remember me ticked when it is ticked', async () => {
    const user = userEvent.setup()
    auth.login.mockResolvedValue(undefined)
    at('/login', <Login mode="login" />)
    await user.type(screen.getByLabelText(/Email/), 'ada@example.test')
    await user.type(screen.getByLabelText(/^Password/), 'correct horse battery')
    await user.click(screen.getByRole('checkbox', { name: 'Remember me' }))
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(auth.login).toHaveBeenLastCalledWith('ada@example.test', 'correct horse battery', true)
  })

  it('answers a failed sign-in with one generic sentence, in the chosen language', async () => {
    window.localStorage.setItem('gfs.language', 'es')
    const user = userEvent.setup()
    auth.login.mockRejectedValue(new ApiError(400,
      { error: 'invalid_credentials', message: 'Email or password is incorrect.' }))
    at('/login', <Login mode="login" />)
    await user.type(screen.getByLabelText(/Correo/), 'ada@example.test')
    await user.type(screen.getByLabelText(/^Contraseña/), 'wrong password')
    await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
    expect(await screen.findByText('El correo o la contraseña no son correctos.')).toBeInTheDocument()
  })

  it('says to wait when sign-in has been slowed down', async () => {
    const user = userEvent.setup()
    auth.login.mockRejectedValue(new ApiError(429, { error: 'too_many_attempts', message: 'x' }))
    at('/login', <Login mode="login" />)
    await user.type(screen.getByLabelText(/Email/), 'ada@example.test')
    await user.type(screen.getByLabelText(/^Password/), 'whatever it is')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Too many attempts. Please wait a few minutes and try again.'))
      .toBeInTheDocument()
  })
})

describe('forgot password', () => {
  it('always gives the same reply, and sends the language for the email', async () => {
    window.localStorage.setItem('gfs.language', 'fr')
    const user = userEvent.setup()
    api.post.mockResolvedValue({ message: 'ignored' })
    at('/forgot-password', <ForgotPassword />)
    await user.type(screen.getByLabelText(/E-mail/i), 'nobody@example.test')
    await user.click(screen.getByRole('button', { name: 'Envoyer le lien' }))
    expect(await screen.findByText(
      'Si un compte existe pour cette adresse e-mail, nous t’avons envoyé un lien de réinitialisation.'))
      .toBeInTheDocument()
    expect(api.post).toHaveBeenCalledWith('/api/v1/auth/password/forgot',
      { email: 'nobody@example.test', language: 'fr' })
  })

  it('needs an email first', async () => {
    const user = userEvent.setup()
    at('/forgot-password', <ForgotPassword />)
    await user.click(screen.getByRole('button', { name: 'Send reset link' }))
    expect(screen.getByText('Enter your email.')).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()
  })
})

describe('reset password', () => {
  it('needs the new password twice, under the sign-up rule', async () => {
    const user = userEvent.setup()
    at('/reset-password?token=abc', <ResetPassword />)
    await user.type(screen.getByLabelText(/^New password/), 'short')
    await user.click(screen.getByRole('button', { name: 'Update password' }))
    expect(screen.getByText('Use at least 12 characters — a short phrase works well.')).toBeInTheDocument()

    await user.clear(screen.getByLabelText(/^New password/))
    await user.type(screen.getByLabelText(/^New password/), 'a long enough passphrase')
    await user.type(screen.getByLabelText(/^Confirm new password/), 'a different passphrase')
    await user.click(screen.getByRole('button', { name: 'Update password' }))
    expect(screen.getByText('The two passwords do not match.')).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()
  })

  it('updates the password and asks them to sign in', async () => {
    const user = userEvent.setup()
    api.post.mockResolvedValue({ message: 'Password updated, please sign in.' })
    at('/reset-password?token=abc', <ResetPassword />)
    await user.type(screen.getByLabelText(/^New password/), 'a long enough passphrase')
    await user.type(screen.getByLabelText(/^Confirm new password/), 'a long enough passphrase')
    await user.click(screen.getByRole('button', { name: 'Update password' }))
    expect(await screen.findByText('Password updated, please sign in.')).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledWith('/api/v1/auth/password/reset',
      { token: 'abc', password: 'a long enough passphrase', language: 'en' })
  })

  it('says when a link is used or out of time, and offers a new one', async () => {
    const user = userEvent.setup()
    api.post.mockRejectedValue(new ApiError(400, { error: 'reset_link_invalid', message: 'x' }))
    at('/reset-password?token=old', <ResetPassword />)
    await user.type(screen.getByLabelText(/^New password/), 'a long enough passphrase')
    await user.type(screen.getByLabelText(/^Confirm new password/), 'a long enough passphrase')
    await user.click(screen.getByRole('button', { name: 'Update password' }))
    expect(await screen.findByText('This reset link has expired or has already been used. Ask for a new one.'))
      .toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ask for a new link' })).toHaveAttribute('href', '/forgot-password')
  })
})

describe('account settings: password', () => {
  it('changes it with the current one', async () => {
    const user = userEvent.setup()
    api.post.mockResolvedValue(undefined)
    at('/account', <ChangePassword email="ada@example.test" hasPassword />)
    await user.type(screen.getByLabelText(/^Current password/), 'old passphrase here')
    await user.type(screen.getByLabelText(/^New password/), 'a long enough passphrase')
    await user.type(screen.getByLabelText(/^Confirm new password/), 'a long enough passphrase')
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText(/Your other devices have been signed out/)).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledWith('/api/v1/auth/password/change',
      { currentPassword: 'old passphrase here', newPassword: 'a long enough passphrase', language: 'en' })
  })

  it('says under the field when the current password is wrong', async () => {
    const user = userEvent.setup()
    api.post.mockRejectedValue(new ApiError(400, { error: 'current_password_incorrect', message: 'x' }))
    at('/account', <ChangePassword email="ada@example.test" hasPassword />)
    await user.type(screen.getByLabelText(/^Current password/), 'not it')
    await user.type(screen.getByLabelText(/^New password/), 'a long enough passphrase')
    await user.type(screen.getByLabelText(/^Confirm new password/), 'a long enough passphrase')
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText('Your current password is incorrect.')).toBeInTheDocument()
  })

  it('offers a Google or Discord account "Set a password", which emails the link', async () => {
    const user = userEvent.setup()
    api.post.mockResolvedValue(undefined)
    at('/account', <ChangePassword email="ada@example.test" hasPassword={false} />)
    expect(screen.queryByLabelText(/^Current password/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Set a password' }))
    expect(await screen.findByText(/We've sent a link to ada@example.test/)).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledWith('/api/v1/auth/password/set-link', { language: 'en' })
  })
})
