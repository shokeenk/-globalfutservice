import { useEffect, useMemo, useRef, useState } from 'react'
import { Spinner } from './ui'

/** Stable local calendar key, `YYYY-MM-DD`. Never derived from an ISO string by slicing. */
export function dayKey(date: Date): string {
  return date.toLocaleDateString('en-CA')
}

/** Midnight, local, for the first of the month `date` falls in. */
export function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

/** Exclusive end: midnight local on the first of the following month. */
export function endOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth() + 1, 1)
}

function addMonths(date: Date, delta: number): Date {
  return new Date(date.getFullYear(), date.getMonth() + delta, 1)
}

function sameDay(a: Date, b: Date): boolean {
  return dayKey(a) === dayKey(b)
}

/**
 * Which weekday the grid starts on, taken from the reader's locale.
 *
 * <p>Sunday-first is correct in the US and wrong in India, the UK and most of Europe —
 * and a calendar whose columns are shifted by one is a calendar people misread dates
 * off. `Intl.Locale.weekInfo` knows the answer; where it is unsupported (Firefox at
 * time of writing) Monday is the better fallback for this audience.
 */
function firstDayOfWeek(): number {
  try {
    const locale = new Intl.Locale(navigator.language) as Intl.Locale & {
      weekInfo?: { firstDay: number }
      getWeekInfo?: () => { firstDay: number }
    }
    const info = locale.getWeekInfo?.() ?? locale.weekInfo
    // weekInfo counts Monday as 1 … Sunday as 7; JS getDay() has Sunday as 0.
    if (info?.firstDay) return info.firstDay % 7
  } catch {
    /* fall through */
  }
  return 1
}

/**
 * A month grid for picking a date.
 *
 * <p>Replaces a horizontal strip of "days that happen to have slots". The strip was
 * honest but shapeless: it could not show that a coach works Tuesdays and Thursdays,
 * it could not show that next week is fully booked, and it gave no sense of how far
 * ahead booking is open. A month reads all three at a glance, which is the entire
 * reason booking interfaces use one.
 *
 * <p>Days with no availability are rendered but disabled rather than hidden. Absence
 * is information — "nothing on Sundays" is something the reader should be able to
 * see, and a grid with holes punched in it is harder to read than a grid with dim
 * cells.
 *
 * <p>Keyboard handling follows the ARIA grid pattern: one tab stop for the whole
 * month, arrows to move, PageUp/PageDown to change month. Thirty-one tab stops per
 * month is what makes most custom date pickers unusable without a mouse.
 */
export function Calendar({
  month,
  onMonthChange,
  availableDays,
  selected,
  onSelect,
  minDate,
  maxDate,
  loading = false,
  labels,
}: {
  month: Date
  onMonthChange: (next: Date) => void
  /** Local `YYYY-MM-DD` keys that have at least one bookable start. */
  availableDays: Set<string>
  selected: string | null
  onSelect: (key: string) => void
  minDate: Date
  maxDate: Date
  loading?: boolean
  labels: {
    previousMonth: string
    nextMonth: string
    available: string
    unavailable: string
  }
}) {
  const weekStart = useMemo(firstDayOfWeek, [])
  const today = useMemo(() => new Date(), [])

  // The cell that owns the single tab stop. Moves with the arrow keys.
  const [focusedKey, setFocusedKey] = useState<string | null>(null)
  const gridRef = useRef<HTMLDivElement | null>(null)

  const first = startOfMonth(month)

  /*
   * Leading blanks so the first of the month lands under the right weekday, then one
   * cell per day. Trailing blanks are not padded out — an incomplete final row is
   * fine and avoids rendering days from a month the reader is not looking at.
   */
  const lead = (first.getDay() - weekStart + 7) % 7
  const daysInMonth = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate()
  const cells: (Date | null)[] = [
    ...Array.from({ length: lead }, () => null),
    ...Array.from({ length: daysInMonth }, (_, i) =>
      new Date(month.getFullYear(), month.getMonth(), i + 1)),
  ]

  const weekdayNames = useMemo(() => {
    const formatter = new Intl.DateTimeFormat(undefined, { weekday: 'short' })
    // Any known Sunday works as an anchor; 2024-01-07 was one.
    return Array.from({ length: 7 }, (_, i) =>
      formatter.format(new Date(2024, 0, 7 + ((weekStart + i) % 7))))
  }, [weekStart])

  const monthLabel = useMemo(
    () => new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' }).format(month),
    [month],
  )

  const canGoBack = startOfMonth(month) > startOfMonth(minDate)
  const canGoForward = endOfMonth(month) <= maxDate

  const isBookable = (date: Date) =>
    availableDays.has(dayKey(date)) && date >= startOfDay(minDate) && date <= maxDate

  // Keep the roving tab stop on something that exists in the month on screen.
  useEffect(() => {
    if (!focusedKey) return
    const stillHere = cells.some((c) => c && dayKey(c) === focusedKey)
    if (!stillHere) setFocusedKey(null)
    // `cells` is derived from `month`; depending on month is the honest dependency.
  }, [month]) // eslint-disable-line react-hooks/exhaustive-deps

  const tabStopKey =
    focusedKey
    ?? (selected && cells.some((c) => c && dayKey(c) === selected) ? selected : null)
    ?? dayKey(cells.find((c): c is Date => c !== null && isBookable(c)) ?? first)

  function move(from: Date, deltaDays: number) {
    const target = new Date(from.getFullYear(), from.getMonth(), from.getDate() + deltaDays)
    if (target < startOfDay(minDate) || target > maxDate) return
    if (target.getMonth() !== month.getMonth() || target.getFullYear() !== month.getFullYear()) {
      onMonthChange(startOfMonth(target))
    }
    setFocusedKey(dayKey(target))
    // Focus after the re-render that the month change may have caused.
    requestAnimationFrame(() => {
      gridRef.current
        ?.querySelector<HTMLButtonElement>(`[data-day="${dayKey(target)}"]`)
        ?.focus()
    })
  }

  function onKeyDown(event: React.KeyboardEvent, date: Date) {
    const step: Record<string, number> = {
      ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7,
    }
    if (event.key in step) {
      event.preventDefault()
      move(date, step[event.key] as number)
      return
    }
    if (event.key === 'PageUp' || event.key === 'PageDown') {
      event.preventDefault()
      const next = addMonths(month, event.key === 'PageUp' ? -1 : 1)
      if (event.key === 'PageUp' ? canGoBack : canGoForward) onMonthChange(next)
    }
  }

  /*
   * Tight padding on a phone, comfortable above it.
   *
   * A seven-column month grid at 320px is a hard geometry problem: three levels of
   * nested padding were leaving 196px for seven cells and six gaps, which is a 25px
   * tap target. Every pixel of gutter reclaimed here goes straight into the cells.
   * Forty-four is not reachable — seven of those alone exceed the viewport — so the
   * target is the WCAG 2.2 floor of 24, cleared with room rather than by a pixel.
   */
  return (
    <div className="plate p-2 sm:p-5">
      {/* ------------------------------------------------------------- header --- */}
      <div className="mb-4 flex items-center justify-between gap-3">
        <NavButton
          label={labels.previousMonth}
          disabled={!canGoBack}
          onClick={() => onMonthChange(addMonths(month, -1))}
          direction="prev"
        />
        <p
          className="display text-[15px] text-chalk"
          // Announced as one unit when the month changes, so a screen-reader user
          // hears "March 2026" rather than nothing at all.
          aria-live="polite"
        >
          {monthLabel}
        </p>
        <NavButton
          label={labels.nextMonth}
          disabled={!canGoForward}
          onClick={() => onMonthChange(addMonths(month, 1))}
          direction="next"
        />
      </div>

      {/* ---------------------------------------------------------- weekdays --- */}
      <div className="grid grid-cols-7 gap-0.5 sm:gap-1">
        {weekdayNames.map((name) => (
          <div
            key={name}
            aria-hidden="true"
            className="pb-1.5 text-center text-[10.5px] font-semibold uppercase tracking-[0.1em] text-chalk-faint"
          >
            {name.slice(0, 2)}
          </div>
        ))}
      </div>

      {/* -------------------------------------------------------------- grid --- */}
      <div ref={gridRef} className="relative grid grid-cols-7 gap-0.5 sm:gap-1" role="grid" aria-label={monthLabel}>
        {loading && (
          <div className="absolute inset-0 z-10 grid place-items-center rounded-edge bg-paper backdrop-blur-[1px]">
            <Spinner size={20} className="text-chalk-muted" />
          </div>
        )}

        {cells.map((date, index) => {
          if (!date) return <div key={`pad-${index}`} role="gridcell" aria-hidden="true" />

          const key = dayKey(date)
          const bookable = isBookable(date)
          const isSelected = selected === key
          const isToday = sameDay(date, today)

          return (
            <button
              key={key}
              type="button"
              role="gridcell"
              data-day={key}
              disabled={!bookable}
              tabIndex={key === tabStopKey ? 0 : -1}
              aria-selected={isSelected}
              aria-label={`${new Intl.DateTimeFormat(undefined, {
                weekday: 'long', day: 'numeric', month: 'long',
              }).format(date)} — ${bookable ? labels.available : labels.unavailable}`}
              onClick={() => bookable && onSelect(key)}
              onKeyDown={(event) => onKeyDown(event, date)}
              onFocus={() => setFocusedKey(key)}
              className={[
                'relative grid aspect-square place-items-center rounded-edge text-[13px]',
                'tnum font-medium transition-[background-color,color,transform] duration-200',
                'ease-out-expo',
                isSelected
                  ? 'bg-brand-500 text-paper shadow-glow'
                  : bookable
                    ? 'bg-ink-700 text-chalk hover:bg-ink-500 active:scale-95'
                    /*
                     * Unavailable days are dim, not illegible. At half opacity this
                     * measured 2.02:1 on the light theme — technically exempt as a
                     * disabled control, but these are dates a reader is scanning to
                     * work out which days are open, so the number has to be readable.
                     * Full-strength faint still sits clearly below the bookable days,
                     * which carry a white ground and a dot.
                     */
                    : 'text-chalk-faint',
                // Today is marked with a ring rather than a fill, so it never competes
                // with the selected day for the same visual language.
                isToday && !isSelected ? 'ring-1 ring-inset ring-ink-300' : '',
              ].join(' ')}
            >
              {date.getDate()}
              {/*
                A dot under bookable days. Availability is already carried by the
                lighter ground, but ground alone is a colour-only signal — the dot is
                what makes it survive a monochrome display or low vision. WCAG 1.4.1.
              */}
              {bookable && !isSelected && (
                <span
                  aria-hidden="true"
                  className="absolute bottom-1 h-1 w-1 rounded-full bg-brand-500"
                />
              )}
            </button>
          )
        })}
      </div>
    </div>
  )
}

/** Midnight local, so a comparison against `minDate` does not exclude today. */
function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

function NavButton({
  label, disabled, onClick, direction,
}: {
  label: string
  disabled: boolean
  onClick: () => void
  direction: 'prev' | 'next'
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      // 44px, not 36. These are the controls a phone user taps to page through
      // months, and they sit right beside the day grid — an undersized target next
      // to a dense one is where mis-taps come from.
      className="grid h-11 w-11 shrink-0 place-items-center rounded-edge border border-ink-400
                 bg-ink-700 text-chalk-muted transition-colors duration-200
                 hover:border-ink-300 hover:text-chalk
                 disabled:pointer-events-none disabled:opacity-30"
    >
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
           strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d={direction === 'prev' ? 'M15 6l-6 6 6 6' : 'M9 6l6 6-6 6'} />
      </svg>
    </button>
  )
}
