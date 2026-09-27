import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Badge, Button, Field, Input, Select, Skeleton } from '../../components/ui'
import { AdminPage } from './shell/AdminPage'
import type { BadgeTone } from '../../components/ui'
import { ApiError, api } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import type {
  AdminSession, AdminSessionEvent, AvailabilityWindow, CoachAdmin, CoachExtraSlot, CoachTimeOff,
  CoachingSettings,
} from '../../lib/types'

/**
 * The coach's diary, and the rules that generate it.
 *
 * <p><b>Two zones, everywhere.</b> Times are stored in UTC and shown in IST, because that
 * is where the coaching is delivered and the person reading this screen should never do
 * arithmetic. The customer's own zone is shown beside it wherever there is room: it is
 * the time they will quote on the day, and the gap between the two is where a missed
 * session comes from. Times typed in here -- a new start, an extra window -- are read as
 * IST for the same reason.
 *
 * <p><b>Pending</b> is a slot picked at checkout whose payment has not been verified.
 * Approving the payment confirms it; this screen's Confirm is the fallback for when that
 * did not happen, and the server refuses it until the order is paid.
 */

/** Asia/Kolkata. Named once so every formatter below agrees. */
const BUSINESS_ZONE = 'Asia/Kolkata'
/** India does not observe daylight saving, so IST is always this offset from UTC. */
const IST_OFFSET = '+05:30'

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

const STATUS_TONE: Record<string, BadgeTone> = {
  PENDING: 'brand',
  SCHEDULED: 'gold',
  COMPLETED: 'ok',
  NO_SHOW: 'warn',
  CANCELLED_BY_CUSTOMER: 'neutral',
  CANCELLED_BY_COACH: 'neutral',
  RELEASED: 'neutral',
}

const STATUS_LABEL: Record<string, string> = {
  PENDING: 'Pending payment',
  SCHEDULED: 'Confirmed',
  COMPLETED: 'Completed',
  NO_SHOW: 'No-show',
  CANCELLED_BY_CUSTOMER: 'Cancelled by customer',
  CANCELLED_BY_COACH: 'Cancelled by coach',
  RELEASED: 'Released',
}

/** The five filters the diary offers, each covering the statuses it means. */
const FILTERS: [string, string, string[]][] = [
  ['ALL', 'All', []],
  ['PENDING', 'Pending', ['PENDING']],
  ['CONFIRMED', 'Confirmed', ['SCHEDULED']],
  ['COMPLETED', 'Completed', ['COMPLETED']],
  ['CANCELLED', 'Cancelled', ['CANCELLED_BY_CUSTOMER', 'CANCELLED_BY_COACH', 'RELEASED']],
  ['NO_SHOW', 'No-show', ['NO_SHOW']],
]

function label(status: string): string {
  return STATUS_LABEL[status] ?? status.replace(/_/g, ' ').toLowerCase()
}

function inZone(iso: string, zone: string): string {
  try {
    return new Intl.DateTimeFormat('en-GB', {
      weekday: 'short', day: 'numeric', month: 'short',
      hour: '2-digit', minute: '2-digit', hour12: false, timeZone: zone,
    }).format(new Date(iso))
  } catch {
    // An unrecognised zone from an older session. Saying so beats rendering the
    // business zone as if it were the customer's.
    return '—'
  }
}

function timeOnly(iso: string, zone: string): string {
  try {
    return new Intl.DateTimeFormat('en-GB', {
      hour: '2-digit', minute: '2-digit', hour12: false, timeZone: zone,
    }).format(new Date(iso))
  } catch {
    return '—'
  }
}

/** A `datetime-local` value, read as IST: "2026-10-05T19:00" becomes 13:30 UTC. */
function istToIso(local: string): string {
  return new Date(`${local}:00${IST_OFFSET}`).toISOString()
}

/** The Monday of the week an instant falls in, as seen from the business zone. */
function weekStart(from: Date): Date {
  const d = new Date(from)
  d.setHours(0, 0, 0, 0)
  const isoDay = (d.getDay() + 6) % 7
  d.setDate(d.getDate() - isoDay)
  return d
}

function startOfDay(from: Date): Date {
  const d = new Date(from)
  d.setHours(0, 0, 0, 0)
  return d
}

const dayKey = (d: Date | string) => new Intl.DateTimeFormat('en-CA', {
  timeZone: BUSINESS_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
}).format(typeof d === 'string' ? new Date(d) : d)

export default function AdminCoaching() {
  useSeo({ title: 'Coaching — admin', noindex: true })

  const [coaches, setCoaches] = useState<CoachAdmin[] | null>(null)
  const [coachId, setCoachId] = useState<string>('')
  const [sessions, setSessions] = useState<AdminSession[] | null>(null)
  const [timeOff, setTimeOff] = useState<CoachTimeOff[]>([])
  const [view, setView] = useState<'day' | 'week' | 'list'>('week')
  const [filter, setFilter] = useState<string>('ALL')
  const [anchor, setAnchor] = useState<Date>(() => weekStart(new Date()))
  const [day, setDay] = useState<Date>(() => startOfDay(new Date()))
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    api.get<CoachAdmin[]>('/api/v1/admin/coaching/coaches')
      .then((found) => {
        setCoaches(found)
        const first = found[0]
        if (first) setCoachId((current) => current || first.id)
      })
      .catch(() => setCoaches([]))
  }, [])

  /*
   * The window is a fortnight around the anchor rather than exactly the week shown.
   * Paging one week at a time then re-fetching for each is a request per click on a
   * screen somebody scrolls through; a wider window makes stepping instant. The day
   * view's day is kept inside it.
   */
  const loadDiary = useCallback(async () => {
    if (!coachId) return
    const from = new Date(anchor)
    from.setDate(from.getDate() - 7)
    const to = new Date(anchor)
    to.setDate(to.getDate() + 21)
    try {
      setSessions(await api.get<AdminSession[]>(
        `/api/v1/admin/coaching/sessions?coachId=${encodeURIComponent(coachId)}`
        + `&from=${from.toISOString()}&to=${to.toISOString()}`))
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not load the diary.')
      setSessions([])
    }
  }, [coachId, anchor])

  useEffect(() => { void loadDiary() }, [loadDiary])

  const coach = coaches?.find((c) => c.id === coachId) ?? null

  const visible = useMemo(() => {
    if (!sessions) return []
    const statuses = FILTERS.find(([v]) => v === filter)?.[2] ?? []
    return statuses.length === 0 ? sessions : sessions.filter((s) => statuses.includes(s.status))
  }, [sessions, filter])

  /** Every action goes through here: one place that reports and reloads. */
  const act = async (done: string, call: () => Promise<unknown>) => {
    try {
      await call()
      setNotice(done)
      setError(null)
      await loadDiary()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'That change was refused.')
    }
  }

  const actions: SessionActions = {
    confirm: (s) => act('Confirmed. The customer has been told.',
      () => api.post(`/api/v1/admin/coaching/sessions/${encodeURIComponent(s.ref)}/confirm`)),
    reschedule: (s, startsAt) => act('Moved. The customer and Discord have been told.',
      () => api.post(`/api/v1/admin/coaching/sessions/${encodeURIComponent(s.ref)}/reschedule`,
        { startsAt })),
    cancel: (s, note) => act(s.status === 'PENDING'
      ? 'Released. The slot is free again.'
      : 'Cancelled, and the session returned to the customer.',
      () => api.post(`/api/v1/admin/coaching/sessions/${encodeURIComponent(s.ref)}/cancel`,
        { note: note || null })),
    outcome: (s, next) => act(`Marked ${label(next).toLowerCase()}.`,
      () => api.post(`/api/v1/admin/coaching/sessions/${encodeURIComponent(s.ref)}/outcome`,
        { status: next, note: null })),
  }

  const showDay = (d: Date) => {
    setDay(startOfDay(d))
    // Keep the day inside the loaded window.
    const monday = weekStart(d)
    if (monday.getTime() !== anchor.getTime()) setAnchor(monday)
    setView('day')
  }

  return (
    <>
      <AdminPage eyebrow="Services" title="Coaching" description="The diary, and the rules behind it.">
        {notice && <div className="mb-4"><Alert tone="ok">{notice}</Alert></div>}
        {error && <div className="mb-4"><Alert tone="warn">{error}</Alert></div>}

        {coaches === null && <Skeleton className="h-40" />}
        {coaches !== null && coaches.length === 0 && (
          <Alert tone="neutral">No coaches yet. Add one before anything can be booked.</Alert>
        )}

        {coach && (
          <>
            <div className="flex flex-wrap items-end gap-3">
              <Field label="Coach">
                {(p) => (
                  <Select {...p} value={coachId} onChange={(e) => setCoachId(e.target.value)}>
                    {coaches?.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.displayName}{c.active ? '' : ' (inactive)'}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
              <Field label="Status">
                {(p) => (
                  <Select {...p} value={filter} onChange={(e) => setFilter(e.target.value)}>
                    {FILTERS.map(([value, text]) => <option key={value} value={value}>{text}</option>)}
                  </Select>
                )}
              </Field>
              <div className="flex gap-2" role="group" aria-label="View">
                {(['day', 'week', 'list'] as const).map((v) => (
                  <Button key={v} variant={view === v ? 'primary' : 'secondary'} size="md"
                          aria-pressed={view === v} onClick={() => setView(v)}>
                    {v === 'day' ? 'Day' : v === 'week' ? 'Week' : 'List'}
                  </Button>
                ))}
              </div>
            </div>

            <p className="mt-3 text-[12px] text-chalk-faint">
              Times are shown in {BUSINESS_ZONE} (IST). Each booking also carries the
              customer's own zone, which is the time they will quote back on the day.
            </p>

            {sessions === null && <Skeleton className="mt-5 h-40" />}
            {sessions !== null && view === 'week' && (
              <WeekView sessions={visible} anchor={anchor} onAnchor={setAnchor} onDay={showDay} />
            )}
            {sessions !== null && view === 'day' && (
              <DayView sessions={visible} day={day} onDay={showDay} actions={actions} />
            )}
            {sessions !== null && view === 'list' && (
              <ListView sessions={visible} actions={actions} />
            )}

            <Settings onError={setError} onNotice={setNotice} />

            <Availability coach={coach} onSaved={(c) => {
              setCoaches((all) => all?.map((x) => (x.id === c.id ? c : x)) ?? all)
              setNotice('Weekly hours saved.')
            }} onError={setError} />

            <ExtraSlots coachId={coach.id} onError={setError} onNotice={setNotice} />

            <TimeOff coachId={coach.id} entries={timeOff} onChanged={setTimeOff}
                     onError={setError} onNotice={setNotice} />
          </>
        )}
      </AdminPage>
    </>
  )
}

interface SessionActions {
  confirm: (s: AdminSession) => void
  reschedule: (s: AdminSession, startsAt: string) => void
  cancel: (s: AdminSession, note: string) => void
  outcome: (s: AdminSession, next: string) => void
}

/* ------------------------------------------------------------------ week view --- */

function WeekView({ sessions, anchor, onAnchor, onDay }: {
  sessions: AdminSession[]
  anchor: Date
  onAnchor: (d: Date) => void
  onDay: (d: Date) => void
}) {
  const days = useMemo(() => {
    return Array.from({ length: 7 }, (_, i) => {
      const d = new Date(anchor)
      d.setDate(d.getDate() + i)
      return d
    })
  }, [anchor])

  const step = (weeks: number) => {
    const next = new Date(anchor)
    next.setDate(next.getDate() + weeks * 7)
    onAnchor(next)
  }

  /* Grouped by the business zone's calendar day, not the browser's. An operator in
     another country must still see the week the coach is working. */
  const byDay = useMemo(() => {
    const map = new Map<string, AdminSession[]>()
    for (const s of sessions) {
      const key = dayKey(s.startsAt)
      const list = map.get(key) ?? []
      list.push(s)
      map.set(key, list)
    }
    return map
  }, [sessions])

  return (
    <div className="mt-5">
      <div className="flex items-center justify-between">
        <Button variant="secondary" size="sm" onClick={() => step(-1)}>← Previous</Button>
        <p className="text-[13px] font-semibold text-chalk">
          {days[0]?.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}
          {' – '}
          {days[6]?.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })}
        </p>
        <Button variant="secondary" size="sm" onClick={() => step(1)}>Next →</Button>
      </div>

      <div className="mt-3 grid gap-px overflow-hidden rounded-panel bg-ink-400
                      sm:grid-cols-7">
        {days.map((d, i) => {
          const rows = (byDay.get(dayKey(d)) ?? [])
            .sort((a, b) => a.startsAt.localeCompare(b.startsAt))
          return (
            <div key={d.toISOString()} className="min-h-[120px] bg-paper p-2.5">
              <button type="button" onClick={() => onDay(d)}
                      className="text-[11px] font-semibold uppercase tracking-wide text-chalk-faint
                                 hover:text-chalk hover:underline">
                {DAYS[i]} {d.getDate()}
              </button>
              <div className="mt-2 flex flex-col gap-1.5">
                {rows.length === 0 && <span className="text-[11px] text-chalk-faint">—</span>}
                {rows.map((s) => (
                  <button key={s.ref} type="button" onClick={() => onDay(d)}
                          className={[
                            'rounded-edge px-2 py-1.5 text-left',
                            s.status === 'PENDING'
                              ? 'border border-dashed border-brand-500/60 bg-brand-500/[0.06]'
                              : 'bg-ink-700/50',
                          ].join(' ')}>
                    <p className="tnum text-[12px] font-semibold text-chalk">
                      {timeOnly(s.startsAt, BUSINESS_ZONE)}
                      {s.status === 'PENDING' && (
                        <span className="ml-1 text-[10.5px] font-medium text-brand-400">pending</span>
                      )}
                    </p>
                    <p className="truncate text-[11px] text-chalk-muted">
                      {s.inGameId ?? s.customerEmail ?? '—'}
                    </p>
                    {s.sessionLabel && (
                      <p className="text-[11px] text-chalk-faint">{s.sessionLabel}</p>
                    )}
                  </button>
                ))}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------- day view --- */

function DayView({ sessions, day, onDay, actions }: {
  sessions: AdminSession[]
  day: Date
  onDay: (d: Date) => void
  actions: SessionActions
}) {
  const rows = sessions
    .filter((s) => dayKey(s.startsAt) === dayKey(day))
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt))

  const step = (days: number) => {
    const next = new Date(day)
    next.setDate(next.getDate() + days)
    onDay(next)
  }

  return (
    <div className="mt-5">
      <div className="flex items-center justify-between">
        <Button variant="secondary" size="sm" onClick={() => step(-1)}>← Previous day</Button>
        <p className="text-[13px] font-semibold text-chalk">
          {day.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}
        </p>
        <Button variant="secondary" size="sm" onClick={() => step(1)}>Next day →</Button>
      </div>
      {rows.length === 0
        ? <div className="mt-3"><Alert tone="neutral">Nothing on this day.</Alert></div>
        : <div className="mt-3 flex flex-col gap-2">
            {rows.map((s) => <SessionRow key={s.ref} s={s} actions={actions} />)}
          </div>}
    </div>
  )
}

/* ------------------------------------------------------------------ list view --- */

function ListView({ sessions, actions }: {
  sessions: AdminSession[]
  actions: SessionActions
}) {
  if (sessions.length === 0) {
    return <div className="mt-5"><Alert tone="neutral">Nothing in this window.</Alert></div>
  }
  return (
    <div className="mt-5 flex flex-col gap-2">
      {[...sessions].sort((a, b) => a.startsAt.localeCompare(b.startsAt)).map((s) => (
        <SessionRow key={s.ref} s={s} actions={actions} />
      ))}
    </div>
  )
}

/* ---------------------------------------------------------------- one session --- */

function SessionRow({ s, actions }: { s: AdminSession; actions: SessionActions }) {
  const [moving, setMoving] = useState(false)
  const [newStart, setNewStart] = useState('')
  const [cancelling, setCancelling] = useState(false)
  const [note, setNote] = useState('')
  const [history, setHistory] = useState<AdminSessionEvent[] | null>(null)
  const [showHistory, setShowHistory] = useState(false)

  const live = s.status === 'PENDING' || s.status === 'SCHEDULED'

  const toggleHistory = async () => {
    if (showHistory) { setShowHistory(false); return }
    setShowHistory(true)
    if (history === null) {
      try {
        setHistory(await api.get<AdminSessionEvent[]>(
          `/api/v1/admin/coaching/sessions/${encodeURIComponent(s.ref)}/events`))
      } catch {
        setHistory([])
      }
    }
  }

  return (
    <div className="surface p-4" data-testid={`session-${s.ref}`}>
      <div className="flex flex-wrap items-start gap-4">
        <div className="min-w-[190px] flex-1">
          <p className="tnum text-[13.5px] font-semibold text-chalk">
            {inZone(s.startsAt, BUSINESS_ZONE)} <span className="text-chalk-faint">IST</span>
          </p>
          {s.customerTimezone && (
            <p className="tnum text-[12px] text-chalk-muted">
              {inZone(s.startsAt, s.customerTimezone)}{' '}
              <span className="text-chalk-faint">{s.customerTimezone}</span>
            </p>
          )}
          {s.status === 'PENDING' && s.holdExpiresAt && (
            <p className="mt-1 text-[11.5px] text-brand-400">
              Held until {inZone(s.holdExpiresAt, BUSINESS_ZONE)} IST unless paid
            </p>
          )}
        </div>

        <div className="min-w-[220px] flex-1">
          <p className="break-all text-[13px] text-chalk">{s.customerEmail ?? '—'}</p>
          <p className="text-[12px] text-chalk-faint">
            {s.sessionLabel ? `Session ${s.sessionLabel}` : '—'}
            {s.orderRef && <> · <span className="tnum">{s.orderRef}</span></>}
          </p>
          <dl className="mt-1.5 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-[12px]">
            <dt className="text-chalk-faint">In-game ID</dt><dd className="text-chalk">{s.inGameId ?? '—'}</dd>
            <dt className="text-chalk-faint">Platform</dt><dd className="text-chalk">{s.platform ?? '—'}</dd>
            <dt className="text-chalk-faint">Rank</dt><dd className="text-chalk">{s.rank ?? '—'}</dd>
            {s.improvementFocus && (
              <><dt className="text-chalk-faint">Wants to improve</dt>
                <dd className="text-chalk">{s.improvementFocus}</dd></>
            )}
          </dl>
          {s.customerNote && (
            <p className="mt-1 text-[12px] italic text-chalk-muted">“{s.customerNote}”</p>
          )}
        </div>

        <div className="flex min-w-[150px] flex-col gap-1.5">
          <Badge tone={STATUS_TONE[s.status] ?? 'neutral'}>{label(s.status)}</Badge>
          {s.paymentStatus && (
            <span className="text-[11.5px] text-chalk-faint">
              Payment: {s.paymentStatus.replace(/_/g, ' ').toLowerCase()}
            </span>
          )}
          {s.rescheduleCount > 0 && (
            <span className="text-[11.5px] text-chalk-faint">Moved {s.rescheduleCount}×</span>
          )}
        </div>
      </div>

      {/* Only what the state machine allows from here, so a button can never produce a
          refusal the operator has to interpret. */}
      <div className="mt-3 flex flex-wrap gap-2">
        {s.status === 'PENDING' && (
          <Button variant="primary" size="sm" onClick={() => actions.confirm(s)}>Confirm</Button>
        )}
        {live && (
          <Button variant="secondary" size="sm" aria-expanded={moving}
                  onClick={() => { setMoving((v) => !v); setCancelling(false) }}>Reschedule</Button>
        )}
        {live && (
          <Button variant="secondary" size="sm" aria-expanded={cancelling}
                  onClick={() => { setCancelling((v) => !v); setMoving(false) }}>
            {s.status === 'PENDING' ? 'Release' : 'Cancel'}
          </Button>
        )}
        {s.allowedTransitions.includes('COMPLETED') && (
          <Button variant="secondary" size="sm" onClick={() => actions.outcome(s, 'COMPLETED')}>
            Mark completed
          </Button>
        )}
        {s.allowedTransitions.includes('NO_SHOW') && (
          <Button variant="secondary" size="sm" onClick={() => actions.outcome(s, 'NO_SHOW')}>
            Mark no-show
          </Button>
        )}
        <Button variant="secondary" size="sm" aria-expanded={showHistory} onClick={() => void toggleHistory()}>
          History
        </Button>
      </div>

      {moving && (
        <div className="mt-3 flex flex-wrap items-end gap-2">
          <Field label="New start (IST)">
            {(p) => <Input {...p} type="datetime-local" value={newStart}
                           onChange={(e) => setNewStart(e.target.value)} />}
          </Field>
          <Button size="md" disabled={!newStart}
                  onClick={() => { actions.reschedule(s, istToIso(newStart)); setMoving(false) }}>
            Move it
          </Button>
        </div>
      )}

      {cancelling && (
        <div className="mt-3 flex flex-wrap items-end gap-2">
          <Field label="Reason (optional)">
            {(p) => <Input {...p} value={note} onChange={(e) => setNote(e.target.value)} />}
          </Field>
          <Button size="md" onClick={() => { actions.cancel(s, note); setCancelling(false) }}>
            {s.status === 'PENDING' ? 'Release the slot' : 'Cancel the session'}
          </Button>
        </div>
      )}

      {showHistory && (
        <ol className="mt-3 flex flex-col gap-1 border-t border-ink-400 pt-2 text-[12px]">
          {history === null && <li className="text-chalk-faint">Loading…</li>}
          {history?.length === 0 && <li className="text-chalk-faint">No history recorded.</li>}
          {history?.map((e, i) => (
            <li key={i} className="flex flex-wrap gap-x-2 text-chalk-muted">
              <span className="tnum text-chalk-faint">{inZone(e.at, BUSINESS_ZONE)} IST</span>
              <span className="text-chalk">
                {e.type === 'RESCHEDULED' && e.fromTime && e.toTime
                  ? `Moved ${inZone(e.fromTime, BUSINESS_ZONE)} → ${inZone(e.toTime, BUSINESS_ZONE)}`
                  : e.type === 'HELD' ? 'Held at checkout'
                    : e.type === 'BOOKED' ? 'Booked'
                      : `${e.fromStatus ? label(e.fromStatus) : '—'} → ${e.toStatus ? label(e.toStatus) : '—'}`}
              </span>
              <span>by {e.actorEmail ?? (e.actor ? e.actor.toLowerCase() : 'system')}</span>
              {e.detail && <span className="text-chalk-faint">· {e.detail}</span>}
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------- settings --- */

function Settings({ onError, onNotice }: {
  onError: (m: string) => void
  onNotice: (m: string) => void
}) {
  const [values, setValues] = useState<CoachingSettings | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.get<CoachingSettings>('/api/v1/admin/coaching/settings')
      .then(setValues)
      .catch((e) => onError(e instanceof ApiError ? e.message : 'Could not load the settings.'))
  }, [onError])

  if (!values) return null

  const set = (k: keyof CoachingSettings, v: string) =>
    setValues((all) => (all ? { ...all, [k]: Number(v) } : all))

  const save = async () => {
    setBusy(true)
    try {
      setValues(await api.put<CoachingSettings>('/api/v1/admin/coaching/settings', values))
      onNotice('Booking settings saved. They apply to slots offered from now on.')
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'Those settings were not accepted.')
    } finally {
      setBusy(false)
    }
  }

  const fields: [keyof CoachingSettings, string, string][] = [
    ['minNoticeMinutes', 'Minimum notice (minutes)', 'Slots sooner than this are not offered. 720 is 12 hours.'],
    ['bufferMinutes', 'Buffer between sessions (minutes)', 'Kept free either side of every booking.'],
    ['holdMinutes', 'Checkout hold (minutes)', 'How long a picked slot waits for payment proof.'],
    ['singleSessionMinutes', 'Single session length (minutes)', 'Applies to new purchases.'],
    ['blockSessionMinutes', 'Package session length (minutes)', 'Applies to new purchases.'],
  ]

  return (
    <div className="surface mt-8 p-5">
      <h2 className="stamp mb-1">Booking settings</h2>
      <p className="mb-4 text-[12.5px] text-chalk-muted">
        Admins only. Sessions already booked keep the length they were booked at.
      </p>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {fields.map(([key, text, hint]) => (
          <Field key={key} label={text} hint={hint}>
            {(p) => <Input {...p} type="number" min={0} value={String(values[key])}
                           onChange={(e) => set(key, e.target.value)} />}
          </Field>
        ))}
      </div>
      <Button size="md" className="mt-4" loading={busy} onClick={() => void save()}>Save settings</Button>
    </div>
  )
}

/* ---------------------------------------------------------------- extra slots --- */

function ExtraSlots({ coachId, onError, onNotice }: {
  coachId: string
  onError: (m: string) => void
  onNotice: (m: string) => void
}) {
  const [entries, setEntries] = useState<CoachExtraSlot[]>([])
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.get<CoachExtraSlot[]>(`/api/v1/admin/coaching/coaches/${encodeURIComponent(coachId)}/extra-slots`)
      .then(setEntries)
      .catch(() => setEntries([]))
  }, [coachId])

  const add = async () => {
    if (!from || !to) return
    setBusy(true)
    try {
      const created = await api.post<CoachExtraSlot>(
        `/api/v1/admin/coaching/coaches/${encodeURIComponent(coachId)}/extra-slots`,
        { startsAt: istToIso(from), endsAt: istToIso(to), reason: reason || null })
      setEntries((all) => [...all, created].sort((a, b) => a.startsAt.localeCompare(b.startsAt)))
      setFrom(''); setTo(''); setReason('')
      onNotice('Extra slot added. Its times are offered from now on.')
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'That extra slot was not accepted.')
    } finally {
      setBusy(false)
    }
  }

  const remove = async (id: number) => {
    try {
      await api.del(`/api/v1/admin/coaching/extra-slots/${id}`)
      setEntries((all) => all.filter((e) => e.id !== id))
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'That extra slot could not be removed.')
    }
  }

  return (
    <div className="surface mt-5 p-5">
      <h2 className="stamp mb-1">Extra slots</h2>
      <p className="mb-4 text-[12.5px] text-chalk-muted">
        One-off windows outside the weekly hours, entered in IST. Admins only.
      </p>
      <div className="flex flex-wrap items-end gap-2">
        <Field label="From (IST)">
          {(p) => <Input {...p} type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />}
        </Field>
        <Field label="To (IST)">
          {(p) => <Input {...p} type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />}
        </Field>
        <Field label="Reason (optional)">
          {(p) => <Input {...p} value={reason} onChange={(e) => setReason(e.target.value)} />}
        </Field>
        <Button size="md" loading={busy} disabled={!from || !to} onClick={() => void add()}>
          Add slot
        </Button>
      </div>
      {entries.length > 0 && (
        <div className="mt-4 flex flex-col gap-1.5">
          {entries.map((e) => (
            <div key={e.id} className="flex flex-wrap items-center justify-between gap-2
                                       border-t border-ink-400 pt-2 text-[12.5px]">
              <span className="tnum text-chalk-muted">
                {inZone(e.startsAt, BUSINESS_ZONE)} → {inZone(e.endsAt, BUSINESS_ZONE)} IST
                {e.reason && <span className="text-chalk-faint"> · {e.reason}</span>}
              </span>
              <Button variant="secondary" size="sm" onClick={() => void remove(e.id)}>Remove</Button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/* --------------------------------------------------------------- availability --- */

function Availability({ coach, onSaved, onError }: {
  coach: CoachAdmin
  onSaved: (c: CoachAdmin) => void
  onError: (m: string) => void
}) {
  const [windows, setWindows] = useState<AvailabilityWindow[]>(coach.availability)
  const [busy, setBusy] = useState(false)

  useEffect(() => { setWindows(coach.availability) }, [coach])

  const save = async () => {
    setBusy(true)
    try {
      /*
       * Sent whole, never row by row. A weekly schedule is read as one thing —
       * "Tuesdays and Thursdays, 6 to 10" — and patching it a row at a time invites the
       * half-applied state where the second call fails and the coach is bookable on a
       * day they never agreed to. The endpoint replaces the set for the same reason.
       */
      onSaved(await api.post<CoachAdmin>(
        `/api/v1/admin/coaching/coaches/${encodeURIComponent(coach.id)}/availability`,
        { windows }))
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'Those hours were not accepted.')
    } finally {
      setBusy(false)
    }
  }

  const update = (i: number, patch: Partial<AvailabilityWindow>) =>
    setWindows((all) => all.map((w, n) => (n === i ? { ...w, ...patch } : w)))

  return (
    <div className="surface mt-8 p-5">
      <h2 className="stamp mb-1">Weekly hours</h2>
      <p className="mb-4 text-[12.5px] text-chalk-muted">
        In {coach.timezone}, the coach's own zone. Slots are generated from these, minus
        anything already booked and anything blocked below.
      </p>

      <div className="flex flex-col gap-2">
        {windows.map((w, i) => (
          <div key={i} className="flex flex-wrap items-center gap-2">
            <Select value={String(w.dayOfWeek)}
                    onChange={(e) => update(i, { dayOfWeek: Number(e.target.value) })}>
              {DAYS.map((d, n) => <option key={d} value={n + 1}>{d}</option>)}
            </Select>
            <Input type="time" value={w.start.slice(0, 5)}
                   onChange={(e) => update(i, { start: `${e.target.value}:00` })} />
            <span className="text-chalk-faint">to</span>
            <Input type="time" value={w.end.slice(0, 5)}
                   onChange={(e) => update(i, { end: `${e.target.value}:00` })} />
            <Button variant="secondary" size="sm"
                    onClick={() => setWindows((all) => all.filter((_, n) => n !== i))}>
              Remove
            </Button>
          </div>
        ))}
        {windows.length === 0 && (
          <p className="text-[12.5px] text-chalk-faint">
            No hours set, so nothing can be booked.
          </p>
        )}
      </div>

      <div className="mt-4 flex gap-2">
        <Button variant="secondary" size="md" onClick={() =>
          setWindows((all) => [...all, { dayOfWeek: 1, start: '18:00:00', end: '22:00:00' }])}>
          Add a window
        </Button>
        <Button size="md" loading={busy} onClick={() => void save()}>Save hours</Button>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------- time off --- */

function TimeOff({ coachId, entries, onChanged, onError, onNotice }: {
  coachId: string
  entries: CoachTimeOff[]
  onChanged: (e: CoachTimeOff[]) => void
  onError: (m: string) => void
  onNotice: (m: string) => void
}) {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)

  const add = async () => {
    if (!from || !to) return
    setBusy(true)
    try {
      const created = await api.post<CoachTimeOff>(
        `/api/v1/admin/coaching/coaches/${encodeURIComponent(coachId)}/time-off`,
        { startsAt: new Date(from).toISOString(), endsAt: new Date(to).toISOString(),
          reason: reason || null })
      onChanged([...entries, created])
      setFrom(''); setTo(''); setReason('')
      onNotice('Blocked. Those slots will stop being offered.')
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'That block was not accepted.')
    } finally {
      setBusy(false)
    }
  }

  const remove = async (id: number) => {
    try {
      await api.del(`/api/v1/admin/coaching/time-off/${id}`)
      onChanged(entries.filter((e) => e.id !== id))
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'That block could not be removed.')
    }
  }

  return (
    <div className="surface mt-5 p-5">
      <h2 className="stamp mb-1">Blocked time</h2>
      <p className="mb-4 text-[12.5px] text-chalk-muted">
        Holidays, streams, anything that should stop slots being offered. Entered in your
        browser's zone and stored in UTC.
      </p>

      <div className="flex flex-wrap items-end gap-2">
        <Field label="From">
          {(p) => <Input {...p} type="datetime-local" value={from}
                         onChange={(e) => setFrom(e.target.value)} />}
        </Field>
        <Field label="To">
          {(p) => <Input {...p} type="datetime-local" value={to}
                         onChange={(e) => setTo(e.target.value)} />}
        </Field>
        <Field label="Reason (optional)">
          {(p) => <Input {...p} value={reason} onChange={(e) => setReason(e.target.value)} />}
        </Field>
        <Button size="md" loading={busy} disabled={!from || !to} onClick={() => void add()}>
          Block it
        </Button>
      </div>

      {entries.length > 0 && (
        <div className="mt-4 flex flex-col gap-1.5">
          {entries.map((e) => (
            <div key={e.id} className="flex flex-wrap items-center justify-between gap-2
                                       border-t border-ink-400 pt-2 text-[12.5px]">
              <span className="tnum text-chalk-muted">
                {inZone(e.startsAt, BUSINESS_ZONE)} → {inZone(e.endsAt, BUSINESS_ZONE)}
                {e.reason && <span className="text-chalk-faint"> · {e.reason}</span>}
              </span>
              <Button variant="secondary" size="sm" onClick={() => void remove(e.id)}>
                Remove
              </Button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
