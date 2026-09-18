import { useState } from 'react'
import { Alert, Button, Field, Input, Textarea } from '../../components/ui'
import { ApiError, api } from '../../lib/api'
import { useAuth } from '../../state/AuthContext'

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

  return (
    <div className="surface mb-5 p-5">
      <p className="stamp mb-1">Announcement</p>
      <p className="mb-4 text-[12.5px] text-chalk-muted">
        Goes to every customer&rsquo;s notification bell. There is no undo.
      </p>

      {error && <div className="mb-4"><Alert tone="warn">{error}</Alert></div>}
      {sentTo !== null && (
        <div className="mb-4">
          <Alert tone="ok">{`Sent to ${sentTo} ${sentTo === 1 ? 'customer' : 'customers'}.`}</Alert>
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-2">
        <Field label="Title" required hint="Up to 120 characters.">
          {(props) => (
            <Input
              {...props}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={120}
              placeholder="10% off all coin orders this weekend"
            />
          )}
        </Field>

        <Field label="Link" hint="Optional. A path on this site, like /boosting.">
          {(props) => (
            <Input
              {...props}
              value={link}
              onChange={(e) => setLink(e.target.value)}
              maxLength={200}
              placeholder="/boosting"
            />
          )}
        </Field>
      </div>

      <div className="mt-4">
        <Field label="Message" hint="Optional. Up to 500 characters.">
          {(props) => (
            <Textarea
              {...props}
              value={body}
              onChange={(e) => setBody(e.target.value)}
              maxLength={500}
              rows={3}
              placeholder="Use code SAVE10 at checkout until Sunday night."
            />
          )}
        </Field>
      </div>

      <div className="mt-4">
        <Button onClick={() => void send()} loading={sending} disabled={!title.trim()}>
          Send to every customer
        </Button>
      </div>
    </div>
  )
}
