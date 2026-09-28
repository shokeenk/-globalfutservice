import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { LuChevronRight, LuCircleCheck, LuClock, LuMessageCircle, LuSearch, LuX } from 'react-icons/lu'
import { api } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import type { AdminSupportOverview, AdminSupportPage, AdminSupportTicket } from '../../lib/types'
import { AdminPage } from './shell/AdminPage'
import { CategoryTag, SUPPORT_CATEGORIES, SUPPORT_CATEGORY, TICKET_STATUS, TICKET_TABS } from './support/shared'
import { TicketPanel } from './support/TicketPanel'
import { StatusBadge } from './ui/Badge'
import { ago, dateAndTime } from './ui/format'
import { StatCard } from './ui/StatCard'
import { TabRow } from './ui/Tabs'
import { Pagination, TableCard, TableState, Th } from './ui/Table'

const PAGE_SIZE = 25

const selectClass = 'h-10 w-full rounded-admin-control border border-admin-line bg-white px-2.5 text-[13px] '
  + 'text-admin-ink focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20'

/**
 * Support: every ticket from the contact form, and every conversation staff started from
 * a customer's page, with the one that is open beside the list.
 *
 * <p>A ticket is Open while it is with staff, Waiting once staff have answered, and
 * Resolved when closed. The customer writing again moves it back to Open, so the Open tab
 * is always the work to do. Replies reach the customer by email, with a private link back
 * into the conversation; notes never leave this page.
 */
export default function AdminSupport() {
  useSeo({ title: 'Support', noindex: true })
  const location = useLocation()
  const navigate = useNavigate()

  const [params, setParams] = useSearchParams()
  const tab = params.get('status') ?? ''
  const category = params.get('category') ?? ''
  const search = params.get('search') ?? ''
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '1', 10) - 1 || 0)
  const openRef = params.get('ticket')

  const update = useCallback((patch: Record<string, string | null>) => {
    setParams((current) => {
      const next = new URLSearchParams(current)
      for (const [key, value] of Object.entries(patch)) {
        if (value === null || value === '') next.delete(key)
        else next.set(key, value)
      }
      if (!('page' in patch) && Object.keys(patch).some((k) => k !== 'ticket')) next.delete('page')
      return next
    }, { replace: true })
  }, [setParams])

  // A ticket just opened from a customer's page says so once, then the message is dropped
  // from history so a reload does not repeat it.
  const [notice, setNotice] = useState<string | null>((location.state as { notice?: string } | null)?.notice ?? null)
  useEffect(() => {
    if ((location.state as { notice?: string } | null)?.notice) {
      navigate(`${location.pathname}${location.search}`, { replace: true, state: null })
    }
  }, [location, navigate])

  const [overview, setOverview] = useState<AdminSupportOverview | null>(null)
  const [overviewFailed, setOverviewFailed] = useState(false)
  const [result, setResult] = useState<AdminSupportPage | null>(null)
  const [failed, setFailed] = useState(false)

  const [searchText, setSearchText] = useState(search)
  useEffect(() => { setSearchText(search) }, [search])
  useEffect(() => {
    if (searchText === search) return
    const timer = setTimeout(() => update({ search: searchText.trim() || null }), 350)
    return () => clearTimeout(timer)
  }, [searchText, search, update])

  const query = useMemo(() => {
    const q = new URLSearchParams()
    if (tab) q.set('status', tab)
    if (category) q.set('category', category)
    // "#TKT-…" as the table shows it finds the ticket too.
    const s = search.trim().replace(/^#/, '')
    if (s) q.set('search', s)
    q.set('page', String(page))
    q.set('size', String(PAGE_SIZE))
    return q.toString()
  }, [tab, category, search, page])

  const loadOverview = useCallback(async () => {
    try {
      setOverview(await api.get<AdminSupportOverview>('/api/v1/admin/support/overview'))
      setOverviewFailed(false)
    } catch {
      setOverviewFailed(true)
    }
  }, [])

  const loadTable = useCallback(async () => {
    try {
      setResult(await api.get<AdminSupportPage>(`/api/v1/admin/support/tickets?${query}`))
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

  const filtered = Boolean(tab || category || search.trim())
  const clearFilters = () => update({ status: null, category: null, search: null })
  const closePanel = useCallback(() => update({ ticket: null }), [update])
  const refresh = useCallback(() => { void loadOverview(); void loadTable() }, [loadOverview, loadTable])
  const failedCounts = overviewFailed && !overview
  const total = overview ? overview.open + overview.waiting + overview.resolved : null
  const tabCount = (key: string) => !overview ? null
    : key === 'open' ? overview.open : key === 'waiting' ? overview.waiting : key === 'resolved' ? overview.resolved : total

  return (
    <div className={openRef ? 'xl:grid xl:grid-cols-[minmax(0,1fr)_minmax(380px,420px)] xl:items-start xl:gap-4' : ''}>
      <div className="min-w-0">
        <AdminPage eyebrow="Support" title="Support" description="View and manage customer support tickets.">
          {notice && (
            <div role="status" className="mb-4 flex items-start justify-between gap-3 rounded-admin-control bg-admin-green-tint px-4 py-3 text-[13px] text-admin-green-ink">
              <span>{notice}</span>
              <button type="button" onClick={() => setNotice(null)} aria-label="Dismiss"><LuX aria-hidden="true" className="h-4 w-4" /></button>
            </div>
          )}

          <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-3">
            <StatCard size="lg" icon={LuMessageCircle} tone="red" label="Open Tickets" failed={failedCounts}
              value={overview ? overview.open : null} />
            <StatCard size="lg" icon={LuClock} tone="amber" label="Waiting for Customer" failed={failedCounts}
              value={overview ? overview.waiting : null} />
            <StatCard size="lg" icon={LuCircleCheck} tone="green" label="Resolved" failed={failedCounts}
              value={overview ? overview.resolved : null} />
          </div>

          <TableCard
            header={(
              <>
                <div className="border-b border-admin-line">
                  <TabRow
                    label="Status"
                    active={TICKET_TABS.some((t) => t.key === tab) ? tab : null}
                    onChange={(key) => update({ status: key || null })}
                    items={TICKET_TABS.map((t) => ({ key: t.key, label: t.label, count: tabCount(t.key) }))}
                  />
                </div>
                <div className="flex flex-wrap gap-3 border-b border-admin-line p-3.5">
                  <div className="min-w-[200px] flex-[3_1_280px]">
                    <label htmlFor="ticket-search" className="sr-only">
                      Search tickets by reference, customer name or email, subject or order ID
                    </label>
                    <div className="flex h-10 items-center gap-2 rounded-admin-control border border-admin-line bg-white px-3
                                    focus-within:border-admin-red focus-within:ring-2 focus-within:ring-admin-red/20">
                      <LuSearch aria-hidden="true" className="h-4 w-4 shrink-0 text-admin-faint" />
                      <input id="ticket-search" type="search" value={searchText} maxLength={100}
                        onChange={(e) => setSearchText(e.target.value)}
                        placeholder="Search ticket, customer, email or order ID..."
                        className="min-w-0 flex-1 bg-transparent text-[13px] text-admin-ink placeholder:text-admin-faint focus:outline-none" />
                    </div>
                  </div>
                  <div className="min-w-[150px] flex-[1_1_170px]">
                    <label htmlFor="ticket-category-filter" className="sr-only">Category</label>
                    <select id="ticket-category-filter" value={category} onChange={(e) => update({ category: e.target.value || null })}
                      className={selectClass}>
                      <option value="">All Categories</option>
                      {SUPPORT_CATEGORIES.map((c) => <option key={c} value={c}>{SUPPORT_CATEGORY[c].label}</option>)}
                    </select>
                  </div>
                </div>
              </>
            )}
            footer={result && result.total > 0 ? (
              <Pagination page={page} size={PAGE_SIZE} total={result.total} noun="tickets"
                onPage={(p) => update({ page: p > 0 ? String(p + 1) : null })} />
            ) : undefined}
          >
            <table className={`w-full border-collapse text-admin-cell ${openRef ? 'min-w-[700px] [&_td]:px-2.5 [&_th]:px-2.5' : 'min-w-[900px]'}`}>
              <thead className="bg-[#FAFBFC]">
                <tr className="border-b border-admin-line">
                  <Th>Ticket</Th>
                  <Th>Customer</Th>
                  <Th>Issue</Th>
                  <Th>Order</Th>
                  <Th>Status</Th>
                  <Th>Updated</Th>
                  {!openRef && <Th><span className="sr-only">Open</span></Th>}
                </tr>
              </thead>
              {failed && !result ? (
                <TableState kind="error" columns={7} title="Could not load tickets" onRetry={() => void loadTable()}>
                  The list did not come back from the server. Nothing has changed; try again.
                </TableState>
              ) : !result ? (
                <TableState kind="loading" columns={7} />
              ) : result.items.length === 0 ? (
                <TableState kind="empty" columns={7} title={filtered ? 'No tickets match these filters' : 'No tickets yet'}>
                  {filtered ? (
                    <button type="button" onClick={clearFilters} className="font-medium text-admin-red-text underline">
                      Clear the filters
                    </button>
                  ) : 'Messages from the contact form appear here.'}
                </TableState>
              ) : (
                <tbody>
                  {result.items.map((t) => (
                    <TicketRow key={t.ref} ticket={t} selected={openRef === t.ref} compact={Boolean(openRef)}
                      onOpen={() => update({ ticket: t.ref })} />
                  ))}
                </tbody>
              )}
            </table>
          </TableCard>
        </AdminPage>
      </div>

      {openRef && <TicketPanel ticketRef={openRef} onClose={closePanel} onChanged={refresh} />}
    </div>
  )
}

function TicketRow({
  ticket: t, selected, compact, onOpen,
}: {
  ticket: AdminSupportTicket
  selected: boolean
  compact: boolean
  onOpen: () => void
}) {
  const status = TICKET_STATUS[t.status] ?? { label: t.status, tone: 'grey' as const }
  return (
    // The whole row opens the ticket for a mouse; the reference is the button for everyone else.
    <tr onClick={onOpen}
      className={`cursor-pointer border-b border-admin-line last:border-b-0 ${selected ? 'bg-[#FFF8F8]' : 'hover:bg-[#FCFCFD]'}`}>
      <td className="whitespace-nowrap px-3 py-3">
        <button type="button" onClick={(e) => { e.stopPropagation(); onOpen() }} aria-expanded={selected}
          aria-label={`Open ticket ${t.ref}: ${t.subject}`}
          className="rounded font-semibold text-admin-red-text hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red">
          #{t.ref}
        </button>
      </td>
      <td className={`${compact ? 'max-w-[130px]' : 'max-w-[210px]'} px-3 py-3`}>
        {t.customerName && <p className="truncate font-medium text-admin-ink" title={t.customerName}>{t.customerName}</p>}
        <p className={`truncate ${t.customerName ? 'text-[12px] text-admin-faint' : 'text-admin-ink'}`} title={t.email}>{t.email}</p>
      </td>
      <td className="px-3 py-3">
        <div className="flex items-center gap-2.5">
          <CategoryTag category={t.category} />
          <span className={`truncate text-admin-muted ${compact ? 'max-w-[110px]' : 'max-w-[220px]'}`} title={t.subject}>{t.subject}</span>
        </div>
      </td>
      <td className="whitespace-nowrap px-3 py-3">
        {t.orderRef ? (
          <Link to={`/admin/orders/${t.orderRef}`} onClick={(e) => e.stopPropagation()} className="text-admin-muted hover:text-admin-ink hover:underline">
            #{t.orderRef}
          </Link>
        ) : <span className="text-admin-faint">—</span>}
      </td>
      <td className="px-3 py-3"><StatusBadge dot label={status.label} tone={status.tone} /></td>
      <td className="whitespace-nowrap px-3 py-3 text-admin-muted">
        <time dateTime={t.lastActivityAt} title={dateAndTime(t.lastActivityAt)}>{ago(t.lastActivityAt)}</time>
      </td>
      {!compact && <td className="w-8 px-2 py-3 text-admin-faint"><LuChevronRight aria-hidden="true" className="h-4 w-4" /></td>}
    </tr>
  )
}
