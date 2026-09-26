import { useEffect, useState } from 'react'
import { Skeleton } from '../../components/ui'
import { ApiError, api } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import type { AdminRevenue } from '../../lib/types'
import { AdminPage } from './shell/AdminPage'

/**
 * Figures about the business, as opposed to the work queue. Admin only: the route is
 * wrapped in RequireAdmin, and the endpoint behind it refuses anyone else.
 *
 * <p>The revenue card came here from the Orders page, unchanged in what it counts. The
 * queue is where an operator decides what to do next; how much the business took this
 * month does not help with that, and it is not an operator's figure to see.
 */
export default function AdminAnalytics() {
  useSeo({ title: 'Analytics', noindex: true })
  return (
    <AdminPage eyebrow="Analytics" title="Analytics" description="How the business is doing.">
      <RevenueCard />
    </AdminPage>
  )
}

function RevenueCard() {
  const [revenue, setRevenue] = useState<AdminRevenue | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    api.get<AdminRevenue>('/api/v1/admin/analytics/revenue')
      .then((r) => { if (live) setRevenue(r) })
      .catch((e) => {
        if (live) setError(e instanceof ApiError ? e.message : 'Could not load the revenue figure.')
      })
    return () => { live = false }
  }, [])

  return (
    <section
      aria-labelledby="revenue-30d"
      className="rounded-admin-card border border-admin-line bg-white p-5 shadow-admin-card"
    >
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 id="revenue-30d" className="text-admin-eyebrow font-medium uppercase text-admin-faint">
            Revenue, last 30 days
          </h2>
          {revenue && (
            <p className="mt-2 text-[32px] font-bold leading-none tabular-nums text-admin-ink">
              {revenue.revenueLast30dFormatted}
            </p>
          )}
          {!revenue && !error && <Skeleton className="mt-2 h-8 w-40" />}
          {error && <p role="alert" className="mt-2 text-[13px] text-admin-red-text">{error}</p>}
        </div>
        <p className="max-w-md text-[12.5px] leading-relaxed text-admin-muted">
          Counts delivered and completed orders. Orders inside their guarantee window are
          included — the money is taken, but the loyalty points have not settled yet.
        </p>
      </div>
    </section>
  )
}
