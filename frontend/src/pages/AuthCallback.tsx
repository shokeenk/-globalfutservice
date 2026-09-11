import { useEffect, useState } from 'react'
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { Alert, ButtonLink, Section } from '../components/ui'
import { PageHeader } from '../components/PageHeader'
import { useT } from '../i18n'
import { peekReturnTo, rememberReturnTo } from '../lib/returnTo'
import { useAuth } from '../state/AuthContext'

/**
 * Where a social sign-in lands.
 *
 * <p>There is almost nothing to do here, which is the point. The backend has already
 * set the refresh cookie, and {@code AuthProvider} exchanges that for a session on its
 * first paint — the same code path a returning visitor takes. So this page waits for
 * that to settle and then gets out of the way.
 *
 * <p>It exists at all for the failure cases. A refusal cannot be an alert on a page
 * nobody is on: the customer is arriving from Google or Discord with no idea what went
 * wrong, and "sign in with your password first" is only useful if it is on the screen
 * in front of them, next to a link that takes them there.
 */
export default function AuthCallback() {
  const t = useT()
  const [params] = useSearchParams()
  const { account, loading } = useAuth()
  const navigate = useNavigate()

  const error = params.get('oauth_error')

  /*
   * Back to the page that sent them to sign in -- usually an order half-built in the
   * configurator. Google and Discord are a full-page round trip, so router state did not
   * survive it and this used to send everybody to /account, whose order button starts a
   * coin order. /login stored the path before they left; it is read once, here, and
   * cleared so a later sign-in in this tab cannot be sent back to an old order.
   */
  const [returnTo] = useState(peekReturnTo)
  useEffect(() => rememberReturnTo(null), [])
  const destination = returnTo ?? '/account'

  useEffect(() => {
    if (!error && !loading && account) {
      /*
       * `replace`, so the back button returns to wherever they started rather than to
       * this page — which would re-run a callback whose one-time code is long spent.
       */
      navigate(destination, { replace: true })
    }
  }, [error, loading, account, navigate, destination])

  if (!error && !loading && account) return <Navigate to={destination} replace />

  const message =
    error === 'NO_EMAIL'
      ? t.auth.oauthNoEmail
      : error === 'UNVERIFIED_EMAIL_CONFLICT'
        ? t.auth.oauthUnverified
        : t.auth.oauthFailed

  return (
    <>
      <PageHeader
        eyebrow={t.auth.signInTitle}
        title={error ? t.auth.signInHeading : t.auth.signInLead}
      />
      <Section className="rhythm-section">
        <div className="measure">
          {error ? (
            <>
              <Alert tone="warn">{message}</Alert>
              <div className="mt-6 flex flex-wrap gap-3">
                <ButtonLink to="/login" size="lg" state={returnTo ? { from: returnTo } : undefined}>
                  {t.auth.signInButton}
                </ButtonLink>
                <ButtonLink to="/" variant="ghost" size="lg">
                  {t.nav.home}
                </ButtonLink>
              </div>
            </>
          ) : (
            /*
             * The in-between: the cookie is set and the exchange is in flight. Usually
             * a single frame, but it is a real state and a blank screen at the end of a
             * sign-in reads as a failure.
             */
            <p className="text-body-sm text-chalk-muted">{t.auth.signInLead}</p>
          )}
        </div>
      </Section>
    </>
  )
}
