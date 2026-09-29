import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../../lib/api'
import type { VendorControlState, VendorReviewItem } from '../../../lib/types'
import { AdminButton } from '../ui/controls'
import { shortDateTime } from '../ui/format'
import { Th } from '../ui/Table'
import { VENDOR_STATE_LABEL } from './vendor'

/** The question before calls to the partner start again. */
export function resumeCallsQuestion(): string {
  return 'Resume calls to FUT Transfer?\n\n'
    + 'Only once the API user and key have been fixed. If they are still wrong, the next call is refused '
    + 'and everything pauses again.'
}

/**
 * FUT Transfer on the Orders page, for admins: whether calls to it are paused, and the
 * orders there waiting for a decision.
 *
 * <p>Silent when there is nothing to say. An order lands here when we could not prove what
 * happened, the partner delivered short, or it stopped reporting on it -- each opens on the
 * order page, where the actions are.
 */
export function VendorReview({ onChanged }: { onChanged?: () => void } = {}) {
  const [items, setItems] = useState<VendorReviewItem[] | null>(null)
  const [control, setControl] = useState<VendorControlState | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [resuming, setResuming] = useState(false)

  const load = useCallback(async () => {
    try {
      const [review, state] = await Promise.all([
        api.get<VendorReviewItem[]>('/api/v1/admin/vendor/needs-review'),
        api.get<VendorControlState>('/api/v1/admin/vendor/control'),
      ])
      setItems(review)
      setControl(state)
      setError(null)
    } catch {
      setError('Could not read the FUT Transfer review list. Refresh to try again.')
    }
  }, [])

  useEffect(() => {
    void load()
    const timer = setInterval(() => void load(), 60_000)
    return () => clearInterval(timer)
  }, [load])

  async function resume() {
    if (!window.confirm(resumeCallsQuestion())) return
    setResuming(true)
    try {
      setControl(await api.post<VendorControlState>('/api/v1/admin/vendor/resume'))
      onChanged?.()
    } catch {
      setError('Could not resume calls. Reload and check whether it went through.')
    } finally {
      setResuming(false)
    }
  }

  const paused = control?.paused === true
  if (!error && !paused && (items === null || items.length === 0)) return null

  return (
    <section
      aria-labelledby="vendor-review"
      className="mb-5 overflow-hidden rounded-admin-card border border-admin-line bg-white shadow-admin-card"
    >
      <div className="flex items-center justify-between gap-3 px-5 py-4">
        <h2 id="vendor-review" className="flex items-center gap-2.5 text-[15px] font-semibold text-admin-ink">
          <span aria-hidden="true" className="h-2 w-2 rounded-full bg-admin-red" />
          FUT Transfer: needs a decision
          {items && items.length > 0 && (
            <span className="rounded-[6px] bg-admin-red px-1.5 py-0.5 text-[12px] font-semibold tabular-nums text-white">
              {items.length}
            </span>
          )}
        </h2>
        <p className="text-[12px] text-admin-faint">Longest waiting first</p>
      </div>

      {paused && (
        <div role="alert" className="mx-5 mb-4 flex flex-wrap items-center justify-between gap-3 rounded-admin-control
                                     bg-admin-red-tint px-3.5 py-2.5 text-[13px] text-admin-red-ink">
          <span>
            Calls to FUT Transfer are paused{control?.pausedAt ? ` since ${shortDateTime(control.pausedAt)}` : ''}:
            it refused our API credentials{control?.reason ? ` (${control.reason})` : ''}. Nothing is sent or read
            until they are fixed and calls are resumed.
          </span>
          <AdminButton variant="attention" disabled={resuming} onClick={() => void resume()}>
            {resuming ? 'Resuming…' : 'Resume calls'}
          </AdminButton>
        </div>
      )}

      {error && (
        <p role="alert" className="mx-5 mb-4 rounded-admin-control bg-admin-red-tint px-3.5 py-2.5 text-[13px] text-admin-red-ink">
          {error}
        </p>
      )}

      {items && items.length > 0 && (
        <div className="relative overflow-x-auto">
          <table className="w-full min-w-[760px] border-collapse text-admin-cell">
            <thead className="bg-[#FAFBFC]">
              <tr className="border-y border-admin-line">
                <Th>Order</Th>
                <Th>State</Th>
                <Th>Why</Th>
                <Th>Delivered</Th>
                <Th>Waiting since</Th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.externalRef} className="border-b border-admin-line align-top last:border-b-0">
                  <td className="px-4 py-3">
                    <Link to={`/admin/orders/${item.externalRef}`} className="font-semibold text-admin-red-text hover:underline">
                      {item.externalRef}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-admin-ink">{VENDOR_STATE_LABEL[item.state] ?? item.state}</td>
                  <td className="max-w-[420px] px-4 py-3 text-admin-muted">
                    {item.lastErrorCode && <span className="font-mono text-[12px] text-admin-ink">{item.lastErrorCode} </span>}
                    {item.reviewReason ?? ''}
                  </td>
                  <td className="px-4 py-3 tabular-nums text-admin-ink">
                    {item.deliveredK === null ? '—' : `${item.deliveredK}K`} of {item.amountOrderedK}K
                  </td>
                  <td className="px-4 py-3 text-admin-muted">{shortDateTime(item.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
