import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Atmosphere } from '../components/Atmosphere'
import { BrandBadge } from '../brand/Logo'
import { Alert, Button, Card, Checkbox, Field, Input, Section } from '../components/ui'
import { SocialSignIn } from '../components/SocialSignIn'
import { useT } from '../i18n'
import { ApiError } from '../lib/api'
import { useSeo } from '../lib/seo'
import { useAuth } from '../state/AuthContext'

export default function Login({ mode }: { mode: 'login' | 'register' }) {
  const isRegister = mode === 'register'
  const t = useT()
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
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Per-field messages from the server. The summary it sends says "check the
  // highlighted fields", so something has to actually do the highlighting.
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  if (account) {
    const destination = (location.state as { from?: string } | null)?.from
      ?? (account.role === 'CUSTOMER' ? '/account' : '/admin')
    return <Navigate to={destination} replace />
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    setFieldErrors({})
    try {
      if (isRegister) {
        await register({
          email: email.trim(),
          password,
          displayName: displayName.trim() || undefined,
          acceptedTerms,
        })
      } else {
        await login(email.trim(), password)
      }
      navigate('/account', { replace: true })
    } catch (e) {
      if (e instanceof ApiError) {
        setFieldErrors(e.fieldErrors)
        // When the server named the fields, the banner would only repeat what is now
        // written under each input. Show it only when there is nothing more specific.
        setError(Object.keys(e.fieldErrors).length > 0 ? null : e.message)
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
                <Input
                  {...props}
                  type="password"
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
              </>
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
          <Link to={isRegister ? '/login' : '/register'} className="font-semibold text-brand-400 hover:underline">
            {isRegister ? t.auth.signInLink : t.auth.createLink}
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
