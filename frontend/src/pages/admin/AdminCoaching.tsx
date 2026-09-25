import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Badge, Button, Field, Input, Select, Skeleton } from '../../components/ui'
import { AdminPage } from './shell/AdminPage'
import type { BadgeTone } from '../../components/ui'
import { ApiError, api } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import type { AdminSession, AvailabilityWindow, CoachAdmin, CoachTimeOff } from '../../lib/types'

/**
 * The coach's diary, and the rules that generate it.
 *
 * <p>Every endpoint behind this screen already existed; what did not exist was anywhere
 * to see them. Availability, time off and session outcomes were all reachable by HTTP and
 * by nothing else, which meant the only way to know what a week looked like was to query
 * the database.
 *
 * <p><b>Two zones, everywhere.</b> Times are stored in UTC and shown in IST, because that
 * is where the coaching is delivered and the person reading this screen should never do
 * arithmetic. The customer's own zone is shown beside it wherever there is room: it is
 * the time they will quote on the day, and the gap between the two is where a missed
 * session comes from.
 */

/** Asia/Kolkata. Named once so every formatter below agrees. */
const BUSINESS_ZONE = 'Asia/Kolkata'

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

const STATUS_TONE: Record<string, BadgeTone> = {
  SCHEDULED: 'gold',
  COMPLETED: 'ok',
  NO_SHOW: 'warn',
  CANCELLED_BY_CUSTOMER: 'neutral',
  CANCELLED_BY_COACH: 'neutral',
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

/** The Monday of the week an instant falls in, as seen from the business zone. */
function weekStart(from: Date): Date {
  const d = new Date(from)
  d.setHours(0, 0, 0, 0)
  const isoDay = (d.getDay() + 6) % 7
  d.setDate(d.getDate() - isoDay)
  return d
}

export default function AdminCoaching() {
  useSeo({ title: 'Coaching — admin', noindex: true })

  const [coaches, setCoaches] = useState<CoachAdmin[] | null>(null)
  const [coachId, setCoachId] = useState<string>('')
  const [sessions, setSessions] = useState<AdminSession[] | null>(null)
  const [timeOff, setTimeOff] = useState<CoachTimeOff[]>([])
  const [view, setView] = useState<'week' | 'list'>('week')
  const [status, setStatus] = useState<string>('ALL')
  const [anchor, setAnchor] = useState<Date>(() => weekStart(new Date()))
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
   * screen somebody scrolls through; a wider window makes stepping instant.
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
    return status === 'ALL' ? sessions : sessions.filter((s) => s.status === status)
  }, [sessions, status])

  const act = async (ref: string, next: string) => {
    try {
      await api.post(`/api/v1/admin/coaching/sessions/${encodeURIComponent(ref)}/outcome`,
        { status: next, note: null })
      setNotice(`Session marked ${next.replace(/_/g, ' ').toLowerCase()}.`)
      await loadDiary()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'That change was refused.')
    }
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
                  <Select {...p} value={status} onChange={(e) => setStatus(e.target.value)}>
                    <option value="ALL">All</option>
                    <option value="SCHEDULED">Scheduled</option>
                    <option value="COMPLETED">Completed</option>
                    <option value="NO_SHOW">No-show</option>
                    <option value="CANCELLED_BY_CUSTOMER">Cancelled by customer</option>
                    <option value="CANCELLED_BY_COACH">Cancelled by coach</option>
                  </Select>
                )}
              </Field>
              <div className="flex gap-2">
                <Button variant={view === 'week' ? 'primary' : 'secondary'} size="md"
                        onClick={() => setView('week')}>Week</Button>
                <Button variant={view === 'list' ? 'primary' : 'secondary'} size="md"
                        onClick={() => setView('list')}>List</Button>
              </div>
            </div>

            <p className="mt-3 text-[12px] text-chalk-faint">
              Times are shown in {BUSINESS_ZONE}. Each row also carries the customer's own
              zone, which is the time they will quote back on the day.
            </p>

            {view === 'week'
              ? <WeekView sessions={visible} anchor={anchor} onAnchor={setAnchor} />
              : <ListView sessions={visible} onAct={act} />}

            <Availability coach={coach} onSaved={(c) => {
              setCoaches((all) => all?.map((x) => (x.id === c.id ? c : x)) ?? all)
              setNotice('Weekly hours saved.')
            }} onError={setError} />

            <TimeOff coachId={coach.id} entries={timeOff} onChanged={setTimeOff}
                     onError={setError} onNotice={setNotice} />
          </>
        )}
      </AdminPage>
    </>
  )
}

/* ------------------------------------------------------------------ week view --- */

function WeekView({ sessions, anchor, onAnchor }: {
  sessions: AdminSession[]
  anchor: Date
  onAnchor: (d: Date) => void
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
      const key = new Intl.DateTimeFormat('en-CA', {
        timeZone: BUSINESS_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
      }).format(new Date(s.startsAt))
      const list = map.get(key) ?? []
      list.push(s)
      map.set(key, list)
    }
    return map
  }, [sessions])

  const keyFor = (d: Date) => new Intl.DateTimeFormat('en-CA', {
    timeZone: BUSINESS_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(d)

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
          const rows = (byDay.get(keyFor(d)) ?? [])
            .sort((a, b) => a.startsAt.localeCompare(b.startsAt))
          return (
            <div key={d.toISOString()} className="min-h-[120px] bg-paper p-2.5">
              <p className="text-[11px] font-semibold uppercase tracking-wide text-chalk-faint">
                {DAYS[i]} {d.getDate()}
              </p>
              <div className="mt-2 flex flex-col gap-1.5">
                {rows.length === 0 && <span className="text-[11px] text-chalk-faint">—</span>}
                {rows.map((s) => (
                  <div key={s.ref} className="rounded-edge bg-ink-700/50 px-2 py-1.5">
                    <p className="tnum text-[12px] font-semibold text-chalk">
                      {timeOnly(s.startsAt, BUSINESS_ZONE)}
                    </p>
                    <p className="truncate text-[11px] text-chalk-muted">
                      {s.customerEmail ?? '—'}
                    </p>
                    {s.sessionLabel && (
                      <p className="text-[11px] text-chalk-faint">{s.sessionLabel}</p>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ list view --- */

function ListView({ sessions, onAct }: {
  sessions: AdminSession[]
  onAct: (ref: string, next: string) => void
}) {
  if (sessions.length === 0) {
    return <div className="mt-5"><Alert tone="neutral">Nothing in this window.</Alert></div>
  }
  return (
    <div className="mt-5 flex flex-col gap-2">
      {[...sessions].sort((a, b) => a.startsAt.localeCompare(b.startsAt)).map((s) => (
        <div key={s.ref} className="surface flex flex-wrap items-start gap-4 p-4">
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
          </div>

          <div className="min-w-[200px] flex-1">
            <p className="break-all text-[13px] text-chalk">{s.customerEmail ?? '—'}</p>
            <p className="text-[12px] text-chalk-faint">
              {s.sessionLabel ?? '—'}
              {s.orderRef && <> · <span className="tnum">{s.orderRef}</span></>}
            </p>
            {s.customerNote && (
              <p className="mt-1 text-[12px] italic text-chalk-muted">“{s.customerNote}”</p>
            )}
          </div>

          <div className="flex min-w-[150px] flex-col gap-1.5">
            <Badge tone={STATUS_TONE[s.status] ?? 'neutral'}>
              {s.status.replace(/_/g, ' ').toLowerCase()}
            </Badge>
            {s.paymentStatus && (
              <span className="text-[11.5px] text-chalk-faint">
                Payment: {s.paymentStatus.toLowerCase()}
              </span>
            )}
            {s.rescheduleCount > 0 && (
              <span className="text-[11.5px] text-chalk-faint">
                Moved {s.rescheduleCount}×
              </span>
            )}
          </div>

          {/* Only the transitions the state machine actually allows from here, so a
              button can never produce a refusal the operator has to interpret. */}
          <div className="flex flex-wrap gap-2">
            {s.allowedTransitions
              .filter((t) => t !== 'CANCELLED_BY_CUSTOMER')
              .map((t) => (
                <Button key={t} variant="secondary" size="sm" onClick={() => onAct(s.ref, t)}>
                  {t === 'COMPLETED' ? 'Completed'
                    : t === 'NO_SHOW' ? 'No-show'
                      : t === 'CANCELLED_BY_COACH' ? 'Cancel' : t.toLowerCase()}
                </Button>
              ))}
          </div>
        </div>
      ))}
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
