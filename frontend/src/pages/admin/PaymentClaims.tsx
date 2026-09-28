import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { LuWallet } from 'react-icons/lu'
import { api } from '../../lib/api'
import type { AdminPaymentClaim } from '../../lib/types'
import { reviewClaim } from './payments/review'
import { StatusBadge } from './ui/Badge'
import { AdminButton } from './ui/controls'
import { shortDateTime } from './ui/format'
import { METHOD_LABEL } from './ui/status'
import { Th } from './ui/Table'

/**
 * The review desk for payments made outside the gateway.
 *
 * Without something on this screen the whole manual-payment flow is a dead end:
 * customers submit references into a table nobody reads and their orders sit in
 * AWAITING_PAYMENT until they email support. Verifying here is the only thing in the
 * system that marks such an order paid.
 *
 * On the Orders page it opens under the Needs Attention button, and a row's Verify
 * Payment opens it with that order's claim highlighted. What it does is unchanged: the
 * same list, the same confirmation, the same two endpoints.
 */
/**
 * The customer's screenshot, fetched only when an operator asks for it.
 *
 * <p>Two reasons it is not simply an `<img src>` pointed at the endpoint. The route needs
 * an operator's bearer token, which an `img` tag cannot send — it would fetch
 * unauthenticated and render a broken image. And these files are a customer's bank
 * screenshot: loading every one of them into a queue that refreshes every twenty seconds
 * would put personal financial data on screen continuously, for rows nobody is looking
 * at, and pull megabytes out of the database to do it.
 */
export function ProofThumb({ claimId, hasProof }: { claimId: number; hasProof: boolean }) {
  const [url, setUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)

  // Object URLs are revoked on unmount; without this each queue refresh that had an
  // image open would strand one.
  useEffect(() => () => { if (url) URL.revokeObjectURL(url) }, [url])

  if (!hasProof) {
    return <p className="mt-1 text-[11.5px] text-admin-faint">No screenshot</p>
  }

  if (url) {
    return (
      <a href={url} target="_blank" rel="noopener noreferrer" className="mt-1.5 block">
        <img
          src={url}
          alt={`Payment screenshot for claim ${claimId}`}
          className="max-h-28 rounded-[6px] border border-admin-line"
        />
      </a>
    )
  }

  return (
    <button
      type="button"
      disabled={loading}
      onClick={() => {
        setLoading(true)
        setFailed(false)
        api.blobUrl(`/api/v1/admin/payment-claims/${claimId}/proof`)
          .then(setUrl)
          .catch(() => setFailed(true))
          .finally(() => setLoading(false))
      }}
      className="mt-1 text-[11.5px] font-semibold text-admin-red-text hover:underline
                 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red"
    >
      {loading ? 'Loading…' : failed ? 'Could not load — retry' : 'Show screenshot'}
    </button>
  )
}

export function PaymentClaims({
  highlight, onReviewed,
}: {
  /** An order reference whose claim should be brought into view and marked. */
  highlight?: string | null
  /** Told after a verify or reject goes through, so the page can refresh its counts. */
  onReviewed?: () => void
} = {}) {
  const [claims, setClaims] = useState<AdminPaymentClaim[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)
  const highlighted = useRef<HTMLTableRowElement>(null)

  const load = useCallback(async () => {
    try {
      setClaims(await api.get<AdminPaymentClaim[]>('/api/v1/admin/payment-claims'))
      setError(null)
    } catch {
      setError('Could not load payment claims. Check the API is reachable.')
      setClaims([])
    }
  }, [])

  useEffect(() => {
    void load()
    const timer = setInterval(() => void load(), 20_000)
    return () => clearInterval(timer)
  }, [load])

  // Once the rows are in, bring the claim the row's button asked about into view.
  const loaded = claims !== null
  useEffect(() => {
    if (loaded && highlight) highlighted.current?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
  }, [loaded, highlight])

  async function review(claim: AdminPaymentClaim, outcome: 'verify' | 'reject') {
    setBusyId(claim.id)
    setError(null)
    try {
      // The same questions the Payments page asks, from one definition.
      const done = await reviewClaim({ ...claim, amountFormatted: claim.totalFormatted }, outcome)
      if (!done) return
      await load()
      onReviewed?.()
    } catch {
      setError(`Could not ${outcome} that claim. Reload and check whether it went through.`)
    } finally {
      setBusyId(null)
    }
  }

  return (
    <section
      aria-labelledby="payments-to-check"
      className="mb-5 overflow-hidden rounded-admin-card border border-admin-line bg-white shadow-admin-card"
    >
      <div className="flex items-center justify-between gap-3 px-5 py-4">
        <h2 id="payments-to-check" className="flex items-center gap-2.5 text-[15px] font-semibold text-admin-ink">
          <span aria-hidden="true" className="h-2 w-2 rounded-full bg-admin-red" />
          Payments to check
          {claims && claims.length > 0 && (
            <span className="rounded-[6px] bg-admin-red px-1.5 py-0.5 text-[12px] font-semibold tabular-nums text-white">
              {claims.length}
            </span>
          )}
        </h2>
        <p className="text-[12px] text-admin-faint">Oldest first</p>
      </div>

      {error && (
        <p role="alert" className="mx-5 mb-4 rounded-admin-control bg-admin-red-tint px-3.5 py-2.5 text-[13px] text-admin-red-ink">
          {error}
        </p>
      )}

      {claims === null ? (
        <div className="space-y-2 px-5 pb-5" aria-busy="true">
          {[0, 1].map((i) => <span key={i} className="block h-10 animate-pulse rounded bg-admin-grey-tint" />)}
        </div>
      ) : claims.length === 0 ? (
        // After a failed read the list is empty because nothing came back, not because
        // nothing is waiting: the error above is the whole message then.
        error ? null : <div className="px-5 pb-8 pt-2 text-center">
          <LuWallet aria-hidden="true" className="mx-auto h-6 w-6 text-admin-faint" />
          <p className="mt-2 text-[14px] font-semibold text-admin-ink">Nothing waiting</p>
          <p className="mt-1 text-[13px] text-admin-muted">
            Payments customers have reported show up here for checking.
          </p>
        </div>
      ) : (
        <div className="relative overflow-x-auto">
          <table className="w-full min-w-[860px] border-collapse text-admin-cell">
            <thead className="bg-[#FAFBFC]">
              <tr className="border-y border-admin-line">
                <Th>Order</Th>
                <Th>Paid to</Th>
                <Th>Reference</Th>
                <Th>Amount</Th>
                <Th>Submitted</Th>
                <Th>Decision</Th>
              </tr>
            </thead>
            <tbody>
              {claims.map((claim) => {
                const marked = highlight === claim.publicRef
                return (
                  <tr
                    key={claim.id}
                    ref={marked ? highlighted : undefined}
                    aria-current={marked ? 'true' : undefined}
                    className={`border-b border-admin-line align-top last:border-b-0 ${marked ? 'bg-[#FFF8F8]' : ''}`}
                  >
                    <td className={`px-4 py-3 ${marked ? 'shadow-[inset_3px_0_0_#DB1825]' : ''}`}>
                      <Link
                        to={`/admin/orders/${claim.publicRef}`}
                        className="font-semibold text-admin-red-text hover:underline"
                      >
                        {claim.publicRef}
                      </Link>
                      <div className="text-[12px] text-admin-faint">{claim.customerEmail}</div>
                    </td>

                    <td className="px-4 py-3">
                      <StatusBadge label={METHOD_LABEL[claim.method] ?? claim.method} tone="grey" />
                      {/*
                        The destination is the row's most important column and the reason
                        the claim records it: it tells the operator which account to open.
                        Wrapped rather than truncated -- a half-shown wallet address is
                        indistinguishable from a different wallet address.
                      */}
                      <div className="mt-1 text-[12px] text-admin-muted" style={{ overflowWrap: 'anywhere' }}>
                        {claim.destination}
                      </div>
                    </td>

                    <td className="px-4 py-3">
                      <code className="text-[12.5px] tabular-nums text-admin-ink" style={{ overflowWrap: 'anywhere' }}>
                        {claim.reference}
                      </code>
                      <ProofThumb claimId={claim.id} hasProof={claim.hasProof} />
                    </td>

                    <td className="whitespace-nowrap px-4 py-3 font-semibold tabular-nums text-admin-ink">
                      {claim.totalFormatted}
                    </td>

                    <td className="whitespace-nowrap px-4 py-3 text-[12px] text-admin-muted">
                      {shortDateTime(claim.submittedAt)}
                    </td>

                    <td className="px-4 py-3">
                      <div className="flex gap-2">
                        <AdminButton
                          size="sm"
                          variant="primary"
                          disabled={busyId === claim.id}
                          onClick={() => void review(claim, 'verify')}
                        >
                          {busyId === claim.id ? 'Working…' : 'Verify'}
                        </AdminButton>
                        <AdminButton
                          size="sm"
                          disabled={busyId === claim.id}
                          onClick={() => void review(claim, 'reject')}
                        >
                          Reject
                        </AdminButton>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
