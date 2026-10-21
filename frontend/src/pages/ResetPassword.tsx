import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { AuthScreen } from '../components/AuthScreen'
import { PasswordInput } from '../components/PasswordInput'
import { Alert, Button, ButtonLink, Field, revealFirstError } from '../components/ui'
import { useI18n } from '../i18n'
import { api, ApiError } from '../lib/api'
import { authErrorMessage } from '../lib/authErrors'
import { useSeo } from '../lib/seo'
import { useAuth } from '../state/AuthContext'

/**
 * The page the reset email links to: /reset-password?token=...
 *
 * <p>A new password and the same again, under the sign-up rule (12 characters or more).
 * Success signs the account out everywhere -- this browser included -- so the page says so
 * and sends them to sign in with the new one.
 */
export default function ResetPassword() {
  const { t, lang } = useI18n()
  useSeo({ title: t.auth.resetTitle, noindex: true })
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const { account, logout } = useAuth()

  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [fieldErrors, setFieldErrors] = useState<{ password?: string; confirm?: string }>({})
  const [error, setError] = useState<string | null>(null)
  const [linkDead, setLinkDead] = useState(false)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    const errors: { password?: string; confirm?: string } = {}
    if (!password) errors.password = t.auth.passwordRequired
    else if (password.length < 12) errors.password = t.auth.passwordTooShort
    if (!errors.password && confirm !== password) errors.confirm = t.auth.passwordsDontMatch
    setFieldErrors(errors)
    if (errors.password || errors.confirm) {
      revealFirstError()
      return
    }
    setBusy(true)
    try {
      await api.post('/api/v1/auth/password/reset', { token, password, language: lang })
      // Every session was signed out on the server; drop this tab's copy too.
      if (account) await logout()
      setDone(true)
    } catch (e) {
      if (e instanceof ApiError && e.code === 'reset_link_invalid') setLinkDead(true)
      else if (e instanceof ApiError && e.fieldErrors.password) setFieldErrors({ password: t.auth.passwordTooShort })
      else setError(authErrorMessage(e, t))
    } finally {
      setBusy(false)
    }
  }

  const back = (
    <Link to="/login" className="font-semibold text-brand-400 hover:underline">
      {t.auth.backToSignIn}
    </Link>
  )

  if (!token || linkDead) {
    return (
      <AuthScreen heading={t.auth.resetTitle} footer={back}>
        <div className="space-y-4">
          <Alert tone="warn">{token ? t.auth.resetInvalid : t.auth.resetMissing}</Alert>
          <ButtonLink to="/forgot-password" full size="lg">{t.auth.requestNewLink}</ButtonLink>
        </div>
      </AuthScreen>
    )
  }

  if (done) {
    return (
      <AuthScreen heading={t.auth.resetTitle}>
        <div className="space-y-4">
          <Alert tone="ok">{t.auth.resetDone}</Alert>
          <ButtonLink to="/login" full size="lg">{t.auth.signInButton}</ButtonLink>
        </div>
      </AuthScreen>
    )
  }

  return (
    <AuthScreen heading={t.auth.resetTitle} lead={t.auth.resetLead} footer={back}>
      <form onSubmit={submit} className="space-y-4" noValidate>
        <Field label={t.auth.newPassword} required error={fieldErrors.password} hint={t.auth.passwordHint}>
          {(props) => (
            <PasswordInput {...props} value={password} required minLength={12} autoComplete="new-password"
                           onChange={(e) => setPassword(e.target.value)} />
          )}
        </Field>
        <Field label={t.auth.confirmPassword} required error={fieldErrors.confirm}>
          {(props) => (
            <PasswordInput {...props} value={confirm} required autoComplete="new-password"
                           onChange={(e) => setConfirm(e.target.value)} />
          )}
        </Field>
        {error && <Alert tone="warn">{error}</Alert>}
        <Button type="submit" full size="lg" loading={busy}>
          {t.auth.resetButton}
        </Button>
      </form>
    </AuthScreen>
  )
}
