import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { LuArrowRight, LuChartColumn, LuCircleCheck, LuClock, LuFileText } from 'react-icons/lu'
import { api } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import { useAuth } from '../../state/AuthContext'
import type { AdminDashboard as Dashboard, AdminOrderRow, CurrencyRevenue } from '../../lib/types'
import { AdminPage } from './shell/AdminPage'
import { OrderStatusBadge, PaymentBadge } from './ui/Badge'
import { ago, shortDateTime } from './ui/format'
import { PLATFORM_LABEL } from './ui/status'
import { StatCard, TrendBlock } from './ui/StatCard'
import { TableCard, TableState, Th } from './ui/Table'

/**
 * The console's first page: today in four figures and two short lists.
 *
 * <p>Every "vs yesterday" is today so far against yesterday up to the same time, so a
 * figure is not "down" at nine in the morning just because the day has barely started.
 *
 * <p><b>Today's Revenue is an admin's.</b> For an operator the card is not drawn and its
 * endpoint is never called; the server refuses it anyway. It follows the rule the 30-day
 * figure on Analytics uses — delivered and completed orders, dated by when they were
 * placed — and shows rupees large with any other currency beneath, because there are no
 * exchange rates to add them together with.
 */
export default function AdminDashboard() {
  useSeo({ title: 'Dashboard', noindex: true })
  const { account } = useAuth()
  const isAdmin = account?.role === 'ADMIN'

  const [data, setData] = useState<Dashboard | null>(null)
  const [failed, setFailed] = useState(false)
  const [revenue, setRevenue] = useState<CurrencyRevenue[] | null>(null)
  const [revenueFailed, setRevenueFailed] = useState(false)
  const [now, setNow] = useState(() => Date.now())

  const load = useCallback(async () => {
    try {
      setData(await api.get<Dashboard>('/api/v1/admin/dashboard'))
      setFailed(false)
    } catch {
      setFailed(true)
    }
    setNow(Date.now())
  }, [])

  const loadRevenue = useCallback(async () => {
    if (!isAdmin) return
    try {
      setRevenue(await api.get<CurrencyRevenue[]>('/api/v1/admin/dashboard/revenue'))
      setRevenueFailed(false)
    } catch {
      setRevenueFailed(true)
    }
  }, [isAdmin])

  useEffect(() => {
    void load()
    void loadRevenue()
    const timer = setInterval(() => { void load(); void loadRevenue() }, 30_000)
    return () => clearInterval(timer)
  }, [load, loadRevenue])

  const countsFailed = failed && !data
  const inr = revenue?.find((r) => r.currency === 'INR') ?? null
  const others = revenue?.filter((r) => r.currency !== 'INR' && (r.todayMinor > 0 || r.yesterdayMinor > 0)) ?? []

  return (
    <AdminPage eyebrow="Dashboard" title="Dashboard" description="Overview of your orders and activity.">
      <div className={`mb-5 grid grid-cols-1 gap-3.5 sm:grid-cols-2 ${isAdmin ? 'xl:grid-cols-4' : 'xl:grid-cols-3'}`}>
        <StatCard
          size="lg" icon={LuFileText} tone="red" label="New Orders" failed={countsFailed}
          value={data?.newToday ?? null}
          aside={data && <TrendBlock current={data.newToday} previous={data.newYesterdaySoFar} against="yesterday" />}
        />
        <StatCard
          size="lg" icon={LuClock} tone="amber" label="Pending Orders" failed={countsFailed}
          value={data?.pending ?? null}
          // More waiting than yesterday is not good news: the reference draws it in red.
          aside={data && <TrendBlock current={data.pending} previous={data.pendingYesterday} against="yesterday" good="down" />}
        />
        <StatCard
          size="lg" icon={LuCircleCheck} tone="green" label="Completed Today" failed={countsFailed}
          value={data?.deliveredToday ?? null}
          aside={data && (
            <TrendBlock current={data.deliveredToday} previous={data.deliveredYesterdaySoFar} against="yesterday" />
          )}
        />
        {isAdmin && (
          <StatCard
            size="lg" icon={LuChartColumn} tone="red" label="Today's Revenue" failed={revenueFailed && !revenue}
            value={inr?.todayFormatted ?? null}
            aside={inr && <TrendBlock current={inr.todayMinor} previous={inr.yesterdayMinor} against="yesterday" />}
            sub={others.length > 0 ? `+ ${others.map((o) => o.todayFormatted).join(' · ')} today` : undefined}
          />
        )}
      </div>

      <OrderList
        title="New Orders"
        subtitle="The latest orders placed"
        failed={countsFailed}
        onRetry={() => void load()}
        rows={data?.newest.map((order) => ({ order, when: order.createdAt })) ?? null}
        status={(order) => <PaymentBadge state={order.paymentState} />}
        statusHeading="Payment"
        timeHeading="Time"
        time={(when) => ago(when, now)}
      />

      <OrderList
        title="Recent Orders"
        subtitle="The orders whose status changed most recently"
        failed={countsFailed}
        onRetry={() => void load()}
        rows={data?.recent.map((r) => ({ order: r.order, when: r.changedAt })) ?? null}
        status={(order) => <OrderStatusBadge status={order.status} />}
        statusHeading="Status"
        timeHeading="Date"
        time={(when) => shortDateTime(when)}
      />
    </AdminPage>
  )
}

const COLUMNS = 8

/**
 * One of the dashboard's two short tables. Five rows and a way to the full list.
 */
function OrderList({
  title, subtitle, rows, failed, onRetry, status, statusHeading, timeHeading, time,
}: {
  title: string
  subtitle: string
  rows: { order: AdminOrderRow; when: string }[] | null
  failed: boolean
  onRetry: () => void
  status: (order: AdminOrderRow) => ReactNode
  statusHeading: string
  timeHeading: string
  time: (when: string) => string
}) {
  const id = `dashboard-${title.toLowerCase().replace(/\s+/g, '-')}`
  return (
    <section aria-labelledby={id} className="mb-5">
      <TableCard header={(
        <div className="flex items-center justify-between gap-3 px-5 py-4">
          <div className="min-w-0">
            <h2 id={id} className="flex items-center gap-2.5 text-[17px] font-semibold text-admin-ink">
              <span aria-hidden="true" className="h-2.5 w-2.5 rounded-full bg-admin-red" />
              {title}
            </h2>
            <p className="ml-5 text-[12px] text-admin-faint">{subtitle}</p>
          </div>
          <Link
            to="/admin/orders"
            className="inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-admin-control px-1 text-[13.5px] font-medium
                       text-admin-red-text hover:underline focus-visible:outline-none focus-visible:ring-2
                       focus-visible:ring-admin-red"
          >
            View all<span className="sr-only"> orders</span>
            <LuArrowRight aria-hidden="true" className="h-4 w-4" />
          </Link>
        </div>
      )}>
        <table className="w-full min-w-[900px] border-collapse text-admin-cell">
          <thead className="bg-[#FAFBFC]">
            <tr className="border-y border-admin-line">
              <Th>Order ID</Th>
              <Th>Service</Th>
              <Th>Customer</Th>
              <Th>Amount</Th>
              <Th>Platform</Th>
              <Th>{statusHeading}</Th>
              <Th>{timeHeading}</Th>
              <Th className="text-right">Action</Th>
            </tr>
          </thead>
          {failed ? (
            <TableState kind="error" columns={COLUMNS} title="Could not load orders" onRetry={onRetry}>
              Nothing has changed; try again.
            </TableState>
          ) : rows === null ? (
            <TableState kind="loading" columns={COLUMNS} />
          ) : rows.length === 0 ? (
            <TableState kind="empty" columns={COLUMNS} title="No orders yet">
              When a customer places one it will appear here.
            </TableState>
          ) : (
            <tbody>
              {rows.map(({ order, when }) => (
                <tr key={order.publicRef} className="border-b border-admin-line last:border-b-0">
                  <td className="whitespace-nowrap px-3 py-3">
                    <Link to={`/admin/orders/${order.publicRef}`} className="font-semibold text-admin-ink hover:underline">
                      #{order.publicRef}
                    </Link>
                  </td>
                  <td className="max-w-[240px] truncate px-3 py-3 text-admin-ink" title={order.serviceLabel}>
                    {order.serviceLabel}
                  </td>
                  <td className="max-w-[200px] truncate px-3 py-3 text-admin-muted" title={order.customerEmail ?? undefined}>
                    {order.customerName ?? order.customerEmail ?? '—'}
                  </td>
                  <td className="whitespace-nowrap px-3 py-3 font-medium tabular-nums text-admin-ink">
                    {order.totalFormatted}
                  </td>
                  {/* In words here, as the dashboard reference writes it; the Orders page uses logos. */}
                  <td className="whitespace-nowrap px-3 py-3 text-admin-muted">
                    {order.platform ? PLATFORM_LABEL[order.platform] ?? order.platform : '—'}
                  </td>
                  <td className="px-3 py-3">{status(order)}</td>
                  <td className="whitespace-nowrap px-3 py-3 text-admin-muted">{time(when)}</td>
                  <td className="px-3 py-3 text-right">
                    <Link
                      to={`/admin/orders/${order.publicRef}`}
                      aria-label={`View order ${order.publicRef}`}
                      className="inline-flex h-8 items-center rounded-admin-control border border-[#F6D3D6] bg-[#FDF3F4]
                                 px-3.5 text-[12.5px] font-medium text-admin-red-text hover:bg-admin-red-tint
                                 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red"
                    >
                      View
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          )}
        </table>
      </TableCard>
    </section>
  )
}
