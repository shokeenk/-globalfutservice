import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useT } from '../../i18n'
import { detachTawk, reloadPage, startTawk, tawkConfig } from '../../lib/tawk'
import type { TawkChat } from '../../lib/tawk'
import { Alert } from '../ui'

/**
 * The live chat, as a block inside the support page -- never a bubble over the site.
 *
 * <p>It loads as the page opens, already open, under a short notice saying who provides it
 * and what it is given. The page is only reached from an order's support button, so
 * opening it is the customer asking for the chat.
 *
 * <p>Leaving the page takes the chat out of view; it never ends the conversation. Coming
 * back to the same order picks the conversation up again.
 *
 * <p>Without the ids, or if the chat cannot load, the page says so and points to the
 * Support page. It never shows a broken box.
 */
export function SupportChat({ reference, chat }: { reference: string; chat: TawkChat }) {
  const t = useT()
  const s = t.orderSupport
  const config = tawkConfig()
  const host = useRef<HTMLDivElement>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const ids = tawkConfig()
    if (!ids || !host.current) return
    if (startTawk(ids, reference, chat, host.current, () => setFailed(true)) === 'reload') {
      // The chat in this page load is another order's, which tawk.to cannot be told to
      // forget; or this order's, already drawn, which cannot be put back on the page. A
      // fresh page load starts it cleanly, and tawk.to brings the conversation back.
      reloadPage()
      return
    }
    return () => detachTawk()
  }, [reference]) // eslint-disable-line react-hooks/exhaustive-deps -- the chat's fields are read once, when it starts

  if (!config) {
    return (
      <Alert tone="neutral" title={s.unavailable}>
        <Link className="font-semibold text-brand-400 hover:underline" to="/support">{s.unavailableLink}</Link>
      </Alert>
    )
  }

  return (
    <div>
      {failed ? (
        <Alert tone="warn" title={s.loadFailed}>
          <Link className="font-semibold text-brand-400 hover:underline" to="/support">{s.unavailableLink}</Link>
        </Alert>
      ) : (
        <div className="mb-3" data-testid="chat-notice">
          <p className="text-body-sm font-semibold text-chalk">{s.chatNoticeTitle}</p>
          <p className="mt-1 text-[12.5px] leading-relaxed text-chalk-muted">{s.chatNotice}</p>
        </div>
      )}
      {/*
        Where the chat renders. In the page from the first render, so it exists before
        tawk.to looks for it; tall enough for tawk.to's own minimum (it enlarges anything
        under 330px). Hidden if the chat could not load, rather than left as an empty box.
      */}
      <div
        ref={host}
        data-testid="chat-host"
        className={failed ? 'hidden' : 'h-[560px] w-full overflow-hidden rounded-panel border border-ink-400'}
      />
    </div>
  )
}
