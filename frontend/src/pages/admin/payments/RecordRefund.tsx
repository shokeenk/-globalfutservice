import { useState, type FormEvent } from 'react'
import { ApiError, api } from '../../../lib/api'
import { buttonClasses } from '../ui/controls'
import { Modal } from '../ui/Modal'
import { METHOD_LABEL } from '../ui/status'

const field = 'mt-1 h-10 w-full rounded-admin-control border border-admin-line bg-white px-3 text-[13.5px] '
  + 'text-admin-ink focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20'

export interface RefundRecorded {
  publicRef: string
  amountMinor: number
  amountFormatted: string
  method: string
  reference: string
  at: string
}

/**
 * Recording money already sent back to a customer. Admins only.
 *
 * <p>The amount is not typed: a refund is the order's full total, which the server fills
 * in, so it cannot be mistyped. What the admin gives is how the money went back, the
 * reference of that transfer, and why. The form says what else happens, because it is not
 * reversible: the order becomes Refunded, the points they spent come back, the coupon is
 * released and any EA sign-in held is deleted.
 */
export function RecordRefund({
  initialRef = '', amountFormatted, onClose, onRecorded,
}: {
  initialRef?: string
  /** Known when opened from a payment; shown so the admin sees what is being refunded. */
  amountFormatted?: string
  onClose: () => void
  onRecorded: (refund: RefundRecorded) => void
}) {
  const [publicRef, setPublicRef] = useState(initialRef)
  const [method, setMethod] = useState('UPI')
  const [reference, setReference] = useState('')
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ready = publicRef.trim() && reference.trim() && reason.trim()

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!ready || saving) return
    const what = amountFormatted ? `${amountFormatted} on ${publicRef.trim()}` : `the full total of ${publicRef.trim()}`
    if (!window.confirm(`Record a refund of ${what}, sent by ${METHOD_LABEL[method]} with reference `
      + `${reference.trim()}?\n\nThe order becomes Refunded: points spent are returned, the coupon is `
      + `released and any sign-in held is deleted. This cannot be undone.`)) return
    setSaving(true)
    setError(null)
    try {
      onRecorded(await api.post<RefundRecorded>('/api/v1/admin/payments/refunds', {
        publicRef: publicRef.trim(), method, reference: reference.trim(), reason: reason.trim(),
      }))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The refund could not be recorded. Nothing has changed.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal title="Record Refund" onClose={onClose}>
      <form onSubmit={(e) => void submit(e)}>
        <p className="text-[13px] text-admin-muted">
          For money you have already sent back by hand. The full order total is recorded
          {amountFormatted ? <> — <strong className="text-admin-ink">{amountFormatted}</strong></> : null}.
        </p>

        <label className="mt-4 block text-[12.5px] font-medium text-admin-ink">
          Order
          <input value={publicRef} onChange={(e) => setPublicRef(e.target.value)} maxLength={32}
            placeholder="GFS-26-XXXXXXXX" readOnly={Boolean(initialRef)}
            className={`${field} ${initialRef ? 'bg-admin-page' : ''}`} />
        </label>

        <label className="mt-3 block text-[12.5px] font-medium text-admin-ink">
          Sent back by
          <select value={method} onChange={(e) => setMethod(e.target.value)} className={field}>
            {Object.entries(METHOD_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>

        <label className="mt-3 block text-[12.5px] font-medium text-admin-ink">
          Reference of the money sent back
          <input value={reference} onChange={(e) => setReference(e.target.value)} maxLength={120}
            placeholder="UTR, PayPal refund ID or transaction hash" className={field} />
        </label>

        <label className="mt-3 block text-[12.5px] font-medium text-admin-ink">
          Reason <span className="font-normal text-admin-faint">(for staff; the customer is not shown this)</span>
          <textarea value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} rows={3}
            className={`${field} h-auto py-2`} />
        </label>

        {error && <p role="alert" className="mt-3 rounded-admin-control bg-admin-red-tint px-3 py-2 text-[13px] text-admin-red-ink">{error}</p>}

        <div className="mt-5 flex justify-end gap-2">
          <button type="button" onClick={onClose} className={buttonClasses('outline')}>Cancel</button>
          <button type="submit" disabled={!ready || saving} className={buttonClasses('primary')}>
            {saving ? 'Recording…' : 'Record Refund'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
