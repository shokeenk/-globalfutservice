import { useState } from 'react'
import { Link } from 'react-router-dom'
import { AuthScreen } from '../components/AuthScreen'
import { Turnstile } from '../components/Turnstile'
import { Alert, Button, Field, Input, revealFirstError } from '../components/ui'
import { useI18n } from '../i18n'
import { api, ApiError } from '../lib/api'
import { looksLikeEmail } from '../lib/validation'
import { useSeo } from '../lib/seo'
import { useSignupConfig } from '../lib/signupConfig'

/**
 * "Forgot password?": an email address in, the same sentence out.
 *
 * <p>The reply never depends on whether an account exists -- the server does not say, and
 * this page shows its own translation of the one sentence either way. Only being asked too
 * often is different, and that is about the asking, not the address.
 */
export default function ForgotPassword() {
  const { t, lang } = useI18n()
  useSeo({ title: t.auth.forgotTitle, noindex: true })

  const [email, setEmail] = useState('')
  const [fieldError, setFieldError] = useState<string | undefined>()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [sent, setSent] = useState(false)
  // The human check, when it is set up: one token per request.
  const siteKey = useSignupConfig()?.turnstileSiteKey ?? null
  const [humanToken, setHumanToken] = useState<string | null>(null)
  const [widget, setWidget] = useState(0)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    const problem = !email.trim() ? t.auth.emailRequired : !looksLikeEmail(email) ? t.auth.emailInvalid : undefined
    setFieldError(problem)
    if (problem) {
      revealFirstError()
      return
    }
    if (siteKey && !humanToken) {
      setError(t.auth.humanCheckRequired)
      return
    }
    const token = siteKey ? humanToken : null
    if (token) {
      setHumanToken(null)
      setWidget((w) => w + 1)
    }
    setBusy(true)
    try {
      await api.post('/api/v1/auth/password/forgot', { email: email.trim(), language: lang, humanToken: token })
      setSent(true)
    } catch (e) {
      setError(e instanceof ApiError && e.status === 429 ? t.auth.tooManyAttempts
        : e instanceof ApiError && e.code === 'human_check_required' ? t.auth.humanCheckRequired
        : e instanceof ApiError && e.code === 'human_check_failed' ? t.auth.humanCheckFailed
        : e instanceof ApiError && e.code === 'network_unreachable' ? e.message : t.auth.genericError)
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthScreen
      heading={t.auth.forgotHeading}
      lead={sent ? undefined : t.auth.forgotLead}
      footer={
        <Link to="/login" className="font-semibold text-brand-400 hover:underline">
          {t.auth.backToSignIn}
        </Link>
      }
    >
      {sent ? (
        <div className="space-y-3">
          <Alert tone="ok">{t.auth.forgotSent}</Alert>
          <p className="text-[12.5px] leading-relaxed text-chalk-faint">{t.auth.forgotSentNote}</p>
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-4" noValidate>
          <Field label={t.auth.email} required error={fieldError}>
            {(props) => (
              <Input {...props} type="email" value={email} autoComplete="email" required
                     onChange={(e) => setEmail(e.target.value)} />
            )}
          </Field>
          <Turnstile key={widget} siteKey={siteKey} language={lang} onToken={setHumanToken} />
          {error && <Alert tone="warn">{error}</Alert>}
          <Button type="submit" full size="lg" loading={busy}>
            {t.auth.forgotButton}
          </Button>
        </form>
      )}
    </AuthScreen>
  )
}
