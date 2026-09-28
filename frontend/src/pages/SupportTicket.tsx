import { useCallback, useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { PageHeader } from '../components/PageHeader'
import { Alert, Badge, Button, ButtonLink, EmptyState, Field, Section, Skeleton, Textarea } from '../components/ui'
import type { BadgeTone } from '../components/ui'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { dateTime } from '../lib/format'
import { useSeo } from '../lib/seo'
import type { SupportStatus, SupportThread } from '../lib/types'

/** Where a ticket stands, in the customer's words, with the tone of the storefront's badges. */
export function ticketStatus(t: ReturnType<typeof useT>, status: SupportStatus): { label: string; tone: BadgeTone } {
  if (status === 'ANSWERED') return { label: t.supportTicket.statusAnswered, tone: 'brand' }
  if (status === 'CLOSED') return { label: t.supportTicket.statusClosed, tone: 'ok' }
  return { label: t.supportTicket.statusOpen, tone: 'info' }
}

/**
 * One support request, as its customer sees it: our replies and theirs, and a box to
 * answer in.
 *
 * <p>Reached from the link in our email, which carries a key for this ticket alone, or
 * from the account page when signed in with the account that sent it. Anyone else is told
 * there is no such request, whether it exists or not. Staff appear as the business, never
 * by name, and staff notes are never sent here.
 */
export default function SupportTicket() {
  const t = useT()
  useSeo({ title: t.supportTicket.seoTitle, noindex: true })
  const { ref = '' } = useParams()
  const [params] = useSearchParams()
  const key = params.get('key')
  const query = key ? `?key=${encodeURIComponent(key)}` : ''
  const path = `/api/v1/support/tickets/${encodeURIComponent(ref)}${query}`

  const [thread, setThread] = useState<SupportThread | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'missing' | 'failed'>('loading')
  const [reply, setReply] = useState('')
  const [sending, setSending] = useState(false)
  const [result, setResult] = useState<{ tone: 'ok' | 'warn'; text: string } | null>(null)

  const load = useCallback(async () => {
    setState('loading')
    try {
      setThread(await api.get<SupportThread>(path))
      setState('ready')
    } catch (e) {
      setState(e instanceof ApiError && e.status === 404 ? 'missing' : 'failed')
    }
  }, [path])

  useEffect(() => { void load() }, [load])

  async function send() {
    if (!reply.trim() || sending) return
    setSending(true)
    setResult(null)
    try {
      setThread(await api.post<SupportThread>(
        `/api/v1/support/tickets/${encodeURIComponent(ref)}/messages${query}`, { message: reply.trim() }))
      setReply('')
      setResult({ tone: 'ok', text: t.supportTicket.sent })
    } catch (e) {
      setResult({ tone: 'warn', text: e instanceof ApiError && e.status !== 404 ? e.message : t.supportTicket.sendFailed })
    } finally {
      setSending(false)
    }
  }

  if (state === 'missing') {
    return (
      <Section className="rhythm-section">
        <EmptyState title={t.supportTicket.notFoundTitle}>
          {t.supportTicket.notFoundBody}
          <span className="mt-5 block">
            <ButtonLink to="/support" variant="secondary" size="md">{t.supportTicket.newTicket}</ButtonLink>
          </span>
        </EmptyState>
      </Section>
    )
  }

  const status = thread ? ticketStatus(t, thread.status) : null

  return (
    <>
      <PageHeader
        eyebrow={`${t.supportTicket.eyebrow} · ${ref}`}
        title={thread?.subject ?? t.supportTicket.seoTitle}
        lead={thread ? t.supportTicket.opened(dateTime(thread.createdAt)) : undefined}
        intensity={0.35}
      />
      <Section className="rhythm-section">
        <div className="mx-auto max-w-3xl">
          {state === 'failed' && (
            <Alert tone="warn">
              {t.supportTicket.loadFailed}{' '}
              <button type="button" onClick={() => void load()} className="font-semibold underline">
                {t.supportTicket.retry}
              </button>
            </Alert>
          )}
          {state === 'loading' && <Skeleton className="h-56 w-full" />}

          {thread && state === 'ready' && (
            <>
              <div className="mb-5 flex flex-wrap items-center gap-3 text-[13px] text-chalk-muted">
                {status && <Badge tone={status.tone}>{status.label}</Badge>}
                {thread.orderRef && (
                  <span>
                    {t.supportTicket.order}{' '}
                    <Link to={`/track?ref=${encodeURIComponent(thread.orderRef)}`} className="tnum text-chalk underline">
                      {thread.orderRef}
                    </Link>
                  </span>
                )}
              </div>

              <ol className="space-y-4">
                {thread.messages.map((m, index) => {
                  const ours = m.from === 'SUPPORT'
                  return (
                    <li key={index} className={`flex ${ours ? 'justify-start' : 'justify-end'}`}>
                      <div className={`max-w-[88%] rounded-panel border p-4 sm:max-w-[80%] ${ours
                        ? 'border-brand-500/30 bg-brand-500/[0.08]' : 'border-ink-400 bg-ink-700'}`}>
                        <p className="mb-1.5 flex flex-wrap items-baseline gap-x-2 text-[12px]">
                          <strong className={ours ? 'text-brand-400' : 'text-chalk'}>
                            {ours ? t.supportTicket.us : t.supportTicket.you}
                          </strong>
                          <time dateTime={m.at} className="text-chalk-faint">{dateTime(m.at)}</time>
                        </p>
                        <p className="whitespace-pre-wrap break-words text-[14px] leading-relaxed text-chalk">{m.body}</p>
                      </div>
                    </li>
                  )
                })}
              </ol>

              <form className="surface mt-8 space-y-4 p-5 sm:p-6" onSubmit={(event) => {
                event.preventDefault()
                void send()
              }}>
                {thread.status === 'CLOSED' && <p className="text-[13px] text-chalk-muted">{t.supportTicket.closedNote}</p>}
                <Field label={t.supportTicket.replyLabel} hint={t.supportTicket.noPassword}>
                  {(props) => (
                    <Textarea {...props} rows={5} value={reply} maxLength={4000}
                              placeholder={t.supportTicket.replyPlaceholder}
                              onChange={(e) => setReply(e.target.value)} />
                  )}
                </Field>
                {result && <Alert tone={result.tone}>{result.text}</Alert>}
                <Button type="submit" size="lg" loading={sending} disabled={!reply.trim()}>
                  {t.supportTicket.send}
                </Button>
              </form>
            </>
          )}
        </div>
      </Section>
    </>
  )
}
