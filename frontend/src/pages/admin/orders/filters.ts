import type { AdminOrderOverview } from '../../../lib/types'
import { SERVICE_TABS, STATUS_TABS } from '../ui/status'

/**
 * The Orders page's filters, kept in the address.
 *
 * <p>The URL is the one place the filters live. That is what makes a saved view, the
 * bell's link to Needs Attention and the top bar's search all the same thing: a link to
 * this page with some parameters on it. It also means a reload, or a link pasted to a
 * colleague, shows exactly the same table.
 */
export interface OrderFilters {
  /** COINS, BOOSTING, CHAMPS, RIVALS, COACHING, or '' for all. */
  service: string
  /** Comma-separated real statuses, or '' for all. */
  status: string
  platform: string
  /** YYYY-MM-DD, India time. */
  from: string
  to: string
  search: string
  attention: boolean
  /** Zero-based. */
  page: number
}

export const EMPTY: OrderFilters = {
  service: '', status: '', platform: '', from: '', to: '', search: '', attention: false, page: 0,
}

export const PAGE_SIZE = 25

export function readFilters(params: URLSearchParams): OrderFilters {
  const page = Number.parseInt(params.get('page') ?? '', 10)
  return {
    service: params.get('service') ?? '',
    status: params.get('status') ?? '',
    platform: params.get('platform') ?? '',
    from: params.get('from') ?? '',
    to: params.get('to') ?? '',
    search: params.get('search') ?? '',
    attention: params.get('attention') === '1',
    // One-based in the address, for people; zero-based everywhere else.
    page: Number.isFinite(page) && page > 1 ? page - 1 : 0,
  }
}

/** The address for a set of filters, leaving out everything at its default. */
export function writeFilters(f: OrderFilters): URLSearchParams {
  const out = new URLSearchParams()
  if (f.service) out.set('service', f.service)
  if (f.status) out.set('status', f.status)
  if (f.platform) out.set('platform', f.platform)
  if (f.from) out.set('from', f.from)
  if (f.to) out.set('to', f.to)
  if (f.search.trim()) out.set('search', f.search.trim())
  if (f.attention) out.set('attention', '1')
  if (f.page > 0) out.set('page', String(f.page + 1))
  return out
}

/** The API query for the table (with paging) or the export (without). */
export function apiQuery(f: OrderFilters, paged = true): string {
  const out = new URLSearchParams()
  if (f.service) out.set('service', f.service)
  if (f.status) out.set('status', f.status)
  if (f.platform) out.set('platform', f.platform)
  if (f.from) out.set('from', f.from)
  if (f.to) out.set('to', f.to)
  if (f.search.trim()) out.set('search', f.search.trim())
  if (f.attention) out.set('attention', 'true')
  if (paged) {
    out.set('page', String(f.page))
    out.set('size', String(PAGE_SIZE))
  }
  return out.toString()
}

/** Whether anything is narrowing the table. */
export function isFiltered(f: OrderFilters): boolean {
  return Boolean(f.service || f.status || f.platform || f.from || f.to || f.search.trim() || f.attention)
}

const sameSet = (a: string[], b: string[]) =>
  a.length === b.length && [...a].sort().join() === [...b].sort().join()

/** Which status tab the filter is, or null when it is a status no tab covers, e.g. Refunded. */
export function statusTabFor(status: string): string | null {
  const statuses = status ? status.split(',').filter(Boolean) : []
  return STATUS_TABS.find((tab) => sameSet(tab.statuses, statuses))?.key ?? null
}

/** Which service tab the filter is; Champs or Rivals alone light no tab. */
export function serviceTabFor(service: string): string | null {
  return SERVICE_TABS.some((tab) => tab.key === service) ? service : null
}

/** The SKUs a service filter covers, including the dropdown's finer values. */
export function skusFor(service: string): string[] {
  switch (service) {
    case 'CHAMPS': return ['BOOST_CHAMPS']
    case 'RIVALS': return ['BOOST_RIVALS']
    default: return SERVICE_TABS.find((tab) => tab.key === service)?.skus ?? []
  }
}

/**
 * The count beside a tab, from the overview's counts by service and status.
 *
 * <p>Service tabs count every status; status tabs count within the chosen service, which
 * is how the reference's two rows relate.
 */
export function countFor(
  overview: AdminOrderOverview, skus: string[], statuses: string[],
): number {
  return overview.counts
    .filter((c) => (skus.length === 0 || skus.includes(c.sku))
      && (statuses.length === 0 || statuses.includes(c.status)))
    .reduce((sum, c) => sum + c.count, 0)
}

/** The filters a saved view keeps: everything but the page number. */
export function toSaved(f: OrderFilters): Record<string, string> {
  const out: Record<string, string> = {}
  for (const key of ['service', 'status', 'platform', 'from', 'to', 'search'] as const) {
    if (f[key]) out[key] = f[key]
  }
  if (f.attention) out.attention = '1'
  return out
}

export function fromSaved(saved: Record<string, string>): OrderFilters {
  return readFilters(new URLSearchParams(saved))
}

/** "26 Sep – 27 Sep" for the date-range button. */
export function rangeLabel(from: string, to: string): string {
  const fmt = (d: string) => new Date(`${d}T00:00:00Z`)
    .toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' })
  if (from && to) return from === to ? fmt(from) : `${fmt(from)} – ${fmt(to)}`
  if (from) return `From ${fmt(from)}`
  if (to) return `Until ${fmt(to)}`
  return 'Select Date'
}

/**
 * "2 Payments • 3 Sign-ins • 1 Disputed", leaving out what is zero: the card is read at a
 * glance, and "0 Disputed" is a word to read that says nothing.
 */
export function attentionBreakdown(o: AdminOrderOverview): string {
  const parts = [
    [o.paymentsToCheck, 'Payment', 'Payments'],
    [o.signInsToWork, 'Sign-in', 'Sign-ins'],
    [o.disputed, 'Disputed', 'Disputed'],
  ] as const
  const said = parts.filter(([n]) => n > 0).map(([n, one, many]) => `${n} ${n === 1 ? one : many}`)
  return said.length ? said.join(' • ') : 'Nothing waiting'
}
