import type { ReactNode } from 'react'
import { LuChevronLeft, LuChevronRight, LuCircleAlert, LuInbox } from 'react-icons/lu'
import { AdminButton } from './controls'

/**
 * The white card every console table sits in.
 *
 * <p>The table scrolls sideways inside the card rather than pushing the page wider, so on
 * a phone the header, filters and pagination stay put and only the columns move.
 */
export function TableCard({ children, footer }: { children: ReactNode; footer?: ReactNode }) {
  return (
    <div className="overflow-hidden rounded-admin-card border border-admin-line bg-white shadow-admin-card">
      <div className="overflow-x-auto">{children}</div>
      {footer}
    </div>
  )
}

/** Column heading, in the references' small grey capitals. */
export function Th({ children, className = '' }: { children?: ReactNode; className?: string }) {
  return (
    <th
      scope="col"
      className={`whitespace-nowrap px-4 py-3 text-left text-admin-th font-semibold uppercase text-admin-faint ${className}`}
    >
      {children}
    </th>
  )
}

/**
 * What a table shows instead of rows: loading, failed, or nothing to show.
 *
 * <p>A failed read says so and offers to try again. It never shows an empty table,
 * which would read as "no orders" when the truth is "could not ask".
 */
export function TableState({
  kind, title, children, onRetry, columns,
}: {
  kind: 'loading' | 'error' | 'empty'
  title?: string
  children?: ReactNode
  onRetry?: () => void
  columns: number
}) {
  if (kind === 'loading') {
    return (
      <tbody aria-busy="true">
        {Array.from({ length: 5 }, (_, i) => (
          <tr key={i} className="border-t border-admin-line">
            <td colSpan={columns} className="px-4 py-4">
              <span className="block h-4 w-full animate-pulse rounded bg-admin-grey-tint" />
            </td>
          </tr>
        ))}
      </tbody>
    )
  }
  const Icon = kind === 'error' ? LuCircleAlert : LuInbox
  return (
    <tbody>
      <tr className="border-t border-admin-line">
        <td colSpan={columns} className="px-6 py-14 text-center">
          <Icon
            aria-hidden="true"
            className={`mx-auto h-7 w-7 ${kind === 'error' ? 'text-admin-red-icon' : 'text-admin-faint'}`}
          />
          <p role={kind === 'error' ? 'alert' : undefined} className="mt-3 text-[14px] font-semibold text-admin-ink">
            {title}
          </p>
          {children && <div className="mx-auto mt-1 max-w-md text-[13px] text-admin-muted">{children}</div>}
          {onRetry && (
            <AdminButton size="sm" className="mt-4" onClick={onRetry}>Try again</AdminButton>
          )}
        </td>
      </tr>
    </tbody>
  )
}

/**
 * The page numbers to show: always the first and last, the current one and its
 * neighbours, and a gap marker wherever pages are skipped. Zero-based in, zero-based out.
 */
export function pageList(current: number, pages: number): Array<number | 'gap'> {
  if (pages <= 7) return Array.from({ length: pages }, (_, i) => i)
  const keep = new Set([0, pages - 1, current - 1, current, current + 1].filter((p) => p >= 0 && p < pages))
  if (current <= 2) [1, 2, 3].forEach((p) => keep.add(p))
  if (current >= pages - 3) [pages - 4, pages - 3, pages - 2].forEach((p) => keep.add(p))
  const sorted = [...keep].sort((a, b) => a - b)
  const out: Array<number | 'gap'> = []
  sorted.forEach((p, i) => {
    if (i > 0 && p - sorted[i - 1]! > 1) out.push('gap')
    out.push(p)
  })
  return out
}

/** "Showing 1–25 of 128" and the page numbers. */
export function Pagination({
  page, size, total, onPage, noun,
}: {
  page: number
  size: number
  total: number
  onPage: (page: number) => void
  /** "orders", "customers": what is being counted. */
  noun: string
}) {
  const pages = Math.max(1, Math.ceil(total / size))
  const first = total === 0 ? 0 : page * size + 1
  const last = Math.min(total, (page + 1) * size)
  const cell = 'grid h-8 min-w-8 place-items-center rounded-[7px] px-2 text-[13px] tabular-nums focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red'

  return (
    <nav
      aria-label="Pagination"
      className="flex flex-wrap items-center justify-between gap-3 border-t border-admin-line px-4 py-3"
    >
      <p className="text-[13px] text-admin-muted">
        Showing {first.toLocaleString('en-IN')}–{last.toLocaleString('en-IN')} of{' '}
        {total.toLocaleString('en-IN')} {noun}
      </p>
      {pages > 1 && (
        <ul className="flex items-center gap-1.5">
          <li>
            <button
              type="button"
              aria-label="Previous page"
              disabled={page === 0}
              onClick={() => onPage(page - 1)}
              className={`${cell} border border-admin-line text-admin-ink hover:bg-admin-page disabled:opacity-40`}
            >
              <LuChevronLeft aria-hidden="true" className="h-4 w-4" />
            </button>
          </li>
          {pageList(page, pages).map((p, i) => (
            <li key={p === 'gap' ? `gap-${i}` : p}>
              {p === 'gap' ? (
                <span aria-hidden="true" className="px-1 text-admin-faint">…</span>
              ) : (
                <button
                  type="button"
                  aria-label={`Page ${p + 1}`}
                  aria-current={p === page ? 'page' : undefined}
                  onClick={() => onPage(p)}
                  className={`${cell} border ${p === page
                    ? 'border-admin-red bg-admin-red font-semibold text-white'
                    : 'border-admin-line text-admin-ink hover:bg-admin-page'}`}
                >
                  {p + 1}
                </button>
              )}
            </li>
          ))}
          <li>
            <button
              type="button"
              aria-label="Next page"
              disabled={page >= pages - 1}
              onClick={() => onPage(page + 1)}
              className={`${cell} border border-admin-line text-admin-ink hover:bg-admin-page disabled:opacity-40`}
            >
              <LuChevronRight aria-hidden="true" className="h-4 w-4" />
            </button>
          </li>
        </ul>
      )}
    </nav>
  )
}
