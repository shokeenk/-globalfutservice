import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { LuCopy, LuX } from 'react-icons/lu'
import { ApiError } from '../../../lib/api'
import type { AdminPayment } from '../../../lib/types'
import { ProofThumb } from '../PaymentClaims'
import { StatusBadge } from '../ui/Badge'
import { buttonClasses } from '../ui/controls'
import { dateAndTime } from '../ui/format'
import { MethodMark } from '../ui/MethodMark'
import { METHOD_LABEL, PAY_STATUS } from '../ui/status'
import { reviewClaim } from './review'

/**
 * One payment, beside the table on a wide screen and over it on a narrow one.
 *
 * <p>A pending payment can be verified or rejected here, with exactly the questions the
 * payments-to-check panel asks. A refunded one shows the record of the money sent back,
 * or says there is none, for refunds made before they were recorded.
 */
export function PaymentPanel({
  payment: p, isAdmin, onClose, onChanged, onRefund,
}: {
  payment: AdminPayment
  isAdmin: boolean
  onClose: () => void
  onChanged: (message: string) => void
  onRefund: (payment: AdminPayment) => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const closeButton = useRef<HTMLButtonElement>(null)
  const status = PAY_STATUS[p.status] ?? { label: p.status, tone: 'grey' as const }

  useEffect(() => { closeButton.current?.focus(); setError(null) }, [p.claimId])
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onClose()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const review = async (outcome: 'verify' | 'reject') => {
    setBusy(true)
    setError(null)
    try {
      const done = await reviewClaim({ id: p.claimId, publicRef: p.publicRef, amountFormatted: p.amountFormatted,
        destination: p.destination, reference: p.reference }, outcome)
      if (done) onChanged(outcome === 'verify' ? `${p.publicRef} is verified and paid.` : `The payment on ${p.publicRef} was rejected.`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : `Could not ${outcome} that payment. Reload and check whether it went through.`)
    } finally {
      setBusy(false)
    }
  }

  const rows: Array<[string, ReactNode]> = [
    ['Order', <Link key="o" to={`/admin/orders/${p.publicRef}`} className="font-semibold text-admin-red-text hover:underline">#{p.publicRef}</Link>],
    ['Customer', <span key="c">{p.customerName && <span className="block">{p.customerName}</span>}<span className="text-admin-muted">{p.email}</span></span>],
    ['Method', <MethodMark key="m" method={p.method} />],
    // Which account to open, in full: half a wallet address looks like a different one.
    ['Paid to', <span key="d" style={{ overflowWrap: 'anywhere' }}>{p.destination}</span>],
    ['Reference', (
      <span key="r" className="inline-flex items-start gap-1.5">
        <code className="text-[12.5px]" style={{ overflowWrap: 'anywhere' }}>{p.reference}</code>
        <button type="button" onClick={() => void navigator.clipboard?.writeText(p.reference)}
          aria-label="Copy reference" className="text-admin-faint hover:text-admin-ink">
          <LuCopy aria-hidden="true" className="h-3.5 w-3.5" />
        </button>
      </span>
    )],
    ['Screenshot', <ProofThumb key="s" claimId={p.claimId} hasProof={p.hasProof} />],
    ['Submitted', dateAndTime(p.submittedAt)],
    ...(p.reviewedAt ? [['Reviewed', `${dateAndTime(p.reviewedAt)}${p.reviewedBy ? ` by ${p.reviewedBy}` : ''}`] as [string, ReactNode]] : []),
    ...(p.reviewNote ? [['Note', p.reviewNote] as [string, ReactNode]] : []),
  ]

  return (
    <>
      <div aria-hidden="true" onClick={onClose} className="fixed inset-0 z-40 bg-black/40 xl:hidden" />
      <aside
        aria-label="Payment details"
        className="fixed inset-y-0 right-0 z-50 w-full max-w-[400px] overflow-y-auto bg-white shadow-admin-pop
                   xl:sticky xl:top-[70px] xl:z-auto xl:max-h-[calc(100vh-84px)] xl:max-w-none xl:rounded-admin-card
                   xl:border xl:border-admin-line xl:shadow-admin-card"
      >
        <div className="flex items-start gap-3 border-b border-admin-line p-5">
          <div className="min-w-0 flex-1">
            <p className="text-[12px] uppercase tracking-[0.06em] text-admin-faint">Payment</p>
            <p className="mt-0.5 text-[24px] font-bold tabular-nums text-admin-ink">{p.amountFormatted}</p>
            <StatusBadge dot label={status.label} tone={status.tone} className="mt-1.5" />
          </div>
          <button ref={closeButton} type="button" onClick={onClose} aria-label="Close payment details"
            className="grid h-8 w-8 shrink-0 place-items-center rounded-admin-control text-admin-ink hover:bg-admin-page
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red">
            <LuX aria-hidden="true" className="h-5 w-5" />
          </button>
        </div>

        <div className="p-5">
          {p.status === 'PENDING' && (
            <div className="mb-5 rounded-admin-control bg-admin-amber-tint p-3.5">
              <p className="text-[13px] text-admin-amber-ink">
                Nobody has checked this yet. Find {p.amountFormatted} in {p.destination} with the reference below.
              </p>
              <div className="mt-3 flex gap-2">
                <button type="button" disabled={busy} onClick={() => void review('verify')} className={buttonClasses('primary', 'sm')}>
                  {busy ? 'Working…' : 'Verify'}
                </button>
                <button type="button" disabled={busy} onClick={() => void review('reject')} className={buttonClasses('outline', 'sm')}>
                  Reject
                </button>
              </div>
            </div>
          )}
          {error && <p role="alert" className="mb-4 rounded-admin-control bg-admin-red-tint px-3 py-2 text-[13px] text-admin-red-ink">{error}</p>}

          <dl className="divide-y divide-admin-line">
            {rows.map(([label, value]) => (
              <div key={label} className="flex items-baseline gap-3 py-2.5 text-[13px]">
                <dt className="w-24 shrink-0 text-admin-faint">{label}</dt>
                <dd className="min-w-0 flex-1 text-admin-ink">{value}</dd>
              </div>
            ))}
          </dl>

          {p.status === 'REFUNDED' && (
            <section aria-labelledby="refund-record" className="mt-5 rounded-admin-control border border-admin-line p-4">
              <h3 id="refund-record" className="text-[14px] font-semibold text-admin-ink">Refund</h3>
              {p.refund ? (
                <dl className="mt-2 space-y-1.5 text-[13px]">
                  <div className="flex gap-3"><dt className="w-24 text-admin-faint">Amount</dt><dd>{p.refund.amountFormatted}</dd></div>
                  <div className="flex gap-3"><dt className="w-24 text-admin-faint">Sent by</dt><dd>{METHOD_LABEL[p.refund.method] ?? p.refund.method}</dd></div>
                  <div className="flex gap-3"><dt className="w-24 text-admin-faint">Reference</dt><dd style={{ overflowWrap: 'anywhere' }}>{p.refund.reference}</dd></div>
                  <div className="flex gap-3"><dt className="w-24 text-admin-faint">Reason</dt><dd>{p.refund.reason}</dd></div>
                  <div className="flex gap-3"><dt className="w-24 text-admin-faint">Recorded</dt>
                    <dd>{dateAndTime(p.refund.at)}{p.refund.by ? ` by ${p.refund.by}` : ''}</dd></div>
                </dl>
              ) : (
                <p className="mt-1.5 text-[13px] text-admin-muted">
                  This order was marked refunded before refunds were recorded, so there is no amount or reference on file.
                </p>
              )}
            </section>
          )}

          {isAdmin && p.status === 'SUCCESS' && (
            <button type="button" onClick={() => onRefund(p)} className={`${buttonClasses('outline')} mt-5 w-full`}>
              Record Refund
            </button>
          )}
        </div>
      </aside>
    </>
  )
}
