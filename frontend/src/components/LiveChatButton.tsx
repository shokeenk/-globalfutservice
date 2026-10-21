import { useEffect, useState } from 'react'
import { useT } from '../i18n'
import { floatingTawkConfig, hideFloatingTawk, openFloatingTawk, reloadPage, setFloatingOrder } from '../lib/tawk'
import { Spinner } from './ui'

/**
 * The pages with the floating live chat button: Support, order tracking, and the coin,
 * boosting and coaching checkouts. Nowhere else.
 *
 * <p>An order's own support page is not here: it already has the chat in the page, and a
 * second one floating over it would be the same conversation twice.
 */
const LIVE_CHAT_PAGES = new Set(['/support', '/contact', '/track', '/order', '/boosting/checkout', '/coaching/book'])

export function isLiveChatPage(pathname: string): boolean {
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname
  return LIVE_CHAT_PAGES.has(path)
}

/** Whether this page shows the chat button: one of the pages, and the widget configured. */
export function showsLiveChat(pathname: string): boolean {
  return isLiveChatPage(pathname) && floatingTawkConfig() !== null
}

/**
 * The order the page in view is about, for the chat: staff see its reference beside the
 * conversation. Only the reference -- the chat is never given an email, a sign-in or
 * backup codes. Cleared when the page goes.
 */
export function useLiveChatOrder(reference: string | null | undefined): void {
  useEffect(() => {
    setFloatingOrder(reference ?? null)
    return () => setFloatingOrder(null)
  }, [reference])
}

/** Set before a page load made only to switch from the embedded chat; read once after it. */
const OPEN_AFTER_RELOAD = 'gfs.liveChat.open'

/**
 * The red chat button, bottom right. Pressing it opens the tawk.to chat over the page --
 * the customer never leaves what they were doing. Nothing loads from tawk.to until it is
 * pressed.
 *
 * <p>Red, round and labelled "Live chat", where the Discord link is Discord's own blue and
 * says "community": the two are never mistaken for each other.
 */
export function LiveChatButton() {
  const t = useT()
  const config = floatingTawkConfig()
  const [state, setState] = useState<'idle' | 'opening' | 'failed'>('idle')

  function open() {
    if (!config) return
    setState('opening')
    const outcome = openFloatingTawk(config, () => setState('idle'), () => setState('failed'))
    if (outcome === 'reload') {
      // This page load has the order support page's embedded chat, and tawk.to runs one
      // widget per page load. A fresh load switches, and opens the chat once it is back.
      try {
        window.sessionStorage.setItem(OPEN_AFTER_RELOAD, '1')
      } catch {
        // Without storage the page still reloads; the customer presses once more.
      }
      reloadPage()
    }
  }

  useEffect(() => {
    let reopen = false
    try {
      reopen = window.sessionStorage.getItem(OPEN_AFTER_RELOAD) === '1'
      window.sessionStorage.removeItem(OPEN_AFTER_RELOAD)
    } catch {
      // No storage: nothing was set.
    }
    if (reopen) open()
    // Leaving for a page without the button closes the window; the conversation stays.
    return () => hideFloatingTawk()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps -- once, as the button appears

  if (!config) return null

  return (
    <div className="fixed bottom-5 right-5 z-50 flex flex-col items-end gap-2">
      {state === 'failed' && (
        <p role="alert" className="max-w-[260px] rounded-panel border border-ink-400 bg-ink-700 p-3
                                    text-[12.5px] leading-relaxed text-chalk shadow-e3">
          {t.liveChat.failed}
        </p>
      )}
      <button
        type="button"
        onClick={open}
        aria-busy={state === 'opening' || undefined}
        data-testid="live-chat-button"
        className="inline-flex h-14 items-center gap-2 rounded-full bg-brand-500 px-5 text-body-sm
                   font-semibold text-white shadow-glow transition-transform duration-200 hover:scale-105
                   focus:outline-none focus:ring-2 focus:ring-brand-400 focus:ring-offset-2
                   focus:ring-offset-ink"
      >
        {state === 'opening'
          ? <Spinner size={18} />
          : <ChatIcon />}
        {t.liveChat.button}
      </button>
    </div>
  )
}

function ChatIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0">
      <path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z" />
    </svg>
  )
}
