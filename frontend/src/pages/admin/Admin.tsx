import { PageHeader } from '../../components/PageHeader'
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Badge, Button, ButtonLink, EmptyState, Input, Section, Select, Skeleton } from '../../components/ui'
import { api } from '../../lib/api'
import { dateTime } from '../../lib/format'
import { useSeo } from '../../lib/seo'
import type { AdminStats, OrderSummary } from '../../lib/types'
import { statusTone } from '../Track'

/**
 * Every status an order can be in, in lifecycle order.
 *
 * <p>This list used to cover seven of the thirteen states, and the six it omitted
 * included AWAITING_PAYMENT — where unpaid orders sit, and therefore where most of
 * the queue lives on any normal day. There was no way to filter to them at all.
 *
 * <p>"Waiting on customer" was also ambiguous enough to be wrong: it means waiting for
 * an EA sign-in, not waiting for payment, and an operator reading it the other way
 * would conclude the queue was broken when it was answering correctly.
 */
const QUEUE_FILTERS = [
  ['', 'Everything'],
  ['AWAITING_PAYMENT', 'Awaiting payment'],
  ['PAID', 'Just paid'],
  ['CREDENTIALS_PENDING', 'Waiting for sign-in'],
  ['READY_FOR_DELIVERY', 'Ready to work'],
  ['IN_PROGRESS', 'In progress'],
  ['ON_HOLD', 'On hold'],
  ['DELIVERED', 'Delivered'],
  ['DISPUTED', 'Disputed'],
  ['COMPLETED', 'Completed'],
  ['REFUNDED', 'Refunded'],
  ['CREDITED', 'Credited'],
  ['ABANDONED', 'Abandoned'],
] as const

/** The human label for a status value, for use in messages about the active filter. */
function filterLabel(value: string): string {
  return QUEUE_FILTERS.find(([v]) => v === value)?.[1] ?? value
}

/**
 * The operations console.
 *
 * <p>This is the screen the business lives in, so it gets more care than the
 * homepage does. The default view is the work queue rather than a chart: the first
 * question every morning is "what needs doing", not "how are we trending".
 */
export default function Admin() {
  useSeo({ title: 'Operations', noindex: true })

  const [stats, setStats] = useState<AdminStats | null>(null)
  const [orders, setOrders] = useState<OrderSummary[] | null>(null)
  const [status, setStatus] = useState('')
  const [search, setSearch] = useState('')
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const query = new URLSearchParams()
      if (status) query.set('status', status)
      if (search.trim()) query.set('search', search.trim())
      const [nextStats, nextOrders] = await Promise.all([
        api.get<AdminStats>('/api/v1/admin/orders/stats'),
        api.get<OrderSummary[]>(`/api/v1/admin/orders?${query.toString()}`),
      ])
      setStats(nextStats)
      setOrders(nextOrders)
      setError(null)
    } catch {
      setError('Could not load the queue. Check the API is reachable.')
      // Clear the rows as well as setting the error. Leaving `orders` null kept the
      // loading skeleton on screen underneath the error banner, so a failed request
      // looked exactly like a slow one — and an empty filter result looked like both.
      setOrders([])
    }
  }, [status, search])

  useEffect(() => {
    void load()
    // Cheap polling rather than websockets. At this volume it is the right amount
    // of engineering, and it keeps the queue current while somebody is watching it.
    const timer = setInterval(() => void load(), 20_000)
    return () => clearInterval(timer)
  }, [load])

  return (
    <>
      <PageHeader eyebrow="Operations" title="Order queue" intensity={0.3} />
      {/*
        The wide measure, which this page should always have had.
        `max-w-6xl` is the reading width for prose. This is a seven-column queue an
        operator scans sideways, and at the narrower measure the table overflowed its
        own wrapper by ~190px — so the two columns that answer "what is it" and "when"
        sat permanently off the right edge behind a scrollbar.
      */}
      <Section wide className="rhythm-section">
      {error && <Alert tone="warn">{error}</Alert>}

      {stats && (
        /*
         * One instrument panel, not six cards.
         *
         * A row of separately-bordered tiles gives an operator six frames to look
         * past before reaching six numbers. Butting the cells together against a
         * hairline grid removes the frames entirely — the eye lands straight on the
         * figures, and a console that gets read every few minutes all day should
         * cost as little to read as possible.
         */
        <dl className="mb-5 grid grid-cols-2 gap-px overflow-hidden rounded-edge bg-ink-400
                       shadow-sm ring-1 ring-ink-400 sm:grid-cols-3 lg:grid-cols-6">
          <Stat label="Ready to work" value={stats.readyForDelivery} accent />
          {/* Same wording as the filter. A tile and a filter counting the same thing
              under two different names is how an operator concludes one of them lies. */}
          <Stat label="Waiting for sign-in" value={stats.credentialsPending} />
          <Stat label="In progress" value={stats.inProgress} />
          <Stat label="On hold" value={stats.onHold} warn={stats.onHold > 0} />
          <Stat label="Disputed" value={stats.disputed} warn={stats.disputed > 0} />
          <Stat label="Sign-ins held" value={stats.credentialsHeld} />
        </dl>
      )}

      {stats && (
        /*
         * White, not the recessed plate, and the figure sized like a figure.
         *
         * This is the one number on the page a person might actually want to look at,
         * and it was set two steps down from the six queue counters beside it — a grey
         * band with small type reads as a caption for the panel above rather than as
         * the day's revenue. It sits on the same white as the instrument panel now,
         * with the amount at display-lg so the hierarchy matches the importance.
         */
        <div className="surface mb-6 flex flex-wrap items-center justify-between gap-4 p-5">
          <div>
            <p className="stamp">Revenue, last 30 days</p>
            <p className="tnum display mt-2.5 text-display-lg text-chalk">
              {stats.revenueLast30dFormatted}
            </p>
          </div>
          <p className="max-w-md text-[12px] leading-relaxed text-chalk-faint">
            Counts delivered and completed orders. Orders inside their guarantee window are
            included — the money is taken, but the loyalty points have not settled yet.
          </p>
        </div>
      )}

      <div className="plate mb-5 flex flex-wrap items-center gap-3 p-3">
        <Select value={status} onChange={(e) => setStatus(e.target.value)} className="max-w-[240px]">
          {QUEUE_FILTERS.map(([value, label]) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </Select>
        <Input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Reference or email"
          className="max-w-[280px]"
        />
        <Button variant="secondary" onClick={() => void load()}>Refresh</Button>
        {/*
          Coupons is the only thing on this bar that leaves the page, so it is the only
          thing on it drawn as a destination rather than a control. Refresh stays
          secondary: it acts on the view you are already looking at.
        */}
        <ButtonLink to="/admin/coupons" variant="primary" size="md">Coupons</ButtonLink>
      </div>

      {!orders && <Skeleton className="h-64 w-full" />}
      {/*
        Name the filter that found nothing. "Nothing in this view" is true but useless:
        it reads identically whether the queue is genuinely clear, the filter is too
        narrow, or the request failed — and an operator staring at a blank panel will
        assume the last one every time.
      */}
      {orders?.length === 0 && !error && (
        <EmptyState
          title={
            status
              ? `No orders are ${filterLabel(status).toLowerCase()}`
              : 'No orders yet'
          }
        >
          {status ? (
            <>
              Nothing is in that state right now.{' '}
              <button
                type="button"
                onClick={() => setStatus('')}
                className="text-brand-400 underline hover:text-brand-600"
              >
                Show every order
              </button>{' '}
              to see the whole queue.
            </>
          ) : (
            'When a customer places one it will appear here.'
          )}
        </EmptyState>
      )}

      {orders && orders.length > 0 && (
        <div className="surface overflow-x-auto">
          <table className="w-full min-w-[1060px] text-left text-sm">
            {/*
              The header row stays put while the queue scrolls. On a list that runs to
              a couple of hundred orders, column headings that scroll away mean an
              operator has to remember which column is "Total" and which is "Placed" —
              and the two are adjacent and both numeric.
            */}
            <thead className="sticky top-0 z-10 border-b border-ink-300 bg-ink-500">
              <tr className="text-[11.5px] uppercase tracking-wider text-chalk-faint">
                <th scope="col" className="w-[164px] px-4 py-3.5 font-semibold">Reference</th>
                <th scope="col" className="w-[230px] px-4 py-3.5 font-semibold">Service</th>
                <th scope="col" className="w-[190px] px-4 py-3.5 font-semibold">Customer</th>
                <th scope="col" className="w-[176px] px-4 py-3.5 font-semibold">Method</th>
                <th scope="col" className="w-[100px] px-4 py-3.5 font-semibold">Total</th>
                <th scope="col" className="w-[196px] px-4 py-3.5 font-semibold">Status</th>
                <th scope="col" className="w-[128px] px-4 py-3.5 font-semibold">Placed</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-400">
              {orders.map((order) => (
                <tr key={order.publicRef} className="transition-colors hover:bg-brand-500/[0.045]">
                  <td className="px-4 py-3.5">
                    <Link
                      to={`/admin/orders/${order.publicRef}`}
                      className="tnum whitespace-nowrap font-semibold text-brand-400 hover:underline"
                    >
                      {order.publicRef}
                    </Link>
                  </td>
                  <td className="px-4 py-3.5 text-chalk-muted">{order.serviceLabel}</td>
                  <td className="px-4 py-3.5 text-chalk-muted">{order.customerEmail ?? '—'}</td>
                  {/*
                    Method and the held-sign-in flag on one line that cannot break.
                    Inline, the badge wrapped beneath the word on exactly the rows that
                    had one — so the single most important row in the queue was also the
                    only one a different height, and the eye read that as a rendering
                    fault rather than as a flag.
                  */}
                  <td className="whitespace-nowrap px-4 py-3.5">
                    <span className="text-[12.5px] text-chalk-muted">
                      {order.deliveryMethod === 'COMFORT_TRADE' ? 'Comfort' : 'Auction'}
                    </span>
                    {order.credentialsHeld && (
                      <Badge tone="brand" className="ml-2">Sign-in held</Badge>
                    )}
                  </td>
                  <td className="tnum whitespace-nowrap px-4 py-3.5 font-semibold text-chalk">
                    {order.totalFormatted}
                  </td>
                  {/*
                    The same words the filter uses, not the raw enum.
                    The dropdown above offers "Waiting for sign-in" and this column used to
                    answer "CREDENTIALS PENDING" — the two naming one state differently is
                    exactly the thing the tile labels were already careful to avoid, and it
                    put the database's vocabulary in front of someone who never chose it.
                  */}
                  <td className="whitespace-nowrap px-4 py-3.5">
                    <Badge tone={statusTone(order.status)}>{filterLabel(order.status)}</Badge>
                  </td>
                  <td className="whitespace-nowrap px-4 py-3.5 text-[12.5px] text-chalk-faint">
                    {dateTime(order.createdAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      </Section>
    </>
  )
}

/**
 * One counter in the instrument panel.
 *
 * <p><b>A zero is drawn quieter than a number.</b> Every count used to render at the
 * same weight, so six empty queues shouted exactly as loud as six full ones and the
 * operator had to read all six to learn there was nothing to do. Muting the zeros
 * inverts that: a quiet console means quiet, and any queue with work in it is the only
 * thing on the row with contrast. This is the panel's whole job — it is read every few
 * minutes all day, and most of those reads should end in under a second.
 *
 * <p>The rule along the top edge appears only on the two states that mean <em>act
 * now</em> — the ready queue, and anything alarmed. In-progress and waiting counts are
 * context, not instructions, and giving them a marker too would spend the signal.
 */
function Stat({
  label, value, accent = false, warn = false,
}: {
  label: string
  value: number
  accent?: boolean
  warn?: boolean
}) {
  const alarmed = warn && value > 0
  const idle = value === 0
  const flagged = !idle && (accent || alarmed)
  return (
    /*
     * The alarm tint is opaque, not `bg-warn/[0.06]`.
     *
     * The cells are butted against a grid whose gaps are painted by the parent's own
     * background, so a translucent tint here composites over that grey rather than over
     * the white it was mixed against — the pale red arrives as a muddy pink. This is the
     * same 6% of warn over white, resolved once instead of at paint time.
     */
    <div className={`relative p-4 ${alarmed ? 'bg-[#FAF2F1]' : 'bg-paper'}`}>
      {flagged && (
        <span
          aria-hidden="true"
          className={`absolute inset-x-0 top-0 h-[3px] ${alarmed ? 'bg-warn' : 'bg-brand-500'}`}
        />
      )}
      <dt className="text-[10.5px] font-semibold uppercase tracking-[0.14em] text-chalk-faint">
        {label}
      </dt>
      <dd
        className={`tnum display mt-2 text-[28px] leading-none ${
          idle ? 'text-chalk-faint' : alarmed ? 'text-warn' : accent ? 'text-brand-500' : 'text-chalk'
        }`}
      >
        {value}
      </dd>
    </div>
  )
}
