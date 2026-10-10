import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { LuTriangleAlert } from 'react-icons/lu'
import { api, ApiError } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import { useAuth } from '../../state/AuthContext'
import { AdminPage } from './shell/AdminPage'
import { StatusBadge } from './ui/Badge'
import { AdminButton } from './ui/controls'
import { Modal } from './ui/Modal'
import { TableCard, TableState, Th } from './ui/Table'
import { TabRow } from './ui/Tabs'
import type { Tone } from './ui/status'

/**
 * Payop: international payments.
 *
 * <p>An operator sees all of it and can ask Payop about a payment, which only pays an order
 * if Payop's answer passes every check an IPN's would. Changing a fee, loading a pricing
 * sheet, entering an exchange rate and accepting a payment by hand are an admin's, as the
 * endpoints behind them are; for an operator those controls are simply not drawn.
 *
 * <p>An admin can also price one method by hand -- the card method the sheet does not list --
 * and run the Payop check: every method Payop lists for a country right now, and whether
 * customers there are offered it.
 */

interface Rate { currency: string; rate: number | null; source: string | null; date: string | null; current: boolean }

interface Overview {
  enabled: boolean
  configured: boolean
  jwtExpiresAt: string | null
  merchantPaysNote: string
  methods: number
  activeMethods: number
  rates: Rate[]
  needsAttention: number
}

interface Fee {
  methodId: number
  name: string
  type: string
  region: string | null
  fixedEur: number
  percent: number
  countries: string[]
  currencies: string[]
  active: boolean
  version: number
  updatedAt: string
  /** SHEET: from the pricing sheet. MANUAL: priced by hand; an import keeps it. */
  source?: 'SHEET' | 'MANUAL'
}

/** One method Payop lists for the country checked, with its fee row if priced. */
interface LiveMethod {
  methodId: number
  title: string | null
  type: string | null
  currencies: string[]
  countries: string[]
  card: boolean
  fee: Fee | null
  offered: boolean
}

/** What the "Add a method" form starts from: blank, or a method Payop lists without a fee. */
interface ManualDraft {
  methodId: string
  name: string
  type: string
  countries: string
  currencies: string
}

interface Invoice {
  id: number
  orderRef: string | null
  invoiceId: string | null
  status: string
  reason: string | null
  methodName: string
  currency: string
  totalFormatted: string
  amountSent: string
  country: string | null
  txid: string | null
  createdAt: string
  expiresAt: string
}

interface Rejected { payload: string; receivedAt: string }

interface Audit { methodId: number; version: number; action: string; before: string | null; after: string; at: string }

interface ImportResult {
  methods: number; added: number; changed: number; unchanged: number; deactivated: number; keptManual?: number
}

type View = 'payments' | 'refused' | 'fees' | 'rates' | 'check'

const STATUS_TONE: Record<string, Tone> = {
  PAID: 'green', OPEN: 'blue', CREATING: 'blue', REVIEW: 'amber', DUPLICATE: 'red', FAILED: 'grey', EXPIRED: 'grey',
}

/** What a stored reason code means, for whoever is reading the list. */
export function reasonText(reason: string | null): string {
  if (!reason) return ''
  if (reason.startsWith('CREATE_')) return 'Payop did not create the invoice'
  if (reason.startsWith('ORDER_')) return `Paid on an order that is ${reason.slice(6).toLowerCase().replace(/_/g, ' ')}`
  return ({
    AMOUNT_MISMATCH: 'Payop reports a different amount',
    CURRENCY_MISMATCH: 'Payop reports a different currency',
    ORDER_MISMATCH: 'Payop’s transaction is for another order',
    METADATA_MISMATCH: 'Names a different payment attempt',
    UNCONFIRMED_AMOUNT: 'Payop did not state the amount: check the dashboard',
    DUPLICATE_PAYMENT: 'Duplicate payment, refund needed',
    REPLACED: 'Customer chose another method',
    LIFETIME: '24 hours passed',
    TIMEOUT: 'Timed out',
    REJECTED: 'Rejected by Payop for security reasons',
    FAILED: 'Failed',
  } as Record<string, string>)[reason] ?? reason
}

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' }) : '—')

export default function AdminPayop() {
  useSeo({ title: 'International payments', noindex: true })
  const { account } = useAuth()
  const isAdmin = account?.role === 'ADMIN'
  const [view, setView] = useState<View>('payments')
  const [overview, setOverview] = useState<Overview | null>(null)
  const [overviewFailed, setOverviewFailed] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)

  const loadOverview = useCallback(() => {
    setOverviewFailed(false)
    api.get<Overview>('/api/v1/admin/payop').then(setOverview).catch(() => setOverviewFailed(true))
  }, [])
  useEffect(loadOverview, [loadOverview])

  const say = useCallback((tone: 'ok' | 'error', text: string) => setNotice({ tone, text }), [])

  return (
    <AdminPage
      eyebrow="Payments"
      title="International (Payop)"
      description="Payop fees, exchange rates, and the Payop payments that need a person."
      action={<Link to="/admin/payments" className="text-[13px] font-medium text-admin-red-text hover:underline">All payments</Link>}
    >
      {notice && (
        <div role={notice.tone === 'error' ? 'alert' : 'status'}
          className={`mb-4 rounded-admin-control px-4 py-3 text-[13px] ${
            notice.tone === 'error' ? 'bg-admin-red-tint text-admin-red-ink' : 'bg-admin-green-tint text-admin-green-ink'}`}>
          {notice.text}
        </div>
      )}

      <OverviewCard overview={overview} failed={overviewFailed} onRetry={loadOverview} />

      <div className="mt-5 overflow-hidden rounded-admin-card border border-admin-line bg-white">
        <TabRow
          label="Payop sections"
          active={view}
          onChange={(key) => setView(key as View)}
          items={[
            { key: 'payments', label: 'Payments', count: overview?.needsAttention ?? null },
            { key: 'refused', label: 'Refused notifications', count: null },
            { key: 'fees', label: 'Fee table', count: overview?.methods ?? null },
            { key: 'rates', label: 'Exchange rates', count: null },
            ...(isAdmin ? [{ key: 'check', label: 'Payop check', count: null }] : []),
          ]}
        />
      </div>

      <div className="mt-4">
        {view === 'payments' && <Payments isAdmin={isAdmin} say={say} onChanged={loadOverview} />}
        {view === 'refused' && <RefusedNotifications />}
        {view === 'fees' && <Fees isAdmin={isAdmin} say={say} onChanged={loadOverview} />}
        {view === 'rates' && <Rates isAdmin={isAdmin} say={say} onChanged={loadOverview} />}
        {view === 'check' && isAdmin && <PayopCheck say={say} onChanged={loadOverview} />}
      </div>
    </AdminPage>
  )
}

/* ----------------------------------------------------------------- overview --- */

function OverviewCard({ overview, failed, onRetry }: { overview: Overview | null; failed: boolean; onRetry: () => void }) {
  if (failed) {
    return (
      <div role="alert" className="rounded-admin-card border border-admin-line bg-white p-5 text-[13.5px] text-admin-ink">
        The Payop settings did not load. <AdminButton size="sm" className="ml-2" onClick={onRetry}>Try again</AdminButton>
      </div>
    )
  }
  if (!overview) return <div className="h-28 animate-pulse rounded-admin-card bg-admin-grey-tint" />
  const missingRates = overview.rates.filter((r) => !r.current).map((r) => r.currency)
  return (
    <div className="grid gap-4 rounded-admin-card border border-admin-line bg-white p-5 text-[13.5px] text-admin-ink md:grid-cols-2">
      <div className="space-y-2">
        <p>
          <StatusBadge label={overview.enabled ? 'On' : 'Off'} tone={overview.enabled ? 'green' : 'grey'} />
          <span className="ml-2 text-admin-muted">
            {overview.enabled ? 'Offered to customers on non-INR orders.' : 'Not offered to anyone (GFS_PAYOP_ENABLED is false).'}
          </span>
        </p>
        <p className="text-admin-muted">
          Credentials {overview.configured ? 'set' : 'not set'} · token expires {overview.jwtExpiresAt ?? 'on a date not recorded'}
        </p>
        <p className="text-admin-muted">
          Fee table: {overview.activeMethods} of {overview.methods} methods on
          {missingRates.length > 0 && <> · no exchange rate for {missingRates.join(', ')}, so Payop is not offered in it</>}
        </p>
      </div>
      <p className="flex gap-2 rounded-admin-control bg-admin-amber-tint p-3 text-[13px] text-admin-amber-ink">
        <LuTriangleAlert aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
        <span>{overview.merchantPaysNote}</span>
      </p>
    </div>
  )
}

/* ----------------------------------------------------------------- payments --- */

function Payments({ isAdmin, say, onChanged }: {
  isAdmin: boolean
  say: (tone: 'ok' | 'error', text: string) => void
  onChanged: () => void
}) {
  const [show, setShow] = useState<'attention' | 'all'>('attention')
  const [rows, setRows] = useState<Invoice[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [acting, setActing] = useState<{ row: Invoice; kind: 'verify' | 'accept' } | null>(null)

  const load = useCallback(() => {
    setRows(null)
    setFailed(false)
    api.get<Invoice[]>(`/api/v1/admin/payop/invoices?show=${show}`).then(setRows).catch(() => setFailed(true))
  }, [show])
  useEffect(load, [load])

  return (
    <>
      <TableCard header={
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-admin-line px-4 py-3">
          <p className="text-[14px] font-semibold text-admin-ink">
            {show === 'attention' ? 'In review, or paid twice' : 'Latest 200'}
          </p>
          <AdminButton size="sm" onClick={() => setShow(show === 'attention' ? 'all' : 'attention')}>
            {show === 'attention' ? 'Show all recent' : 'Show only those needing a person'}
          </AdminButton>
        </div>
      }>
        <table className="w-full min-w-[880px] text-[13px]">
          <thead><tr><Th>Order</Th><Th>Status</Th><Th>Why</Th><Th>Method</Th><Th>Amount</Th><Th>Created</Th><Th /></tr></thead>
          {failed ? <TableState kind="error" columns={7} title="The Payop payments did not load" onRetry={load} />
            : !rows ? <TableState kind="loading" columns={7} />
              : rows.length === 0 ? <TableState kind="empty" columns={7} title={show === 'attention' ? 'Nothing needs a person' : 'No Payop payments yet'} />
                : (
                  <tbody>
                    {rows.map((r) => (
                      <tr key={r.id} className="border-t border-admin-line align-top">
                        <td className="px-3 py-3 font-medium">
                          {r.orderRef ? <Link className="text-admin-red-text hover:underline" to={`/admin/orders/${r.orderRef}`}>{r.orderRef}</Link> : '—'}
                          <span className="block break-all text-[11.5px] font-normal text-admin-faint">{r.invoiceId ?? 'no invoice'}</span>
                        </td>
                        <td className="px-3 py-3"><StatusBadge label={r.status} tone={STATUS_TONE[r.status] ?? 'grey'} /></td>
                        <td className="px-3 py-3 text-admin-muted">{reasonText(r.reason)}</td>
                        <td className="px-3 py-3">{r.methodName}{r.country && <span className="text-admin-faint"> · {r.country}</span>}</td>
                        <td className="px-3 py-3 tabular-nums">{r.totalFormatted}</td>
                        <td className="px-3 py-3 text-admin-muted">{when(r.createdAt)}</td>
                        <td className="px-3 py-3 text-right">
                          <div className="flex justify-end gap-2">
                            {r.invoiceId && r.status !== 'PAID' && (
                              <AdminButton size="sm" onClick={() => setActing({ row: r, kind: 'verify' })}>Check with Payop</AdminButton>
                            )}
                            {isAdmin && r.status === 'REVIEW' && (
                              <AdminButton size="sm" variant="soft" onClick={() => setActing({ row: r, kind: 'accept' })}>Accept</AdminButton>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                )}
        </table>
      </TableCard>
      {acting && (
        <PaymentAction
          row={acting.row}
          kind={acting.kind}
          onClose={() => setActing(null)}
          onDone={(text) => { setActing(null); say('ok', text); load(); onChanged() }}
          onError={(text) => say('error', text)}
        />
      )}
    </>
  )
}

function PaymentAction({ row, kind, onClose, onDone, onError }: {
  row: Invoice
  kind: 'verify' | 'accept'
  onClose: () => void
  onDone: (text: string) => void
  onError: (text: string) => void
}) {
  const [txid, setTxid] = useState(row.txid ?? '')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit() {
    if (!txid.trim() || (kind === 'accept' && !note.trim()) || busy) return
    setBusy(true)
    try {
      const result = await api.post<{ outcome: string }>(
        `/api/v1/admin/payop/invoices/${row.id}/${kind}`,
        kind === 'verify' ? { txid: txid.trim() } : { txid: txid.trim(), note: note.trim() })
      onDone(`${row.orderRef ?? 'Payment'}: ${result.outcome.toLowerCase().replace(/_/g, ' ')}`)
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'That did not go through. Try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={kind === 'verify' ? 'Check with Payop' : 'Accept this payment'} onClose={onClose}>
      <div className="space-y-4 text-[13.5px] text-admin-ink">
        <p className="text-admin-muted">
          {kind === 'verify'
            ? 'Asks Payop about the transaction. The order is paid only if Payop says it was accepted for this invoice’s exact amount, currency and order.'
            : 'Only after checking this payment in Payop’s dashboard. The order is marked paid as if Payop had confirmed it, and a second payment on a paid order still becomes a duplicate.'}
        </p>
        <p>{row.orderRef} · {row.amountSent} {row.currency} · {row.methodName}</p>
        <label className="block">
          <span className="text-[12.5px] font-medium text-admin-muted">Payop transaction ID (txid)</span>
          <input value={txid} onChange={(e) => setTxid(e.target.value)} spellCheck={false}
            className="mt-1 h-10 w-full rounded-admin-control border border-admin-line px-3 font-mono text-[12.5px]" />
        </label>
        {kind === 'accept' && (
          <label className="block">
            <span className="text-[12.5px] font-medium text-admin-muted">What you checked</span>
            <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={3}
              className="mt-1 w-full rounded-admin-control border border-admin-line px-3 py-2 text-[13px]" />
          </label>
        )}
        <div className="flex justify-end gap-2">
          <AdminButton onClick={onClose}>Cancel</AdminButton>
          <AdminButton variant="primary" disabled={!txid.trim() || (kind === 'accept' && !note.trim()) || busy}
            onClick={() => void submit()}>
            {kind === 'verify' ? 'Ask Payop' : 'Accept payment'}
          </AdminButton>
        </div>
      </div>
    </Modal>
  )
}

/* ----------------------------------------------------- refused notifications --- */

function RefusedNotifications() {
  const [rows, setRows] = useState<Rejected[] | null>(null)
  const [failed, setFailed] = useState(false)
  const load = useCallback(() => {
    setRows(null)
    setFailed(false)
    api.get<Rejected[]>('/api/v1/admin/payop/rejected').then(setRows).catch(() => setFailed(true))
  }, [])
  useEffect(load, [load])

  const parsed = useMemo(() => (rows ?? []).map((r) => {
    try {
      return { ...r, data: JSON.parse(r.payload) as Record<string, unknown> }
    } catch {
      return { ...r, data: {} as Record<string, unknown> }
    }
  }), [rows])

  return (
    <TableCard header={
      <p className="border-b border-admin-line px-4 py-3 text-[13px] text-admin-muted">
        Notifications naming one of our invoices that came from an address not on Payop’s list. Nothing was changed.
        To check one, use “Check with Payop” on its payment with the txid shown here.
      </p>
    }>
      <table className="w-full min-w-[760px] text-[13px]">
        <thead><tr><Th>Received</Th><Th>From</Th><Th>Invoice</Th><Th>Transaction</Th><Th>State</Th></tr></thead>
        {failed ? <TableState kind="error" columns={5} title="The refused notifications did not load" onRetry={load} />
          : !rows ? <TableState kind="loading" columns={5} />
            : rows.length === 0 ? <TableState kind="empty" columns={5} title="None refused" />
              : (
                <tbody>
                  {parsed.map((r, i) => (
                    <tr key={i} className="border-t border-admin-line">
                      <td className="px-3 py-3 text-admin-muted">{when(r.receivedAt)}</td>
                      <td className="px-3 py-3 font-mono text-[12px]">{String(r.data.sourceIp ?? '—')}</td>
                      <td className="px-3 py-3 break-all font-mono text-[12px]">{String(r.data.invoiceId ?? '—')}</td>
                      <td className="px-3 py-3 break-all font-mono text-[12px]">{String(r.data.txid ?? '—')}</td>
                      <td className="px-3 py-3">{String(r.data.state ?? '—')}</td>
                    </tr>
                  ))}
                </tbody>
              )}
      </table>
    </TableCard>
  )
}

/* ----------------------------------------------------------------- fee table --- */

function Fees({ isAdmin, say, onChanged }: {
  isAdmin: boolean
  say: (tone: 'ok' | 'error', text: string) => void
  onChanged: () => void
}) {
  const [rows, setRows] = useState<Fee[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [filter, setFilter] = useState('')
  const [editing, setEditing] = useState<Fee | null>(null)
  const [importing, setImporting] = useState(false)
  const [adding, setAdding] = useState(false)
  const [history, setHistory] = useState<Audit[] | null>(null)

  const load = useCallback(() => {
    setRows(null)
    setFailed(false)
    api.get<Fee[]>('/api/v1/admin/payop/fees').then(setRows).catch(() => setFailed(true))
  }, [])
  useEffect(load, [load])

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase()
    return (rows ?? []).filter((r) => !q || r.name.toLowerCase().includes(q) || String(r.methodId).includes(q)
      || r.countries.some((c) => c.toLowerCase() === q))
  }, [rows, filter])

  return (
    <>
      <TableCard header={
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-admin-line px-4 py-3">
          <input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter by name, ID or country code"
            aria-label="Filter the fee table"
            className="h-9 w-full max-w-xs rounded-admin-control border border-admin-line px-3 text-[13px]" />
          <div className="flex gap-2">
            <AdminButton size="sm" onClick={() => api.get<Audit[]>('/api/v1/admin/payop/fees/audit').then(setHistory)
              .catch(() => say('error', 'The change history did not load.'))}>Change history</AdminButton>
            {isAdmin && <AdminButton size="sm" onClick={() => setAdding(true)}>Add a method</AdminButton>}
            {isAdmin && <AdminButton size="sm" variant="primary" onClick={() => setImporting(true)}>Import pricing sheet</AdminButton>}
          </div>
        </div>
      }>
        <table className="w-full min-w-[900px] text-[13px]">
          <thead><tr><Th>ID</Th><Th>Method</Th><Th>Region</Th><Th>Fee</Th><Th>Countries</Th><Th>Currencies</Th><Th>On</Th><Th /></tr></thead>
          {failed ? <TableState kind="error" columns={8} title="The fee table did not load" onRetry={load} />
            : !rows ? <TableState kind="loading" columns={8} />
              : shown.length === 0 ? <TableState kind="empty" columns={8} title={rows.length === 0 ? 'The fee table is empty' : 'No method matches'}>
                {rows.length === 0 && 'Import the client’s Payop pricing sheet to fill it.'}
              </TableState>
                : (
                  <tbody>
                    {shown.map((r) => (
                      <tr key={r.methodId} className="border-t border-admin-line align-top">
                        <td className="px-3 py-3 tabular-nums text-admin-muted">{r.methodId}</td>
                        <td className="px-3 py-3 font-medium">{r.name}<span className="block text-[11.5px] font-normal text-admin-faint">{r.type} · v{r.version}{r.source === 'MANUAL' ? ' · priced by hand' : ''}</span></td>
                        <td className="px-3 py-3 text-admin-muted">{r.region ?? '—'}</td>
                        <td className="px-3 py-3 tabular-nums">{Number(r.fixedEur).toFixed(2)} EUR + {r.percent}%</td>
                        <td className="max-w-[220px] px-3 py-3 text-admin-muted">{r.countries.includes('*') ? 'Everywhere' : r.countries.join(', ')}</td>
                        <td className="px-3 py-3 text-admin-muted">{r.currencies.join(', ')}</td>
                        <td className="px-3 py-3"><StatusBadge label={r.active ? 'On' : 'Off'} tone={r.active ? 'green' : 'grey'} /></td>
                        <td className="px-3 py-3 text-right">
                          {isAdmin && <AdminButton size="sm" onClick={() => setEditing(r)}>Edit</AdminButton>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                )}
        </table>
      </TableCard>
      {editing && (
        <FeeEditor fee={editing} onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); say('ok', `${editing.name}: saved as a new version.`); load(); onChanged() }}
          onError={(text) => say('error', text)} />
      )}
      {adding && (
        <ManualFeeEditor onClose={() => setAdding(false)}
          onSaved={(saved) => { setAdding(false); say('ok', `${saved.name}: priced by hand, version ${saved.version}.`); load(); onChanged() }}
          onError={(text) => say('error', text)} />
      )}
      {importing && (
        <SheetImport onClose={() => setImporting(false)}
          onDone={(r) => {
            setImporting(false)
            say('ok', `Imported ${r.methods} methods: ${r.added} added, ${r.changed} changed, ${r.unchanged} unchanged, ${r.deactivated} switched off`
              + (r.keptManual ? `, ${r.keptManual} priced by hand kept.` : '.'))
            load(); onChanged()
          }}
          onError={(text) => say('error', text)} />
      )}
      {history && (
        <Modal title="Fee table changes" onClose={() => setHistory(null)} width="max-w-[760px]">
          {history.length === 0 ? <p className="text-[13px] text-admin-muted">No changes yet.</p> : (
            <ul className="max-h-[60vh] space-y-2 overflow-y-auto text-[12.5px]">
              {history.map((a, i) => (
                <li key={i} className="rounded-admin-control border border-admin-line p-2.5">
                  <p className="font-medium">Method {a.methodId} · v{a.version} · {a.action.toLowerCase()} · {when(a.at)}</p>
                  {a.before && <p className="break-all text-admin-faint">Before: {a.before}</p>}
                  <p className="break-all text-admin-muted">After: {a.after}</p>
                </li>
              ))}
            </ul>
          )}
        </Modal>
      )}
    </>
  )
}

const listOf = (value: string) => value.split(/[\s,]+/).map((v) => v.trim().toUpperCase()).filter(Boolean)

function FeeEditor({ fee, onClose, onSaved, onError }: {
  fee: Fee
  onClose: () => void
  onSaved: () => void
  onError: (text: string) => void
}) {
  const [fixedEur, setFixedEur] = useState(Number(fee.fixedEur).toFixed(2))
  const [percent, setPercent] = useState(String(fee.percent))
  const [countries, setCountries] = useState(fee.countries.join(', '))
  const [currencies, setCurrencies] = useState(fee.currencies.join(', '))
  const [active, setActive] = useState(fee.active)
  const [busy, setBusy] = useState(false)

  async function save() {
    if (busy) return
    setBusy(true)
    try {
      await api.put(`/api/v1/admin/payop/fees/${fee.methodId}`, {
        fixedEur: Number(fixedEur), percent: Number(percent),
        countries: listOf(countries), currencies: listOf(currencies), active,
      })
      onSaved()
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'The change was not saved.')
    } finally {
      setBusy(false)
    }
  }

  const input = 'mt-1 h-10 w-full rounded-admin-control border border-admin-line px-3 text-[13px]'
  return (
    <Modal title={`Edit ${fee.name}`} onClose={onClose}>
      <div className="space-y-3 text-[13.5px] text-admin-ink">
        <p className="text-admin-muted">Saved as a new version; payments already started keep the fee they were shown.</p>
        <div className="grid grid-cols-2 gap-3">
          <label className="block"><span className="text-[12.5px] text-admin-muted">Fixed part (EUR)</span>
            <input className={input} inputMode="decimal" value={fixedEur} onChange={(e) => setFixedEur(e.target.value)} /></label>
          <label className="block"><span className="text-[12.5px] text-admin-muted">Percentage</span>
            <input className={input} inputMode="decimal" value={percent} onChange={(e) => setPercent(e.target.value)} /></label>
        </div>
        <label className="block"><span className="text-[12.5px] text-admin-muted">Countries (two-letter codes, or * for everywhere)</span>
          <input className={input} value={countries} onChange={(e) => setCountries(e.target.value)} /></label>
        <label className="block"><span className="text-[12.5px] text-admin-muted">Processing currencies</span>
          <input className={input} value={currencies} onChange={(e) => setCurrencies(e.target.value)} /></label>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Offered to customers
        </label>
        <div className="flex justify-end gap-2">
          <AdminButton onClick={onClose}>Cancel</AdminButton>
          <AdminButton variant="primary" disabled={busy} onClick={() => void save()}>Save</AdminButton>
        </div>
      </div>
    </Modal>
  )
}

/**
 * Pricing one method by hand: Payop's ID for it, its name and type, and its fee. A method
 * the table already has is corrected as a new version; any other is added, and an import of
 * the sheet keeps it. The server checks it as it checks the sheet.
 */
function ManualFeeEditor({ draft, onClose, onSaved, onError }: {
  draft?: ManualDraft
  onClose: () => void
  onSaved: (saved: Fee) => void
  onError: (text: string) => void
}) {
  const [methodId, setMethodId] = useState(draft?.methodId ?? '')
  const [name, setName] = useState(draft?.name ?? '')
  const [type, setType] = useState(draft?.type ?? '')
  const [fixedEur, setFixedEur] = useState('')
  const [percent, setPercent] = useState('')
  const [countries, setCountries] = useState(draft?.countries ?? '')
  const [currencies, setCurrencies] = useState(draft?.currencies ?? '')
  const [active, setActive] = useState(true)
  const [busy, setBusy] = useState(false)

  async function save() {
    if (busy) return
    setBusy(true)
    try {
      onSaved(await api.post<Fee>('/api/v1/admin/payop/fees', {
        methodId: Number(methodId), name: name.trim(), type: type.trim(), region: null,
        fixedEur: Number(fixedEur), percent: Number(percent),
        countries: listOf(countries), currencies: listOf(currencies), active,
      }))
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'The method was not saved.')
    } finally {
      setBusy(false)
    }
  }

  const input = 'mt-1 h-10 w-full rounded-admin-control border border-admin-line px-3 text-[13px]'
  return (
    <Modal title="Price a method by hand" onClose={onClose} width="max-w-[560px]">
      <div className="space-y-3 text-[13.5px] text-admin-ink">
        <p className="text-admin-muted">
          For a method the pricing sheet does not list, such as cards. Use Payop&rsquo;s own ID and type for it (the Payop
          check shows them). Importing the sheet again keeps it. An ID already in the table is saved as a new version.
        </p>
        <div className="grid grid-cols-2 gap-3">
          <label className="block"><span className="text-[12.5px] text-admin-muted">Payop method ID</span>
            <input className={input} inputMode="numeric" value={methodId}
              onChange={(e) => setMethodId(e.target.value.replace(/\D/g, ''))} /></label>
          <label className="block"><span className="text-[12.5px] text-admin-muted">Type (e.g. cards_international)</span>
            <input className={input} value={type} onChange={(e) => setType(e.target.value)} /></label>
        </div>
        <label className="block"><span className="text-[12.5px] text-admin-muted">Name customers see</span>
          <input className={input} value={name} onChange={(e) => setName(e.target.value)} maxLength={120} /></label>
        <div className="grid grid-cols-2 gap-3">
          <label className="block"><span className="text-[12.5px] text-admin-muted">Fixed part (EUR)</span>
            <input className={input} inputMode="decimal" value={fixedEur} onChange={(e) => setFixedEur(e.target.value)} /></label>
          <label className="block"><span className="text-[12.5px] text-admin-muted">Percentage</span>
            <input className={input} inputMode="decimal" value={percent} onChange={(e) => setPercent(e.target.value)} /></label>
        </div>
        <label className="block"><span className="text-[12.5px] text-admin-muted">Countries (two-letter codes, or * for everywhere)</span>
          <input className={input} value={countries} onChange={(e) => setCountries(e.target.value)} /></label>
        <label className="block"><span className="text-[12.5px] text-admin-muted">Processing currencies</span>
          <input className={input} value={currencies} onChange={(e) => setCurrencies(e.target.value)} /></label>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Offered to customers
        </label>
        <div className="flex justify-end gap-2">
          <AdminButton onClick={onClose}>Cancel</AdminButton>
          <AdminButton variant="primary" disabled={busy} onClick={() => void save()}>Save</AdminButton>
        </div>
      </div>
    </Modal>
  )
}

/* -------------------------------------------------------------- payop check --- */

/**
 * What Payop lists for a country right now, fetched fresh: each method's ID, title, type,
 * currencies and countries, whether it takes cards, its fee if the table has one, and
 * whether customers there are offered it. A method with no fee can be priced from here.
 */
function PayopCheck({ say, onChanged }: { say: (tone: 'ok' | 'error', text: string) => void; onChanged: () => void }) {
  const [country, setCountry] = useState('IN')
  const [rows, setRows] = useState<LiveMethod[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState<string | null>(null)
  const [pricing, setPricing] = useState<ManualDraft | null>(null)

  const check = useCallback(async (code: string) => {
    setBusy(true)
    setFailed(null)
    try {
      setRows(await api.get<LiveMethod[]>(`/api/v1/admin/payop/live-methods?country=${encodeURIComponent(code)}`))
    } catch (e) {
      setRows(null)
      setFailed(e instanceof ApiError ? e.message : 'Payop could not be asked just now.')
    } finally {
      setBusy(false)
    }
  }, [])

  return (
    <>
      <TableCard header={
        <div className="flex flex-wrap items-center gap-3 border-b border-admin-line px-4 py-3">
          <label className="flex items-center gap-2 text-[13px] text-admin-muted">Country
            <input value={country} onChange={(e) => setCountry(e.target.value.toUpperCase().slice(0, 2))}
              aria-label="Country to check" className="h-9 w-16 rounded-admin-control border border-admin-line px-3 text-[13px] uppercase" />
          </label>
          <AdminButton size="sm" variant="primary" disabled={busy || country.length !== 2}
            onClick={() => void check(country)}>{busy ? 'Asking Payop…' : 'Check with Payop'}</AdminButton>
          <p className="text-[12.5px] text-admin-faint">Every method Payop lists for this country now. Offered: listed, priced and on.</p>
        </div>
      }>
        <table className="w-full min-w-[980px] text-[13px]">
          <thead><tr><Th>ID</Th><Th>Title</Th><Th>Type</Th><Th>Currencies</Th><Th>Countries</Th><Th>Fee</Th><Th>Offered</Th><Th /></tr></thead>
          {failed ? <TableState kind="error" columns={8} title={failed} onRetry={() => void check(country)} />
            : !rows ? <TableState kind="empty" columns={8} title="Choose a country and check">India is filled in.</TableState>
              : rows.length === 0 ? <TableState kind="empty" columns={8} title={`Payop lists no method for ${country}`} />
                : (
                  <tbody data-testid="payop-check">
                    {rows.map((m) => (
                      <tr key={m.methodId} className="border-t border-admin-line align-top">
                        <td className="px-3 py-3 tabular-nums text-admin-muted">{m.methodId}</td>
                        <td className="px-3 py-3 font-medium">{m.title ?? '—'}{m.card && <span className="ml-2"><StatusBadge label="Cards" tone="blue" /></span>}</td>
                        <td className="px-3 py-3 text-admin-muted">{m.type ?? '—'}</td>
                        <td className="px-3 py-3 text-admin-muted">{m.currencies.join(', ') || '—'}</td>
                        <td className="max-w-[220px] px-3 py-3 text-admin-muted">{m.countries.join(', ') || 'Not limited by Payop'}</td>
                        <td className="px-3 py-3 tabular-nums">{m.fee ? `${Number(m.fee.fixedEur).toFixed(2)} EUR + ${m.fee.percent}%` : 'No fee'}</td>
                        <td className="px-3 py-3"><StatusBadge label={m.offered ? 'Offered' : 'Hidden'} tone={m.offered ? 'green' : 'grey'} /></td>
                        <td className="px-3 py-3 text-right">
                          {!m.fee && (
                            <AdminButton size="sm" onClick={() => setPricing({
                              methodId: String(m.methodId), name: m.title ?? '', type: m.type ?? '',
                              countries: m.countries.join(', ') || country, currencies: m.currencies.join(', '),
                            })}>Add fee</AdminButton>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                )}
        </table>
      </TableCard>
      {pricing && (
        <ManualFeeEditor draft={pricing} onClose={() => setPricing(null)}
          onSaved={(saved) => { setPricing(null); say('ok', `${saved.name}: priced by hand, version ${saved.version}.`); onChanged(); void check(country) }}
          onError={(text) => say('error', text)} />
      )}
    </>
  )
}

function SheetImport({ onClose, onDone, onError }: {
  onClose: () => void
  onDone: (result: ImportResult) => void
  onError: (text: string) => void
}) {
  const [file, setFile] = useState<File | null>(null)
  const [expected, setExpected] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit() {
    if (!file || busy) return
    setBusy(true)
    try {
      const form = new FormData()
      form.append('file', file)
      if (expected.trim()) form.append('expected', expected.trim())
      onDone(await api.upload<ImportResult>('/api/v1/admin/payop/fees/import', form))
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'The sheet was not imported.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title="Import the Payop pricing sheet" onClose={onClose}>
      <div className="space-y-3 text-[13.5px] text-admin-ink">
        <p className="text-admin-muted">
          All or nothing: if any row cannot be read, or the count is not what you expect, nothing changes and each problem
          is listed. Methods the sheet no longer has are switched off, except those priced by hand. The file is read and not
          kept.
        </p>
        <input type="file" accept=".xlsx" aria-label="Pricing sheet" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        <label className="block"><span className="text-[12.5px] text-admin-muted">How many methods it should have (optional)</span>
          <input className="mt-1 h-10 w-full rounded-admin-control border border-admin-line px-3 text-[13px]" inputMode="numeric"
            value={expected} onChange={(e) => setExpected(e.target.value.replace(/\D/g, ''))} placeholder="e.g. 104" /></label>
        <div className="flex justify-end gap-2">
          <AdminButton onClick={onClose}>Cancel</AdminButton>
          <AdminButton variant="primary" disabled={!file || busy} onClick={() => void submit()}>Import</AdminButton>
        </div>
      </div>
    </Modal>
  )
}

/* ------------------------------------------------------------ exchange rates --- */

function Rates({ isAdmin, say, onChanged }: {
  isAdmin: boolean
  say: (tone: 'ok' | 'error', text: string) => void
  onChanged: () => void
}) {
  const [data, setData] = useState<{ current: Rate[]; recent: Rate[] } | null>(null)
  const [failed, setFailed] = useState(false)
  const [currency, setCurrency] = useState('AED')
  const [rate, setRate] = useState('')
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    setFailed(false)
    api.get<{ current: Rate[]; recent: Rate[] }>('/api/v1/admin/payop/fx').then(setData).catch(() => setFailed(true))
  }, [])
  useEffect(load, [load])

  async function enter() {
    if (!rate.trim() || busy) return
    setBusy(true)
    try {
      await api.post('/api/v1/admin/payop/fx', { currency, rate: Number(rate), date })
      say('ok', `EUR → ${currency} rate saved.`)
      setRate('')
      load(); onChanged()
    } catch (e) {
      say('error', e instanceof ApiError ? e.message : 'The rate was not saved.')
    } finally {
      setBusy(false)
    }
  }

  async function refresh() {
    try {
      const r = await api.post<{ stored: number }>('/api/v1/admin/payop/fx/refresh')
      say('ok', r.stored > 0 ? `Stored ${r.stored} new ECB rate(s).` : 'The ECB had nothing newer, or could not be reached.')
      load(); onChanged()
    } catch {
      say('error', 'The ECB rates could not be fetched.')
    }
  }

  return (
    <div className="space-y-4">
      <TableCard header={
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-admin-line px-4 py-3">
          <p className="text-[13px] text-admin-muted">
            The EUR fixed part of a fee is converted at the ECB’s daily rate while it is under five days old, otherwise at the
            newest rate entered here. The ECB publishes no AED rate.
          </p>
          {isAdmin && <AdminButton size="sm" onClick={() => void refresh()}>Fetch ECB rates now</AdminButton>}
        </div>
      }>
        <table className="w-full min-w-[520px] text-[13px]">
          <thead><tr><Th>Currency</Th><Th>Used now</Th><Th>Source</Th><Th>Rate date</Th></tr></thead>
          {failed ? <TableState kind="error" columns={4} title="The exchange rates did not load" onRetry={load} />
            : !data ? <TableState kind="loading" columns={4} />
              : (
                <tbody>
                  {data.current.map((r) => (
                    <tr key={r.currency} className="border-t border-admin-line">
                      <td className="px-3 py-3 font-medium">EUR → {r.currency}</td>
                      <td className="px-3 py-3 tabular-nums">{r.rate ?? <span className="text-admin-red-text">None: Payop not offered</span>}</td>
                      <td className="px-3 py-3 text-admin-muted">{r.source ?? '—'}</td>
                      <td className="px-3 py-3 text-admin-muted">{r.date ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              )}
        </table>
      </TableCard>
      {isAdmin && (
        <div className="flex flex-wrap items-end gap-3 rounded-admin-card border border-admin-line bg-white p-4 text-[13px]">
          <label className="block"><span className="text-[12.5px] text-admin-muted">Currency</span>
            <select value={currency} onChange={(e) => setCurrency(e.target.value)}
              className="mt-1 block h-10 rounded-admin-control border border-admin-line px-3">
              {['AED', 'USD', 'GBP'].map((c) => <option key={c}>{c}</option>)}
            </select></label>
          <label className="block"><span className="text-[12.5px] text-admin-muted">1 EUR =</span>
            <input value={rate} onChange={(e) => setRate(e.target.value)} inputMode="decimal" placeholder="4.1224"
              className="mt-1 block h-10 w-32 rounded-admin-control border border-admin-line px-3" /></label>
          <label className="block"><span className="text-[12.5px] text-admin-muted">Rate date</span>
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)}
              className="mt-1 block h-10 rounded-admin-control border border-admin-line px-3" /></label>
          <AdminButton variant="primary" disabled={!rate.trim() || busy} onClick={() => void enter()}>Save rate</AdminButton>
        </div>
      )}
    </div>
  )
}
