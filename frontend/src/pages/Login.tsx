import { useEffect, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Atmosphere } from '../components/Atmosphere'
import { BrandBadge } from '../brand/Logo'
import { Alert, Button, Card, Checkbox, Field, Input, Section, revealFirstError } from '../components/ui'
import { PasswordInput } from '../components/PasswordInput'
import { SocialSignIn } from '../components/SocialSignIn'
import { Turnstile } from '../components/Turnstile'
import { useI18n } from '../i18n'
import { ApiError } from '../lib/api'
import { authErrorMessage } from '../lib/authErrors'
import { looksLikeEmail } from '../lib/validation'
import { useSignupConfig } from '../lib/signupConfig'
import { rememberReturnTo, safeReturnPath } from '../lib/returnTo'
import { useSeo } from '../lib/seo'
import { useAuth } from '../state/AuthContext'

export default function Login({ mode }: { mode: 'login' | 'register' }) {
  const isRegister = mode === 'register'
  const { t, lang } = useI18n()
  useSeo({
    title: isRegister ? t.auth.registerTitle : t.auth.signInTitle,
    noindex: true,
  })

  const { account, login, register } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [acceptedTerms, setAcceptedTerms] = useState(false)
  /*
   * Unticked unless an admin has turned on "Pre-tick marketing consent" (off by default:
   * pre-ticked consent is not valid for EU customers). Either way what is recorded is what
   * the customer submits, and once they touch the box the setting no longer moves it.
   */
  const config = useSignupConfig()
  const [marketingOptIn, setMarketingOptIn] = useState(false)
  const [marketingTouched, setMarketingTouched] = useState(false)
  useEffect(() => {
    if (config && !marketingTouched) setMarketingOptIn(config.prefillMarketingConsent)
  }, [config]) // eslint-disable-line react-hooks/exhaustive-deps -- only as the setting arrives
  /*
   * The human check: always on sign-up; on sign-in once three tries have failed here, or
   * the server asks for it. A token is good once, so the widget is remounted after use.
   */
  const [humanToken, setHumanToken] = useState<string | null>(null)
  const [widget, setWidget] = useState(0)
  const [failures, setFailures] = useState(0)
  const [checkAsked, setCheckAsked] = useState(false)
  const siteKey = config?.turnstileSiteKey ?? null
  const showCheck = Boolean(siteKey) && (isRegister || checkAsked || failures >= 3)
  // Unticked: a session that ends with the browser. Ticked: the long-lived cookie.
  const [rememberMe, setRememberMe] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Per-field messages from the server. The summary it sends says "check the
  // highlighted fields", so something has to actually do the highlighting.
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  /*
   * The page that sent them here, which is usually an order: the configurator's account
   * gate passes its own URL, and the order's service and package live in that URL's query
   * string. Both ways out of this page go back to it -- the form below from here, and
   * Google or Discord through /auth/callback, which cannot see router state after a
   * full-page round trip and so reads the copy stored for it. Visiting /login without one
   * clears any older copy, so a sign-in never lands on an order from earlier in the tab.
   */
  const from = safeReturnPath((location.state as { from?: unknown } | null)?.from)
  useEffect(() => rememberReturnTo(from), [from])

  if (account) {
    const destination = from ?? (account.role === 'CUSTOMER' ? '/account' : '/admin')
    return <Navigate to={destination} replace />
  }

  /** The required fields, checked here first so the answer is in the customer's language. */
  function missing(): Record<string, string> {
    const errors: Record<string, string> = {}
    if (!email.trim()) errors.email = t.auth.emailRequired
    else if (!looksLikeEmail(email)) errors.email = t.auth.emailInvalid
    if (!password) errors.password = t.auth.passwordRequired
    else if (isRegister && password.length < 12) errors.password = t.auth.passwordTooShort
    return errors
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    const errors = missing()
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) {
      revealFirstError()
      return
    }
    if (showCheck && !humanToken) {
      setError(t.auth.humanCheckRequired)
      return
    }
    const token = showCheck ? humanToken : null
    if (token) {
      // Spent by this submit, whatever the answer: the next try needs a fresh one.
      setHumanToken(null)
      setWidget((w) => w + 1)
    }
    setBusy(true)
    try {
      if (isRegister) {
        await register({
          email: email.trim(),
          password,
          displayName: displayName.trim() || undefined,
          acceptedTerms,
          marketingOptIn,
          language: lang,
          humanToken: token,
        })
      } else {
        await login(email.trim(), password, rememberMe, token)
      }
      rememberReturnTo(null)
      navigate(from ?? '/account', { replace: true })
    } catch (e) {
      if (e instanceof ApiError && (e.code === 'human_check_required' || e.code === 'human_check_failed')) {
        setCheckAsked(true)
      } else if (!isRegister && e instanceof ApiError && e.code === 'invalid_credentials') {
        setFailures((n) => n + 1)
      }
      if (!isRegister || (e instanceof ApiError && e.code.startsWith('human_check'))) {
        // One sentence for every failed sign-in, in their language. Never which field.
        setError(authErrorMessage(e, t))
      } else if (e instanceof ApiError) {
        setFieldErrors(e.fieldErrors)
        // When the server named the fields, the banner would only repeat what is now
        // written under each input. Show it only when there is nothing more specific.
        setError(Object.keys(e.fieldErrors).length > 0 ? null : e.status === 429 ? t.auth.tooManyAttempts : e.message)
      } else {
        setError(t.auth.genericError)
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    /*
     * The auth screen gets its own light rather than the shared masthead.
     *
     * There is no page content to introduce here — it is a single form, and a
     * masthead above it would be a heading for a heading. Instead the atmosphere
     * runs behind the whole viewport and the card floats in it, which is the one
     * layout on the site where a centred composition is the right answer: nothing
     * competes for attention because there is nothing else on the screen.
     */
    <section className="relative isolate flex min-h-[calc(100dvh-72px)] items-center overflow-hidden">
      <Atmosphere intensity={0.7} />
      <Section className="relative rhythm-section">
      <div className="mx-auto max-w-md">
        <div className="mb-8 text-center">
          {/* The auth screen is the one centred composition on the site, and the
              only page with nothing competing for the eye — so it gets the crest
              rather than the horizontal wordmark. */}
          <BrandBadge size={96} aura className="mx-auto" priority />
          <h1 className="display mt-7 text-display-lg text-sheen">
            {isRegister ? t.auth.registerHeading : t.auth.signInHeading}
          </h1>
          <p className="mt-3 text-body-sm text-chalk-muted">
            {isRegister ? t.auth.registerLead : t.auth.signInLead}
          </p>
        </div>

        <Card className="p-7 shadow-e3">
          {/*
            Above the form, because it is the faster route and burying it under a
            password field is how a one-tap sign-in gets missed. Renders nothing at all
            when no provider is configured, so the form simply starts where it always did.
          */}
          <SocialSignIn />

          <form onSubmit={submit} className="space-y-4" noValidate>
            <Field label={t.auth.email} required error={fieldErrors.email}>
              {(props) => (
                <Input {...props} type="email" value={email} autoComplete="email" required
                       onChange={(e) => setEmail(e.target.value)} />
              )}
            </Field>

            <Field
              label={t.auth.password}
              required
              error={fieldErrors.password}
              hint={isRegister ? t.auth.passwordHint : undefined}
            >
              {(props) => (
                <PasswordInput
                  {...props}
                  value={password}
                  required
                  minLength={isRegister ? 12 : undefined}
                  // Tells a password manager which flow this is, so it offers to
                  // generate on sign-up and to fill on sign-in.
                  autoComplete={isRegister ? 'new-password' : 'current-password'}
                  onChange={(e) => setPassword(e.target.value)}
                />
              )}
            </Field>

            {isRegister && (
              <>
                <Field label={t.auth.displayName} error={fieldErrors.displayName}>
                  {(props) => (
                    <Input {...props} value={displayName} autoComplete="nickname"
                           onChange={(e) => setDisplayName(e.target.value)} />
                  )}
                </Field>
                <Checkbox checked={acceptedTerms} onChange={setAcceptedTerms}>
                  {t.auth.acceptPrefix}{' '}
                  <Link to="/terms" className="text-brand-400 hover:underline">
                    {t.auth.acceptTerms}
                  </Link>{' '}
                  {t.auth.acceptAnd}{' '}
                  <Link to="/privacy" className="text-brand-400 hover:underline">
                    {t.auth.acceptPrivacy}
                  </Link>.
                </Checkbox>
                {/* The submit button is disabled until this is ticked, so the server
                    should never reject on it — but if it ever does, the reason lands
                    next to the control rather than nowhere. */}
                {fieldErrors.acceptedTerms && (
                  <p role="alert" className="text-[12px] text-brand-400">
                    {fieldErrors.acceptedTerms}
                  </p>
                )}
                {/* Its own control, below the terms and set apart from them, in plain grey:
                    an offer, not a condition. The submit button does not depend on it --
                    saying no to marketing is a complete answer. */}
                <div className="border-t border-ink-400 pt-4" data-testid="marketing-consent">
                  <Checkbox checked={marketingOptIn}
                            onChange={(next) => { setMarketingTouched(true); setMarketingOptIn(next) }}>
                    <span className="text-chalk-muted">{t.auth.marketingOptIn}</span>
                    <span className="mt-0.5 block text-[12px] text-chalk-faint">{t.auth.marketingOptInNote}</span>
                  </Checkbox>
                </div>
              </>
            )}

            {showCheck && (
              <Turnstile key={widget} siteKey={siteKey} language={lang} onToken={setHumanToken} />
            )}

            {!isRegister && (
              <div className="flex items-center justify-between gap-4">
                <Checkbox checked={rememberMe} onChange={setRememberMe}>
                  {t.auth.rememberMe}
                </Checkbox>
                <Link to="/forgot-password" className="shrink-0 text-[13px] font-semibold text-brand-400 hover:underline">
                  {t.auth.forgotLink}
                </Link>
              </div>
            )}

            {error && <Alert tone="warn">{error}</Alert>}

            <Button
              type="submit"
              full
              size="lg"
              loading={busy}
              disabled={isRegister && !acceptedTerms}
            >
              {isRegister ? t.auth.createAccount : t.auth.signInButton}
            </Button>
          </form>
        </Card>

        <p className="mt-6 text-center text-[13.5px] text-chalk-muted">
          {isRegister ? `${t.auth.haveAccount} ` : `${t.auth.newHere} `}
          <Link
            to={isRegister ? '/login' : '/register'}
            state={from ? { from } : undefined}
            className="font-semibold text-brand-400 hover:underline"
          >
            {isRegister ? t.auth.signInLink : t.auth.createAccount}
          </Link>
        </p>

        <p className="mt-4 text-center text-[12px] text-chalk-faint">
          {t.auth.guestNote}
        </p>
      </div>
      </Section>
    </section>
  )
}
