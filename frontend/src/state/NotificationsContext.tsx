import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../lib/api'
import { useAuth } from './AuthContext'

export type Notification = {
  id: number
  kind: string
  title: string
  body: string | null
  link: string | null
  orderRef: string | null
  createdAt: string
  read: boolean
}

type FeedResponse = { items: Notification[]; unread: number }

type NotificationsState = {
  items: Notification[]
  unread: number
  /** The ones that arrived while this page was open, waiting to be shown as a toast. */
  toasts: Notification[]
  dismissToast: (id: number) => void
  /** Opening the bell: everything in it counts as seen. */
  markAllRead: () => void
  refresh: () => void
}

const NotificationsContext = createContext<NotificationsState | null>(null)

/** How often the feed is re-read while the tab is in front. */
const POLL_MS = 30_000

/**
 * The customer's notification feed, and the rule about what gets a toast.
 *
 * <p><b>A toast is for something that just happened.</b> Anything already in the feed when
 * this mounts is history: an order approved an hour ago is read in the bell, not popped up
 * as though it were news. So the cut-off is the moment the page opened, and only rows
 * created after it are queued — which also means a refresh does not replay yesterday.
 *
 * <p><b>Polling, not sockets.</b> Thirty seconds while the tab is in front, plus an
 * immediate read when it comes back to the front, is inside the window that matters here:
 * the events are minutes apart and a person watching for one is looking at the page. A
 * socket would be a second server surface to keep alive for no gain a customer can feel.
 *
 * <p>Signed out there is no feed at all — the bell belongs to an account, and a guest's
 * order is followed by its reference.
 */
export function NotificationsProvider({ children }: { children: ReactNode }) {
  const { account } = useAuth()
  const [items, setItems] = useState<Notification[]>([])
  const [unread, setUnread] = useState(0)
  const [toasts, setToasts] = useState<Notification[]>([])

  /*
   * Everything before this instant is history. Set once when the provider mounts rather
   * than when the account resolves, so a slow session check cannot turn an old
   * notification into a pop-up.
   */
  const openedAt = useRef(Date.now())
  const toasted = useRef(new Set<number>())

  const refresh = useCallback(() => {
    if (!account) return
    api.get<FeedResponse>('/api/v1/notifications?limit=20')
      .then((feed) => {
        setItems(feed.items)
        setUnread(feed.unread)
        const fresh = feed.items.filter((item) =>
          !item.read
          && !toasted.current.has(item.id)
          && new Date(item.createdAt).getTime() > openedAt.current)
        if (fresh.length === 0) return
        for (const item of fresh) toasted.current.add(item.id)
        // Oldest first, so a burst reads in the order it happened.
        setToasts((queue) => [...queue, ...fresh.reverse()])
      })
      .catch(() => {
        /* A missed poll is not worth a message; the next one is 30 seconds away. */
      })
  }, [account])

  useEffect(() => {
    if (!account) {
      setItems([])
      setUnread(0)
      setToasts([])
      return
    }
    refresh()
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') refresh()
    }, POLL_MS)
    const onFocus = () => refresh()
    window.addEventListener('focus', onFocus)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', onFocus)
    }
  }, [account, refresh])

  const dismissToast = useCallback((id: number) => {
    setToasts((queue) => queue.filter((item) => item.id !== id))
  }, [])

  const markAllRead = useCallback(() => {
    if (!account || unread === 0) return
    // Shown as read straight away; the server is told in the same breath. A bell that
    // stays lit until a round trip finishes reads as a click that did not register.
    setItems((current) => current.map((item) => ({ ...item, read: true })))
    setUnread(0)
    api.post('/api/v1/notifications/read', {}).catch(() => refresh())
  }, [account, unread, refresh])

  const value = useMemo(
    () => ({ items, unread, toasts, dismissToast, markAllRead, refresh }),
    [items, unread, toasts, dismissToast, markAllRead, refresh],
  )

  return <NotificationsContext.Provider value={value}>{children}</NotificationsContext.Provider>
}

export function useNotifications(): NotificationsState {
  const context = useContext(NotificationsContext)
  if (!context) throw new Error('useNotifications must be used inside NotificationsProvider')
  return context
}
