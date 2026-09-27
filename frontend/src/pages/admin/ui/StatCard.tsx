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
  icon: Icon, tone, label, value, sub, aside, onClick, active = false, failed = false, size = 'md',
}: {
  icon: IconType
  tone: Tone
  label: string
  /** Null while loading. A string is shown as it is, e.g. an amount already formatted. */
  value: number | string | null
  sub?: ReactNode
  /** Beside the figure, at the right: the dashboard's trend. */
  aside?: ReactNode
  onClick?: () => void
  active?: boolean
  failed?: boolean
  /** {@code lg} for the dashboard's four cards, which the reference draws larger. */
  size?: 'md' | 'lg'
}) {
  const lg = size === 'lg'
  const body = (
    <>
      <span
        aria-hidden="true"
        className={`grid shrink-0 place-items-center ${lg ? 'h-14 w-14 rounded-[12px]' : 'h-10 w-10 rounded-[10px]'}
                    ${TONE_CLASSES[tone].tile}`}
      >
        <Icon className={lg ? 'h-6 w-6' : 'h-5 w-5'} />
      </span>
      <span className="min-w-0 flex-1">
        <span className={`block font-medium leading-snug text-admin-ink ${lg ? 'text-[13.5px]' : 'text-[12.5px]'}`}>
          {label}
        </span>
        <span className="mt-1 flex items-end justify-between gap-2">
        <span className={`block font-bold tabular-nums text-admin-ink ${lg ? 'text-[26px] leading-tight' : 'text-admin-stat'}`}>
          {failed ? (
            <>
              <span aria-hidden="true">—</span>
              <span className="sr-only">Could not load</span>
            </>
          ) : value === null ? (
            <span aria-label="Loading" className="inline-block h-6 w-10 animate-pulse rounded bg-admin-grey-tint align-middle" />
          ) : typeof value === 'number' ? (
            value.toLocaleString('en-IN')
          ) : (
            value
          )}
        </span>
        {aside && !failed && value !== null && <span className="shrink-0">{aside}</span>}
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
export function TrendLine({
  current, previous, against, good = 'up',
}: {
  current: number
  previous: number
  against: string
  /** Which way is good news. Down, for a backlog: the reference draws more pending in red. */
  good?: 'up' | 'down'
}) {
  const t = trend(current, previous)
  if (t.direction === 'flat') {
    return <span>Same as {against}</span>
  }
  const Arrow = t.direction === 'up' ? LuArrowUpRight : LuArrowDownRight
  const colour = t.direction === good ? 'text-admin-up' : 'text-admin-down'
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

/**
 * The dashboard's trend, beside the figure: the change on top, "vs yesterday" beneath.
 *
 * <p>Same rules as {@link TrendLine}: no percentage from zero, and colour by whether the
 * change is good news for this figure.
 */
export function TrendBlock({
  current, previous, against, good = 'up',
}: {
  current: number
  previous: number
  against: string
  good?: 'up' | 'down'
}) {
  const t = trend(current, previous)
  if (t.direction === 'flat') {
    return <span className="block text-right text-[11.5px] leading-snug text-admin-faint">Same as<br />{against}</span>
  }
  const Arrow = t.direction === 'up' ? LuArrowUpRight : LuArrowDownRight
  const colour = t.direction === good ? 'text-admin-up' : 'text-admin-down'
  return (
    <span className="block text-right leading-snug">
      <span className={`inline-flex items-center gap-0.5 text-[14px] font-semibold ${colour}`}>
        <Arrow aria-hidden="true" className="h-4 w-4" />
        <span className="sr-only">{t.direction === 'up' ? 'Up' : 'Down'} </span>
        {t.percent === null ? `${current - previous} more` : `${t.percent}%`}
      </span>
      <span className="block text-[11.5px] text-admin-faint">
        {t.percent === null ? `than ${against}` : `vs ${against}`}
      </span>
    </span>
  )
}
