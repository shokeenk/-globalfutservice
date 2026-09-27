import { useCallback, useEffect, useMemo, useState } from 'react'
import { Calendar, endOfMonth, startOfMonth } from '../../components/Calendar'
import { CoachIcon } from '../../components/CoachingIcons'
import { Alert, Button, Skeleton } from '../../components/ui'
import { useT } from '../../i18n'
import { ApiError, api } from '../../lib/api'
import type { Coach, CoachSlots } from '../../lib/types'

/** The slot picked at checkout, sent with the order and held until it is paid. */
export interface ChosenSlot {
  coachId: string
  coachName: string
  /** ISO instant. */
  startsAt: string
  /** The customer's own zone, so emails and reminders can say "your time". */
  timezone: string
}

/** The zone this browser is in, e.g. Europe/London. What every time here is shown in. */
export const VIEWER_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone

/**
 * A slot in the customer's own time, with the zone named: "Mon 5 Oct, 09:30 (Europe/London)".
 * Named because a bare time is ambiguous to anyone who is not in India, which is where the
 * coach is.
 */
export function formatSlot(iso: string, zone: string = VIEWER_ZONE): string {
  const when = new Intl.DateTimeFormat(undefined, {
    weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
    timeZone: zone,
  }).format(new Date(iso))
  return `${when} (${zone})`
}

/**
 * The checkout's Schedule step: a month showing which days have free slots, then that
 * day's times, all in the customer's own time zone.
 *
 * <p>Picking a time only selects it. The slot is held when the order is placed, on the
 * next step, and re-checked on the server then -- so a slot someone else takes in the
 * meantime sends the customer back here with a message, and the calendar reloads.
 *
 * <p>Slots are asked for at the length of the product being bought: a six-pack session is
 * forty minutes, and laying the calendar out in hours would hide the slots that fit.
 */
export function ScheduleStep({
  variant, sessionsInPack, value, onChange, onContinue, notice, refreshKey,
}: {
  variant: string
  /** How many sessions the product includes; more than one shows the "book the rest later" note. */
  sessionsInPack: number
  value: ChosenSlot | null
  onChange: (slot: ChosenSlot | null) => void
  onContinue: () => void
  /** A message to show above the calendar, e.g. that the slot picked was just taken. */
  notice: string | null
  /** Bumped to make the calendar re-read the free slots. */
  refreshKey: number
}) {
  const t = useT()
  const b = t.coachingBook
  const c = t.coaching

  const [coaches, setCoaches] = useState<Coach[] | null>(null)
  const [coachId, setCoachId] = useState<string | null>(value?.coachId ?? null)
  const [slots, setSlots] = useState<CoachSlots | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [month, setMonth] = useState(() =>
    startOfMonth(value ? new Date(value.startsAt) : new Date()))
  const [day, setDay] = useState<string | null>(
    value ? new Date(value.startsAt).toLocaleDateString('en-CA') : null)
  const [touched, setTouched] = useState(false)

  const today = useMemo(() => new Date(), [])
  const maxDate = useMemo(
    () => new Date(today.getFullYear(), today.getMonth(), today.getDate() + 60), [today])

  useEffect(() => {
    let live = true
    api.get<Coach[]>('/api/v1/coaching/coaches')
      .then((list) => {
        if (!live) return
        setCoaches(list)
        setCoachId((current) => current ?? list[0]?.id ?? null)
      })
      .catch((e: unknown) => {
        if (!live) return
        setCoaches([])
        setError(e instanceof ApiError ? e.message : c.loadCoachesFailed)
      })
    return () => { live = false }
  }, [c.loadCoachesFailed])

  const coach = coaches?.find((x) => x.id === coachId) ?? null

  const loadSlots = useCallback(async (visible: Date, signal: { cancelled: boolean }) => {
    if (!coach) return
    setLoading(true)
    setError(null)
    const from = new Date(Math.max(startOfMonth(visible).getTime(), today.getTime()))
    const to = endOfMonth(visible)
    try {
      const data = await api.get<CoachSlots>(
        `/api/v1/coaching/coaches/${encodeURIComponent(coach.id)}/slots`
        + `?from=${encodeURIComponent(from.toISOString())}`
        + `&to=${encodeURIComponent(to.toISOString())}`
        + `&variant=${encodeURIComponent(variant)}`,
      )
      if (!signal.cancelled) setSlots(data)
    } catch (e: unknown) {
      if (!signal.cancelled) setError(e instanceof ApiError ? e.message : c.loadSlotsFailed)
    } finally {
      if (!signal.cancelled) setLoading(false)
    }
  }, [coach, today, variant, c.loadSlotsFailed])

  useEffect(() => {
    const signal = { cancelled: false }
    void loadSlots(month, signal)
    return () => { signal.cancelled = true }
  }, [loadSlots, month, refreshKey])

  /* Grouped by the customer's local day: which day an instant falls on depends on where
     you are, and it is the customer's calendar this has to match. */
  const byDay = useMemo(() => {
    const groups = new Map<string, string[]>()
    for (const iso of slots?.slots ?? []) {
      const key = new Date(iso).toLocaleDateString('en-CA')
      const bucket = groups.get(key)
      if (bucket) bucket.push(iso)
      else groups.set(key, [iso])
    }
    return groups
  }, [slots])

  const days = useMemo(() => Array.from(byDay.keys()), [byDay])
  const activeDay = day && byDay.has(day) ? day : days[0] ?? null
  const times = activeDay ? byDay.get(activeDay) ?? [] : []

  function choose(iso: string) {
    if (!coach) return
    onChange({ coachId: coach.id, coachName: coach.displayName, startsAt: iso, timezone: VIEWER_ZONE })
  }

  function next() {
    setTouched(true)
    if (value) onContinue()
  }

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="display text-display-md text-chalk">{b.scheduleTitle}</h1>
      <p className="mt-2 text-body-sm text-chalk-muted">{b.scheduleLead}</p>
      {sessionsInPack > 1 && (
        <p className="mt-1 text-body-sm text-chalk-muted">{b.schedulePackNote(sessionsInPack - 1)}</p>
      )}

      {notice && <div className="mt-5"><Alert tone="warn">{notice}</Alert></div>}
      {error && <div className="mt-5"><Alert tone="warn">{error}</Alert></div>}

      <div className="mt-6 rounded-panel border border-ink-400 bg-paper p-5 shadow-e1 sm:p-6">
        {coaches === null ? (
          <Skeleton className="h-64 w-full" />
        ) : coaches.length === 0 ? (
          <p className="text-body-sm text-chalk">{c.noCoachesTitle}</p>
        ) : (
          <>
            {coaches.length > 1 && (
              <div role="radiogroup" aria-label="Coach" className="mb-4 flex flex-wrap gap-2">
                {coaches.map((x) => (
                  <button
                    key={x.id}
                    type="button"
                    role="radio"
                    aria-checked={x.id === coachId}
                    onClick={() => { setCoachId(x.id); onChange(null) }}
                    className={[
                      'rounded-edge border-2 px-4 py-2 text-[13px] font-semibold transition-colors',
                      x.id === coachId ? 'border-brand-500 text-chalk'
                        : 'border-ink-400 text-chalk-muted hover:border-ink-300',
                    ].join(' ')}
                  >
                    {x.displayName}
                  </button>
                ))}
              </div>
            )}

            <p className="text-[13px] text-chalk-muted">
              {c.timesShownIn} <strong className="text-chalk">{VIEWER_ZONE}</strong>
              {coach && coach.timezone !== VIEWER_ZONE && (
                <> · {c.coachesFrom(coach.displayName, coach.timezone)}</>
              )}
            </p>

            <div className="mt-4 grid gap-4 md:grid-cols-[minmax(0,320px)_1fr] md:items-start">
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
                  previousMonth: c.previousMonth,
                  nextMonth: c.nextMonth,
                  available: c.dayAvailable,
                  unavailable: c.dayUnavailable,
                }}
              />

              <div className="plate p-4 sm:p-5">
                {loading ? (
                  <Skeleton className="h-40 w-full" />
                ) : activeDay ? (
                  <>
                    <p className="stamp mb-4">
                      {new Intl.DateTimeFormat(undefined, {
                        weekday: 'long', day: 'numeric', month: 'long',
                      }).format(new Date(`${activeDay}T12:00:00`))}
                    </p>
                    <div role="radiogroup" aria-label={b.scheduleTitle} className="grid grid-cols-3 gap-2 sm:grid-cols-4">
                      {times.map((iso) => {
                        const picked = value?.startsAt === iso
                        return (
                          <button
                            key={iso}
                            type="button"
                            role="radio"
                            aria-checked={picked}
                            onClick={() => choose(iso)}
                            className={[
                              'tnum grid h-12 place-items-center rounded-edge border text-[13.5px] font-semibold',
                              'transition-[background-color,border-color,color] duration-200',
                              picked
                                ? 'border-brand-500 bg-brand-500 text-paper'
                                : 'border-ink-400 bg-ink-700 text-chalk hover:border-ink-300 hover:bg-ink-600',
                            ].join(' ')}
                          >
                            {new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}
                          </button>
                        )
                      })}
                    </div>
                  </>
                ) : (
                  <div className="grid min-h-[160px] place-items-center px-4 text-center">
                    <div>
                      <p className="text-body-sm text-chalk">
                        {c.noSlotsInMonth(new Intl.DateTimeFormat(undefined, { month: 'long' }).format(month))}
                      </p>
                      {coach && <p className="mt-2 text-[12.5px] text-chalk-faint">{c.noSlotsBody(coach.displayName)}</p>}
                    </div>
                  </div>
                )}
              </div>
            </div>
          </>
        )}
      </div>

      {value && (
        <p className="mt-4 text-body-sm text-chalk">
          <strong>{b.summarySession}:</strong> {formatSlot(value.startsAt, value.timezone)}
        </p>
      )}
      {touched && !value && (
        <p role="alert" className="mt-4 text-[12.5px] text-warn">{b.pickTimeRequired}</p>
      )}

      <Button full size="lg" className="mt-6" onClick={next}>
        {b.continue} <CoachIcon name="arrowRight" className="ml-1.5 h-4 w-4" />
      </Button>
    </div>
  )
}
