import type { AdminPayment, PaymentTotal } from '../../../lib/types'
import { formatMinor } from '../../../lib/money'

/**
 * Sums the page makes itself, adding statuses together within one currency, written the
 * way the server writes amounts. Nothing here adds currencies together.
 */
export { formatMinor }

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
