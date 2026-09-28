import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { LuCopy, LuSearch, LuShoppingBag, LuUserPlus, LuUsers } from 'react-icons/lu'
import { api } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import { useAuth } from '../../state/AuthContext'
import type { AdminCustomer, AdminCustomerOverview, AdminCustomerPage } from '../../lib/types'
import { AdminPage } from './shell/AdminPage'
import { CustomerPanel } from './customers/CustomerPanel'
import { Avatar, spentText } from './customers/shared'
import { MenuItem, RowMenu } from './ui/controls'
import { shortDate } from './ui/format'
import { StatCard, TrendBlock } from './ui/StatCard'
import { Pagination, TableCard, TableState, Th } from './ui/Table'

const PAGE_SIZE = 25

const FILTERS = [
  { value: 'all', label: 'All Customers' },
  { value: 'with', label: 'Has Orders' },
  { value: 'without', label: 'No Orders' },
] as const

/**
 * Customers: every account and every guest who has checked out, and what they have done.
 *
 * <p>A guest is a customer the same as anybody with a password; one who gave no name at
 * checkout is shown as "Guest". What a customer has spent is an admin's figure: the column
 * and the figure are drawn only when the server sends them, which it does for admins.
 *
 * <p>The search, the filter, the page and the open customer all live in the address, so a
 * link to a customer opens them, and the top bar's search lands here filtered.
 */
export default function AdminCustomers() {
  useSeo({ title: 'Customers', noindex: true })
  const { account } = useAuth()
  const isAdmin = account?.role === 'ADMIN'

  const [params, setParams] = useSearchParams()
  const search = params.get('search') ?? ''
  const filter = (params.get('filter') ?? 'all') as (typeof FILTERS)[number]['value']
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '1', 10) - 1 || 0)
  const open = params.get('customer')

  const update = useCallback((patch: Record<string, string | null>) => {
    setParams((current) => {
      const next = new URLSearchParams(current)
      for (const [key, value] of Object.entries(patch)) {
        if (value === null || value === '') next.delete(key)
        else next.set(key, value)
      }
      return next
    }, { replace: true })
  }, [setParams])

  const [overview, setOverview] = useState<AdminCustomerOverview | null>(null)
  const [overviewFailed, setOverviewFailed] = useState(false)
  const [result, setResult] = useState<AdminCustomerPage | null>(null)
  const [failed, setFailed] = useState(false)

  const [searchText, setSearchText] = useState(search)
  useEffect(() => { setSearchText(search) }, [search])
  useEffect(() => {
    if (searchText === search) return
    const timer = setTimeout(() => update({ search: searchText.trim() || null, page: null }), 350)
    return () => clearTimeout(timer)
  }, [searchText, search, update])

  const query = useMemo(() => {
    const q = new URLSearchParams({ filter, page: String(page), size: String(PAGE_SIZE) })
    if (search.trim()) q.set('search', search.trim())
    return q.toString()
  }, [filter, page, search])

  const loadOverview = useCallback(async () => {
    try {
      setOverview(await api.get<AdminCustomerOverview>('/api/v1/admin/customers/overview'))
      setOverviewFailed(false)
    } catch {
      setOverviewFailed(true)
    }
  }, [])

  const loadTable = useCallback(async () => {
    try {
      setResult(await api.get<AdminCustomerPage>(`/api/v1/admin/customers?${query}`))
      setFailed(false)
    } catch {
      setFailed(true)
    }
  }, [query])

  useEffect(() => { void loadOverview() }, [loadOverview])
  useEffect(() => {
    setResult(null)
    void loadTable()
  }, [loadTable])

  // An admin's column. The server sends the figures to admins only, so for anyone else
  // there would be nothing to put in it.
  const withMoney = isAdmin
  const columns = withMoney ? 7 : 6
  const closePanel = useCallback(() => update({ customer: null }), [update])

  return (
    <AdminPage eyebrow="Customers" title="Customers" description="Manage customer accounts, view their orders and details.">
      <div className="mb-5 grid grid-cols-1 gap-3.5 md:grid-cols-3">
        <StatCard size="lg" icon={LuUsers} tone="red" label="Total Customers"
          value={overview?.total ?? null} failed={overviewFailed && !overview}
          sub={overview && 'Accounts and guests'} />
        <StatCard size="lg" icon={LuUserPlus} tone="green" label="New This Month"
          value={overview?.newThisMonth ?? null} failed={overviewFailed && !overview}
          aside={overview && <TrendBlock current={overview.newThisMonth} previous={overview.newLastMonthSoFar} against="last month" />} />
        <StatCard size="lg" icon={LuShoppingBag} tone="blue" label="Customers With Orders"
          value={overview?.withOrders ?? null} failed={overviewFailed && !overview}
          aside={overview && <TrendBlock current={overview.withOrders} previous={overview.withOrdersLastMonth} against="last month" />} />
      </div>

      <div className={open ? 'xl:grid xl:grid-cols-[minmax(0,1fr)_360px] xl:items-start xl:gap-4' : ''}>
        <div className="min-w-0">
          <div className="mb-3 flex flex-wrap items-center gap-3 rounded-admin-card border border-admin-line bg-white p-3.5 shadow-admin-card">
            <div className="min-w-[220px] flex-[1_1_320px]">
              <label htmlFor="customer-search" className="sr-only">
                Search customers by name, email, EA ID or Discord name or ID
              </label>
              <div className="flex h-10 items-center gap-2 rounded-admin-control border border-admin-line bg-white px-3
                              focus-within:border-admin-red focus-within:ring-2 focus-within:ring-admin-red/20">
                <LuSearch aria-hidden="true" className="h-4 w-4 shrink-0 text-admin-faint" />
                <input
                  id="customer-search"
                  type="search"
                  value={searchText}
                  maxLength={100}
                  onChange={(e) => setSearchText(e.target.value)}
                  placeholder="Search name, email, EA ID, Discord ID..."
                  className="min-w-0 flex-1 bg-transparent text-[13px] text-admin-ink placeholder:text-admin-faint focus:outline-none"
                />
              </div>
            </div>
            <div role="group" aria-label="Show" className="flex rounded-admin-control bg-admin-page p-1">
              {FILTERS.map((f) => (
                <button
                  key={f.value}
                  type="button"
                  aria-pressed={filter === f.value}
                  onClick={() => update({ filter: f.value === 'all' ? null : f.value, page: null })}
                  className={`h-8 whitespace-nowrap rounded-[6px] px-3.5 text-[13px] font-medium transition-colors
                              focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red ${
                                filter === f.value ? 'bg-admin-red-tint text-admin-red-text' : 'text-admin-ink hover:bg-white'}`}
                >
                  {f.label}
                </button>
              ))}
            </div>
          </div>

          <TableCard
            footer={result && result.total > 0 ? (
              <Pagination page={page} size={PAGE_SIZE} total={result.total} noun="customers"
                onPage={(p) => update({ page: p > 0 ? String(p + 1) : null })} />
            ) : undefined}
          >
            <table className={`w-full border-collapse text-admin-cell ${open ? 'min-w-[720px]' : 'min-w-[900px]'}`}>
              <thead className="bg-[#FAFBFC]">
                <tr className="border-b border-admin-line">
                  <Th>Customer</Th>
                  <Th>Email</Th>
                  <Th>Orders</Th>
                  {withMoney && <Th>Total Spent</Th>}
                  <Th>Last Order</Th>
                  <Th>Joined</Th>
                  <Th className="text-right">Actions</Th>
                </tr>
              </thead>
              {failed && !result ? (
                <TableState kind="error" columns={columns} title="Could not load customers" onRetry={() => void loadTable()}>
                  The list did not come back from the server. Nothing has changed; try again.
                </TableState>
              ) : !result ? (
                <TableState kind="loading" columns={columns} />
              ) : result.items.length === 0 ? (
                <TableState kind="empty" columns={columns}
                  title={search || filter !== 'all' ? 'No customers match' : 'No customers yet'}>
                  {search || filter !== 'all' ? (
                    <button type="button" onClick={() => update({ search: null, filter: null, page: null })}
                      className="font-medium text-admin-red-text underline">
                      Show every customer
                    </button>
                  ) : 'Customers appear here when they sign up or place an order.'}
                </TableState>
              ) : (
                <tbody>
                  {result.items.map((c) => (
                    <CustomerRow key={c.key} customer={c} withMoney={withMoney} selected={open === c.key}
                      compact={Boolean(open)} onView={() => update({ customer: c.key })} />
                  ))}
                </tbody>
              )}
            </table>
          </TableCard>
        </div>

        {open && (
          <CustomerPanel
            customerKey={open}
            isAdmin={isAdmin}
            onClose={closePanel}
            onRenamed={() => void loadTable()}
          />
        )}
      </div>
    </AdminPage>
  )
}

function CustomerRow({
  customer: c, withMoney, selected, compact, onView,
}: {
  customer: AdminCustomer
  withMoney: boolean
  selected: boolean
  /** The panel is open beside the table: names and emails get less room. */
  compact: boolean
  onView: () => void
}) {
  const nameWidth = compact ? 'max-w-[140px]' : 'max-w-[170px]'
  return (
    <tr className={`border-b border-admin-line last:border-b-0 ${selected ? 'bg-[#FFF8F8]' : 'hover:bg-[#FCFCFD]'}`}>
      <td className="px-3 py-2.5">
        <div className="flex items-center gap-3">
          <Avatar name={c.name} seed={c.key} />
          <div className="min-w-0">
            <p className={`${nameWidth} truncate font-semibold text-admin-ink`} title={c.name}>{c.name}</p>
            <p className={`${nameWidth} truncate text-[12px] text-admin-faint`}>
              {c.eaHandle ? `EA ID: ${c.eaHandle}` : c.kind === 'GUEST' ? 'Guest checkout' : 'No EA ID yet'}
            </p>
          </div>
        </div>
      </td>
      <td className={`${compact ? 'max-w-[150px]' : 'max-w-[210px]'} truncate px-3 py-2.5 text-admin-ink`} title={c.email}>
        {c.email}
      </td>
      <td className="px-3 py-2.5 tabular-nums text-admin-ink">{c.orders}</td>
      {withMoney && (
        <td className="whitespace-nowrap px-3 py-2.5 font-medium tabular-nums text-admin-ink">
          {c.spent ? spentText(c.spent) : '—'}
        </td>
      )}
      <td className="whitespace-nowrap px-3 py-2.5 text-admin-ink">{shortDate(c.lastOrderAt)}</td>
      <td className="whitespace-nowrap px-3 py-2.5 text-admin-ink">{shortDate(c.joinedAt)}</td>
      <td className="whitespace-nowrap px-3 py-2.5">
        <div className="flex items-center justify-end gap-1.5">
          <button
            type="button"
            onClick={onView}
            aria-label={`View ${c.name}`}
            aria-expanded={selected}
            className="inline-flex h-8 items-center rounded-admin-control border border-[#F6D3D6] bg-[#FDF3F4] px-3.5
                       text-[12.5px] font-medium text-admin-red-text hover:bg-admin-red-tint focus-visible:outline-none
                       focus-visible:ring-2 focus-visible:ring-admin-red"
          >
            View
          </button>
          <RowMenu label={`More for ${c.name}`}>
            {(close) => (
              <MenuItem onSelect={() => { close(); void navigator.clipboard?.writeText(c.email) }}>
                <LuCopy aria-hidden="true" className="h-4 w-4 text-admin-faint" />
                Copy email
              </MenuItem>
            )}
          </RowMenu>
        </div>
      </td>
    </tr>
  )
}
