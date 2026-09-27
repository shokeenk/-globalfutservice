import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { LuBookmark, LuCalendar, LuChevronDown, LuTrash2 } from 'react-icons/lu'
import { ApiError, api } from '../../../lib/api'
import type { SavedView } from '../../../lib/types'
import { buttonClasses, Popover } from '../ui/controls'
import { todayInIndia } from '../ui/format'
import { rangeLabel, toSaved, type OrderFilters } from './filters'

const input = 'h-9 w-full rounded-admin-control border border-admin-line bg-white px-2.5 text-[13px] text-admin-ink '
  + 'focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20'

/**
 * Saved Views: your own named filters for this page.
 *
 * <p>The list is read when the menu opens rather than on every page load, since most
 * visits never open it. Choosing one applies its filters by changing the address, so a
 * saved view is exactly the page a link to it would show.
 */
export function SavedViewsMenu({
  filters, onApply,
}: {
  filters: OrderFilters
  onApply: (saved: Record<string, string>) => void
}) {
  return (
    <Popover
      align="right"
      buttonClassName={buttonClasses('outline')}
      trigger={(open) => (
        <>
          <LuBookmark aria-hidden="true" className="h-4 w-4" />
          Saved Views
          <LuChevronDown aria-hidden="true" className={`h-4 w-4 transition-transform ${open ? 'rotate-180' : ''}`} />
        </>
      )}
      panelClassName="w-[300px] p-3"
    >
      {(close) => <SavedViewsPanel filters={filters} onApply={(v) => { onApply(v); close() }} />}
    </Popover>
  )
}

function SavedViewsPanel({
  filters, onApply,
}: {
  filters: OrderFilters
  onApply: (saved: Record<string, string>) => void
}) {
  const [views, setViews] = useState<SavedView[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [name, setName] = useState('')
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    try {
      setViews(await api.get<SavedView[]>('/api/v1/admin/saved-views?page=orders'))
      setError(null)
    } catch {
      setViews([])
      setError('Could not load your saved views.')
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (!name.trim() || saving) return
    setSaving(true)
    setError(null)
    try {
      await api.post('/api/v1/admin/saved-views', { page: 'orders', name: name.trim(), filters: toSaved(filters) })
      setName('')
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not save that view.')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (view: SavedView) => {
    if (!window.confirm(`Delete the saved view "${view.name}"?`)) return
    setError(null)
    try {
      await api.del(`/api/v1/admin/saved-views/${view.id}`)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not delete that view.')
    }
  }

  return (
    <div>
      <p className="px-1 text-[11px] font-semibold uppercase tracking-[0.06em] text-admin-faint">Your views</p>
      {views === null ? (
        <span className="mt-2 block h-8 animate-pulse rounded bg-admin-grey-tint" aria-label="Loading" />
      ) : views.length === 0 ? (
        <p className="mt-2 px-1 text-[12.5px] text-admin-muted">
          None yet. Set the filters you want, name them below, and they are one click away.
        </p>
      ) : (
        <ul className="mt-1.5 max-h-60 overflow-y-auto">
          {views.map((view) => (
            <li key={view.id} className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => onApply(view.filters)}
                className="min-w-0 flex-1 truncate rounded-[6px] px-2 py-2 text-left text-[13px] text-admin-ink
                           hover:bg-admin-page focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red"
              >
                {view.name}
              </button>
              <button
                type="button"
                onClick={() => void remove(view)}
                aria-label={`Delete saved view ${view.name}`}
                className="grid h-8 w-8 shrink-0 place-items-center rounded-[6px] text-admin-faint hover:bg-admin-page
                           hover:text-admin-red-text focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red"
              >
                <LuTrash2 aria-hidden="true" className="h-4 w-4" />
              </button>
            </li>
          ))}
        </ul>
      )}

      <form onSubmit={(e) => void save(e)} className="mt-3 border-t border-admin-line pt-3">
        <label htmlFor="saved-view-name" className="px-1 text-[12px] font-medium text-admin-ink">
          Save the current filters as
        </label>
        <div className="mt-1.5 flex gap-2">
          <input
            id="saved-view-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={60}
            placeholder="e.g. Disputed PlayStation"
            className={input}
          />
          <button type="submit" disabled={!name.trim() || saving} className={buttonClasses('primary', 'sm')}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
      {error && <p role="alert" className="mt-2 px-1 text-[12.5px] text-admin-red-ink">{error}</p>}
    </div>
  )
}

/**
 * The date filter: two days, both optional, in India time. The table filters on when an
 * order was placed.
 */
export function DateRangeMenu({
  from, to, onChange,
}: {
  from: string
  to: string
  onChange: (from: string, to: string) => void
}) {
  return (
    <Popover
      buttonClassName={`${buttonClasses('outline')} h-9 w-full justify-between px-3 text-[13px] font-normal`}
      trigger={() => (
        <>
          <span className={from || to ? 'text-admin-ink' : 'text-admin-faint'}>{rangeLabel(from, to)}</span>
          <LuCalendar aria-hidden="true" className="h-4 w-4 text-admin-faint" />
        </>
      )}
      label={from || to ? `Date range: ${rangeLabel(from, to)}` : 'Date range: any date'}
      panelClassName="w-[260px] p-3"
    >
      {(close) => <DateRangePanel from={from} to={to} onApply={(f, t) => { onChange(f, t); close() }} />}
    </Popover>
  )
}

function DateRangePanel({ from, to, onApply }: { from: string; to: string; onApply: (from: string, to: string) => void }) {
  const [start, setStart] = useState(from)
  const [end, setEnd] = useState(to)
  const today = todayInIndia()
  const backwards = Boolean(start && end && end < start)

  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!backwards) onApply(start, end) }}>
      <p className="text-[12px] text-admin-muted">Orders placed between these days, India time.</p>
      <label className="mt-3 block text-[12px] font-medium text-admin-ink">
        From
        <input type="date" value={start} max={today} onChange={(e) => setStart(e.target.value)} className={`mt-1 ${input}`} />
      </label>
      <label className="mt-2.5 block text-[12px] font-medium text-admin-ink">
        To
        <input type="date" value={end} max={today} onChange={(e) => setEnd(e.target.value)} className={`mt-1 ${input}`} />
      </label>
      {backwards && <p role="alert" className="mt-2 text-[12px] text-admin-red-ink">The end is before the start.</p>}
      <div className="mt-3 flex justify-between gap-2">
        <button type="button" onClick={() => onApply('', '')} className={buttonClasses('outline', 'sm')}>Clear</button>
        <button type="submit" disabled={backwards} className={buttonClasses('primary', 'sm')}>Apply</button>
      </div>
    </form>
  )
}
