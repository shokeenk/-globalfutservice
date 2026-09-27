import type { AdminPayment, PaymentTotal } from '../../../lib/types'

const SYMBOL: Record<string, string> = { INR: '₹', USD: '$', EUR: '€', GBP: '£' }

/**
 * An amount in minor units, written the way the server writes it: symbol, Indian grouping
 * for rupees (₹3,42,850.00) and Western for the rest ($1,250.00), two decimals.
 *
 * <p>Needed on this side only for sums the page makes itself, adding statuses together
 * within one currency. Nothing here adds currencies together.
 */
export function formatMinor(minor: number, currency: string): string {
  const negative = minor < 0
  const abs = Math.abs(minor)
  const whole = Math.floor(abs / 100).toString()
  const fraction = (abs % 100).toString().padStart(2, '0')
  let grouped: string
  if (currency === 'INR' && whole.length > 3) {
    const last3 = whole.slice(-3)
    const rest = whole.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',')
    grouped = `${rest},${last3}`
  } else {
    grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  }
  return `${negative ? '-' : ''}${SYMBOL[currency] ?? `${currency} `}${grouped}.${fraction}`
}

export interface Summed {
  count: number
  /** In the one currency asked for. Zero for anyone the server sends no amounts to. */
  minor: number
}

/** How many payments, and how much in one currency, across the given statuses. */
export function sum(totals: PaymentTotal[], statuses: AdminPayment['status'][], currency = 'INR'): Summed {
  let count = 0
  let minor = 0
  for (const t of totals) {
    if (!statuses.includes(t.status)) continue
    count += t.count
    if (t.currency === currency && t.minor != null) minor += t.minor
  }
  return { count, minor }
}

/** Every currency other than rupees with money in it, for the line beneath a rupee figure. */
export function otherCurrencies(totals: PaymentTotal[], statuses: AdminPayment['status'][]): string[] {
  const out: Record<string, number> = {}
  for (const t of totals) {
    if (t.currency === 'INR' || !statuses.includes(t.status) || t.minor == null) continue
    out[t.currency] = (out[t.currency] ?? 0) + t.minor
  }
  return Object.entries(out).filter(([, minor]) => minor > 0).map(([currency, minor]) => formatMinor(minor, currency))
}

export const ALL_STATUSES: AdminPayment['status'][] = ['SUCCESS', 'PENDING', 'FAILED', 'REFUNDED']
