import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom'
import { PageHeader } from '../components/PageHeader'
import { SupportChat } from '../components/support/SupportChat'
import { Alert, Button, Field, Input, Section, Spinner } from '../components/ui'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { useSeo } from '../lib/seo'
import type { SupportContext } from '../lib/types'
import { useAuth } from '../state/AuthContext'

/** An order reference as the site writes it: letters, digits and dashes. Anything else is not put in the form. */
const REFERENCE = /^[A-Za-z0-9-]{4,40}$/

/**
 * The live chat for any order, from an email or a guest order page: /support/chat?ref=...
 *
 * <p>Signed in, it hands over to the order's own support page, which the server only opens
 * for the order's owner. Signed out, it asks for the email the order was placed with -- the
 * same proof as looking a guest order up -- and opens the same chat, with the reference
 * filled in. A guest coming from their order page brings that email in router state, never
 * in the address, so it is not asked for twice.
 *
 * <p>Support is this chat only: no Discord, and never the EA password or backup codes, which
 * go in the secure form on the order page.
 */
export default function GuestOrderChat() {
  const t = useT()
  const s = t.orderSupport
  const { account, loading } = useAuth()
  const [params] = useSearchParams()
  const location = useLocation()
  const fromState = (location.state as { email?: unknown } | null)?.email
  /** The email the order page handed over, if any: only that one opens the chat by itself. */
  const handedEmail = typeof fromState === 'string' ? fromState.trim() : ''
  const initialRef = (params.get('ref') ?? '').trim()

  const [reference, setReference] = useState(REFERENCE.test(initialRef) ? initialRef.toUpperCase() : '')
  const [email, setEmail] = useState(handedEmail)
  const [context, setContext] = useState<SupportContext | null>(null)
  const [state, setState] = useState<'idle' | 'opening' | 'not-found' | 'failed'>('idle')
  const tried = useRef(false)
  useSeo({ title: s.guestChatTitle, noindex: true })

  const open = useCallback(async (ref: string, mail: string) => {
    setState('opening')
    try {
      const found = await api.post<SupportContext>('/api/v1/orders/track/support-context',
        { publicRef: ref.trim(), email: mail.trim() })
      setContext(found)
      setState('idle')
    } catch (e) {
      setState(e instanceof ApiError && e.status === 404 ? 'not-found' : 'failed')
    }
  }, [])

  // Handed the email by the order page: straight to the chat, once. Typing one never opens it.
  useEffect(() => {
    if (tried.current || loading || account || !reference || !handedEmail) return
    tried.current = true
    void open(reference, handedEmail)
  }, [loading, account, reference, handedEmail, open])

  if (loading) return <div className="grid min-h-[40vh] place-items-center"><Spinner size={32} /></div>
  if (account && REFERENCE.test(reference)) {
    // The order's own page: the server opens it for its owner, and says "not found" otherwise.
    return <Navigate to={`/orders/${encodeURIComponent(reference)}/support`} replace />
  }

  if (context) {
    const summary = context.summary
    return (
      <>
        <PageHeader eyebrow={s.eyebrow} title={s.guestChatTitle} lead={`#${summary.reference} · ${summary.service}`} />
        <Section className="rhythm-section">
          <div className="mx-auto max-w-4xl space-y-4">
            <SupportChat reference={summary.reference} chat={context.chat} />
            <p className="text-[12.5px] leading-relaxed text-chalk-faint" data-testid="never-in-chat">
              {s.neverInChat}
            </p>
          </div>
        </Section>
      </>
    )
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!reference.trim() || !email.trim()) return
    void open(reference, email)
  }

  return (
    <>
      <PageHeader eyebrow={s.eyebrow} title={s.guestChatTitle}
                  lead={reference ? s.guestChatLead(reference) : undefined} />
      <Section className="rhythm-section">
        <form className="mx-auto max-w-md space-y-4" onSubmit={submit} data-testid="guest-chat-form">
          <Field label={s.guestChatRefLabel}>
            {(props) => (
              <Input {...props} value={reference} onChange={(e) => setReference(e.target.value)}
                     autoComplete="off" maxLength={40} required />
            )}
          </Field>
          <Field label={s.guestChatEmailLabel}>
            {(props) => (
              <Input {...props} type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                     autoComplete="email" maxLength={254} required />
            )}
          </Field>
          {state === 'not-found' && <Alert tone="warn">{s.guestChatNotFound}</Alert>}
          {state === 'failed' && <Alert tone="warn">{s.pageFailed}</Alert>}
          <Button type="submit" loading={state === 'opening'} full>{s.guestChatOpen}</Button>
          <p className="text-[12.5px] text-chalk-muted">
            <Link to="/login" state={{ from: reference ? `/orders/${encodeURIComponent(reference)}/support` : '/account' }}
                  className="font-semibold text-brand-400 hover:underline">
              {s.guestChatSignIn}
            </Link>
          </p>
          <p className="text-[12.5px] leading-relaxed text-chalk-faint">{s.neverInChatShort}</p>
        </form>
      </Section>
    </>
  )
}
