import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Calendar, endOfMonth, startOfMonth } from '../components/Calendar'
import { PageHeader } from '../components/PageHeader'
import { Testimonials } from '../components/Testimonials'
import { Reveal } from '../motion/Reveal'
import {
  Alert,
  Badge,
  Button,
  ButtonLink,
  Card,
  EmptyState,
  Section,
  Skeleton,
  Spinner,
} from '../components/ui'
import { BrandRing } from '../brand/Ring'
import { useT } from '../i18n'
import { useCatalogLabels } from '../content/catalogLabels'
import { ApiError, api } from '../lib/api'
import { SEASON, useSeo } from '../lib/seo'
import type { CatalogOption, Coach, CoachSlots, CoachingSession, MyCoaching } from '../lib/types'
import { useAuth } from '../state/AuthContext'
import { useCatalog } from '../state/CatalogContext'

/**
 * The coaching storefront and booking calendar in one page.
 *
 * <p>The order the page presents things in matters. A visitor who has never bought
 * coaching sees the pricing first and the calendar not at all — showing an empty booking
 * grid to someone with no credits is an invitation to click through three steps and be
 * told no. A customer with credits sees the calendar first, because for them the only
 * remaining question is "when".
 */
export default function Coaching() {
  const t = useT()
  useSeo({
    title: t.coaching.seoTitle(SEASON),
    description: t.coaching.seoDescription(SEASON),
  })

  const { account } = useAuth()
  const { catalog } = useCatalog()

  const [coaches, setCoaches] = useState<Coach[] | null>(null)
  const [mine, setMine] = useState<MyCoaching | null>(null)
  const [error, setError] = useState<string | null>(null)

  const coachingService = catalog?.services.find((s) => s.sku === 'COACHING')

  useEffect(() => {
    let cancelled = false
    api
      .get<Coach[]>('/api/v1/coaching/coaches')
      .then((list) => !cancelled && setCoaches(list))
      .catch((e: unknown) => {
        if (!cancelled) {
          setCoaches([])
          setError(e instanceof ApiError ? e.message : t.coaching.loadCoachesFailed)
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  const loadMine = useCallback(() => {
    if (!account) {
      setMine(null)
      return
    }
    api
      .get<MyCoaching>('/api/v1/coaching/me')
      .then(setMine)
      .catch(() => setMine(null))
  }, [account])

  useEffect(loadMine, [loadMine])

  const credits = mine?.creditBalance ?? 0

  return (
    <>
      <PageHeader
        eyebrow={t.coaching.eyebrow(SEASON)}
        title={t.coaching.title}
        lead={t.coaching.lead}
        // The strongest light on the site after the homepage hero. Coaching is the
        // one product where the customer is buying a person's judgement rather than
        // a throughput, and the page has to look like it is worth that.
        intensity={0.75}
        aside={
          account && credits > 0 ? (
            /*
             * A credit balance is the single most useful thing this page can tell a
             * signed-in customer, so it is promoted out of the content flow and into
             * the masthead as a reading. Buried in an alert below the fold it was
             * routinely missed, and someone who does not know they have credits buys
             * a second block.
             */
            <div className="plate ticks px-6 py-5 text-center">
              <p className="tnum display text-[clamp(2.4rem,5vw,3rem)] leading-none text-chalk">
                {credits}
              </p>
              <p className="mt-2 text-[11px] uppercase tracking-[0.16em] text-chalk-faint">
                {t.coaching.creditsLeft(credits)}
              </p>
            </div>
          ) : undefined
        }
      />

      {(error || (account && credits > 0)) && (
        <Section className="pt-8">
          {error && <Alert tone="warn">{error}</Alert>}
          {account && credits > 0 && (
            <Alert tone="ok">
              {t.coaching.creditsAlertPrefix} <strong>{credits}</strong>{' '}
              {t.coaching.creditsLeft(credits)}{t.coaching.creditsAlertSuffix}
            </Alert>
          )}
        </Section>
      )}

      {/* Credits first for someone who has them; pricing first for everyone else. */}
      {account && credits > 0 ? (
        <>
          <BookingArea coaches={coaches} mine={mine} onBooked={loadMine} />
          <Pricing options={coachingService?.options} currency={catalog?.currency} secondary />
        </>
      ) : (
        <>
          <Pricing options={coachingService?.options} currency={catalog?.currency} />
          <CoachList coaches={coaches} />
        </>
      )}

      {mine && mine.upcoming.length > 0 && (
        <UpcomingSessions sessions={mine.upcoming} onChanged={loadMine} />
      )}

      {/* Coaching-only, and no filter — a reader on this page has already chosen
          the service, so offering them coin testimonials would be noise. */}
      <div className="border-t border-ink-400 bg-paper">
        <Testimonials only="coaching" />
      </div>
    </>
  )
}

/* ------------------------------------------------------------------ pricing ---------- */

function Pricing({
  options,
  currency,
  secondary = false,
}: {
  options: CatalogOption[] | undefined
  currency: string | undefined
  secondary?: boolean
}) {
  const t = useT()
  const labels = useCatalogLabels()
  /*
   * The block's saving badge.
   *
   * The block is six 40-minute sessions at Rs.750 each — a different product from
   * the single 1-hour session at Rs.1,000. The list price is therefore 750 × 6 =
   * Rs.4,500, and the block's Rs.4,050 represents a 10% saving on that.
   *
   * Because the block and single are different durations, the list price is derived
   * from the pack's own price (pack / 0.9) rather than from the single-session
   * rate card. If the pack price changes, the badge recalculates automatically.
   */
  const pack = options?.find((o) => o.variant === 'MONTHLY_6_SESSIONS')

  let listFormatted: string | null = null
  let savingPercent = 0
  if (pack && currency) {
    const listMinor = Math.round(pack.unitPriceMinor / 0.9)
    if (listMinor > pack.unitPriceMinor) {
      savingPercent = Math.round(((listMinor - pack.unitPriceMinor) / listMinor) * 100)
      listFormatted = new Intl.NumberFormat(undefined, {
        style: 'currency',
        currency,
        maximumFractionDigits: 0,
      }).format(listMinor / 100)
    }
  }

  return (
    <Section
      className={secondary ? 'rhythm-section' : 'pb-4 pt-8'}
      title={secondary ? t.coaching.needMore : undefined}
    >
      {/*
        A heading the eye does not need and the document structure does.

        In the primary layout this block deliberately has no visible title — the
        masthead above it has already said what the page is, and a second heading
        would be repetition. But the price cards below are `h3`, so without an `h2`
        here the page jumps h1 → h3, and anyone navigating by headings lands in a
        level that has no parent. One screen-reader-only element fixes the outline
        without putting a word back on screen.
      */}
      {!secondary && <h2 className="sr-only">{t.coaching.pricingHeading}</h2>}

      <div className="grid gap-4 sm:grid-cols-2">
        {!options && Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} className="h-44 w-full" />)}

        {options?.map((option, index) => {
          const isPack = option.variant === 'MONTHLY_6_SESSIONS'
          return (
            <Reveal key={option.variant ?? option.label} delay={index * 90}>
              {/*
                The block is the recommended purchase, so it is the one that is lit.
                A brand-tinted ground and a real border against a plain panel is a
                clearer recommendation than a "most popular" ribbon, and it does not
                cost a line of copy to say it.
              */}
              <div
                className={[
                  'relative flex h-full flex-col overflow-hidden rounded-panel border p-7',
                  /*
                    Both grounds are opaque. `bg-paper` was white at half strength,
                    which over the page composited to something within a percent of the
                    page itself — the card was carried entirely by its border. It also
                    means anything painted behind the section (the soft colour wash now
                    used on several bands) would show through unevenly and tint one card
                    differently from its neighbour.
                  */
                  isPack
                    ? 'border-gold-500/40 bg-[#FFFDF6] shadow-e3'
                    : 'border-ink-400 bg-paper shadow-e2',
                ].join(' ')}
              >
                {isPack && (
                  <div
                    aria-hidden="true"
                    className="pointer-events-none absolute -right-16 -top-16 h-48 w-48 rounded-full
                               bg-gold-500/10 blur-[70px]"
                  />
                )}

                {isPack && savingPercent > 0 && (
                  <Badge tone="gold" className="relative self-start">
                    {t.coaching.saveBadge(savingPercent)}
                  </Badge>
                )}

                <h3 className="display relative mt-4 text-display-md text-chalk">
                  {labels.option(option)}
                </h3>

                {/*
                  The list price sits above the real one rather than beside it.
                  Side by side at similar sizes, a struck-through figure and a live
                  figure read as two prices and the customer has to work out which
                  one applies. Stacked, with the live price three steps larger, the
                  hierarchy answers that before it is asked.
                */}
                <div className="relative mt-5">
                  {isPack && listFormatted && (
                    <p className="tnum text-body-sm text-chalk-faint line-through">
                      {listFormatted}
                    </p>
                  )}
                  <p className="tnum display text-[clamp(1.9rem,4vw,2.4rem)] leading-none text-chalk">
                    {option.unitPriceFormatted}
                  </p>
                </div>

                <p className="relative mt-5 flex-1 text-body-sm leading-relaxed text-chalk-muted">
                  {isPack ? t.coaching.packBody : t.coaching.singleBody}
                </p>

                <ButtonLink
                  to={`/order?service=COACHING&variant=${option.variant ?? ''}`}
                  className="relative mt-7"
                  full
                  size="md"
                  variant={isPack ? 'primary' : 'secondary'}
                >
                  {isPack ? t.coaching.buyBlock : t.coaching.buySession}
                </ButtonLink>
              </div>
            </Reveal>
          )
        })}
      </div>
    </Section>
  )
}

/* -------------------------------------------------------------------- coaches -------- */

function CoachList({ coaches }: { coaches: Coach[] | null }) {
  const t = useT()
  if (coaches && coaches.length === 0) {
    return null
  }
  return (
    <Section className="py-10" title={t.coaching.whoTitle}>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {!coaches && Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-48 w-full" />)}
        {coaches?.map((coach) => (
          <Card key={coach.id} className="p-6">
            <div className="flex items-center gap-3">
              {coach.avatarUrl ? (
                <img
                  src={coach.avatarUrl}
                  alt=""
                  className="h-12 w-12 rounded-full object-cover"
                />
              ) : (
                /*
                  Initials in the brand ring rather than a grey circle.

                  A coach without a photograph is the common case, and the fallback
                  is what most people will actually see. A plain disc says "missing
                  image"; the ring says the person belongs to this company.
                */
                <BrandRing size={48} strokeClassName="text-brand-500">
                  <span className="text-sm font-semibold text-chalk-muted">
                    {coach.displayName.slice(0, 2).toUpperCase()}
                  </span>
                </BrandRing>
              )}
              <div>
                <p className="font-semibold text-chalk">{coach.displayName}</p>
                {coach.headline && (
                  <p className="text-[13px] text-chalk-muted">{coach.headline}</p>
                )}
              </div>
            </div>
            {coach.bio && (
              <p className="mt-4 text-[14px] leading-relaxed text-chalk-muted">{coach.bio}</p>
            )}
            <dl className="mt-4 space-y-1 text-[13px] text-chalk-faint">
              {coach.credentials && (
                <div className="flex gap-2">
                  <dt className="text-chalk-muted">{t.coaching.peak}</dt>
                  <dd>{coach.credentials}</dd>
                </div>
              )}
              {coach.languages && (
                <div className="flex gap-2">
                  <dt className="text-chalk-muted">{t.coaching.speaks}</dt>
                  <dd>{coach.languages}</dd>
                </div>
              )}
            </dl>
          </Card>
        ))}
      </div>
    </Section>
  )
}

/* -------------------------------------------------------------------- booking -------- */

function BookingArea({
  coaches,
  mine,
  onBooked,
}: {
  coaches: Coach[] | null
  mine: MyCoaching | null
  onBooked: () => void
}) {
  const t = useT()
  const [selected, setSelected] = useState<string | null>(null)
  const active = coaches?.find((c) => c.id === selected) ?? coaches?.[0] ?? null

  useEffect(() => {
    if (!selected && coaches && coaches.length > 0) {
      setSelected(coaches[0]!.id)
    }
  }, [coaches, selected])

  if (!coaches) {
    return (
      <Section className="py-6">
        <Skeleton className="h-72 w-full" />
      </Section>
    )
  }
  if (coaches.length === 0) {
    return (
      <Section className="py-6">
        <EmptyState title={t.coaching.noCoachesTitle}>{t.coaching.noCoachesBody}</EmptyState>
      </Section>
    )
  }

  return (
    <Section className="py-6" title={t.coaching.bookTitle}>
      {coaches.length > 1 && (
        <div className="mb-5 flex flex-wrap gap-2">
          {coaches.map((coach) => (
            <button
              key={coach.id}
              type="button"
              onClick={() => setSelected(coach.id)}
              aria-pressed={active?.id === coach.id}
              className={[
                'rounded-edge border px-4 py-2.5 text-[13.5px] font-semibold',
                'transition-[background-color,border-color,color,transform] duration-200',
                'ease-out-expo active:scale-95',
                active?.id === coach.id
                  ? 'border-brand-500 bg-brand-500 text-paper shadow-glow'
                  : 'border-ink-400 bg-ink-700 text-chalk-muted hover:border-ink-300 hover:text-chalk',
              ].join(' ')}
            >
              {coach.displayName}
            </button>
          ))}
        </div>
      )}

      {active && <SlotPicker coach={active} policy={mine?.policy} onBooked={onBooked} />}
    </Section>
  )
}

/** The viewer's own zone. Everything is rendered in it, and it is always named. */
const VIEWER_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone

/**
 * Are two IANA zone names the same place?
 *
 * <p>Not a string comparison. A great many zones have more than one name — India is
 * `Asia/Kolkata` and the legacy `Asia/Calcutta`, and a browser will happily report one
 * while the coach's record holds the other. Compared as strings, an Indian customer
 * booking an Indian coach was told "Times shown in Asia/Calcutta · Vinay coaches from
 * Asia/Kolkata", which reads as two different countries.
 *
 * <p>`Intl` resolves an alias to its canonical form, so this asks the question properly.
 * Comparing current UTC offsets instead would have been wrong in the other direction:
 * London and Lisbon share an offset for part of the year and diverge for the rest, and a
 * customer in one booking a coach in the other very much needs telling.
 */
function sameZone(a: string, b: string): boolean {
  if (a === b) return true
  const canonical = (zone: string) => {
    try {
      return new Intl.DateTimeFormat('en-US', { timeZone: zone }).resolvedOptions().timeZone
    } catch {
      return zone
    }
  }
  return canonical(a) === canonical(b)
}

function SlotPicker({
  coach,
  policy,
  onBooked,
}: {
  coach: Coach
  policy: MyCoaching['policy'] | undefined
  onBooked: () => void
}) {
  const t = useT()
  const [slots, setSlots] = useState<CoachSlots | null>(null)
  const [loading, setLoading] = useState(true)
  const [day, setDay] = useState<string | null>(null)
  const [booking, setBooking] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirmed, setConfirmed] = useState<CoachingSession | null>(null)
  const [month, setMonth] = useState(() => startOfMonth(new Date()))

  /*
   * The booking window, from policy rather than from a constant here.
   *
   * `maxAdvanceDays` is served by the API precisely so this does not have to guess.
   * Hardcoding sixty would mean the calendar keeps offering a month that returns
   * nothing the day somebody shortens the horizon.
   */
  const today = useMemo(() => new Date(), [])
  const maxDate = useMemo(() => {
    const days = policy?.maxAdvanceDays ?? 60
    return new Date(today.getFullYear(), today.getMonth(), today.getDate() + days)
  }, [today, policy?.maxAdvanceDays])

  /*
   * Slots are fetched for the visible month, not once for a fixed fortnight.
   *
   * The endpoint takes `from`/`to` and clamps whatever it is given to the bookable
   * window, so paging to a month that is entirely out of bounds returns an empty
   * list rather than an error — which is exactly what lets the grid page freely.
   */
  const loadSlots = useCallback(async (visible: Date, signal?: { cancelled: boolean }) => {
    setLoading(true)
    setError(null)
    const from = new Date(Math.max(startOfMonth(visible).getTime(), today.getTime()))
    const to = endOfMonth(visible)
    try {
      const data = await api.get<CoachSlots>(
        `/api/v1/coaching/coaches/${encodeURIComponent(coach.id)}/slots`
        + `?from=${encodeURIComponent(from.toISOString())}`
        + `&to=${encodeURIComponent(to.toISOString())}`,
      )
      if (signal?.cancelled) return
      setSlots(data)
    } catch (e: unknown) {
      if (signal?.cancelled) return
      setError(e instanceof ApiError ? e.message : t.coaching.loadSlotsFailed)
    } finally {
      if (!signal?.cancelled) setLoading(false)
    }
  }, [coach.id, today])

  useEffect(() => {
    const signal = { cancelled: false }
    void loadSlots(month, signal)
    return () => {
      signal.cancelled = true
    }
  }, [loadSlots, month])

  /**
   * Group by the viewer's local day.
   *
   * The server sends instants; which day one falls on is a question only the viewer's zone
   * can answer, and it is not the same answer the coach would give.
   */
  const byDay = useMemo(() => {
    const groups = new Map<string, string[]>()
    for (const iso of slots?.slots ?? []) {
      const key = new Date(iso).toLocaleDateString('en-CA') // stable YYYY-MM-DD
      const bucket = groups.get(key)
      if (bucket) bucket.push(iso)
      else groups.set(key, [iso])
    }
    return groups
  }, [slots])

  const days = useMemo(() => Array.from(byDay.keys()), [byDay])
  const activeDay = day && byDay.has(day) ? day : days[0] ?? null
  const times = activeDay ? byDay.get(activeDay) ?? [] : []

  async function book(iso: string) {
    setBooking(iso)
    setError(null)
    try {
      const session = await api.post<CoachingSession>('/api/v1/coaching/sessions', {
        coachId: coach.id,
        startsAt: iso,
        timezone: VIEWER_ZONE,
      })
      setConfirmed(session)
      onBooked()
      // The slot we just took, and anything overlapping it, is gone. Re-read rather than
      // filtering locally — the server knows about other people's bookings too.
      await loadSlots(month)
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : t.coaching.bookingFailed)
      // Somebody else took it first. Re-read so the grid stops offering a dead slot.
      if (e instanceof ApiError && e.code === 'slot_unavailable') {
        await loadSlots(month).catch(() => undefined)
      }
    } finally {
      setBooking(null)
    }
  }

  if (loading) return <Skeleton className="h-64 w-full" />

  return (
    <Card className="p-4 sm:p-6">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-[13px] text-chalk-muted">
          {t.coaching.timesShownIn}{' '}
          {/*
            When the two are the same place, the coach's own spelling is shown rather
            than the browser's. Both are correct; `Asia/Kolkata` is the current IANA
            primary name and `Asia/Calcutta` is the deprecated alias a browser may
            still report, and there is no reason to show a customer the older one.
          */}
          <strong className="text-chalk">
            {sameZone(coach.timezone, VIEWER_ZONE) ? coach.timezone : VIEWER_ZONE}
          </strong>
          {!sameZone(coach.timezone, VIEWER_ZONE) && (
            <> · {t.coaching.coachesFrom(coach.displayName, coach.timezone)}</>
          )}
        </p>
        {policy && (
          <p className="text-[13px] text-chalk-faint">
            {t.coaching.policyLine(policy.sessionMinutes, policy.changeCutoffHours)}
          </p>
        )}
      </div>

      {confirmed && (
        <div className="mt-4">
          <Alert tone="ok">
            {t.coaching.bookedFor}{' '}
            <strong>
              {new Date(confirmed.startsAt).toLocaleString(undefined, {
                weekday: 'long',
                day: 'numeric',
                month: 'long',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </strong>
            {t.coaching.emailedDetails}
          </Alert>
        </div>
      )}

      {error && (
        <div className="mt-4">
          <Alert tone="warn">{error}</Alert>
        </div>
      )}

      {/*
        Calendar and times, side by side.

        The month answers "when could I?" and the column answers "what time?" — two
        different questions, so they get two panes rather than one list the reader has
        to re-scan after every date change. Stacked on a phone, where a month grid and
        a time column cannot both be legible at once.
      */}
      <div className="mt-5 grid gap-4 lg:grid-cols-[minmax(0,320px)_1fr] lg:items-start">
        <Calendar
          month={month}
          onMonthChange={setMonth}
          availableDays={new Set(days)}
          selected={activeDay}
          onSelect={setDay}
          minDate={today}
          maxDate={maxDate}
          loading={loading}
          labels={{
            previousMonth: t.coaching.previousMonth,
            nextMonth: t.coaching.nextMonth,
            available: t.coaching.dayAvailable,
            unavailable: t.coaching.dayUnavailable,
          }}
        />

        <div className="plate p-4 sm:p-5">
          {activeDay ? (
            <>
              <p className="stamp mb-4">
                {new Intl.DateTimeFormat(undefined, {
                  weekday: 'long', day: 'numeric', month: 'long',
                }).format(new Date(`${activeDay}T12:00:00`))}
              </p>

              {/*
                Times are set in tabular figures on a fixed grid.

                A booking grid where 09:00 and 11:30 render at different widths reads
                as a jumble; locked digit widths turn the same list into a column you
                can scan. The whole tile is the target — 48px tall, comfortably past
                the touch minimum — and every other tile dims while one is booking, so
                it is unambiguous which slot the spinner belongs to.
              */}
              <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">
                {times.map((iso) => (
                  <button
                    key={iso}
                    type="button"
                    disabled={booking !== null}
                    onClick={() => void book(iso)}
                    className={[
                      'tnum grid h-12 place-items-center rounded-edge border text-[13.5px]',
                      'font-semibold transition-[background-color,border-color,color,transform]',
                      'duration-200 ease-out-expo active:scale-95',
                      booking === iso
                        ? 'border-brand-500 bg-brand-500 text-paper'
                        : 'border-ink-400 bg-ink-700 text-chalk hover:border-ink-300 hover:bg-ink-600',
                      booking !== null && booking !== iso ? 'pointer-events-none opacity-40' : '',
                    ].join(' ')}
                  >
                    {booking === iso ? (
                      <Spinner size={16} />
                    ) : (
                      new Date(iso).toLocaleTimeString(undefined, {
                        hour: '2-digit',
                        minute: '2-digit',
                      })
                    )}
                  </button>
                ))}
              </div>
            </>
          ) : (
            /*
              An empty state that names the month rather than the service.

              "No free times" on a page where the reader has just paged to December is
              ambiguous — it reads as "this coach never works". Naming the month makes
              it clear the answer is scoped to what is on screen, and that paging back
              is worth trying.
            */
              <div className="grid min-h-[180px] place-items-center px-4 text-center">
              <div>
                <span aria-hidden="true" className="mx-auto mb-4 block h-px w-10 bg-brand-500" />
                <p className="text-body-sm text-chalk">
                  {t.coaching.noSlotsInMonth(
                    new Intl.DateTimeFormat(undefined, { month: 'long' }).format(month),
                  )}
                </p>
                <p className="mt-2 text-[12.5px] text-chalk-faint">
                  {t.coaching.noSlotsBody(coach.displayName)}
                </p>
              </div>
            </div>
          )}
        </div>
      </div>
    </Card>
  )
}

/* ------------------------------------------------------------------ upcoming --------- */

function UpcomingSessions({
  sessions,
  onChanged,
}: {
  sessions: CoachingSession[]
  onChanged: () => void
}) {
  const t = useT()
  return (
    <Section className="py-10" title={t.coaching.upcomingTitle}>
      <div className="grid gap-3">
        {sessions.map((session) => (
          <SessionRow key={session.ref} session={session} onChanged={onChanged} />
        ))}
      </div>
      <p className="mt-4 text-[13px] text-chalk-faint">
        {t.coaching.alsoAppears}{' '}
        <Link to="/account" className="underline hover:text-chalk">
          {t.coaching.yourAccount}
        </Link>
        .
      </p>
    </Section>
  )
}

function SessionRow({
  session,
  onChanged,
}: {
  session: CoachingSession
  onChanged: () => void
}) {
  const t = useT()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function cancel() {
    // The warning and the outcome come from the same server-computed flag, so this can
    // never promise a refund the cancel endpoint then declines to give.
    const warning = session.cancelRefundsCredit
      ? t.coaching.cancelRefunds
      : t.coaching.cancelForfeits
    if (!window.confirm(warning)) return

    setBusy(true)
    setError(null)
    try {
      await api.post(`/api/v1/coaching/sessions/${encodeURIComponent(session.ref)}/cancel`)
      onChanged()
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : t.coaching.cancelFailed)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className="flex flex-wrap items-center justify-between gap-4 p-5">
      <div>
        <p className="font-semibold text-chalk">
          {new Date(session.startsAt).toLocaleString(undefined, {
            weekday: 'long',
            day: 'numeric',
            month: 'long',
            hour: '2-digit',
            minute: '2-digit',
          })}
        </p>
        <p className="text-[13px] text-chalk-muted">
          {t.coaching.withCoach} {session.coachName} · {session.ref}
        </p>
        {error && (
          <p className="mt-1 text-[13px] text-rose-400" role="alert">
            {error}
          </p>
        )}
      </div>

      <div className="flex items-center gap-2">
        {/* A plain anchor, not ButtonLink: the meeting URL points at Discord or Meet,
            and a react-router Link would try to resolve it as an in-app route. */}
        {session.meetingUrl && (
          <a
            href={session.meetingUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center justify-center rounded-lg bg-brand-500 px-3 py-1.5
                       text-[13px] font-semibold text-paper transition-colors hover:bg-brand-400"
          >
            {t.coaching.join}
          </a>
        )}
        <Button variant="ghost" size="sm" disabled={busy} onClick={() => void cancel()}>
          {busy ? <Spinner size={16} /> : t.coaching.cancel}
        </Button>
      </div>
    </Card>
  )
}
