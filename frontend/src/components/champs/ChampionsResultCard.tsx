import { useEffect, useRef, useState } from 'react'
import { LuTrophy } from 'react-icons/lu'
import { useT } from '../../i18n'
import { ApiError, api } from '../../lib/api'
import type { Order, OrderResult, SettlementMethod } from '../../lib/types'

/** "18 Oct 2026, 10:30" in the customer's own time zone -- the browser's. */
export function chooseByLabel(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}

/**
 * The "Champions Order Results" card on the tracking page, once Wins Achieved is recorded.
 *
 * <p>Wins ordered and achieved; when every win was achieved, says so and nothing else.
 * Otherwise the amount owed in the order's currency, how it is settled and where that
 * stands -- Pending in red until an admin confirms the refund was sent or the credit was
 * added. Delivered and settled are separate: this can say Pending on a delivered order.
 *
 * <p>The owner chooses here, once, while nobody has: a refund or GFS Store Credit, with a
 * confirmation, and the date after which the default applies. Nobody else sees the buttons.
 */
export function ChampionsResultCard({
  order, canChoose, onChanged,
}: {
  order: Order & { result: OrderResult }
  /** The signed-in owner, on their own page: the choice buttons show. */
  canChoose: boolean
  onChanged?: (order: Order) => void
}) {
  const t = useT()
  const c = t.champs
  const r = order.result
  const [asking, setAsking] = useState<SettlementMethod | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ref = useRef<HTMLDivElement>(null)

  // Opened from the email's button (#order-result): bring the card into view once.
  useEffect(() => {
    if (window.location.hash === '#order-result') {
      ref.current?.scrollIntoView?.({ block: 'start' })
    }
  }, [])

  const owed = !r.allAchieved && r.status !== 'CANCELLED' && r.settlementFormatted != null
  const amountLabel = r.method === 'REFUND' ? c.refundAmount
    : r.method === 'STORE_CREDIT' ? c.creditAmount : c.settlementAmount
  const pill = r.method === 'REFUND'
    ? (r.status === 'PAID' ? c.statusRefundPaid : c.statusRefundPending)
    : r.method === 'STORE_CREDIT'
      ? (r.status === 'ISSUED' ? c.statusCreditIssued : c.statusCreditPending)
      : c.statusPending
  const pending = r.status === 'PENDING'
  const choosing = canChoose && pending && r.method == null && r.chooseBy != null

  async function choose(method: SettlementMethod) {
    setSaving(true)
    setError(null)
    try {
      const updated = await api.post<Order>(
        `/api/v1/orders/${encodeURIComponent(order.publicRef)}/result/choice`, { method })
      setAsking(null)
      onChanged?.(updated)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : c.chooseFailed)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div id="order-result" ref={ref} className="scroll-mt-28">
      <p className="stamp mb-4">{c.detailsEyebrow}</p>
      <div className="rounded-panel border border-ink-400 bg-paper p-5" data-testid="champs-result">
        <h3 className="flex items-center gap-2.5 text-[15px] font-semibold text-chalk">
          <LuTrophy aria-hidden="true" size={20} className="shrink-0 text-brand-500" />
          {c.resultsTitle}
        </h3>

        <dl className="mt-4 divide-y divide-ink-400/70 border-t border-ink-400/70">
          <div className="flex justify-between gap-4 py-2.5 text-[13px]">
            <dt className="text-chalk-muted">{c.winsOrdered}</dt>
            <dd className="tnum font-semibold text-chalk" data-testid="wins-ordered">{c.wins(r.targetLevel)}</dd>
          </div>
          <div className="flex justify-between gap-4 py-2.5 text-[13px]">
            <dt className="text-chalk-muted">{c.winsAchieved}</dt>
            <dd className="tnum font-semibold text-chalk" data-testid="wins-achieved">{c.wins(r.achievedLevel)}</dd>
          </div>
        </dl>

        {r.allAchieved && (
          <p className="mt-3 text-[13px] font-semibold text-chalk" data-testid="all-achieved">{c.allAchieved}</p>
        )}

        {r.status === 'CANCELLED' && (
          <p className="mt-3 text-[13px] text-chalk-muted">{c.cancelled}</p>
        )}

        {owed && (
          <div className="mt-3 border-t border-ink-400/70 pt-3">
            <div className="flex flex-wrap items-end justify-between gap-3">
              <div>
                <p className="text-[12.5px] text-chalk-muted">{amountLabel}</p>
                <p className="tnum display mt-0.5 text-[24px] text-brand-500" data-testid="settlement-amount">
                  {r.settlementFormatted}
                </p>
              </div>
              <span
                data-testid="settlement-status"
                className={`rounded-full border px-3 py-1 text-[12px] font-semibold ${
                  pending
                    ? 'border-brand-500/40 bg-brand-500/[0.08] text-brand-500'
                    : 'border-ink-400 bg-ink-700 text-chalk'}`}
              >
                {pill}
              </span>
            </div>

            {r.method && (
              <p className="mt-2 text-[12.5px] text-chalk-muted" data-testid="settlement-method">
                {c.settlementMethod}:{' '}
                <span className="font-semibold text-chalk">
                  {r.method === 'REFUND' ? c.methodRefund : c.methodCredit}
                </span>
              </p>
            )}

            {choosing && (
              <div className="mt-4" data-testid="settlement-choice">
                {!r.refundAllowed && <p className="mb-3 text-[12.5px] text-chalk-muted">{c.creditOnly}</p>}
                {asking ? (
                  <div className="rounded-edge border border-brand-500/30 bg-brand-500/[0.05] p-3.5">
                    <p className="text-[13px] text-chalk">
                      {asking === 'REFUND'
                        ? c.confirmRefund(r.settlementFormatted ?? '')
                        : c.confirmCredit(r.settlementFormatted ?? '')}
                    </p>
                    <div className="mt-3 flex flex-wrap gap-2">
                      <button
                        type="button"
                        disabled={saving}
                        onClick={() => void choose(asking)}
                        className="h-10 rounded-edge bg-brand-500 px-4 text-[13px] font-semibold text-paper
                                   hover:bg-brand-400 disabled:opacity-60"
                      >
                        {c.confirm}
                      </button>
                      <button
                        type="button"
                        disabled={saving}
                        onClick={() => setAsking(null)}
                        className="h-10 rounded-edge border border-ink-400 px-4 text-[13px] font-semibold text-chalk"
                      >
                        {c.cancel}
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {r.refundAllowed && (
                      <button
                        type="button"
                        onClick={() => setAsking('REFUND')}
                        className="h-10 rounded-edge border border-brand-500 px-4 text-[13px] font-semibold
                                   text-brand-500 hover:bg-brand-500/[0.06]"
                      >
                        {c.getRefund}
                      </button>
                    )}
                    <button
                      type="button"
                      onClick={() => setAsking('STORE_CREDIT')}
                      className="h-10 rounded-edge bg-brand-500 px-4 text-[13px] font-semibold text-paper
                                 hover:bg-brand-400"
                    >
                      {c.getCredit}
                    </button>
                  </div>
                )}
                {r.chooseBy && (
                  <p className="mt-3 text-[12.5px] text-chalk-muted" data-testid="choose-by">
                    {r.defaultMethod === 'REFUND'
                      ? c.chooseByRefund(chooseByLabel(r.chooseBy))
                      : c.chooseByCredit(chooseByLabel(r.chooseBy))}
                  </p>
                )}
                {error && <p className="mt-2 text-[12.5px] text-brand-500">{error}</p>}
              </div>
            )}
          </div>
        )}

        <p className="mt-4 text-[12px] text-chalk-faint">{c.footnote}</p>
      </div>
    </div>
  )
}
