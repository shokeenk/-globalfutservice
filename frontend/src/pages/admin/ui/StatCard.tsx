import type { ReactNode } from 'react'
import type { IconType } from 'react-icons'
import { LuArrowDownRight, LuArrowUpRight, LuChevronRight } from 'react-icons/lu'
import { TONE_CLASSES } from './Badge'
import { trend } from './format'
import type { Tone } from './status'

/**
 * A figure at the top of a page: coloured icon tile, label, number, and one line beneath.
 *
 * <p>Three states besides a number. Loading shows a grey bar where the number goes; a
 * failed read shows a dash and says so, because a zero would be a claim. Given
 * {@code onClick} the whole card is a button that filters the table below to what it
 * counts, with {@code active} marking the filter that is on.
 */
export function StatCard({
  icon: Icon, tone, label, value, sub, onClick, active = false, failed = false,
}: {
  icon: IconType
  tone: Tone
  label: string
  /** Null while loading. */
  value: number | null
  sub?: ReactNode
  onClick?: () => void
  active?: boolean
  failed?: boolean
}) {
  const body = (
    <>
      <span aria-hidden="true" className={`grid h-10 w-10 shrink-0 place-items-center rounded-[10px] ${TONE_CLASSES[tone].tile}`}>
        <Icon className="h-5 w-5" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-[12.5px] font-medium leading-snug text-admin-ink">{label}</span>
        <span className="mt-1 block text-admin-stat font-bold tabular-nums text-admin-ink">
          {failed ? (
            <>
              <span aria-hidden="true">—</span>
              <span className="sr-only">Could not load</span>
            </>
          ) : value === null ? (
            <span aria-label="Loading" className="inline-block h-6 w-10 animate-pulse rounded bg-admin-grey-tint align-middle" />
          ) : (
            value.toLocaleString('en-IN')
          )}
        </span>
        {sub && (
          <span className={`mt-1.5 block text-[11px] leading-snug text-admin-faint ${onClick ? 'pr-4' : ''}`}>{sub}</span>
        )}
      </span>
      {onClick && (
        <LuChevronRight aria-hidden="true" className="absolute bottom-3.5 right-3 h-4 w-4 text-admin-faint" />
      )}
    </>
  )

  const frame = `relative flex min-w-0 items-start gap-3 rounded-admin-card border bg-white p-3.5 text-left shadow-admin-card
                 ${active ? 'border-admin-red ring-1 ring-admin-red' : 'border-admin-line'}`

  if (!onClick) return <div className={frame}>{body}</div>
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`${frame} transition-colors hover:border-[#D5D8DE] focus-visible:outline-none
                  focus-visible:ring-2 focus-visible:ring-admin-red`}
    >
      {body}
    </button>
  )
}

/**
 * "↗ 20% vs yesterday", coloured by direction.
 *
 * <p>Up is green and down is red for the counts it is used with, where more is better.
 */
export function TrendLine({ current, previous, against }: { current: number; previous: number; against: string }) {
  const t = trend(current, previous)
  if (t.direction === 'flat') {
    return <span>Same as {against}</span>
  }
  const Arrow = t.direction === 'up' ? LuArrowUpRight : LuArrowDownRight
  const colour = t.direction === 'up' ? 'text-admin-up' : 'text-admin-down'
  // From nothing, a percentage would be invented; say how many more instead.
  if (t.percent === null) {
    return (
      <span className="inline-flex items-center gap-1">
        <span className={`inline-flex items-center gap-0.5 font-semibold ${colour}`}>
          <Arrow aria-hidden="true" className="h-3.5 w-3.5" />
          {current - previous} more
        </span>
        <span>than {against}</span>
      </span>
    )
  }
  return (
    <span className="inline-flex items-center gap-1">
      <span className={`inline-flex items-center gap-0.5 font-semibold ${colour}`}>
        <Arrow aria-hidden="true" className="h-3.5 w-3.5" />
        <span className="sr-only">{t.direction === 'up' ? 'Up' : 'Down'} </span>
        {t.percent}%
      </span>
      <span>vs {against}</span>
    </span>
  )
}
