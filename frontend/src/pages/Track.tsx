import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { CredentialForm } from '../components/CredentialForm'
import { PageHeader } from '../components/PageHeader'
import { Alert, Badge, Button, Field, Input, Section } from '../components/ui'
import type { BadgeTone } from '../components/ui'
import { useT } from '../i18n'
import { useCatalogLabels } from '../content/catalogLabels'
import { ApiError, api } from '../lib/api'
import { dateTime } from '../lib/format'
import { useSeo } from '../lib/seo'
import type { Order } from '../lib/types'
import { Reveal } from '../motion/Reveal'
import { useAuth } from '../state/AuthContext'

export default function Track() {
  const t = useT()
  useSeo({
    title: t.track.seoTitle,
    description: 'Check where your order is with your reference and email.',
    noindex: true,
  })

  const [params] = useSearchParams()
  const { account } = useAuth()

  /*
   * Prefilled from the URL and from the session.
   *
   * The account page links straight here with `?ref=`, and a signed-in customer's
   * email is already known — so for the common case ("where is the order I just
   * placed?") there is nothing left to type. Typing a reference you were shown two
   * seconds ago into a box is exactly the kind of small indignity that makes a
   * product feel unfinished.
   */
  const [publicRef, setPublicRef] = useState(() => (params.get('ref') ?? '').toUpperCase())
  const [email, setEmail] = useState(account?.email ?? '')
  const [order, setOrder] = useState<Order | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const lookup = useCallback(async (ref: string, mail: string) => {
    setLoading(true)
    setError(null)
    try {
      setOrder(await api.post<Order>('/api/v1/orders/track', {
        publicRef: ref.trim(),
        email: mail.trim(),
      }))
    } catch (e) {
      setOrder(null)
      setError(e instanceof ApiError ? e.message : 'We could not find that order.')
    } finally {
      setLoading(false)
    }
  }, [])

  // The session resolves after first paint, so pick the email up when it lands —
  // but never overwrite something the visitor has already typed.
  const touchedEmail = useRef(false)
  useEffect(() => {
    if (account?.email && !touchedEmail.current && !email) setEmail(account.email)
  }, [account?.email, email])

  /*
   * Auto-lookup, exactly once, and only when the page was opened with both halves
   * already known. Guarded by a ref rather than by state so a re-render cannot fire
   * a second request — and deliberately not re-run when the fields change, because
   * looking up on every keystroke would hammer an endpoint that takes an email
   * address as an argument.
   */
  const autoRan = useRef(false)
  useEffect(() => {
    const ref = params.get('ref')
    if (autoRan.current || !ref || !account?.email) return
    autoRan.current = true
    void lookup(ref, account.email)
  }, [params, account?.email, lookup])

  return (
    <>
      <PageHeader eyebrow={t.track.eyebrow} title={t.track.title} lead={t.track.lead} />

      <Section className="rhythm-section">
        <div className="grid gap-5 lg:grid-cols-[380px_1fr] lg:items-start">
          <Reveal className="surface p-6 lg:sticky lg:top-[88px]">
            <form
              className="space-y-4"
              onSubmit={(event) => {
                event.preventDefault()
                void lookup(publicRef, email)
              }}
            >
              <Field label={t.track.reference} required>
                {(props) => (
                  <Input
                    {...props}
                    value={publicRef}
                    placeholder="GFS-26-XXXXXXXX"
                    autoComplete="off"
                    className="tnum"
                    onChange={(e) => setPublicRef(e.target.value.toUpperCase())}
                  />
                )}
              </Field>
              <Field label={t.track.email} required>
                {(props) => (
                  <Input
                    {...props}
                    type="email"
                    value={email}
                    placeholder="you@example.com"
                    autoComplete="email"
                    onChange={(e) => {
                      touchedEmail.current = true
                      setEmail(e.target.value)
                    }}
                  />
                )}
              </Field>
              {error && <Alert tone="warn">{error}</Alert>}
              {/* A real submit button inside a real form: Enter works from either
                  field, which is how anyone actually uses a two-box lookup. */}
              <Button type="submit" full loading={loading} disabled={!publicRef || !email}>
                {t.track.find}
              </Button>
            </form>
          </Reveal>

          {order ? (
            <OrderView order={order} signedIn={!!account} onSubmitted={setOrder} />
          ) : (
            <div className="plate grid min-h-[300px] place-items-center p-10 text-center">
              <div>
                <span
                  aria-hidden="true"
                  className="mx-auto mb-5 block h-px w-10 bg-brand-500"
                />
                <p className="measure-tight mx-auto text-body-sm leading-relaxed text-chalk-faint">
                  {t.track.emptyHint}
                </p>
              </div>
            </div>
          )}
        </div>
      </Section>
    </>
  )
}

export function OrderView({
  order,
  signedIn = false,
  onSubmitted,
}: {
  order: Order
  /*
    Both optional, and both defaulting to the read-only view. This component is
    exported, so a future caller that only wants to display an order gets the alert
    rather than a credential form wired to nothing.
  */
  signedIn?: boolean
  onSubmitted?: (order: Order) => void
}) {
  const t = useT()
  const labels = useCatalogLabels()
  return (
    <Reveal direction="left" className="plate ticks overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-ink-400/50
                      bg-paper px-6 py-4">
        <div>
          <p className="tnum display text-display-sm text-chalk">{order.publicRef}</p>
          <p className="mt-0.5 text-[12.5px] text-chalk-faint">{order.serviceLabel}</p>
        </div>
        <Badge tone={statusTone(order.status)}>{order.statusLabel}</Badge>
      </div>

      <div className="space-y-7 p-6">
        <NextAction order={order} signedIn={signedIn} onSubmitted={onSubmitted} />

        <SupplierProgress order={order} />

        <dl className="grid gap-px overflow-hidden rounded-edge bg-ink-400 sm:grid-cols-3">
          <Detail label={t.track.total} value={order.totalFormatted} />
          <Detail label={t.track.placed} value={dateTime(order.createdAt)} />
          <Detail
            label={t.track.deliveryMethod}
            value={order.deliveryMethod === 'COMFORT_TRADE' ? 'Comfort trade' : 'Transfer market'}
          />
        </dl>

        {order.lines.length > 0 && (
          <div>
            <p className="stamp mb-4">{t.track.breakdown}</p>
            <dl className="divide-y divide-ink-400/70 border-y border-ink-400/70">
              {order.lines.map((line) => (
                <div key={line.code} className="flex justify-between gap-4 py-2.5 text-[13px]">
                  <dt className="text-chalk-muted">{labels.line(line, order)}</dt>
                  <dd className={`tnum ${line.amountMinor < 0 ? 'text-ok' : 'text-chalk'}`}>
                    {line.amountFormatted}
                  </dd>
                </div>
              ))}
            </dl>
          </div>
        )}

        <div>
          <p className="stamp mb-4">{t.track.history}</p>
          {/*
            A timeline with a real spine.

            The events were previously separate rows each with its own dot, which
            reads as a bulleted list — three things that happened, in no particular
            relation. A continuous rule down the left, with the most recent event
            marked in brand and the rest hollow, says these are stages of one thing
            and shows where it has got to.
          */}
          <ol className="relative space-y-4 pl-6">
            <span
              aria-hidden="true"
              className="absolute bottom-2 left-[3.5px] top-2 w-px bg-gradient-to-b
                         from-brand-500/60 via-ink-300 to-ink-400"
            />
            {order.timeline.map((event, index) => (
              <li key={index} className="relative">
                <span
                  aria-hidden="true"
                  className={[
                    'absolute -left-6 top-[5px] h-2 w-2 rounded-full',
                    index === 0
                      ? 'bg-brand-500 shadow-[0_0_0_3px_theme(colors.red.ring)]'
                      : 'border border-ink-300 bg-ink',
                  ].join(' ')}
                />
                <p className="text-[13px] text-chalk">{event.reason ?? event.toStatus}</p>
                <p className="text-[11.5px] text-chalk-faint">{dateTime(event.at)}</p>
              </li>
            ))}
          </ol>
        </div>
      </div>
    </Reveal>
  )
}

/**
 * What happens next, taken straight from the server.
 *
 * The API computes `nextAction`, and the UI renders it. The alternative — deciding
 * in the browser, from the status, what the customer should do — means two copies
 * of the state machine that slowly stop agreeing, and a customer being shown a
 * "submit your sign-in" form on an order that was delivered yesterday.
 */
function NextAction({
  order,
  signedIn,
  onSubmitted,
}: {
  order: Order
  signedIn: boolean
  onSubmitted?: (order: Order) => void
}) {
  const t = useT()
  switch (order.nextAction) {
    case 'PAY':
      return <Alert tone="warn" title={t.track.payTitle}>{t.track.payBody}</Alert>
    case 'SUBMIT_CREDENTIALS':
      /*
        The form, not a notice about the form.

        This branch used to render an alert reading "sign in to your account to submit
        it securely" — and there was nowhere to submit it. The backend had the vault,
        the endpoint and the purge job; the storefront never grew the field, so the
        instruction was a dead end. Signed out, the alert is still the right answer,
        because the endpoint requires an owned order.
      */
      if (!signedIn) {
        return (
          <Alert tone="brand" title={t.track.credentialsTitle}>
            {t.track.credentialsBody}
          </Alert>
        )
      }
      if (order.credentialsSubmitted) {
        return <Alert tone="ok" title={t.track.credDone}>{t.track.credDoneBody}</Alert>
      }
      return <CredentialForm publicRef={order.publicRef} onSubmitted={onSubmitted ?? (() => {})} />
    case 'CONTACT_SUPPORT':
      /*
        Suppressed when the supplier told us exactly what is wrong.

        This generic line lists the three usual causes and says to get in touch. Beside
        SupplierProgress's specific instruction it was both redundant and contradictory —
        "get in touch" under a message that ends "we will retry automatically". The
        specific one wins; this stays for holds we have no supplier detail for.
      */
      if (order.customerAction) return null
      return <Alert tone="warn" title={t.track.stuckTitle}>{t.track.stuckBody}</Alert>
    case 'ROTATE_PASSWORD':
      return (
        <Alert tone="ok" title={t.track.deliveredTitle}>
          {t.track.deliveredBody(dateTime(order.guaranteeExpiresAt))}
        </Alert>
      )
    case 'AWAIT_REVIEW':
      return <Alert tone="neutral" title={t.track.reviewTitle}>{t.track.reviewBody}</Alert>
    default:
      return null
  }
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-paper p-4">
      <dt className="text-[10.5px] uppercase tracking-[0.14em] text-chalk-faint">{label}</dt>
      <dd className="tnum mt-1.5 text-[14px] font-semibold text-chalk">{value}</dd>
    </div>
  )
}

/**
 * The colour a status wears, grouped by what it asks of the person reading it.
 *
 * <p>Every status used to resolve to red or grey, and since {@code brand} and {@code
 * warn} are both red, an order list came out as a column of near-identical pink chips
 * that had to be read word by word. The grouping below is by <em>required response</em>
 * rather than by stage, because that is the question someone actually has when they
 * scan this column — is this mine to do something about?
 *
 * <p>Shared with the customer's own order view on purpose. "In progress" reading as
 * calm and "on hold" reading as attention is not an operator convenience; it is more
 * honest to a customer than the two arriving in the same red.
 */
export function statusTone(status: string): BadgeTone {
  // Finished, and nothing follows.
  if (status === 'COMPLETED' || status === 'DELIVERED') return 'ok'
  // Stopped, and it takes a person to restart it.
  if (status === 'ON_HOLD' || status === 'DISPUTED') return 'attention'
  // Closed. Kept for the record, but nothing will happen to it.
  if (status === 'REFUNDED' || status === 'ABANDONED' || status === 'CREDITED') return 'neutral'
  // Moving on its own — visible, but not a call to do anything.
  if (status === 'IN_PROGRESS' || status === 'PAID'
      || status === 'DELIVERED_AWAITING_GUARANTEE') return 'info'
  // Everything left is waiting on somebody: pay, sign in, or pick it up.
  return 'brand'
}

/**
 * What the supplier is actually doing, while it is doing it.
 *
 * <p>Two things, and only when they are true. A progress bar once coins start landing —
 * the supplier reports delivered and ordered on every poll, and a number that moves is the
 * whole reason to poll onto our own page instead of linking the customer out to theirs.
 * And, when an order is stuck, the one sentence that unsticks it.
 */
function SupplierProgress({ order }: { order: Order }) {
  const t = useT()

  const action = order.customerAction
  const done = order.deliveredCoins
  const total = order.orderedCoins
  const showBar = done != null && total != null && total > 0

  if (!showBar && !action) return null

  return (
    <div className="space-y-4">
      {showBar && (
        <div className="surface p-5">
          <div className="flex items-baseline justify-between gap-3">
            <p className="text-[13px] font-semibold text-chalk">{t.track.progressTitle}</p>
            <p className="tnum text-[13px] text-chalk-muted">
              {t.track.progressOf(coins(done!), coins(total!))}
            </p>
          </div>
          {/*
            A real progress element, not a styled div. Screen readers announce the value
            without us inventing an aria-live region, and it degrades to something
            meaningful if the CSS never arrives.
          */}
          <div
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={total!}
            aria-valuenow={Math.min(done!, total!)}
            aria-label={t.track.progressTitle}
            className="mt-3 h-2 w-full overflow-hidden rounded-full bg-ink-500"
          >
            <div
              className="h-full rounded-full bg-ok-solid transition-[width] duration-700 ease-out-expo"
              style={{ width: `${Math.min(100, Math.round((done! / total!) * 100))}%` }}
            />
          </div>
        </div>
      )}

      {action && (
        /*
          Tone follows who can fix it. The three that the customer cannot act on are the
          ones where a cheerful "here is what to do" would be a lie.
        */
        <Alert tone={action === 'SUPPLIER_SIDE' ? 'neutral' : 'warn'}>
          {t.track.action[action]}
        </Alert>
      )}
    </div>
  )
}

/** Coins read as 1.5M / 250K, never as 1500000. */
function coins(thousands: number): string {
  const raw = thousands * 1000
  if (raw >= 1_000_000) {
    const m = raw / 1_000_000
    return `${m % 1 === 0 ? m : m.toFixed(1)}M`
  }
  return `${Math.round(raw / 1000)}K`
}

