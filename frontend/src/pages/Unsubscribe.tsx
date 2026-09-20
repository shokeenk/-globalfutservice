import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { PageHeader } from '../components/PageHeader'
import { Alert, Button, Section } from '../components/ui'
import { ApiError, api } from '../lib/api'
import { useSeo } from '../lib/seo'

/**
 * Where a promotional email's unsubscribe link lands.
 *
 * <p><b>Why this page asks rather than just doing it.</b> Corporate mail security products
 * and link-preview bots fetch every URL in an incoming message to check it is safe. If
 * opening this page unsubscribed the reader, those scanners would unsubscribe people who
 * never clicked anything — silently, and worst for customers whose employer runs the
 * strictest scanner. So the page loads, explains, and the opt-out happens on a POST that
 * only a real click produces.
 *
 * <p>Deliberately available to signed-out visitors: somebody unsubscribing from an email
 * should not have to remember a password first. The token in the link is the
 * authorisation, and it identifies nobody to anyone who intercepts it.
 */
export default function Unsubscribe() {
  useSeo({ title: 'Unsubscribe', noindex: true })

  const [params] = useSearchParams()
  const token = params.get('t') ?? ''
  const campaign = params.get('c')
  const preview = params.get('preview') === '1'

  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const confirm = async () => {
    setBusy(true)
    setError(null)
    try {
      await api.post('/api/v1/marketing/unsubscribe', { token, campaign })
      setDone(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'That did not work. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageHeader eyebrow="Email preferences" title="Unsubscribe" intensity={0.25} />
      <Section className="rhythm-section">
        {preview && (
          <Alert tone="neutral">
            This is a preview link. Nothing here will change anyone&rsquo;s preferences.
          </Alert>
        )}

        {!preview && !token && (
          <Alert tone="warn">
            That unsubscribe link is incomplete. Open it directly from the email, or
            contact support and we will do it for you.
          </Alert>
        )}

        {done ? (
          <Alert tone="ok" title="Done">
            You will no longer receive promotional email from Global FUT Services.
          </Alert>
        ) : (
          <>
            <p className="max-w-prose text-chalk-muted">
              This stops <strong className="text-chalk">promotional email</strong> — sales,
              offers and campaign announcements.
            </p>
            {/*
              Stated plainly, because it is the question somebody unsubscribing actually
              has, and because getting it wrong in the other direction means a customer
              not being told their payment failed.
            */}
            <p className="mt-3 max-w-prose text-chalk-muted">
              You will still receive emails about orders you place — payment confirmations,
              delivery notices and anything we need from you to complete an order. Those
              are part of the service, not marketing, and cannot be turned off while you
              have orders with us.
            </p>

            {error && <div className="mt-5"><Alert tone="warn">{error}</Alert></div>}

            <div className="mt-7">
              <Button
                variant="primary"
                onClick={() => void confirm()}
                disabled={busy || preview || !token}
              >
                {busy ? 'Updating…' : 'Unsubscribe from promotional email'}
              </Button>
            </div>
          </>
        )}
      </Section>
    </>
  )
}
