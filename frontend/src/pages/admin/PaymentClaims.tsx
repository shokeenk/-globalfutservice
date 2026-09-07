import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Badge, Button, EmptyState, Skeleton } from '../../components/ui'
import { api } from '../../lib/api'
import { dateTime } from '../../lib/format'
import type { AdminPaymentClaim } from '../../lib/types'

/**
 * The review desk for payments made outside the gateway.
 *
 * Without something on this screen the whole manual-payment flow is a dead end:
 * customers submit references into a table nobody reads and their orders sit in
 * AWAITING_PAYMENT until they email support. Verifying here is the only thing in the
 * system that marks such an order paid.
 *
 * It sits above the order queue on purpose. Every row is a customer who has already
 * sent money and is waiting on us, which makes it the most time-sensitive list in the
 * console -- and unlike the queue below, nothing else will surface it.
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
function ProofThumb({ claimId, hasProof }: { claimId: number; hasProof: boolean }) {
  const [url, setUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)

  // Object URLs are revoked on unmount; without this each queue refresh that had an
  // image open would strand one.
  useEffect(() => () => { if (url) URL.revokeObjectURL(url) }, [url])

  if (!hasProof) {
    return <p className="mt-1 text-[11.5px] text-chalk-faint">No screenshot</p>
  }

  if (url) {
    return (
      <a href={url} target="_blank" rel="noopener noreferrer" className="mt-1.5 block">
        <img
          src={url}
          alt={`Payment screenshot for claim ${claimId}`}
          className="max-h-28 rounded-edge border border-ink-400"
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
      className="mt-1 text-[11.5px] font-semibold text-brand-400 hover:underline
                 focus-visible:outline focus-visible:outline-2
                 focus-visible:outline-offset-2 focus-visible:outline-brand-400"
    >
      {loading ? 'Loading…' : failed ? 'Could not load — retry' : 'Show screenshot'}
    </button>
  )
}

export function PaymentClaims() {
  const [claims, setClaims] = useState<AdminPaymentClaim[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)

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

  async function review(claim: AdminPaymentClaim, outcome: 'verify' | 'reject') {
    /*
     * Verifying releases an order and is not undoable from this screen, so it asks
     * first. The prompt names the amount and the destination rather than the order
     * reference: the question an operator is actually answering is "is this much money
     * in that account", and the reference is the thing they have already been staring
     * at on a bank statement.
     */
    if (outcome === 'verify') {
      const confirmed = window.confirm(
        `Confirm ${claim.totalFormatted} arrived at ${claim.destination} `
        + `with reference ${claim.reference}?\n\n`
        + `This marks order ${claim.publicRef} paid and starts fulfilment.`,
      )
      if (!confirmed) return
    }

    const note = outcome === 'reject'
      ? window.prompt('Why is this being rejected? (optional, kept on the record)') ?? undefined
      : undefined

    setBusyId(claim.id)
    setError(null)
    try {
      await api.post(`/api/v1/admin/payment-claims/${claim.id}/${outcome}`, { note: note ?? null })
      await load()
    } catch {
      setError(`Could not ${outcome} that claim. Reload and check whether it went through.`)
    } finally {
      setBusyId(null)
    }
  }

  if (!claims) {
    return <Skeleton className="h-32 w-full" />
  }

  return (
    <div className="mb-6">
      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h2 className="display text-[15px] text-chalk">
          Payments to check
          {claims.length > 0 && (
            <span className="ml-2 tnum text-[13px] font-semibold text-brand-400">
              {claims.length}
            </span>
          )}
        </h2>
        <p className="text-[12px] text-chalk-faint">Oldest first</p>
      </div>

      {error && <Alert tone="warn">{error}</Alert>}

      {claims.length === 0 ? (
        <EmptyState title="Nothing waiting">
          Payments customers have reported show up here for checking.
        </EmptyState>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[820px] border-collapse text-[13px]">
            <thead>
              <tr className="border-b border-ink-400 text-left text-[11.5px] uppercase tracking-[0.06em] text-chalk-faint">
                <th className="py-2 pr-3 font-semibold">Order</th>
                <th className="py-2 pr-3 font-semibold">Paid to</th>
                <th className="py-2 pr-3 font-semibold">Reference</th>
                <th className="py-2 pr-3 font-semibold">Amount</th>
                <th className="py-2 pr-3 font-semibold">Submitted</th>
                <th className="py-2 font-semibold">Decision</th>
              </tr>
            </thead>
            <tbody>
              {claims.map((claim) => (
                <tr key={claim.id} className="border-b border-ink-400/60 align-top">
                  <td className="py-3 pr-3">
                    <Link
                      to={`/admin/orders/${claim.publicRef}`}
                      className="font-semibold text-brand-400 hover:underline"
                    >
                      {claim.publicRef}
                    </Link>
                    <div className="text-[12px] text-chalk-faint">{claim.customerEmail}</div>
                  </td>

                  <td className="py-3 pr-3">
                    <Badge tone="neutral">{claim.method}</Badge>
                    {/*
                      The destination is the row's most important column and the reason
                      the claim records it: it tells the operator which account to open.
                      Wrapped rather than truncated -- a half-shown wallet address is
                      indistinguishable from a different wallet address.
                    */}
                    <div
                      className="mt-1 text-[12px] text-chalk-muted"
                      style={{ overflowWrap: 'anywhere' }}
                    >
                      {claim.destination}
                    </div>
                  </td>

                  <td className="py-3 pr-3">
                    <code className="tnum text-[12.5px] text-chalk" style={{ overflowWrap: 'anywhere' }}>
                      {claim.reference}
                    </code>
                    <ProofThumb claimId={claim.id} hasProof={claim.hasProof} />
                  </td>

                  <td className="tnum py-3 pr-3 font-semibold text-chalk">{claim.totalFormatted}</td>

                  <td className="py-3 pr-3 text-[12px] text-chalk-muted">
                    {dateTime(claim.submittedAt)}
                  </td>

                  <td className="py-3">
                    <div className="flex gap-2">
                      <Button
                        size="sm"
                        loading={busyId === claim.id}
                        onClick={() => void review(claim, 'verify')}
                      >
                        Verify
                      </Button>
                      <Button
                        size="sm"
                        variant="secondary"
                        disabled={busyId === claim.id}
                        onClick={() => void review(claim, 'reject')}
                      >
                        Reject
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
