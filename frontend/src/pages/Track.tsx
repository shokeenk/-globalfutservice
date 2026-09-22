import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { CredentialForm } from '../components/CredentialForm'
import { PageHeader } from '../components/PageHeader'
import { Alert, Badge, Button, ButtonLink, Field, Input, Section, Skeleton } from '../components/ui'
import type { BadgeTone } from '../components/ui'
import { useT } from '../i18n'
import { useCatalogLabels } from '../content/catalogLabels'
import { ApiError, api } from '../lib/api'
import { ticketLink } from '../lib/discordTicket'
import { dateTime } from '../lib/format'
import { useSeo } from '../lib/seo'
import type { MyCoaching, Order, OrderSummary } from '../lib/types'
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
   * placed?") there is nothing left to type.
   */
  const [publicRef, setPublicRef] = useState(() => (params.get('ref') ?? '').toUpperCase())
  const [email, setEmail] = useState(account?.email ?? '')
  const [order, setOrder] = useState<Order | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /*
   * A signed-in customer gets their orders, not a box asking for a reference.
   *
   * The reference lookup is what a guest has -- no account, no list to show -- so it
   * stays, one click away, rather than being the front door for everybody. Somebody who
   * has just paid and wants to know where the order is should not have to find a
   * reference to ask about an order we already know is theirs.
   */
  const signedIn = !!account
  const [mine, setMine] = useState<OrderSummary[] | null>(null)
  const [tab, setTab] = useState<Tab>('ALL')
  const [showLookup, setShowLookup] = useState(false)

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

  /** One order, by reference, for a customer who owns it. */
  const openOrder = useCallback(async (ref: string) => {
    setLoading(true)
    setError(null)
    try {
      setOrder(await api.get<Order>(`/api/v1/orders/${encodeURIComponent(ref)}`))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'We could not open that order.')
    } finally {
      setLoading(false)
    }
  }, [])

  const loadMine = useCallback(() => {
    if (!signedIn) return
    api.get<OrderSummary[]>('/api/v1/orders').then(setMine).catch(() => setMine([]))
  }, [signedIn])

  useEffect(loadMine, [loadMine])

  // The session resolves after first paint, so pick the email up when it lands —
  // but never overwrite something the visitor has already typed.
  const touchedEmail = useRef(false)
  useEffect(() => {
    if (account?.email && !touchedEmail.current && !email) setEmail(account.email)
  }, [account?.email, email])

  /*
   * Auto-open, exactly once, when the page was opened pointing at an order. Signed in
   * that is a plain fetch; as a guest it needs the email as well, which the session
   * supplies or the form asks for.
   */
  const autoRan = useRef(false)
  useEffect(() => {
    const ref = params.get('ref')
    if (autoRan.current || !ref) return
    // Keyed on `account` itself rather than the boolean, so the guest branch still
    // knows the session may carry an email.
    if (account) {
      autoRan.current = true
      void openOrder(ref)
    } else if (email.trim()) {
      autoRan.current = true
      void lookup(ref, email)
    }
  }, [params, account, email, lookup, openOrder])

  /*
   * The page keeps itself current.
   *
   * An order moves because somebody else acted -- an operator verified a payment, a
   * trader finished. Polling every 20 seconds, and again whenever the tab is brought
   * back to the front, means the customer never has to wonder whether what they are
   * looking at is still true. Stops once the order is finished, which is the point
   * after which nothing changes.
   */
  useEffect(() => {
    if (!order || ['COMPLETED', 'ABANDONED', 'REFUNDED', 'CREDITED'].includes(order.status)) return
    const ref = order.publicRef
    const refresh = () => {
      if (signedIn) {
        api.get<Order>(`/api/v1/orders/${encodeURIComponent(ref)}`).then(setOrder).catch(() => {})
      } else if (email.trim()) {
        api.post<Order>('/api/v1/orders/track', { publicRef: ref, email: email.trim() })
          .then(setOrder).catch(() => {})
      }
    }
    const timer = window.setInterval(refresh, 20_000)
    const onFocus = () => refresh()
    window.addEventListener('focus', onFocus)
    return () => { window.clearInterval(timer); window.removeEventListener('focus', onFocus) }
  }, [order, signedIn, email])

  const visible = (mine ?? []).filter((row) => {
    if (tab === 'ALL') return true
    if (tab === 'COMPLETED') return row.status === 'COMPLETED' || row.status === 'DELIVERED'
    if (tab === 'TRADING') return row.sku === 'TRADING_SERVICE'
    if (tab === 'COACHING') return row.sku === 'COACHING'
    return row.sku.startsWith('BOOST_')
  })

  const lookupForm = (
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
      {/* A real submit button inside a real form: Enter works from either field. */}
      <Button type="submit" full loading={loading} disabled={!publicRef || !email}>
        {t.track.find}
      </Button>
    </form>
  )

  /* ---------------------------------------------------------- signed in ------ */

  if (signedIn) {
    return (
      <>
        <PageHeader eyebrow={t.track.eyebrow} title={t.track.myOrdersTitle} lead={t.track.myOrdersLead} />

        <Section className="rhythm-section">
          {order ? (
            <div className="space-y-5">
              <button
                type="button"
                onClick={() => { setOrder(null); loadMine() }}
                className="inline-flex items-center gap-2 text-body-sm font-semibold text-brand-400
                           hover:underline focus-visible:outline focus-visible:outline-2
                           focus-visible:outline-offset-2 focus-visible:outline-brand-400"
              >
                <span aria-hidden="true">&larr;</span> {t.track.backToOrders}
              </button>
              <OrderView order={order} signedIn onSubmitted={setOrder} />
            </div>
          ) : (
            <div className="space-y-5">
              <OrderTabs current={tab} onTab={setTab} />

              {error && <Alert tone="warn">{error}</Alert>}

              {!mine && <Skeleton className="h-40 w-full" />}

              {mine && visible.length === 0 && (
                <div className="plate grid min-h-[220px] place-items-center p-10 text-center">
                  <p className="text-body-sm text-chalk-faint">
                    {mine.length === 0 ? t.track.noOrdersYet : t.track.noOrdersInTab}
                  </p>
                </div>
              )}

              {visible.length > 0 && (
                <ul className="space-y-3">
                  {visible.map((row) => (
                    <OrderRow key={row.publicRef} row={row} onOpen={() => void openOrder(row.publicRef)} />
                  ))}
                </ul>
              )}

              <div className="pt-2">
                <button
                  type="button"
                  onClick={() => setShowLookup((on) => !on)}
                  className="text-[12.5px] font-semibold text-chalk-muted underline-offset-2 hover:underline"
                >
                  {showLookup ? t.track.guestLookupClose : t.track.guestLookupOpen}
                </button>
                {showLookup && <div className="surface mt-3 max-w-md p-6">{lookupForm}</div>}
              </div>
            </div>
          )}
        </Section>
      </>
    )
  }

  /* ------------------------------------------------------------- guests ------ */

  return (
    <>
      <PageHeader eyebrow={t.track.eyebrow} title={t.track.title} lead={t.track.lead} />

      <Section className="rhythm-section">
        <div className="grid gap-5 lg:grid-cols-[380px_1fr] lg:items-start">
          <Reveal className="surface p-6 lg:sticky lg:top-[88px]">{lookupForm}</Reveal>

          {order ? (
            <OrderView order={order} signedIn={false} onSubmitted={setOrder} />
          ) : (
            <div className="plate grid min-h-[300px] place-items-center p-10 text-center">
              <div>
                <span aria-hidden="true" className="mx-auto mb-5 block h-px w-10 bg-brand-500" />
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

/**
 * Where the conversation about this order happens.
 *
 * <p>Shown once the money is in and while there is still work to do -- which is exactly
 * the window in which a customer has something to say to the team and the team has
 * something to ask. Before payment the next action is to pay; after completion there is
 * nothing to coordinate, and a live "talk to us" panel on a finished order invites
 * questions the page has already answered.
 *
 * <p><b>Three panels, decided by the server.</b> Which one a customer sees depends on
 * whether we know their Discord account, and the storefront is deliberately not the place
 * that works that out -- it does not know how they signed in and should not learn.
 *
 * <ul>
 *   <li><b>DIRECT</b> -- they are already in the channel, so link straight to it.</li>
 *   <li><b>PENDING</b> -- they signed in with Discord and will be let in the moment the
 *       ticket opens. Nothing to do, and saying so beats an instruction they do not
 *       need.</li>
 *   <li><b>VERIFY</b> -- most people. Join the server, run the command, get let in.</li>
 * </ul>
 *
 * <p>A link to a channel is only ever offered to somebody who has actually been granted
 * it. Sending the rest to a channel they cannot see would read as the site being broken,
 * which is what the old shared-channel fallback was working around.
 */
/**
 * The sessions this coaching order paid for.
 *
 * <p>Scoped to the order being looked at, not the account. A customer with two packs
 * open would otherwise see one order's page listing the other order's sessions — which
 * is why the session carries its order reference at all.
 *
 * <p><b>Signed-in only, and that is not a gap.</b> The endpoint behind this is
 * account-scoped: it answers "your sessions" and has no guest form, because a booking
 * belongs to a person rather than to a reference somebody could have read off a
 * screenshot. A guest tracking by reference and email sees the rest of the page as
 * before, and the sessions once they sign in.
 *
 * <p>Times render in the zone the session was booked in, named. "19:00" without saying
 * whose 19:00 is worse than no time at all: the reader cannot tell whether it has been
 * converted for them, and the failure is somebody missing a session they meant to attend.
 */
function BookedSessions({ order, signedIn }: { order: Order; signedIn: boolean }) {
  const t = useT()
  const [mine, setMine] = useState<MyCoaching | null>(null)

  useEffect(() => {
    if (!signedIn || order.sku !== 'COACHING') return
    let stop = false
    api.get<MyCoaching>('/api/v1/coaching/me')
      .then((found) => { if (!stop) setMine(found) })
      // Silent: the sessions are a bonus on this page, and the order itself is the
      // thing the customer came for.
      .catch(() => { if (!stop) setMine(null) })
    return () => { stop = true }
  }, [signedIn, order.sku, order.publicRef])

  if (order.sku !== 'COACHING' || !signedIn || !mine) return null

  const forThisOrder = mine.upcoming.filter((s) => s.orderRef === order.publicRef)
  const booked = forThisOrder.length
  const total = booked + mine.creditBalance

  if (booked === 0 && mine.creditBalance === 0) return null

  return (
    <div className="rounded-panel border border-ink-400 bg-ink-700/30 p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-[13.5px] font-semibold text-chalk">{t.track.sessionsTitle}</p>
        <p className="text-[12.5px] text-chalk-faint">
          {t.track.sessionsBooked(booked, total)}
        </p>
      </div>

      {booked === 0 ? (
        <p className="mt-2 text-[12.5px] leading-relaxed text-chalk-muted">
          {t.track.sessionsNoneYet}
        </p>
      ) : (
        <ul className="mt-3 flex flex-col gap-1.5">
          {[...forThisOrder]
            .sort((a, b) => a.startsAt.localeCompare(b.startsAt))
            .map((s) => (
              <li key={s.ref}
                  className="flex flex-wrap items-baseline justify-between gap-2
                             border-t border-ink-400 pt-2 first:border-t-0 first:pt-0">
                <span className="tnum text-[13px] text-chalk">
                  {sessionWhen(s.startsAt, s.customerTimezone)}
                </span>
                <span className="text-[12px] text-chalk-faint">{s.coachName}</span>
              </li>
            ))}
        </ul>
      )}

      {mine.creditBalance > 0 && (
        <ButtonLink to="/coaching" variant="secondary" full size="md" className="mt-4">
          {t.track.sessionsBookMore}
        </ButtonLink>
      )}
    </div>
  )
}

/** The session time, in the zone it was booked in, with that zone named. */
function sessionWhen(iso: string, zone: string | null): string {
  const target = zone && zone.trim() !== '' ? zone : undefined
  try {
    const when = new Intl.DateTimeFormat('en-GB', {
      weekday: 'short', day: 'numeric', month: 'short',
      hour: '2-digit', minute: '2-digit', hour12: false,
      ...(target ? { timeZone: target } : {}),
    }).format(new Date(iso))
    return target ? `${when} (${target})` : when
  } catch {
    // An unrecognised zone string. Falling back to the browser's is better than
    // rendering nothing, and it is labelled so nobody assumes otherwise.
    return new Intl.DateTimeFormat('en-GB', {
      weekday: 'short', day: 'numeric', month: 'short',
      hour: '2-digit', minute: '2-digit', hour12: false,
    }).format(new Date(iso))
  }
}

function DiscordTicket({ order }: { order: Order }) {
  const t = useT()
  const [copied, setCopied] = useState(false)
  const open = ['PAID', 'CREDENTIALS_PENDING', 'READY_FOR_DELIVERY', 'IN_PROGRESS', 'ON_HOLD', 'DELIVERED']
    .includes(order.status)
  const access = order.discordAccess
  const ticket = ticketLink(access)
  if (!open || !access || !ticket) return null

  const copyCommand = async () => {
    if (!ticket?.command) return
    try {
      await navigator.clipboard.writeText(ticket.command)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard access is refused in some browsers and every insecure context. The
      // command is on screen and selectable, so this costs a convenience, not the step.
    }
  }

  return (
    <div className="rounded-panel border border-[#5865F2]/30 bg-[#5865F2]/[0.06] p-4">
      <div className="flex flex-wrap items-center gap-3">
        <span aria-hidden="true" className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-[#5865F2] text-paper">
          <svg viewBox="0 0 24 24" className="h-5 w-5" fill="currentColor">
            <path d="M20.3 4.4A19.8 19.8 0 0 0 15.4 3l-.24.5a18.3 18.3 0 0 1 4.3 1.4c-2-1.1-4.1-1.6-6.4-1.6-2.3 0-4.4.5-6.4 1.6A18.3 18.3 0 0 1 11 3.5L10.7 3a19.8 19.8 0 0 0-4.9 1.4C2.6 9.1 1.7 13.7 2.1 18.2a19.9 19.9 0 0 0 6 3c.5-.65.9-1.35 1.25-2.1-.7-.25-1.35-.55-1.95-.9.16-.12.32-.25.47-.38a14.2 14.2 0 0 0 12.2 0c.16.14.31.26.47.38-.62.36-1.27.66-1.96.9.36.75.78 1.45 1.25 2.1a19.8 19.8 0 0 0 6-3c.5-5.2-.85-9.75-3.5-13.8ZM8.7 15.4c-1.18 0-2.15-1.07-2.15-2.4S7.5 10.6 8.7 10.6s2.17 1.08 2.15 2.4c0 1.33-.96 2.4-2.15 2.4Zm6.6 0c-1.18 0-2.15-1.07-2.15-2.4s.95-2.4 2.15-2.4 2.17 1.08 2.15 2.4c0 1.33-.95 2.4-2.15 2.4Z" />
          </svg>
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[13.5px] font-semibold text-chalk">
            {access.mode === 'DIRECT' || access.mode === 'QUOTE' ? t.track.discordTicketTitle
              : access.mode === 'PENDING' ? t.track.discordPendingTitle
              : t.track.discordVerifyTitle}
          </p>
          <p className="mt-0.5 text-[12.5px] leading-relaxed text-chalk-muted">
            {access.mode === 'DIRECT' || access.mode === 'QUOTE' ? t.track.discordTicketBody
              : access.mode === 'PENDING' ? t.track.discordPendingBody
              : t.track.discordVerifyBody}
          </p>
        </div>
        {ticket?.direct && (
          <a
            href={ticket.href}
            target="_blank"
            rel="noreferrer"
            className="inline-flex h-10 shrink-0 items-center rounded-edge bg-brand-500 px-4 text-[12.5px]
                       font-semibold text-paper transition-colors duration-200 hover:bg-brand-400
                       focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                       focus-visible:outline-brand-400"
          >
            {t.track.discordOpenTicket}
          </a>
        )}
        {ticket && !ticket.direct && (
          <a
            href={ticket.href}
            target="_blank"
            rel="noreferrer"
            className="inline-flex h-10 shrink-0 items-center rounded-edge bg-brand-500 px-4 text-[12.5px]
                       font-semibold text-paper transition-colors duration-200 hover:bg-brand-400
                       focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                       focus-visible:outline-brand-400"
          >
            {access.mode === 'QUOTE' ? t.track.discordTicketCta : t.track.discordVerifyJoin}
          </a>
        )}
      </div>

      {/*
        The command, on its own line and copyable. It already has the reference in it:
        a customer assembling one from two places on the page is a customer who mistypes
        it, and every mistype is a failed attempt against their own rate limit.
      */}
      {access.mode === 'QUOTE' && (
        <p className="mt-2 text-[11.5px] text-chalk-faint">
          {t.track.discordTicketQuote(order.publicRef)}
        </p>
      )}

      {ticket?.command && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <code className="tnum min-w-0 flex-1 overflow-x-auto rounded-edge bg-ink-700/60 px-3 py-2
                           text-[12.5px] font-semibold text-chalk">
            {ticket.command}
          </code>
          <button
            type="button"
            onClick={() => { void copyCommand() }}
            className="inline-flex h-9 shrink-0 items-center rounded-edge border border-ink-300 px-3
                       text-[12px] font-semibold text-chalk-muted transition-colors duration-200
                       hover:text-chalk focus-visible:outline focus-visible:outline-2
                       focus-visible:outline-offset-2 focus-visible:outline-brand-400"
          >
            {copied ? t.track.discordVerifyCopied : t.track.discordVerifyCopy}
          </button>
        </div>
      )}
    </div>
  )
}

/**
 * The stages this order has not reached yet, in the order it will reach them.
 *
 * <p>Derived from what has already happened rather than from a fixed list: an order that
 * never needed a sign-in should not be shown a sign-in stage it will skip, and one that
 * is finished has nothing ahead of it.
 */
function upcomingStages(order: Order, t: ReturnType<typeof useT>): string[] {
  const done = new Set(order.timeline.map((event) => event.toStatus))
  const ahead: string[] = []
  if (!done.has('PAID')) ahead.push(t.track.stagePaymentVerified)
  if (!done.has('READY_FOR_DELIVERY')) ahead.push(t.track.stageQueued)
  if (!done.has('IN_PROGRESS')) ahead.push(t.track.stageInProgress)
  if (!done.has('COMPLETED') && !done.has('DELIVERED')) ahead.push(t.track.stageCompleted)
  return ['COMPLETED', 'ABANDONED', 'REFUNDED', 'CREDITED'].includes(order.status) ? [] : ahead
}

/* ------------------------------------------------------------- order list --- */

type Tab = 'ALL' | 'BOOSTING' | 'COACHING' | 'TRADING' | 'COMPLETED'

function OrderTabs({ current, onTab }: { current: Tab; onTab: (next: Tab) => void }) {
  const t = useT()
  const tabs: { id: Tab; label: string }[] = [
    { id: 'ALL', label: t.track.tabAll },
    { id: 'BOOSTING', label: t.track.tabBoosting },
    { id: 'COACHING', label: t.track.tabCoaching },
    { id: 'TRADING', label: t.track.tabTrading },
    { id: 'COMPLETED', label: t.track.tabCompleted },
  ]
  return (
    <div role="tablist" aria-label={t.track.myOrdersTitle} className="flex flex-wrap gap-2">
      {tabs.map((entry) => (
        <button
          key={entry.id}
          type="button"
          role="tab"
          aria-selected={current === entry.id}
          onClick={() => onTab(entry.id)}
          className={[
            'h-9 rounded-edge px-4 text-[12.5px] font-semibold transition-colors duration-200',
            current === entry.id
              ? 'bg-brand-500 text-paper'
              : 'bg-ink-700 text-chalk-muted hover:text-chalk',
          ].join(' ')}
        >
          {entry.label}
        </button>
      ))}
    </div>
  )
}

/**
 * One order, as a row: what it is, where it has got to, and the way in.
 *
 * <p>The service type is a coloured tag rather than a word in the title, because the
 * question a customer scanning this list is answering is "which of these is my coaching
 * order" — and that is a shape-and-colour question, not a reading one.
 */
function OrderRow({ row, onOpen }: { row: OrderSummary; onOpen: () => void }) {
  const t = useT()
  return (
    <li className="hairline flex flex-wrap items-center gap-x-4 gap-y-3 rounded-panel bg-paper p-4">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <ServiceTag sku={row.sku} />
          <p className="tnum text-[13.5px] font-semibold text-chalk">{row.publicRef}</p>
        </div>
        <p className="mt-1 text-[12.5px] text-chalk-muted">{row.serviceLabel}</p>
        <p className="mt-0.5 text-[11.5px] text-chalk-faint">{dateTime(row.createdAt)}</p>
      </div>

      <Badge tone={statusTone(row.status)}>{row.statusLabel}</Badge>

      <button
        type="button"
        onClick={onOpen}
        className="inline-flex items-center gap-1.5 text-[12.5px] font-semibold text-brand-400
                   hover:underline focus-visible:outline focus-visible:outline-2
                   focus-visible:outline-offset-2 focus-visible:outline-brand-400"
      >
        {t.track.viewDetails} <span aria-hidden="true">&rarr;</span>
      </button>
    </li>
  )
}

/** The service, as a coloured tag. Same three colours the operator console uses. */
export function ServiceTag({ sku }: { sku: string }) {
  const tone = sku === 'COACHING'
    ? 'border-deep/30 bg-deep/[0.08] text-deep'
    : sku.startsWith('BOOST_')
      ? 'border-brand-500/30 bg-brand-500/[0.08] text-brand-500'
      : 'border-sun-600/40 bg-sun-500/[0.14] text-sun-700'
  const label = sku === 'COACHING' ? 'COACHING' : sku.startsWith('BOOST_') ? 'BOOSTING' : 'TRADING'
  return (
    <span className={`rounded-edge border px-2 py-0.5 text-[10px] font-semibold tracking-[0.08em] ${tone}`}>
      {label}
    </span>
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

        <DiscordTicket order={order} />

        <BookedSessions order={order} signedIn={signedIn} />

        <SupplierProgress order={order} />

        <dl className="grid gap-px overflow-hidden rounded-edge bg-ink-400 sm:grid-cols-3">
          <Detail label={t.track.total} value={order.totalFormatted} />
          <Detail label={t.track.placed} value={dateTime(order.createdAt)} />
          {/*
            Named the way the checkout named it.

            This row used to print the enum's own words -- "Transfer market", "Comfort
            trade" -- while the checkout that sold the order called the same thing "GFS
            Trading Method 3.0 (Latest)". One order, two names, and the second one arrives
            after the money has gone. The enum still decides what happens; this decides
            what it is called, and it answers per service, because "Transfer market" on a
            boosting order was not a different name for the same thing but a wrong one.
          */}
          <Detail label={t.track.deliveryMethod} value={deliveryLabel(order, t)} />
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
          {/*
            The marker sits on the stage the order has actually reached.

            It used to sit on `index === 0`, and the events arrive oldest first — so the
            dot stayed on "Order created" while the order went through payment, checking
            and delivery. The bar looked stuck because it was reading the wrong end of
            the list.

            Stages the order has not reached yet are listed too, greyed and without a
            time. A timeline that ends at wherever the order happens to be answers
            "where is it?" but not "what happens next?", which is the question somebody
            opens this page to ask.
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
                    index === order.timeline.length - 1
                      ? 'bg-brand-500 shadow-[0_0_0_3px_theme(colors.red.ring)]'
                      : 'border border-ink-300 bg-ink',
                  ].join(' ')}
                />
                <p className="text-[13px] text-chalk">{event.reason ?? event.toStatus}</p>
                <p className="text-[11.5px] text-chalk-faint">{dateTime(event.at)}</p>
              </li>
            ))}
            {upcomingStages(order, t).map((stage) => (
              <li key={stage} className="relative">
                <span
                  aria-hidden="true"
                  className="absolute -left-6 top-[5px] h-2 w-2 rounded-full border border-ink-300 bg-ink"
                />
                <p className="text-[13px] text-chalk-faint">{stage}</p>
                <p className="text-[11.5px] text-chalk-faint">&mdash;</p>
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

/**
 * What this order's delivery is called, per service.
 *
 * <p>Coins are worked through the transfer market under the name the checkout uses.
 * Boosting is somebody playing the account, and coaching is a booked session -- neither
 * is a "method" of moving anything, and printing the coin wording on them was simply
 * inaccurate.
 */
function deliveryLabel(order: Order, t: ReturnType<typeof useT>): string {
  if (order.sku === 'COACHING') return t.track.deliveryCoaching
  if (order.sku.startsWith('BOOST_')) return t.track.deliveryBoosting
  return t.track.deliveryTrading
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

