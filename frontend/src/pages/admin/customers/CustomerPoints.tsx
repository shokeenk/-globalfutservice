import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../../lib/api'
import type { PointsBreakdown, PointsHistoryPage, PointsKind } from '../../../lib/types'
import { buttonClasses } from '../ui/controls'
import { shortDate } from '../ui/format'

const KIND: Record<PointsKind, string> = {
  EARNED: 'Earned',
  SPENT: 'Spent',
  RETURNED: 'Returned',
  DAILY_BONUS: 'Daily bonus',
  ADJUSTMENT: 'Adjustment',
  REMOVED: 'Removed',
}

const PAGE_SIZE = 25

const n = (v: number) => v.toLocaleString('en-IN')
const signed = (v: number) => (v > 0 ? `+${n(v)}` : `−${n(Math.abs(v))}`)

/**
 * A customer's points as they see them: the same breakdown, what is on the way, and the
 * full history with the balance after each line. Enough to answer "where did my points
 * go?" without reading the database.
 */
export function CustomerPoints({ customerKey }: { customerKey: string }) {
  const base = `/api/v1/admin/customers/${encodeURIComponent(customerKey)}/points`
  const [breakdown, setBreakdown] = useState<PointsBreakdown | null>(null)
  const [history, setHistory] = useState<PointsHistoryPage | null>(null)
  const [page, setPage] = useState(0)
  const [error, setError] = useState(false)

  useEffect(() => {
    setPage(0)
    setError(false)
    setBreakdown(null)
    api.get<PointsBreakdown>(base).then(setBreakdown).catch(() => setError(true))
  }, [base])

  const loadHistory = useCallback((which: number) => {
    api.get<PointsHistoryPage>(`${base}/history?page=${which}&size=${PAGE_SIZE}`)
      .then(setHistory)
      .catch(() => setError(true))
  }, [base])

  useEffect(() => loadHistory(page), [loadHistory, page])

  const pages = history ? Math.max(1, Math.ceil(history.total / history.size)) : 1
  const b = breakdown

  return (
    <section aria-labelledby="customer-points" className="mt-6" data-testid="customer-points">
      <h3 id="customer-points" className="mb-2 text-[15px] font-semibold text-admin-ink">Points</h3>
      {error ? (
        <p role="alert" className="rounded-admin-control bg-admin-page px-3.5 py-3 text-[13px] text-admin-muted">
          Could not load this customer&apos;s points.
        </p>
      ) : !b ? (
        <span className="block h-24 animate-pulse rounded bg-admin-grey-tint" />
      ) : (
        <>
          <dl className="space-y-1.5 rounded-admin-control bg-admin-page px-3.5 py-3 text-[13px]">
            {([
              ['Available now', n(b.available)],
              ['Earned (all time)', n(b.earned)],
              ['Spent at checkout', b.spent ? `−${n(b.spent)}` : '0'],
              ['Returned from refunds', b.returned ? `+${n(b.returned)}` : '0'],
              ['Removed by staff', b.removed ? `−${n(b.removed)}` : '0'],
              ['On the way', n(b.onTheWay)],
            ] as const).map(([label, value]) => (
              <div key={label} className="flex justify-between gap-3">
                <dt className="text-admin-muted">{label}</dt>
                <dd className="font-semibold tabular-nums text-admin-ink">{value}</dd>
              </div>
            ))}
          </dl>
          <p className="mt-1.5 text-[12px] text-admin-faint" data-testid="customer-points-sum">
            {n(b.earned)} earned − {n(b.spent)} spent + {n(b.returned)} returned − {n(b.removed)} removed
            {' '}= {n(b.available)} available
          </p>
          {b.pending.length > 0 && (
            <ul className="mt-2 space-y-1 text-[12.5px]">
              {b.pending.map((item) => (
                <li key={item.orderRef} className="flex justify-between gap-3">
                  <Link to={`/admin/orders/${item.orderRef}`} className="font-medium text-admin-ink hover:underline">
                    #{item.orderRef}
                  </Link>
                  <span className="tabular-nums text-admin-muted">
                    +{n(item.points)} · {item.landsAt ? `lands ${shortDate(item.landsAt)}` : 'lands after delivery + guarantee'}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {history && history.total > 0 && (
        <div className="mt-4">
          <table className="w-full text-left text-[12.5px]">
            <caption className="sr-only">Points history, newest first</caption>
            <thead className="text-[11.5px] text-admin-faint">
              <tr>
                <th scope="col" className="pb-1 font-medium">Date</th>
                <th scope="col" className="pb-1 font-medium">What</th>
                <th scope="col" className="pb-1 text-right font-medium">Points</th>
                <th scope="col" className="pb-1 text-right font-medium">Balance</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-admin-line" data-testid="customer-points-history">
              {history.entries.map((line) => (
                <tr key={line.id}>
                  <td className="py-1.5 pr-2 text-admin-muted">{shortDate(line.at)}</td>
                  <td className="py-1.5 pr-2 text-admin-ink">
                    {KIND[line.kind]}
                    {line.orderRef && (
                      <Link to={`/admin/orders/${line.orderRef}`} className="block text-[11.5px] text-admin-muted hover:underline">
                        #{line.orderRef}
                      </Link>
                    )}
                  </td>
                  <td className="py-1.5 text-right font-semibold tabular-nums text-admin-ink">{signed(line.amount)}</td>
                  <td className="py-1.5 text-right tabular-nums text-admin-muted">{n(line.balanceAfter)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {pages > 1 && (
            <div className="mt-2 flex items-center justify-between text-[12px] text-admin-muted">
              <button type="button" className={buttonClasses('outline', 'sm')} disabled={page === 0}
                onClick={() => setPage(page - 1)}>Newer</button>
              <span className="tabular-nums">Page {page + 1} of {pages}</span>
              <button type="button" className={buttonClasses('outline', 'sm')} disabled={page + 1 >= pages}
                onClick={() => setPage(page + 1)}>Older</button>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
