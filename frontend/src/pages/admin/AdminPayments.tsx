import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  LuCircleCheck, LuCircleX, LuCopy, LuCreditCard, LuDownload, LuExternalLink, LuHourglass, LuRotateCcw,
  LuSearch, LuX,
} from 'react-icons/lu'
import { api } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import { useAuth } from '../../state/AuthContext'
import type { AdminPayment, AdminPaymentOverview, AdminPaymentPage } from '../../lib/types'
import { AdminPage } from './shell/AdminPage'
import { DateRangeMenu } from './orders/OrderPopovers'
import { PaymentPanel } from './payments/PaymentPanel'
import { RecordRefund } from './payments/RecordRefund'
import { ALL_STATUSES, formatMinor, otherCurrencies, sum } from './payments/totals'
import { StatusBadge } from './ui/Badge'
import { AdminButton, buttonClasses, MenuItem, RowMenu } from './ui/controls'
import { shortDateTime, todayInIndia } from './ui/format'
import { MethodMark } from './ui/MethodMark'
import { StatCard, TrendLine } from './ui/StatCard'
import { METHOD_LABEL, PAY_STATUS } from './ui/status'
import { TabRow } from './ui/Tabs'
import { Pagination, TableCard, TableState, Th } from './ui/Table'

const PAGE_SIZE = 25

const TABS: Array<{ key: string; label: string; status: AdminPayment['status'] | '' }> = [
  { key: '', label: 'All Transactions', status: '' },
  { key: 'SUCCESS', label: 'Verified', status: 'SUCCESS' },
  { key: 'PENDING', label: 'Pending', status: 'PENDING' },
  { key: 'FAILED', label: 'Rejected', status: 'FAILED' },
  { key: 'REFUNDED', label: 'Refunds', status: 'REFUNDED' },
]

const selectClass = 'h-9 w-full rounded-admin-control border border-admin-line bg-white px-2.5 text-[13px] '
  + 'text-admin-ink focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20'

/**
 * Payments: every payment a customer has reported, what became of it, and refunds.
 *
 * <p>The cards are this month against last month at the same point. <b>Money is an
 * admin's</b>: an admin's cards show amounts, rupees large with any other currency
 * beneath; an operator's show how many, because the server sends them no totals. Each
 * row shows its own amount to everybody, as the payments-to-check queue always has.
 *
 * <p>Verifying and rejecting happen in a payment's panel, with the same questions the
 * payments-to-check queue asks. Recording a refund is an admin's, for money already sent
 * back by hand, and marks the order Refunded.
 */
export default function AdminPayments() {
  useSeo({ title: 'Payments', noindex: true })
  const { account } = useAuth()
  const isAdmin = account?.role === 'ADMIN'

  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const method = params.get('method') ?? ''
  const from = params.get('from') ?? ''
  const to = params.get('to') ?? ''
  const search = params.get('search') ?? ''
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '1', 10) - 1 || 0)
  const openId = params.get('payment')

  const update = useCallback((patch: Record<string, string | null>) => {
    setParams((current) => {
      const next = new URLSearchParams(current)
      for (const [key, value] of Object.entries(patch)) {
        if (value === null || value === '') next.delete(key)
        else next.set(key, value)
      }
      if (!('page' in patch) && Object.keys(patch).some((k) => k !== 'payment')) next.delete('page')
      return next
    }, { replace: true })
  }, [setParams])

  const [overview, setOverview] = useState<AdminPaymentOverview | null>(null)
  const [overviewFailed, setOverviewFailed] = useState(false)
  const [result, setResult] = useState<AdminPaymentPage | null>(null)
  const [failed, setFailed] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)
  const [refundFor, setRefundFor] = useState<AdminPayment | 'new' | null>(null)
  const [exporting, setExporting] = useState(false)

  const [searchText, setSearchText] = useState(search)
  useEffect(() => { setSearchText(search) }, [search])
  useEffect(() => {
    if (searchText === search) return
    const timer = setTimeout(() => update({ search: searchText.trim() || null }), 350)
    return () => clearTimeout(timer)
  }, [searchText, search, update])

  const filterQuery = useMemo(() => {
    const q = new URLSearchParams()
    if (status) q.set('status', status)
    if (method) q.set('method', method)
    if (from) q.set('from', from)
    if (to) q.set('to', to)
    if (search.trim()) q.set('search', search.trim())
    return q
  }, [status, method, from, to, search])

  const loadOverview = useCallback(async () => {
    try {
      setOverview(await api.get<AdminPaymentOverview>('/api/v1/admin/payments/overview'))
      setOverviewFailed(false)
    } catch {
      setOverviewFailed(true)
    }
  }, [])

  const loadTable = useCallback(async () => {
    const q = new URLSearchParams(filterQuery)
    q.set('page', String(page))
    q.set('size', String(PAGE_SIZE))
    try {
      setResult(await api.get<AdminPaymentPage>(`/api/v1/admin/payments?${q.toString()}`))
      setFailed(false)
    } catch {
      setFailed(true)
    }
  }, [filterQuery, page])

  useEffect(() => { void loadOverview() }, [loadOverview])
  useEffect(() => {
    setResult(null)
    void loadTable()
  }, [loadTable])

  const reload = () => { void loadOverview(); void loadTable() }
  const open = result?.items.find((p) => String(p.claimId) === openId) ?? null
  const filtered = Boolean(status || method || from || to || search.trim())

  async function exportCsv() {
    setExporting(true)
    setNotice(null)
    try {
      const url = await api.blobUrl(`/api/v1/admin/payments/export?${filterQuery.toString()}`)
      const link = document.createElement('a')
      link.href = url
      link.download = `payments-${todayInIndia()}.csv`
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

  const month = overview?.thisMonth ?? []
  const last = overview?.lastMonthSoFar ?? []
  const card = (statuses: AdminPayment['status'][]) => {
    const now = sum(month, statuses)
    const before = sum(last, statuses)
    const others = otherCurrencies(month, statuses)
    return { now, before, others }
  }
  const total = card(ALL_STATUSES)
  const cards: Array<{ label: string; statuses: AdminPayment['status'][]; icon: typeof LuCreditCard; tone: 'red' | 'green' | 'amber' | 'violet'; noun: string }> = [
    { label: 'Verified', statuses: ['SUCCESS'], icon: LuCircleCheck, tone: 'green', noun: 'payment' },
    { label: 'Pending', statuses: ['PENDING'], icon: LuHourglass, tone: 'amber', noun: 'to check' },
    { label: 'Rejected', statuses: ['FAILED'], icon: LuCircleX, tone: 'red', noun: 'payment' },
    { label: 'Refunds', statuses: ['REFUNDED'], icon: LuRotateCcw, tone: 'violet', noun: 'order' },
  ]
  const countLine = (n: number, noun: string) => (noun === 'to check' ? `${n} to check` : `${n} ${noun}${n === 1 ? '' : 's'}`)
  const failedCounts = overviewFailed && !overview

  return (
    <AdminPage
      eyebrow="Payments"
      title="Payments"
      description="View all payment transactions, check status and manage refunds."
      action={(
        <>
          <Link to="/admin/payments/payop" className={buttonClasses('outline')}>International (Payop)</Link>
          {isAdmin && (
            <AdminButton variant="attention" onClick={() => setRefundFor('new')}>
              <LuRotateCcw aria-hidden="true" className="h-4 w-4" />
              Record Refund
            </AdminButton>
          )}
        </>
      )}
    >
      {notice && (
        <div role={notice.tone === 'error' ? 'alert' : 'status'}
          className={`mb-4 flex items-start justify-between gap-3 rounded-admin-control px-4 py-3 text-[13px] ${
            notice.tone === 'error' ? 'bg-admin-red-tint text-admin-red-ink' : 'bg-admin-green-tint text-admin-green-ink'}`}>
          <span>{notice.text}</span>
          <button type="button" onClick={() => setNotice(null)} aria-label="Dismiss"><LuX aria-hidden="true" className="h-4 w-4" /></button>
        </div>
      )}

      <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 min-[1400px]:grid-cols-5">
        <StatCard
          icon={LuCreditCard} tone="red" label="Total Payments" failed={failedCounts}
          value={overview ? (isAdmin ? formatMinor(total.now.minor, 'INR') : total.now.count) : null}
          sub={overview && (isAdmin ? (
            <>
              <TrendLine current={total.now.minor} previous={total.before.minor} against="last month"
                format={(n) => formatMinor(n, 'INR')} />
              <span className="block">
                {[countLine(total.now.count, 'payment'), ...(total.others.length ? [`+ ${total.others.join(' + ')}`] : [])].join(' · ')}
              </span>
            </>
          ) : (
            <TrendLine current={total.now.count} previous={total.before.count} against="last month" />
          ))}
        />
        {cards.map((c) => {
          const figures = card(c.statuses)
          return (
            <StatCard
              key={c.label} icon={c.icon} tone={c.tone} label={c.label} failed={failedCounts}
              value={overview ? (isAdmin ? formatMinor(figures.now.minor, 'INR') : figures.now.count) : null}
              sub={overview && [
                isAdmin ? countLine(figures.now.count, c.noun) : 'This month',
                ...(isAdmin && figures.others.length ? [`+ ${figures.others.join(' + ')}`] : []),
              ].join(' · ')}
            />
          )
        })}
      </div>
      <p className="-mt-3 mb-4 text-[12px] text-admin-faint">
        Cards count payments reported this month, India time{isAdmin ? '; amounts in rupees, other currencies beside' : ''}.
      </p>

      <div className="mb-3 flex flex-wrap items-stretch justify-between gap-3 overflow-hidden rounded-admin-card border border-admin-line bg-white shadow-admin-card">
        <div className="min-w-0 flex-1">
          <TabRow
            label="Status"
            active={TABS.some((t) => t.key === status) ? status : null}
            onChange={(key) => update({ status: key || null })}
            items={TABS.map((t) => ({
              key: t.key,
              label: t.label,
              count: overview ? (t.status ? overview.allTime[t.status] ?? 0 : Object.values(overview.allTime).reduce((a, b) => a + b, 0)) : null,
            }))}
          />
        </div>
        <div className="flex items-center gap-2 px-3 py-2">
          <div className="w-[210px]">
            <DateRangeMenu from={from} to={to} onChange={(f, t) => update({ from: f || null, to: t || null })} />
          </div>
          {isAdmin && (
            <AdminButton onClick={() => void exportCsv()} disabled={exporting}>
              <LuDownload aria-hidden="true" className="h-4 w-4" />
              {exporting ? 'Exporting…' : 'Export'}
            </AdminButton>
          )}
        </div>
      </div>

      <div className="mb-3 flex flex-wrap items-end gap-3 rounded-admin-card border border-admin-line bg-white p-3.5 shadow-admin-card">
        <div className="min-w-[220px] flex-[2_1_320px]">
          <label htmlFor="payment-search" className="sr-only">
            Search payments by order, customer name or email, or payment or refund reference
          </label>
          <div className="flex h-10 items-center gap-2 rounded-admin-control border border-admin-line bg-white px-3
                          focus-within:border-admin-red focus-within:ring-2 focus-within:ring-admin-red/20">
            <LuSearch aria-hidden="true" className="h-4 w-4 shrink-0 text-admin-faint" />
            <input id="payment-search" type="search" value={searchText} maxLength={100}
              onChange={(e) => setSearchText(e.target.value)}
              placeholder="Search order ID, customer email, transaction ID, payment method..."
              className="min-w-0 flex-1 bg-transparent text-[13px] text-admin-ink placeholder:text-admin-faint focus:outline-none" />
          </div>
        </div>
        <div className="min-w-[160px] flex-[1_1_160px]">
          <label htmlFor="payment-method" className="mb-1 block text-[12px] text-admin-muted">Payment Method</label>
          <select id="payment-method" value={method} onChange={(e) => update({ method: e.target.value || null })} className={selectClass}>
            <option value="">All Methods</option>
            {Object.entries(METHOD_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </div>
        <div className="min-w-[160px] flex-[1_1_160px]">
          <label htmlFor="payment-status" className="mb-1 block text-[12px] text-admin-muted">Status</label>
          <select id="payment-status" value={status} onChange={(e) => update({ status: e.target.value || null })} className={selectClass}>
            <option value="">All Statuses</option>
            {ALL_STATUSES.map((s) => <option key={s} value={s}>{PAY_STATUS[s]!.label}</option>)}
          </select>
        </div>
        <AdminButton onClick={() => update({ status: null, method: null, from: null, to: null, search: null })} disabled={!filtered}>
          <LuX aria-hidden="true" className="h-4 w-4" />
          Clear Filters
        </AdminButton>
      </div>

      <div className={open ? 'xl:grid xl:grid-cols-[minmax(0,1fr)_360px] xl:items-start xl:gap-4' : ''}>
        <div className="min-w-0">
          <TableCard
            footer={result && result.total > 0 ? (
              <Pagination page={page} size={PAGE_SIZE} total={result.total} noun="payments"
                onPage={(p) => update({ page: p > 0 ? String(p + 1) : null })} />
            ) : undefined}
          >
            <table className={`w-full border-collapse text-admin-cell ${open ? 'min-w-[760px]' : 'min-w-[1000px]'}`}>
              <thead className="bg-[#FAFBFC]">
                <tr className="border-b border-admin-line">
                  <Th>Date &amp; Time</Th>
                  <Th>Order ID</Th>
                  <Th>Customer</Th>
                  <Th>{open ? 'Method' : 'Payment Method'}</Th>
                  <Th>Amount</Th>
                  <Th>Status</Th>
                  <Th className="text-right">Actions</Th>
                </tr>
              </thead>
              {failed && !result ? (
                <TableState kind="error" columns={7} title="Could not load payments" onRetry={() => void loadTable()}>
                  The list did not come back from the server. Nothing has changed; try again.
                </TableState>
              ) : !result ? (
                <TableState kind="loading" columns={7} />
              ) : result.items.length === 0 ? (
                <TableState kind="empty" columns={7} title={filtered ? 'No payments match these filters' : 'No payments yet'}>
                  {filtered ? (
                    <button type="button" onClick={() => update({ status: null, method: null, from: null, to: null, search: null })}
                      className="font-medium text-admin-red-text underline">Clear the filters</button>
                  ) : 'Payments customers report appear here.'}
                </TableState>
              ) : (
                <tbody>
                  {result.items.map((p) => (
                    <PaymentRow key={p.claimId} payment={p} selected={openId === String(p.claimId)} compact={Boolean(open)}
                      isAdmin={isAdmin} onView={() => update({ payment: String(p.claimId) })}
                      onRefund={() => setRefundFor(p)} />
                  ))}
                </tbody>
              )}
            </table>
          </TableCard>
        </div>

        {open && (
          <PaymentPanel
            payment={open}
            isAdmin={isAdmin}
            onClose={() => update({ payment: null })}
            onChanged={(text) => { setNotice({ tone: 'ok', text }); reload() }}
            onRefund={(p) => setRefundFor(p)}
          />
        )}
      </div>

      {refundFor && (
        <RecordRefund
          initialRef={refundFor === 'new' ? '' : refundFor.publicRef}
          amountFormatted={refundFor === 'new' ? undefined : refundFor.amountFormatted}
          onClose={() => setRefundFor(null)}
          onRecorded={(r) => {
            setRefundFor(null)
            setNotice({ tone: 'ok', text: `Refund of ${r.amountFormatted} recorded on ${r.publicRef}. The order is now Refunded.` })
            reload()
          }}
        />
      )}
    </AdminPage>
  )
}

function PaymentRow({
  payment: p, selected, compact, isAdmin, onView, onRefund,
}: {
  payment: AdminPayment
  selected: boolean
  compact: boolean
  isAdmin: boolean
  onView: () => void
  onRefund: () => void
}) {
  const style = PAY_STATUS[p.status] ?? { label: p.status, tone: 'grey' as const }
  const refunded = p.status === 'REFUNDED'
  return (
    <tr className={`border-b border-admin-line last:border-b-0 ${selected ? 'bg-[#FFF8F8]' : 'hover:bg-[#FCFCFD]'}`}>
      <td className="whitespace-nowrap px-3 py-3 text-admin-ink">{shortDateTime(p.submittedAt)}</td>
      <td className="whitespace-nowrap px-3 py-3">
        <Link to={`/admin/orders/${p.publicRef}`} className="font-medium text-admin-ink hover:underline">#{p.publicRef}</Link>
      </td>
      <td className={`${compact ? 'max-w-[150px]' : 'max-w-[220px]'} px-3 py-3`}>
        {p.customerName && <p className="truncate font-semibold text-admin-ink" title={p.customerName}>{p.customerName}</p>}
        <p className={`truncate ${p.customerName ? 'text-[12px] text-admin-faint' : 'text-admin-ink'}`} title={p.email}>{p.email}</p>
      </td>
      <td className="px-3 py-3"><MethodMark method={p.method} compact={compact} /></td>
      <td className="whitespace-nowrap px-3 py-3 font-semibold tabular-nums text-admin-ink">{p.amountFormatted}</td>
      <td className="px-3 py-3"><StatusBadge dot label={style.label} tone={style.tone} /></td>
      <td className="whitespace-nowrap px-3 py-3">
        <div className="flex items-center justify-end gap-1.5">
          <button
            type="button"
            onClick={onView}
            aria-expanded={selected}
            aria-label={`${refunded ? 'View refund' : 'View payment'} on order ${p.publicRef}`}
            className={`inline-flex h-8 items-center rounded-admin-control border px-3.5 text-[12.5px] font-medium
                        focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red ${refunded
                          ? 'border-admin-line bg-white text-admin-ink hover:bg-admin-page'
                          : 'border-[#F6D3D6] bg-[#FDF3F4] text-admin-red-text hover:bg-admin-red-tint'}`}
          >
            {refunded ? 'View Refund' : 'View'}
          </button>
          <RowMenu label={`More for the payment on ${p.publicRef}`}>
            {(close) => (
              <>
                <MenuItem onSelect={() => { close(); window.open(`/admin/orders/${p.publicRef}`, '_blank', 'noopener') }}>
                  <LuExternalLink aria-hidden="true" className="h-4 w-4 text-admin-faint" />
                  Open order in a new tab
                </MenuItem>
                <MenuItem onSelect={() => { close(); void navigator.clipboard?.writeText(p.reference) }}>
                  <LuCopy aria-hidden="true" className="h-4 w-4 text-admin-faint" />
                  Copy payment reference
                </MenuItem>
                {isAdmin && p.status === 'SUCCESS' && (
                  <MenuItem onSelect={() => { close(); onRefund() }}>
                    <LuRotateCcw aria-hidden="true" className="h-4 w-4 text-admin-faint" />
                    Record refund
                  </MenuItem>
                )}
              </>
            )}
          </RowMenu>
        </div>
      </td>
    </tr>
  )
}
