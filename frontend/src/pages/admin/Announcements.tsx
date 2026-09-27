import { useState } from 'react'
import { LuChevronDown, LuMegaphone } from 'react-icons/lu'
import { ApiError, api } from '../../lib/api'
import { useAuth } from '../../state/AuthContext'
import { AdminButton } from './ui/controls'

/**
 * One notice, to every customer's bell.
 *
 * <p>The only notification anybody types. Everything else in a customer's feed is an order
 * doing something; this is the business saying something — an offer, a delay, a change of
 * hours — so it needs a person and a send button.
 *
 * <p><b>Admins only, and confirmed before it goes.</b> It reaches every account at once and
 * cannot be recalled, which puts it in a different class from the queue actions around it.
 * The server enforces the role; the confirmation is so nobody sends a half-typed line by
 * pressing Enter.
 *
 * <p>The link is a path on this site, not a URL. An announcement that could carry an
 * arbitrary destination is a phishing message wearing our own chrome.
 */
export function Announcements() {
  const { account } = useAuth()
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [link, setLink] = useState('')
  const [sending, setSending] = useState(false)
  const [sentTo, setSentTo] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  // Folded away until wanted: it is used a few times a month, the queue above all day.
  const [open, setOpen] = useState(false)

  if (account?.role !== 'ADMIN') return null

  const send = async () => {
    if (!title.trim() || sending) return
    if (!window.confirm(`Send "${title.trim()}" to every customer? This cannot be undone.`)) return
    setSending(true)
    setError(null)
    try {
      const result = await api.post<{ sent: number }>('/api/v1/admin/announcements', {
        title: title.trim(),
        body: body.trim() || null,
        link: link.trim() || null,
      })
      setSentTo(result.sent)
      setTitle('')
      setBody('')
      setLink('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'We could not send that announcement.')
    } finally {
      setSending(false)
    }
  }

  const field = 'w-full rounded-admin-control border border-admin-line bg-white px-3 py-2 text-[13.5px] text-admin-ink '
    + 'placeholder:text-admin-faint focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20'

  return (
    <section className="mt-6 rounded-admin-card border border-admin-line bg-white shadow-admin-card">
      <h2>
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          aria-expanded={open}
          aria-controls="announcement-form"
          className="flex w-full items-center gap-3 rounded-admin-card px-5 py-4 text-left focus-visible:outline-none
                     focus-visible:ring-2 focus-visible:ring-admin-red"
        >
          <LuMegaphone aria-hidden="true" className="h-5 w-5 text-admin-faint" />
          <span className="flex-1">
            <span className="block text-[15px] font-semibold text-admin-ink">Announce to customers</span>
            <span className="block text-[12.5px] text-admin-muted">
              Goes to every customer&rsquo;s notification bell. There is no undo.
            </span>
          </span>
          <LuChevronDown
            aria-hidden="true"
            className={`h-4 w-4 text-admin-faint transition-transform ${open ? 'rotate-180' : ''}`}
          />
        </button>
      </h2>

      <div id="announcement-form" hidden={!open} className="border-t border-admin-line px-5 pb-5 pt-4">
        {error && (
          <p role="alert" className="mb-4 rounded-admin-control bg-admin-red-tint px-3.5 py-2.5 text-[13px] text-admin-red-ink">
            {error}
          </p>
        )}
        {sentTo !== null && (
          <p role="status" className="mb-4 rounded-admin-control bg-admin-green-tint px-3.5 py-2.5 text-[13px] text-admin-green-ink">
            {`Sent to ${sentTo} ${sentTo === 1 ? 'customer' : 'customers'}.`}
          </p>
        )}

        <div className="grid gap-4 md:grid-cols-2">
          <label className="block">
            <span className="text-[12.5px] font-medium text-admin-ink">Title <span className="text-admin-red-text">*</span></span>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={120}
              required
              placeholder="10% off all coin orders this weekend"
              className={`mt-1.5 ${field}`}
            />
            <span className="mt-1 block text-[11.5px] text-admin-faint">Up to 120 characters.</span>
          </label>

          <label className="block">
            <span className="text-[12.5px] font-medium text-admin-ink">Link</span>
            <input
              value={link}
              onChange={(e) => setLink(e.target.value)}
              maxLength={200}
              placeholder="/boosting"
              className={`mt-1.5 ${field}`}
            />
            <span className="mt-1 block text-[11.5px] text-admin-faint">Optional. A path on this site, like /boosting.</span>
          </label>
        </div>

        <label className="mt-4 block">
          <span className="text-[12.5px] font-medium text-admin-ink">Message</span>
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            maxLength={500}
            rows={3}
            placeholder="Use code SAVE10 at checkout until Sunday night."
            className={`mt-1.5 ${field}`}
          />
          <span className="mt-1 block text-[11.5px] text-admin-faint">Optional. Up to 500 characters.</span>
        </label>

        <AdminButton
          variant="primary"
          className="mt-4"
          onClick={() => void send()}
          disabled={!title.trim() || sending}
        >
          {sending ? 'Sending…' : 'Send to every customer'}
        </AdminButton>
      </div>
    </section>
  )
}
