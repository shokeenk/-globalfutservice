import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { PageHeader } from '../../components/PageHeader'
import {
  Alert, Badge, Button, Card, Field, Section, Skeleton, Textarea,
} from '../../components/ui'
import { ApiError, api } from '../../lib/api'
import { dateTime } from '../../lib/format'
import { useSeo } from '../../lib/seo'
import type { Order } from '../../lib/types'
import { statusTone } from '../Track'

type Revealed = {
  eaEmail: string
  eaPassword: string
  backupCodes: string[]
  platformHandle: string | null
  note: string | null
}

const TRANSITION_LABELS: Record<string, string> = {
  CREDENTIALS_PENDING: 'Ask for sign-in',
  READY_FOR_DELIVERY: 'Move to queue',
  IN_PROGRESS: 'Start working',
  ON_HOLD: 'Put on hold',
  DELIVERED: 'Mark delivered',
  DISPUTED: 'Open a dispute',
  REFUNDED: 'Refund',
  CREDITED: 'Settle as store credit',
}

export default function AdminOrder() {
  const { publicRef = '' } = useParams()
  useSeo({ title: `Order ${publicRef}`, noindex: true })

  const [order, setOrder] = useState<Order | null>(null)
  const [transitions, setTransitions] = useState<string[]>([])
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [revealed, setRevealed] = useState<Revealed | null>(null)

  const load = useCallback(async () => {
    try {
      const [detail, queue] = await Promise.all([
        api.get<Order>(`/api/v1/admin/orders/${publicRef}`),
        api.get<{ publicRef: string; availableTransitions: string[] }[]>(
          `/api/v1/admin/orders?search=${encodeURIComponent(publicRef)}`,
        ),
      ])
      setOrder(detail)
      setTransitions(queue.find((o) => o.publicRef === publicRef)?.availableTransitions ?? [])
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not load that order.')
    }
  }, [publicRef])

  useEffect(() => {
    void load()
  }, [load])

  async function transition(target: string) {
    setBusy(target)
    setError(null)
    try {
      await api.post(`/api/v1/admin/orders/${publicRef}/transition`, {
        toStatus: target,
        reason: reason.trim() || null,
      })
      setReason('')
      // The vault is emptied by the state machine on completion, so anything on
      // screen is stale the moment the order moves.
      setRevealed(null)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'That action failed.')
    } finally {
      setBusy(null)
    }
  }

  async function reveal() {
    setBusy('reveal')
    setError(null)
    try {
      setRevealed(await api.post<Revealed>(`/api/v1/admin/orders/${publicRef}/credentials/reveal`))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not open the vault.')
    } finally {
      setBusy(null)
    }
  }

  if (!order) {
    return (
      <Section className="rhythm-section">
        {error ? <Alert tone="warn">{error}</Alert> : <Skeleton className="h-72 w-full" />}
      </Section>
    )
  }

  return (
    <>
      {/*
        The reference is the page title.

        An operator arriving from the queue is holding one thing in their head — the
        reference they just clicked — and the page has to confirm it immediately.
        Putting it in the masthead rather than in the first card also means it is
        what the browser tab and the back-button history show.
      */}
      <PageHeader
        eyebrow="Operations · Order"
        title={<span className="tnum">{order.publicRef}</span>}
        lead={order.serviceLabel}
        intensity={0.3}
        aside={
          <div className="flex flex-col items-start gap-3 lg:items-end">
            <Badge tone={statusTone(order.status)}>{order.statusLabel}</Badge>
            <p className="tnum display text-display-md text-chalk">{order.totalFormatted}</p>
          </div>
        }
      />

    <Section className="rhythm-section">
      <Link to="/admin" className="group mb-6 inline-flex items-center gap-2 text-[13px] link-quiet">
        <span aria-hidden="true" className="transition-transform duration-300 ease-out-expo group-hover:-translate-x-1">
          &larr;
        </span>
        Back to the queue
      </Link>

      <div className="grid gap-5 lg:grid-cols-[1fr_360px] lg:items-start">
        <div className="space-y-5">
          <Card className="p-6 sm:p-7">
            <dl className="grid gap-px overflow-hidden rounded-edge bg-ink-400 sm:grid-cols-3">
              <Detail label="Total" value={order.totalFormatted} />
              <Detail
                label="Method"
                value={order.deliveryMethod === 'COMFORT_TRADE' ? 'Comfort trade' : 'Transfer market'}
              />
              <Detail label="Placed" value={dateTime(order.createdAt)} />
              <Detail label="Delivered" value={dateTime(order.deliveredAt)} />
              <Detail label="Guarantee ends" value={dateTime(order.guaranteeExpiresAt)} />
              <Detail label="Points earned" value={String(order.pointsEarned)} />
            </dl>

            {order.lines.length > 0 && (
              <div className="mt-6 border-t border-ink-400 pt-5">
                <p className="stamp mb-4">Frozen price breakdown</p>
                <ul className="space-y-2">
                  {order.lines.map((line) => (
                    <li key={line.code} className="flex justify-between gap-4 text-[13px]">
                      <span className="text-chalk-muted">{line.label}</span>
                      <span className={`tnum ${line.amountMinor < 0 ? 'text-ok' : 'text-chalk'}`}>
                        {line.amountFormatted}
                      </span>
                    </li>
                  ))}
                </ul>
                <p className="mt-3 text-[11.5px] text-chalk-faint">
                  Recorded when the order was placed. Rate changes since then do not affect it.
                </p>
              </div>
            )}
          </Card>

          <Card className="p-7">
            <p className="eyebrow mb-4">History</p>
            <ol className="space-y-4">
              {order.timeline.map((event, index) => (
                <li key={index} className="flex gap-3">
                  <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-brand-500" />
                  <div className="min-w-0">
                    <p className="text-[13px] text-chalk">
                      {event.fromStatus ? `${event.fromStatus} → ` : ''}
                      <span className="font-semibold">{event.toStatus}</span>
                    </p>
                    {event.reason && (
                      <p className="mt-0.5 text-[12.5px] text-chalk-muted">{event.reason}</p>
                    )}
                    <p className="text-[11.5px] text-chalk-faint">
                      {dateTime(event.at)} · {event.actorType}
                      {event.actorLabel ? ` · ${event.actorLabel}` : ''}
                    </p>
                  </div>
                </li>
              ))}
            </ol>
          </Card>
        </div>

        <div className="space-y-5">
          <Card className="p-6">
            <h2 className="display text-[15px] text-chalk">Actions</h2>

            {transitions.includes('DELIVERED') && (
              <Alert tone="warn" title="Delivering is final">
                Marking this delivered sends the delivery email, closes the refund window and
                starts the guarantee clock. It cannot be undone.
              </Alert>
            )}

            <div className="mt-4">
              <Field label="Note" hint="Goes on the order history. Worth writing for anything unusual.">
                {(props) => (
                  <Textarea {...props} rows={2} value={reason}
                            onChange={(e) => setReason(e.target.value)} />
                )}
              </Field>
            </div>

            <div className="mt-4 space-y-2">
              {transitions.length === 0 && (
                <p className="text-[13px] text-chalk-faint">
                  No actions available — this order is finished.
                </p>
              )}
              {transitions.map((target) => (
                <Button
                  key={target}
                  full
                  variant={target === 'DELIVERED' ? 'primary' : 'secondary'}
                  loading={busy === target}
                  onClick={() => void transition(target)}
                >
                  {TRANSITION_LABELS[target] ?? target}
                </Button>
              ))}
            </div>

            {error && <div className="mt-4"><Alert tone="warn">{error}</Alert></div>}
          </Card>

          {order.credentialsRequired && (
            <Card className="border-brand-500/25 p-6">
              <h2 className="display text-[15px] text-chalk">Customer sign-in</h2>

              {!order.credentialsSubmitted && (
                <p className="mt-3 text-[13px] text-chalk-muted">
                  Not submitted yet. The customer has been emailed.
                </p>
              )}

              {order.credentialsSubmitted && !revealed && (
                <>
                  <p className="mt-3 text-[12.5px] leading-relaxed text-chalk-faint">
                    Opening this is recorded against your account, with a timestamp. Open it when
                    you are about to work the order, not before.
                  </p>
                  <Button
                    full
                    variant="danger"
                    className="mt-4"
                    loading={busy === 'reveal'}
                    onClick={() => void reveal()}
                  >
                    Open the vault
                  </Button>
                </>
              )}

              {revealed && (
                <div className="mt-4 space-y-3">
                  <Secret label="EA email" value={revealed.eaEmail} />
                  <Secret label="Password" value={revealed.eaPassword} />
                  {revealed.backupCodes.length > 0 && (
                    <Secret label="Backup codes" value={revealed.backupCodes.join('  ')} />
                  )}
                  {revealed.note && (
                    <p className="text-[12.5px] text-chalk-muted">Note: {revealed.note}</p>
                  )}
                  <Button variant="secondary" full onClick={() => setRevealed(null)}>
                    Hide
                  </Button>
                  <p className="text-[11.5px] leading-relaxed text-chalk-faint">
                    Destroyed automatically when this order completes, and in any case within the
                    retention window. Do not copy it anywhere else.
                  </p>
                </div>
              )}
            </Card>
          )}
        </div>
      </div>
    </Section>
    </>
  )
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-paper p-4">
      <dt className="text-[10.5px] uppercase tracking-[0.14em] text-chalk-faint">{label}</dt>
      <dd className="tnum mt-1.5 text-[14px] font-semibold text-chalk">{value}</dd>
    </div>
  )
}

/** Monospace, selectable, and visually marked as something that should not linger. */
function Secret({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-brand-500/25 bg-brand-50 p-3">
      <p className="text-[10.5px] uppercase tracking-wider text-brand-400">{label}</p>
      <p className="mt-1 select-all break-all font-mono text-[13px] text-chalk">{value}</p>
    </div>
  )
}
