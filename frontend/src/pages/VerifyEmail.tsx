import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { AuthScreen } from '../components/AuthScreen'
import { Alert, ButtonLink, Spinner } from '../components/ui'
import { useT } from '../i18n'
import { api } from '../lib/api'
import { useSeo } from '../lib/seo'
import { useAuth } from '../state/AuthContext'

/**
 * The page the verification email links to: /confirm-email?token=... Verifies once, as it
 * opens, and says so; a used or out-of-date link says how to get a new one.
 */
export default function VerifyEmail() {
  const t = useT()
  const v = t.verifyEmail
  useSeo({ title: v.pageTitle, noindex: true })
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const { account, refreshAccount } = useAuth()
  const [state, setState] = useState<'verifying' | 'done' | 'invalid'>(token ? 'verifying' : 'invalid')
  const sent = useRef(false)

  useEffect(() => {
    // Once: the link works once, and React's development double effect would spend it twice.
    if (!token || sent.current) return
    sent.current = true
    api.post('/api/v1/auth/email/confirm', { token })
      .then(async () => {
        setState('done')
        await refreshAccount()
      })
      .catch(() => setState('invalid'))
  }, [token, refreshAccount])

  return (
    <AuthScreen heading={v.pageTitle}>
      {state === 'verifying' && (
        <p className="flex items-center gap-3 text-body-sm text-chalk-muted"><Spinner size={18} />{v.verifying}</p>
      )}
      {state === 'done' && (
        <div className="space-y-4">
          <Alert tone="ok">{v.done}</Alert>
          <ButtonLink to={account ? '/account' : '/login'} full size="lg">
            {account ? v.toAccount : t.auth.signInButton}
          </ButtonLink>
        </div>
      )}
      {state === 'invalid' && (
        <div className="space-y-4">
          <Alert tone="warn">{v.invalid} {account ? v.invalidSignedIn : v.invalidSignedOut}</Alert>
          <ButtonLink to={account ? '/account' : '/login'} full size="lg">
            {account ? v.toAccount : t.auth.signInButton}
          </ButtonLink>
        </div>
      )}
    </AuthScreen>
  )
}
