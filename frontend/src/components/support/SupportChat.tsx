import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useT } from '../../i18n'
import { attachTawk, detachTawk, reloadPage, startTawk, tawkConfig, tawkStartedFor } from '../../lib/tawk'
import type { TawkChat } from '../../lib/tawk'
import { Alert, Button } from '../ui'

/**
 * The live chat, as a block inside the support page -- never a bubble over the site.
 *
 * <p>Nothing of tawk.to loads until the customer presses Start chat, after being told who
 * provides the chat and what it is given. That is the consent for its cookies, asked where
 * it applies rather than for the whole site.
 *
 * <p>Without the ids, or if the chat cannot load, the page says so and points to the
 * Support page. It never shows a broken box.
 */
export function SupportChat({ reference, chat }: { reference: string; chat: TawkChat }) {
  const t = useT()
  const s = t.orderSupport
  const config = tawkConfig()
  const host = useRef<HTMLDivElement>(null)
  const [started, setStarted] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const running = tawkStartedFor()
    if (running !== null && running !== reference) {
      // The chat in this page load is another order's, and tawk.to cannot be told to
      // forget it. A fresh page load starts this order's cleanly.
      reloadPage()
      return
    }
    if (running === reference && host.current) {
      attachTawk(host.current)
      setStarted(true)
    }
    return () => detachTawk()
  }, [reference])

  if (!config) {
    return (
      <Alert tone="neutral" title={s.unavailable}>
        <Link className="font-semibold text-brand-400 hover:underline" to="/support">{s.unavailableLink}</Link>
      </Alert>
    )
  }

  const start = () => {
    if (!host.current) return
    const result = startTawk(config, reference, chat, host.current, () => setFailed(true))
    if (result === 'reload') {
      reloadPage()
      return
    }
    setStarted(true)
  }

  return (
    <div>
      {failed && (
        <div className="mb-4">
          <Alert tone="warn" title={s.loadFailed}>
            <Link className="font-semibold text-brand-400 hover:underline" to="/support">{s.unavailableLink}</Link>
          </Alert>
        </div>
      )}
      {!started && (
        <div className="rounded-panel border border-ink-400 bg-paper p-5" data-testid="chat-consent">
          <p className="text-body-sm font-semibold text-chalk">{s.chatNoticeTitle}</p>
          <p className="mt-1 text-[12.5px] leading-relaxed text-chalk-muted">{s.chatNotice}</p>
          <Button className="mt-4" onClick={start}>{s.startChat}</Button>
        </div>
      )}
      {/*
        Where the chat renders. Always in the page, so it exists before tawk.to looks for
        it; tall enough for tawk.to's own minimum (it enlarges anything under 330px).
      */}
      <div
        ref={host}
        data-testid="chat-host"
        className={started ? 'h-[560px] w-full overflow-hidden rounded-panel border border-ink-400' : 'hidden'}
      />
    </div>
  )
}
