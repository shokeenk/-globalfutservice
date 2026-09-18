import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useT } from '../i18n'
import { relativeTime } from '../lib/format'
import { useNotifications } from '../state/NotificationsContext'
import { useAuth } from '../state/AuthContext'

/**
 * The bell: how many things happened, and what they were.
 *
 * <p>Red, like everything else on this site that wants attention, and carrying a count
 * only when there is one — a badge showing zero is a permanent small alarm that teaches
 * people to stop looking at the thing it is attached to.
 *
 * <p>Opening it marks everything in it read, server-side. That is the same gesture as
 * reading them, and asking somebody to tick off notifications one by one is work the
 * product invented for itself.
 */
export function NotificationBell() {
  const t = useT()
  const { account } = useAuth()
  const { items, unread, markAllRead } = useNotifications()
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)

  // Close on a click elsewhere or on Escape, like every other menu on the site.
  useEffect(() => {
    if (!open) return
    const onDown = (event: MouseEvent) => {
      if (box.current && !box.current.contains(event.target as Node)) setOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  if (!account) return null

  const toggle = () => {
    const next = !open
    setOpen(next)
    if (next) markAllRead()
  }

  return (
    <div ref={box} className="relative">
      <button
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-label={unread > 0 ? t.notifications.bellWithCount(unread) : t.notifications.bell}
        className="relative grid h-9 w-9 place-items-center rounded-edge text-brand-500
                   transition-colors hover:bg-brand-500/[0.08]
                   focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                   focus-visible:outline-brand-400"
      >
        <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor"
             strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M18 8a6 6 0 1 0-12 0c0 5-2 6-2 6h16s-2-1-2-6" />
          <path d="M13.7 20a2 2 0 0 1-3.4 0" />
        </svg>

        {unread > 0 && (
          <span
            aria-hidden="true"
            className="tnum absolute -right-0.5 -top-0.5 grid h-[18px] min-w-[18px] place-items-center
                       rounded-full bg-brand-500 px-1 text-[10.5px] font-bold leading-none text-paper"
          >
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div
          role="dialog"
          aria-label={t.notifications.title}
          className="absolute right-0 top-11 z-50 w-[min(22rem,calc(100vw-2rem))] overflow-hidden
                     rounded-panel border border-ink-400 bg-paper shadow-e3"
        >
          <p className="border-b border-ink-400 px-4 py-3 text-[13px] font-semibold text-chalk">
            {t.notifications.title}
          </p>

          {items.length === 0 ? (
            <p className="px-4 py-8 text-center text-[12.5px] text-chalk-faint">
              {t.notifications.empty}
            </p>
          ) : (
            /* Scrolls rather than paginating: twenty rows is the whole feed a customer has. */
            <ul className="max-h-[22rem] divide-y divide-ink-400/70 overflow-y-auto">
              {items.map((item) => (
                <li key={item.id}>
                  <Row
                    title={item.title}
                    body={item.body}
                    when={relativeTime(item.createdAt)}
                    link={item.link}
                    unread={!item.read}
                    onNavigate={() => setOpen(false)}
                  />
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}

function Row({
  title, body, when, link, unread, onNavigate,
}: {
  title: string
  body: string | null
  when: string
  link: string | null
  unread: boolean
  onNavigate: () => void
}) {
  const inner = (
    <>
      <div className="flex items-start gap-2">
        {/* A dot rather than a colour on the text: unread is a state, not an emphasis. */}
        <span
          aria-hidden="true"
          className={`mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full ${unread ? 'bg-brand-500' : 'bg-transparent'}`}
        />
        <div className="min-w-0 flex-1">
          <p className="text-[13px] font-semibold leading-snug text-chalk">{title}</p>
          {body && <p className="mt-0.5 text-[12px] leading-relaxed text-chalk-muted">{body}</p>}
          <p className="mt-1 text-[11px] text-chalk-faint">{when}</p>
        </div>
      </div>
    </>
  )

  return link ? (
    <Link to={link} onClick={onNavigate} className="block px-4 py-3 transition-colors hover:bg-ink-700/60">
      {inner}
    </Link>
  ) : (
    <div className="px-4 py-3">{inner}</div>
  )
}
