/**
 * Display helpers.
 *
 * The server sends both a raw minor-unit integer and a pre-formatted string for
 * every amount. The formatted string is what gets rendered: currency formatting is
 * a money decision, and having the browser re-derive it from the integer is how a
 * receipt ends up disagreeing with an invoice. The integer is there for arithmetic
 * the UI genuinely needs, such as drawing a bar.
 */

const INR = new Intl.NumberFormat('en-IN')

export function points(value: number): string {
  return INR.format(value)
}

export function relativeTime(iso: string): string {
  const then = new Date(iso).getTime()
  const seconds = Math.round((then - Date.now()) / 1000)
  const abs = Math.abs(seconds)

  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['second', 60],
    ['minute', 60],
    ['hour', 24],
    ['day', 7],
    ['week', 4.35],
    ['month', 12],
    ['year', Number.POSITIVE_INFINITY],
  ]

  let value = seconds
  for (const [unit, step] of units) {
    if (Math.abs(value) < step) {
      return new Intl.RelativeTimeFormat('en', { numeric: 'auto' }).format(Math.round(value), unit)
    }
    value /= step
  }
  return new Date(iso).toLocaleDateString()
  void abs
}

export function dateTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function bpsToPercent(bps: number): string {
  const pct = bps / 100
  return `${Number.isInteger(pct) ? pct : pct.toFixed(2)}%`
}

/** 3.50 -> "3.5", 4.00 -> "4" */
export function trimNumber(value: string | number): string {
  const n = typeof value === 'string' ? Number.parseFloat(value) : value
  if (Number.isNaN(n)) return String(value)
  return String(Number(n.toFixed(2)))
}

export function coinsLabel(millions: number): string {
  if (millions >= 1) return `${trimNumber(millions)}M coins`
  return `${Math.round(millions * 1000)}K coins`
}
