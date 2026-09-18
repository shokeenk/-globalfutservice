import { useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useT } from '../i18n'
import { useNotifications } from '../state/NotificationsContext'
import type { Notification } from '../state/NotificationsContext'

/** How long one stays before it goes on its own. */
const LIFETIME_MS = 4_000

/** How many are on screen at once. The rest wait their turn. */
const AT_ONCE = 3

/**
 * The bar that appears when something happens while the customer is looking.
 *
 * <p><b>It is a bonus, not the record.</b> Everything shown here is already in the bell,
 * so a toast missed while making tea costs nothing. That is what lets it disappear after
 * four seconds without a second thought.
 *
 * <p>Three at once at most, oldest on top, the rest queued: a burst of status changes
 * should not paint over itself or push the page out of the way. Each carries its own close
 * button for somebody who has read it and wants the screen back.
 *
 * <p>Under the header rather than over it — a customer who has just been told something
 * often wants the bell next, and covering it with the announcement is a small trap.
 */
export function NotificationToasts() {
  const { toasts } = useNotifications()
  const shown = toasts.slice(0, AT_ONCE)

  if (shown.length === 0) return null

  return (
    <div
      role="status"
      aria-live="polite"
      className="pointer-events-none fixed inset-x-0 top-[92px] z-[55] flex flex-col items-center gap-2 px-4"
    >
      {shown.map((toast) => (
        <Toast key={toast.id} toast={toast} />
      ))}
    </div>
  )
}

function Toast({ toast }: { toast: Notification }) {
  const t = useT()
  const { dismissToast } = useNotifications()

  useEffect(() => {
    const timer = window.setTimeout(() => dismissToast(toast.id), LIFETIME_MS)
    return () => window.clearTimeout(timer)
  }, [toast.id, dismissToast])

  const body = (
    <>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[13px] font-semibold text-chalk">{toast.title}</span>
        {toast.body && (
          <span className="mt-0.5 block truncate text-[12px] text-chalk-muted">{toast.body}</span>
        )}
      </span>
    </>
  )

  return (
    <div
      className="pointer-events-auto flex w-full max-w-md items-center gap-3 rounded-panel border
                 border-ink-400 bg-paper px-4 py-3 shadow-e3"
    >
      <span aria-hidden="true" className="h-8 w-1 shrink-0 rounded-full bg-brand-500" />

      {toast.link ? (
        <Link to={toast.link} onClick={() => dismissToast(toast.id)} className="flex min-w-0 flex-1">
          {body}
        </Link>
      ) : (
        <span className="flex min-w-0 flex-1">{body}</span>
      )}

      <button
        type="button"
        onClick={() => dismissToast(toast.id)}
        aria-label={t.notifications.dismiss}
        className="grid h-7 w-7 shrink-0 place-items-center rounded-edge text-chalk-faint
                   transition-colors hover:bg-ink-700 hover:text-chalk
                   focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                   focus-visible:outline-brand-400"
      >
        <svg viewBox="0 0 20 20" className="h-3.5 w-3.5" fill="none" stroke="currentColor"
             strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
          <path d="m5 5 10 10M15 5 5 15" />
        </svg>
      </button>
    </div>
  )
}
