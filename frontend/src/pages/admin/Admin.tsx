import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  LuActivity, LuBadgeCheck, LuCirclePause, LuClipboardList, LuCoins, LuDownload, LuGraduationCap,
  LuLock, LuRefreshCw, LuSearch, LuShieldAlert, LuTriangleAlert, LuTrophy, LuUsers, LuX,
} from 'react-icons/lu'
import { ApiError, api } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import { useAuth } from '../../state/AuthContext'
import type { AdminOrderOverview, AdminOrderPage, AdminOrderRow } from '../../lib/types'
import { Announcements } from './Announcements'
import { PaymentClaims } from './PaymentClaims'
import { AdminPage } from './shell/AdminPage'
import { remindQuestion, releaseQuestion, startQuestion } from './orders/confirmations'
import {
  apiQuery, attentionBreakdown, countFor, EMPTY, fromSaved, isFiltered, PAGE_SIZE, readFilters, serviceTabFor, skusFor,
  statusTabFor, writeFilters, type OrderFilters,
} from './orders/filters'
import type { NextAction } from './orders/nextAction'
import { DateRangeMenu, SavedViewsMenu } from './orders/OrderPopovers'
import { ORDER_COLUMNS, OrderRows, OrderTableHead } from './orders/OrderTable'
import { AdminButton } from './ui/controls'
import { todayInIndia } from './ui/format'
import { StatCard, TrendLine } from './ui/StatCard'
import {
  ORDER_STATUS_ORDER, orderStatus, PLATFORM_LABEL, SERVICE_OPTIONS, SERVICE_TABS, STATUS_TABS,
} from './ui/status'
import { TabRow } from './ui/Tabs'
import { Pagination, TableCard, TableState } from './ui/Table'

const SERVICE_ICONS = { '': LuClipboardList, COINS: LuCoins, BOOSTING: LuTrophy, COACHING: LuGraduationCap }
/** The reference colours each service's icon: amber coins, a red trophy. */
const SERVICE_ICON_CLASS: Record<string, string> = {
  '': 'text-admin-faint', COINS: 'text-admin-amber-icon', BOOSTING: 'text-admin-red-icon', COACHING: 'text-admin-blue-icon',
}

const selectClass = 'h-9 w-full rounded-admin-control border border-admin-line bg-white px-2.5 text-[13px] '
  + 'text-admin-ink focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20'

/**
 * Orders: the page the business is run from.
 *
 * <p>Cards that count what needs a person, two rows of tabs, filters, and the table — all
 * read from the server with real totals, and every one of them a filter on the table
 * below. The filters live in the address, which is what lets the bell, the top-bar search
 * and a saved view all link straight to a filtered table.
 *
 * <p>Everything an order can have done to it here is an action that already existed:
 * verifying a payment, releasing a coin order, starting a boost. The payments-to-check
 * panel opens under Needs Attention, and the announcement composer sits folded at the foot
 * of the page for admins.
 */
export default function Admin() {
  useSeo({ title: 'Orders', noindex: true })
  const { account } = useAuth()
  const isAdmin = account?.role === 'ADMIN'

  const [params, setParams] = useSearchParams()
  const filters = useMemo(() => readFilters(params), [params])
  const setFilters = useCallback((next: OrderFilters) => {
    setParams(writeFilters(next), { replace: true })
  }, [setParams])
  /** Any filter change starts again from the first page. */
  const change = (patch: Partial<OrderFilters>) => setFilters({ ...filters, page: 0, ...patch })

  const [overview, setOverview] = useState<AdminOrderOverview | null>(null)
  const [overviewFailed, setOverviewFailed] = useState(false)
  const [result, setResult] = useState<AdminOrderPage | null>(null)
  const [tableFailed, setTableFailed] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)
  const [claimFor, setClaimFor] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())

  // The search box types into local state and reaches the address a moment later, so
  // every keystroke is not a request.
  const [searchText, setSearchText] = useState(filters.search)
  useEffect(() => { setSearchText(filters.search) }, [filters.search])
  useEffect(() => {
    if (searchText === filters.search) return
    const timer = setTimeout(() => change({ search: searchText }), 350)
    return () => clearTimeout(timer)
  }, [searchText]) // Only the typing starts this; the filters it writes are read fresh.

  const query = apiQuery(filters)

  const loadOverview = useCallback(async () => {
    try {
      setOverview(await api.get<AdminOrderOverview>('/api/v1/admin/orders/overview'))
      setOverviewFailed(false)
    } catch {
      setOverviewFailed(true)
    }
  }, [])

  const loadTable = useCallback(async () => {
    try {
      setResult(await api.get<AdminOrderPage>(`/api/v1/admin/orders/search?${query}`))
      setTableFailed(false)
    } catch {
      setTableFailed(true)
    }
    setNow(Date.now())
  }, [query])

  const reload = useCallback(() => {
    void loadOverview()
    void loadTable()
  }, [loadOverview, loadTable])

  useEffect(() => {
    // A new filter clears the rows first: showing the previous filter's rows under the
    // new filter's tabs, even for a moment, reads as the filter not working.
    setResult(null)
    void loadTable()
  }, [loadTable])

  useEffect(() => {
    void loadOverview()
    // Cheap polling rather than websockets, as before: the queue stays current while
    // somebody is watching it.
    const timer = setInterval(() => reload(), 20_000)
    return () => clearInterval(timer)
  }, [loadOverview, reload])

  const attentionCount = overview
    ? overview.paymentsToCheck + overview.signInsToWork + overview.disputed
    : null
  const showClaims = filters.attention || claimFor !== null

  async function act(row: AdminOrderRow, action: NextAction) {
    setNotice(null)
    if (action.kind === 'verify') {
      setClaimFor(row.publicRef)
      return
    }
    const question = action.kind === 'release' ? releaseQuestion(row.publicRef)
      : action.kind === 'start' ? startQuestion(row.publicRef)
        : action.kind === 'remind' ? remindQuestion(row.publicRef, row.customerEmail)
          : null
    if (!question || !window.confirm(question)) return

    setBusy(row.publicRef)
    try {
      if (action.kind === 'release') {
        await api.post(`/api/v1/admin/orders/${row.publicRef}/approve-fulfilment`)
        setNotice({ tone: 'ok', text: `${row.publicRef} was released to the fulfilment partner.` })
      } else if (action.kind === 'start') {
        await api.post(`/api/v1/admin/orders/${row.publicRef}/transition`, { toStatus: 'IN_PROGRESS', reason: null })
        setNotice({ tone: 'ok', text: `${row.publicRef} is in progress.` })
      } else {
        await api.post(`/api/v1/admin/orders/${row.publicRef}/credentials/remind`)
        setNotice({ tone: 'ok', text: `Reminder sent to ${row.customerEmail ?? 'the customer'} for ${row.publicRef}.` })
      }
      reload()
    } catch (e) {
      setNotice({
        tone: 'error',
        text: e instanceof ApiError ? e.message : `That did not go through for ${row.publicRef}. Reload and check.`,
      })
    } finally {
      setBusy(null)
    }
  }

  const [exporting, setExporting] = useState(false)
  async function exportCsv() {
    setExporting(true)
    setNotice(null)
    try {
      const url = await api.blobUrl(`/api/v1/admin/orders/export?${apiQuery(filters, false)}`)
      const link = document.createElement('a')
      link.href = url
      link.download = `orders-${todayInIndia()}.csv`
      document.body.appendChild(link)
      link.click()
      link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch {
      setNotice({ tone: 'error', text: 'The export could not be downloaded. Try again.' })
    } finally {
      setExporting(false)
    }
  }

  const serviceSkus = skusFor(filters.service)
  const count = (skus: string[], statuses: string[]) => (overview ? countFor(overview, skus, statuses) : null)
  const inProgress = overview ? [
    ['Coins', count(['TRADING_SERVICE'], ['IN_PROGRESS'])],
    ['Boosting', count(['BOOST_CHAMPS', 'BOOST_RIVALS'], ['IN_PROGRESS'])],
    ['Coaching', count(['COACHING'], ['IN_PROGRESS'])],
  ].filter(([, n]) => (n as number) > 0) : []
  const onHold = count([], ['ON_HOLD'])
  const statusIs = (status: string) => !filters.attention && filters.status === status

  return (
    <AdminPage
      eyebrow="Orders"
      title="Orders"
      description="Manage and fulfil every GFS order from one place."
      action={(
        <div className="flex flex-wrap items-center gap-2.5">
          <AdminButton
            variant="attention"
            aria-pressed={filters.attention}
            onClick={() => { setClaimFor(null); change({ attention: !filters.attention, status: '' }) }}
          >
            <LuTriangleAlert aria-hidden="true" className="h-4 w-4" />
            Needs Attention
            {attentionCount !== null && attentionCount > 0 && (
              <span className="grid h-5 min-w-5 place-items-center rounded-full bg-admin-red px-1.5 text-[11px] font-bold text-white">
                {attentionCount}
              </span>
            )}
          </AdminButton>
          {/* Admin only, like the endpoint: a file of orders with their amounts adds up to revenue. */}
          {isAdmin && (
            <AdminButton onClick={() => void exportCsv()} disabled={exporting}>
              <LuDownload aria-hidden="true" className="h-4 w-4" />
              {exporting ? 'Exporting…' : 'Export'}
            </AdminButton>
          )}
          <AdminButton onClick={reload}>
            <LuRefreshCw aria-hidden="true" className="h-4 w-4" />
            Refresh
          </AdminButton>
          <SavedViewsMenu filters={filters} onApply={(saved) => setFilters(fromSaved(saved))} />
        </div>
      )}
    >
      {notice && (
        <div
          role={notice.tone === 'error' ? 'alert' : 'status'}
          className={`mb-4 flex items-start justify-between gap-3 rounded-admin-control px-4 py-3 text-[13px] ${
            notice.tone === 'error' ? 'bg-admin-red-tint text-admin-red-ink' : 'bg-admin-green-tint text-admin-green-ink'}`}
        >
          <span>{notice.text}</span>
          <button type="button" onClick={() => setNotice(null)} aria-label="Dismiss" className="shrink-0">
            <LuX aria-hidden="true" className="h-4 w-4" />
          </button>
        </div>
      )}

      <div className="mb-5 grid grid-cols-2 gap-3 md:grid-cols-3 min-[1400px]:grid-cols-6 min-[1400px]:gap-3.5">
        <StatCard
          icon={LuTriangleAlert} tone="red" label="Needs Attention" value={attentionCount} failed={overviewFailed && !overview}
          sub={overview && attentionBreakdown(overview)}
          onClick={() => { setClaimFor(null); change({ attention: !filters.attention, status: '' }) }}
          active={filters.attention}
        />
        <StatCard
          icon={LuUsers} tone="blue" label="Awaiting Customer" value={overview?.awaitingSignIn ?? null}
          failed={overviewFailed && !overview}
          sub={overview && `${overview.awaitingSignIn} Sign-in${overview.awaitingSignIn === 1 ? '' : 's'}`}
          onClick={() => change({ attention: false, status: 'CREDENTIALS_PENDING' })}
          active={statusIs('CREDENTIALS_PENDING')}
        />
        <StatCard
          icon={LuActivity} tone="amber" label="In Progress" value={count([], ['IN_PROGRESS'])}
          failed={overviewFailed && !overview}
          sub={overview && (inProgress.length ? inProgress.map(([k, n]) => `${n} ${k}`).join(' • ') : 'Nothing in progress')}
          onClick={() => change({ attention: false, status: 'IN_PROGRESS' })}
          active={statusIs('IN_PROGRESS')}
        />
        <StatCard
          icon={LuCirclePause} tone="grey" label="On Hold" value={onHold} failed={overviewFailed && !overview}
          sub={overview && (onHold ? 'Blocked on the customer' : 'No orders on hold')}
          onClick={() => change({ attention: false, status: 'ON_HOLD' })}
          active={statusIs('ON_HOLD')}
        />
        <StatCard
          icon={LuShieldAlert} tone="red" label="Disputed" value={overview?.disputed ?? null}
          failed={overviewFailed && !overview}
          sub={overview && (overview.disputed ? 'Awaiting review' : 'No open disputes')}
          onClick={() => change({ attention: false, status: 'DISPUTED' })}
          active={statusIs('DISPUTED')}
        />
        <StatCard
          icon={LuBadgeCheck} tone="green" label="Completed Today" value={overview?.deliveredToday ?? null}
          failed={overviewFailed && !overview}
          sub={overview && (
            <TrendLine current={overview.deliveredToday} previous={overview.deliveredYesterdaySoFar} against="yesterday" />
          )}
        />
      </div>

      {showClaims && (
        <PaymentClaims highlight={claimFor} onReviewed={reload} />
      )}

      <div className="mb-3 overflow-hidden rounded-admin-card border border-admin-line bg-white shadow-admin-card">
        <TabRow
          label="Service"
          size="lg"
          active={serviceTabFor(filters.service)}
          onChange={(key) => change({ service: key })}
          items={SERVICE_TABS.map((tab) => ({
            key: tab.key,
            label: tab.label,
            count: count(tab.skus, []),
            icon: SERVICE_ICONS[tab.key as keyof typeof SERVICE_ICONS],
            iconClass: SERVICE_ICON_CLASS[tab.key],
          }))}
        />
      </div>

      <div className="mb-3 overflow-hidden rounded-admin-card border border-admin-line bg-white shadow-admin-card">
        <TabRow
          label="Status"
          active={filters.attention ? null : statusTabFor(filters.status)}
          onChange={(key) => change({
            attention: false,
            status: STATUS_TABS.find((tab) => tab.key === key)?.statuses.join(',') ?? '',
          })}
          items={STATUS_TABS.map((tab) => ({
            key: tab.key,
            label: tab.label,
            count: count(serviceSkus, tab.statuses),
          }))}
        />
      </div>

      <div className="mb-3 flex flex-wrap items-end gap-3 rounded-admin-card border border-admin-line bg-white p-3.5 shadow-admin-card">
        <div className="min-w-[220px] flex-[2_1_280px]">
          <label htmlFor="order-search" className="sr-only">
            Search orders by reference, customer name, email, EA ID or payment reference
          </label>
          <div className="flex h-10 items-center gap-2 rounded-admin-control border border-admin-line bg-white px-3
                          focus-within:border-admin-red focus-within:ring-2 focus-within:ring-admin-red/20">
            <LuSearch aria-hidden="true" className="h-4 w-4 shrink-0 text-admin-faint" />
            <input
              id="order-search"
              type="search"
              value={searchText}
              onChange={(e) => setSearchText(e.target.value)}
              maxLength={100}
              placeholder="Search order, customer, email, EA ID, reference..."
              className="min-w-0 flex-1 bg-transparent text-[13px] text-admin-ink placeholder:text-admin-faint focus:outline-none"
            />
          </div>
        </div>
        <FilterField label="Service" id="filter-service">
          <select id="filter-service" value={filters.service} onChange={(e) => change({ service: e.target.value })} className={selectClass}>
            {SERVICE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </FilterField>
        <FilterField label="Status" id="filter-status">
          {/* Every real status, including the ones no tab covers: Refunded, Abandoned… */}
          <select
            id="filter-status"
            value={filters.status.includes(',') ? '' : filters.status}
            onChange={(e) => change({ attention: false, status: e.target.value })}
            className={selectClass}
          >
            <option value="">{filters.status.includes(',') ? 'Several statuses' : 'All Statuses'}</option>
            {ORDER_STATUS_ORDER.map((s) => <option key={s} value={s}>{orderStatus(s).label}</option>)}
          </select>
        </FilterField>
        <FilterField label="Platform" id="filter-platform">
          <select id="filter-platform" value={filters.platform} onChange={(e) => change({ platform: e.target.value })} className={selectClass}>
            <option value="">All Platforms</option>
            {Object.entries(PLATFORM_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </FilterField>
        <FilterField label="Date Range">
          <DateRangeMenu from={filters.from} to={filters.to} onChange={(from, to) => change({ from, to })} />
        </FilterField>
        {isFiltered(filters) && (
          <AdminButton onClick={() => { setClaimFor(null); setFilters(EMPTY) }}>
            <LuX aria-hidden="true" className="h-4 w-4" />
            Clear
          </AdminButton>
        )}
      </div>

      <TableCard
        footer={result && result.total > 0 ? (
          <Pagination page={filters.page} size={PAGE_SIZE} total={result.total} noun="orders"
            onPage={(page) => setFilters({ ...filters, page })} />
        ) : undefined}
      >
        <table className="w-full min-w-[1100px] border-collapse text-admin-cell">
          <OrderTableHead />
          {tableFailed && !result ? (
            <TableState kind="error" columns={ORDER_COLUMNS} title="Could not load orders" onRetry={reload}>
              The list did not come back from the server. Nothing has changed; try again.
            </TableState>
          ) : !result ? (
            <TableState kind="loading" columns={ORDER_COLUMNS} />
          ) : result.items.length === 0 ? (
            <TableState kind="empty" columns={ORDER_COLUMNS}
              title={isFiltered(filters) ? 'No orders match these filters' : 'No orders yet'}
            >
              {isFiltered(filters) ? (
                <button type="button" onClick={() => setFilters(EMPTY)} className="font-medium text-admin-red-text underline">
                  Clear the filters
                </button>
              ) : 'When a customer places one it will appear here.'}
            </TableState>
          ) : (
            <OrderRows rows={result.items} busy={busy} onAction={(row, action) => void act(row, action)} now={now} />
          )}
        </table>
      </TableCard>

      {overview && overview.credentialsHeld > 0 && (
        <p className="mt-3 flex items-center gap-1.5 text-[12px] text-admin-faint">
          <LuLock aria-hidden="true" className="h-3.5 w-3.5" />
          {overview.credentialsHeld} sign-in{overview.credentialsHeld === 1 ? '' : 's'} held in the vault across all orders.
        </p>
      )}

      {/* Admin only, and it renders nothing for an operator. */}
      <Announcements />
    </AdminPage>
  )
}

function FilterField({ label, id, children }: { label: string; id?: string; children: ReactNode }) {
  return (
    <div className="min-w-[150px] flex-[1_1_150px]">
      {id ? (
        <label htmlFor={id} className="mb-1 block text-[12px] text-admin-muted">{label}</label>
      ) : (
        <span className="mb-1 block text-[12px] text-admin-muted">{label}</span>
      )}
      {children}
    </div>
  )
}
