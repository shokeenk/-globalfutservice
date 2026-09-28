import { useState, type FormEvent } from 'react'
import { ApiError, api } from '../../../lib/api'
import type { AdminCustomerDetail, AdminSupportDetail, SupportCategory } from '../../../lib/types'
import { SUPPORT_CATEGORIES, SUPPORT_CATEGORY } from '../support/shared'
import { buttonClasses } from '../ui/controls'
import { Modal } from '../ui/Modal'

const fieldClass = 'mt-1 w-full rounded-admin-control border border-admin-line bg-white px-2.5 text-[13px] text-admin-ink '
  + 'focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20'

/**
 * Writing to one customer first: "Send Email" on their page.
 *
 * <p>It opens a support ticket to them and emails it, so their answer comes back into
 * Support rather than to somebody's inbox. It is for one customer about their account or
 * an order. It is not a way round the marketing rules: campaigns go through Email
 * Marketing, which only reaches people who agreed to them.
 */
export function SendEmail({
  detail, onClose, onSent,
}: {
  detail: AdminCustomerDetail
  onClose: () => void
  onSent: (ticket: AdminSupportDetail) => void
}) {
  const c = detail.customer
  const [category, setCategory] = useState<SupportCategory | ''>('')
  const [orderRef, setOrderRef] = useState('')
  const [subject, setSubject] = useState('')
  const [message, setMessage] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!subject.trim() || !message.trim() || sending) return
    setSending(true)
    setError(null)
    try {
      onSent(await api.post<AdminSupportDetail>('/api/v1/admin/support/tickets', {
        customerKey: c.key,
        orderRef: orderRef || null,
        category: category || null,
        subject: subject.trim(),
        message: message.trim(),
      }))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The email could not be sent. Nothing was sent; try again.')
      setSending(false)
    }
  }

  return (
    <Modal title="Send Email" onClose={onClose} width="max-w-[540px]">
      <form onSubmit={(e) => void submit(e)} className="space-y-3.5">
        <p className="rounded-admin-control bg-admin-page px-3 py-2.5 text-[13px] text-admin-ink">
          To <strong className="font-semibold">{c.name}</strong> &lt;{c.email}&gt;
        </p>

        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <label htmlFor="send-category" className="text-[12.5px] font-medium text-admin-ink">Category</label>
            <select id="send-category" value={category} onChange={(e) => setCategory(e.target.value as SupportCategory | '')}
              className={`${fieldClass} h-9`}>
              <option value="">None</option>
              {SUPPORT_CATEGORIES.map((k) => <option key={k} value={k}>{SUPPORT_CATEGORY[k].label}</option>)}
            </select>
          </div>
          <div>
            <label htmlFor="send-order" className="text-[12.5px] font-medium text-admin-ink">About order</label>
            <select id="send-order" value={orderRef} onChange={(e) => setOrderRef(e.target.value)} className={`${fieldClass} h-9`}>
              <option value="">No particular order</option>
              {detail.recentOrders.map((o) => (
                <option key={o.publicRef} value={o.publicRef}>{o.publicRef} · {o.serviceLabel}</option>
              ))}
            </select>
          </div>
        </div>

        <div>
          <label htmlFor="send-subject" className="text-[12.5px] font-medium text-admin-ink">Subject</label>
          <input id="send-subject" value={subject} maxLength={120} required onChange={(e) => setSubject(e.target.value)}
            className={`${fieldClass} h-9`} />
        </div>
        <div>
          <label htmlFor="send-message" className="text-[12.5px] font-medium text-admin-ink">Message</label>
          <textarea id="send-message" value={message} maxLength={4000} rows={7} required onChange={(e) => setMessage(e.target.value)}
            className={`${fieldClass} resize-y py-2`} />
        </div>

        <p className="text-[12px] leading-relaxed text-admin-faint">
          This opens a support ticket and emails it to them with a private link to answer through; their answer
          comes back to Support. For one customer about their account or an order. Offers and news go through
          Email Marketing, which only reaches people who opted in. Never ask for their password or backup codes.
        </p>

        {error && <p role="alert" className="rounded-admin-control bg-admin-red-tint px-3 py-2 text-[13px] text-admin-red-ink">{error}</p>}

        <div className="flex justify-end gap-2 pt-1">
          <button type="button" onClick={onClose} className={buttonClasses('outline')}>Cancel</button>
          <button type="submit" disabled={!subject.trim() || !message.trim() || sending} className={buttonClasses('primary')}>
            {sending ? 'Sending…' : 'Send Email'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
