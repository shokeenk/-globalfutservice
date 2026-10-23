import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../../../lib/api'
import type { PointsBreakdown, PointsHistoryPage, PointsKind, PointsLine } from '../../../lib/types'
import { Modal } from '../ui/Modal'
import { buttonClasses } from '../ui/controls'
import { shortDate } from '../ui/format'

const KIND: Record<PointsKind, string> = {
  EARNED: 'Earned',
  SPENT: 'Spent',
  RETURNED: 'Returned',
  DAILY_BONUS: 'Daily bonus',
  ADJUSTMENT: 'Adjustment',
  REMOVED: 'Removed',
  STORE_CREDIT_POINTS: 'Store credit paid as points',
  CORRECTION: 'Correction',
}

const PAGE_SIZE = 25

const n = (v: number) => (v < 0 ? `−${Math.abs(v).toLocaleString('en-IN')}` : v.toLocaleString('en-IN'))
const signed = (v: number) => (v > 0 ? `+${n(v)}` : `−${n(Math.abs(v))}`)
const tierName = (t: string) => t.charAt(0) + t.slice(1).toLowerCase()

interface Standing { available: number; earned: number; lifetime: number; tier: string }
interface Outcome { before: Standing; after: Standing; change: number }

/** What a correction is about to do, and where it is sent. */
type Correction =
  | { kind: 'reverse'; line: PointsLine }
  | { kind: 'balance' }

/**
 * A customer's points as they see them: the same breakdown, what is on the way, and the
 * full history with the balance after each line. Enough to answer "where did my points
 * go?" without reading the database. An admin can also correct them: reverse one line, or
 * set the balance -- each previewed, with a reason the customer sees.
 */
export function CustomerPoints({ customerKey, isAdmin = false }: { customerKey: string; isAdmin?: boolean }) {
  const base = `/api/v1/admin/customers/${encodeURIComponent(customerKey)}/points`
  const [breakdown, setBreakdown] = useState<PointsBreakdown | null>(null)
  const [history, setHistory] = useState<PointsHistoryPage | null>(null)
  const [page, setPage] = useState(0)
  const [error, setError] = useState(false)
  const [correcting, setCorrecting] = useState<Correction | null>(null)

  const loadBreakdown = useCallback(() => {
    api.get<PointsBreakdown>(base).then(setBreakdown).catch(() => setError(true))
  }, [base])

  useEffect(() => {
    setPage(0)
    setError(false)
    setBreakdown(null)
    loadBreakdown()
  }, [loadBreakdown])

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
      <div className="mb-2 flex items-center justify-between gap-2">
        <h3 id="customer-points" className="text-[15px] font-semibold text-admin-ink">Points</h3>
        {isAdmin && b && (
          <button type="button" className={buttonClasses('outline', 'sm')} onClick={() => setCorrecting({ kind: 'balance' })}>
            Set points balance
          </button>
        )}
      </div>
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
              ...(b.credited ? [['Store credit paid as points', `+${n(b.credited)}`] as const] : []),
              ...(b.corrected ? [['Corrections', signed(b.corrected)] as const] : []),
              ['On the way', n(b.onTheWay)],
            ] as const).map(([label, value]) => (
              <div key={label} className="flex justify-between gap-3">
                <dt className="text-admin-muted">{label}</dt>
                <dd className="font-semibold tabular-nums text-admin-ink">{value}</dd>
              </div>
            ))}
          </dl>
          <p className="mt-1.5 text-[12px] text-admin-faint" data-testid="customer-points-sum">
            {n(b.earned)} earned{b.credited ? ` + ${n(b.credited)} credited` : ''} − {n(b.spent)} spent
            + {n(b.returned)} returned − {n(b.removed)} removed
            {b.corrected ? ` ${b.corrected > 0 ? '+' : '−'} ${n(Math.abs(b.corrected))} corrected` : ''}
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
                <tr key={line.id} className={line.reversed ? 'text-admin-faint' : undefined}>
                  <td className="py-1.5 pr-2 align-top text-admin-muted">{shortDate(line.at)}</td>
                  <td className="py-1.5 pr-2 align-top text-admin-ink">
                    {line.kind === 'CORRECTION' && line.note ? `Correction: ${line.note}` : KIND[line.kind]}
                    {line.reversed && <span className="ml-1.5 text-[11px] font-medium text-admin-muted">(reversed)</span>}
                    {line.orderRef && (
                      <Link to={`/admin/orders/${line.orderRef}`} className="block text-[11.5px] text-admin-muted hover:underline">
                        #{line.orderRef}
                      </Link>
                    )}
                    {line.by && <span className="block text-[11px] text-admin-faint">by {line.by}</span>}
                    {isAdmin && !line.reversed && line.reversesId === null && line.amount !== 0 && (
                      <button type="button" onClick={() => setCorrecting({ kind: 'reverse', line })}
                        className="mt-0.5 block text-[11.5px] font-medium text-admin-red-text hover:underline">
                        Reverse this entry
                      </button>
                    )}
                  </td>
                  <td className="py-1.5 text-right align-top font-semibold tabular-nums text-admin-ink">{signed(line.amount)}</td>
                  <td className="py-1.5 text-right align-top tabular-nums text-admin-muted">{n(line.balanceAfter)}</td>
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

      {correcting && b && (
        <CorrectionDialog
          base={base}
          correction={correcting}
          available={b.available}
          onClose={() => setCorrecting(null)}
          onDone={() => {
            setCorrecting(null)
            loadBreakdown()
            setPage(0)
            loadHistory(0)
          }}
        />
      )}
    </section>
  )
}

/**
 * Reverse a line, or set the balance: the reason (which the customer sees) and, for the
 * balance, the number; then a preview of the balance, earned total and tier it leads to;
 * then a confirmation. Nothing changes before "Confirm".
 */
function CorrectionDialog({ base, correction, available, onClose, onDone }: {
  base: string
  correction: Correction
  available: number
  onClose: () => void
  onDone: () => void
}) {
  const reversing = correction.kind === 'reverse'
  const [target, setTarget] = useState(String(available >= 0 ? available : 0))
  const [reason, setReason] = useState('')
  const [preview, setPreview] = useState<Outcome | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const url = reversing ? `${base}/entries/${correction.line.id}/reverse` : `${base}/balance`

  function body(confirm: boolean) {
    return reversing
      ? { reason: reason.trim(), confirmed: confirm }
      : { target: Number(target), reason: reason.trim(), confirmed: confirm }
  }

  function check(): string | null {
    if (!reason.trim()) return 'Give a reason: the customer sees it on their points history.'
    if (!reversing && (!/^\d+$/.test(target.trim()))) return 'Enter the new balance: a whole number, 0 or more.'
    return null
  }

  async function send(confirm: boolean) {
    const problem = check()
    if (problem) {
      setError(problem)
      return
    }
    if (confirm && !confirmed) {
      setError('Tick the box to confirm.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const outcome = await api.post<Outcome>(url, body(confirm))
      if (confirm) onDone()
      else setPreview(outcome)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'That could not be saved.')
    } finally {
      setBusy(false)
    }
  }

  // Any edit makes the preview stale: it has to be shown again before confirming.
  function edited<T>(set: (v: T) => void) {
    return (v: T) => {
      set(v)
      setPreview(null)
      setConfirmed(false)
    }
  }

  return (
    <Modal title={reversing ? 'Reverse this entry' : 'Set points balance'} onClose={onClose}>
      <div className="space-y-4 text-[13px] text-admin-ink" data-testid="points-correction">
        {reversing ? (
          <p className="rounded-admin-control bg-admin-page px-3.5 py-2.5">
            {KIND[correction.line.kind]} {signed(correction.line.amount)} on {shortDate(correction.line.at)}
            {correction.line.orderRef ? ` (#${correction.line.orderRef})` : ''}. A linked correction of{' '}
            {signed(-correction.line.amount)} takes it back. Neither line will count as earned or toward the tier.
          </p>
        ) : (
          <div>
            <label htmlFor="points-target" className="mb-1 block font-medium">New balance</label>
            <input id="points-target" type="number" min={0} step={1} inputMode="numeric" value={target}
              aria-describedby="points-target-hint"
              onChange={(e) => edited(setTarget)(e.target.value)}
              className="w-full rounded-admin-control border border-admin-line px-3 py-2 tabular-nums" />
            <p id="points-target-hint" className="mt-1 text-[12px] text-admin-muted">
              One correction for the difference. It does not count as earned or toward the tier.
            </p>
          </div>
        )}
        <label className="block">
          <span className="mb-1 block font-medium">Reason (the customer sees it)</span>
          <input type="text" maxLength={300} value={reason} onChange={(e) => edited(setReason)(e.target.value)}
            className="w-full rounded-admin-control border border-admin-line px-3 py-2" />
        </label>

        {preview && (
          <div data-testid="correction-preview">
            <table className="w-full text-left text-[12.5px]">
              <thead className="text-[11.5px] text-admin-faint">
                <tr><th scope="col" /><th scope="col" className="text-right font-medium">Now</th>
                  <th scope="col" className="text-right font-medium">After</th></tr>
              </thead>
              <tbody className="tabular-nums">
                <tr><th scope="row" className="py-0.5 font-normal text-admin-muted">Available</th>
                  <td className="text-right">{n(preview.before.available)}</td>
                  <td className="text-right font-semibold">{n(preview.after.available)}</td></tr>
                <tr><th scope="row" className="py-0.5 font-normal text-admin-muted">Earned (all time)</th>
                  <td className="text-right">{n(preview.before.earned)}</td>
                  <td className="text-right font-semibold">{n(preview.after.earned)}</td></tr>
                <tr><th scope="row" className="py-0.5 font-normal text-admin-muted">Tier</th>
                  <td className="text-right">{tierName(preview.before.tier)}</td>
                  <td className="text-right font-semibold">{tierName(preview.after.tier)}</td></tr>
              </tbody>
            </table>
            {preview.after.available < 0 && (
              <p className="mt-2 rounded-admin-control bg-[#FFF4E5] px-3 py-2 text-[12.5px]">
                The balance would be {n(preview.after.available)}: some of these points were already spent.
                Set the balance afterwards.
              </p>
            )}
            <label className="mt-3 flex items-start gap-2">
              <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} className="mt-0.5" />
              <span>Make this correction ({signed(preview.change)} points). It is logged with your name.</span>
            </label>
          </div>
        )}

        {error && <p role="alert" className="text-[12.5px] text-admin-red-text">{error}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" className={buttonClasses('outline')} onClick={onClose} disabled={busy}>Cancel</button>
          {preview ? (
            <button type="button" className={buttonClasses('primary')} onClick={() => void send(true)} disabled={busy}>
              Confirm
            </button>
          ) : (
            <button type="button" className={buttonClasses('primary')} onClick={() => void send(false)} disabled={busy}>
              Preview
            </button>
          )}
        </div>
      </div>
    </Modal>
  )
}
