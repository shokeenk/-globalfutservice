/**
 * Times and trends, the way the console shows them.
 *
 * <p>Times are shown in India time whatever the browser's zone, because the business
 * runs on it: the date filter's days are Indian days, the Discord tickets are stamped in
 * it, and an order "placed today" should mean the same thing on every screen.
 */
export const BUSINESS_ZONE = 'Asia/Kolkata'

const SHORT = new Intl.DateTimeFormat('en-US', {
  month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: true,
  timeZone: BUSINESS_ZONE,
})

/** "Sep 26, 03:34 PM", in India time. */
export function shortDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return SHORT.format(new Date(iso))
}

const DATE_ONLY = new Intl.DateTimeFormat('en-US', {
  month: 'short', day: '2-digit', year: 'numeric', timeZone: BUSINESS_ZONE,
})
const DATE_TIME = new Intl.DateTimeFormat('en-US', {
  month: 'short', day: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: true,
  timeZone: BUSINESS_ZONE,
})

/** "Sep 26, 2026", in India time. */
export function shortDate(iso: string | null | undefined): string {
  return iso ? DATE_ONLY.format(new Date(iso)) : '—'
}

/** "Aug 12, 2026, 03:14 PM", in India time. */
export function dateAndTime(iso: string | null | undefined): string {
  return iso ? DATE_TIME.format(new Date(iso)) : '—'
}

const DAY = new Intl.DateTimeFormat('en-CA', { timeZone: BUSINESS_ZONE })

/** Today's date in India as YYYY-MM-DD, the value a date input holds. */
export function todayInIndia(now: Date = new Date()): string {
  return DAY.format(now)
}

function plural(n: number, unit: string): string {
  return `${n} ${unit}${n === 1 ? '' : 's'} ago`
}

/** "2 mins ago", "1 hour ago", "3 days ago". Rounded down, never into the future. */
export function ago(iso: string, now: number = Date.now()): string {
  const seconds = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000))
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return plural(minutes, 'min')
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return plural(hours, 'hour')
  const days = Math.floor(hours / 24)
  if (days < 30) return plural(days, 'day')
  const months = Math.floor(days / 30)
  if (months < 12) return plural(months, 'month')
  return plural(Math.floor(days / 365), 'year')
}

export interface Trend {
  direction: 'up' | 'down' | 'flat'
  /** Whole percent, or null when there is nothing to compare against. */
  percent: number | null
}

/**
 * How today compares with yesterday.
 *
 * <p>From zero to something has no percentage — "up 100%" and "up 2,400%" would both be
 * made up — so it is reported as up with no number.
 */
export function trend(current: number, previous: number): Trend {
  if (current === previous) return { direction: 'flat', percent: 0 }
  if (previous === 0) return { direction: 'up', percent: null }
  const percent = Math.round(Math.abs(current - previous) / previous * 100)
  return { direction: current > previous ? 'up' : 'down', percent }
}
