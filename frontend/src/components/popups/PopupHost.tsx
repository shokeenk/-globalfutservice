import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useI18n } from '../../i18n'
import { api } from '../../lib/api'
import { alreadySeen, forgetStepsNotOn, isCheckout, pageKeyFor, POPUP_STEP_EVENT, rememberSeen, reportPopup,
  stepFiredOn } from '../../lib/popups'
import type { PopupPage, PublicPopup } from '../../lib/popups'
import { usePopupContext } from '../../state/popupContext'
import { PopupCard, PopupModal } from './PopupViews'
import { usePopupFacts } from './popupFacts'

/**
 * The one place popups are shown, mounted once in the storefront layout.
 *
 * <ul>
 *   <li><b>One at a time.</b> Never two on screen. A centred modal goes first; a
 *       bottom-right card waits until it is closed. At most one per page view, unless the
 *       first was closed.</li>
 *   <li><b>Never on the payment step</b> -- pages say so ({@code useNoPopups}), and a card
 *       already showing goes when they do.</li>
 *   <li><b>The card never covers what the customer is using.</b> It sits above the chat
 *       button and any sticky bar, and stays hidden while it would overlap anything marked
 *       {@code data-popup-avoid} (the amount selector, the coupon field, the total, the
 *       Continue and Pay buttons). No room: it is not shown.</li>
 * </ul>
 *
 * <p>Nothing here can change a price, a coupon, points or an order: a popup is words, a
 * picture and at most two links.
 */

/** Pixels kept between the card and anything it must not touch. */
const GAP = 12
/** The card must keep this much clear below the top of the screen (the header). */
const TOP_CLEARANCE = 80

type Rect = { top: number; bottom: number; left: number; right: number }

function visible(r: DOMRect): boolean {
  return r.width > 0 && r.height > 0
}

function overlaps(a: Rect, b: Rect): boolean {
  return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
}

/**
 * Where the card goes: its distance from the bottom of the screen, or null when there is
 * no place for it that covers nothing it must not.
 */
export function cardPlacement(cardWidth: number, cardHeight: number): number | null {
  const vh = window.innerHeight
  const vw = window.innerWidth
  let bottom = 16
  // Above the chat button (or the help assistant) and above any sticky bar: they win.
  for (const el of Array.from(document.querySelectorAll<HTMLElement>('[data-popup-above], [data-popup-sticky-bar]'))) {
    const r = el.getBoundingClientRect()
    if (visible(r)) bottom = Math.max(bottom, vh - r.top + GAP)
  }
  const card: Rect = { left: vw - 16 - cardWidth, right: vw - 16, top: vh - bottom - cardHeight, bottom: vh - bottom }
  if (card.top < TOP_CLEARANCE) return null
  for (const el of Array.from(document.querySelectorAll<HTMLElement>('[data-popup-avoid]'))) {
    const r = el.getBoundingClientRect()
    if (!visible(r)) continue
    const padded = { top: r.top - GAP, bottom: r.bottom + GAP, left: r.left - GAP, right: r.right + GAP }
    if (overlaps(card, padded)) return null
  }
  return bottom
}

/**
 * Routes no popup is ever asked for: an order's support page (the live chat is the page),
 * signing in and its kin, and the payment return. Not even a request is made there.
 */
export function popupFreeRoute(pathname: string): boolean {
  return /^\/(orders|coaching)\/[^/]+\/support\/?$/.test(pathname)
    || /^\/(support\/chat|login|register|forgot-password|reset-password|confirm-email|auth\/callback|payment\/)/.test(pathname)
}

/** The page's campaigns, kept for the page load: asked for once per page and path. */
const fetched = new Map<string, Promise<PublicPopup[]>>()

function loadFor(page: PopupPage | null, path: string): Promise<PublicPopup[]> {
  const k = `${page ?? ''}|${path}`
  let p = fetched.get(k)
  if (!p) {
    const q = new URLSearchParams({ path })
    if (page) q.set('page', page)
    // Never allowed to break a page: anything but a list of popups is no popups.
    p = Promise.resolve()
      .then(() => api.get<PublicPopup[]>(`/api/v1/popups?${q.toString()}`))
      .then((list) => (Array.isArray(list) ? list : []))
      .catch(() => [])
    fetched.set(k, p)
  }
  return p
}

/** For tests: forget fetched lists. */
export function resetPopupHostForTests(): void {
  fetched.clear()
}

/** Which of several ready popups goes first: the modal, then the higher priority. */
export function nextPopup(ready: PublicPopup[]): PublicPopup | null {
  return [...ready].sort((a, b) =>
    (a.type === b.type ? 0 : a.type === 'CENTER_MODAL' ? -1 : 1) || b.priority - a.priority)[0] ?? null
}

export function PopupHost() {
  const { lang, t } = useI18n()
  const location = useLocation()
  const navigate = useNavigate()
  const context = usePopupContext()
  const page = context.page ?? pageKeyFor(location.pathname)
  const path = location.pathname
  const view = `${page ?? ''}|${path}`

  const [candidates, setCandidates] = useState<PublicPopup[]>([])
  const [ready, setReady] = useState<string[]>([])
  const [current, setCurrent] = useState<PublicPopup | null>(null)
  /** Shown this page view and not closed by the customer: then no other may follow. */
  const [spent, setSpent] = useState(false)
  const checkout = isCheckout(page, path)
  const facts = usePopupFacts()

  // A new page view: forget everything about the last one.
  useEffect(() => {
    setCandidates([])
    setReady([])
    setCurrent(null)
    setSpent(false)
    // Steps fired on another path are over; one fired on this path stays heard.
    forgetStepsNotOn(path)
    if (popupFreeRoute(path)) return
    let live = true
    void loadFor(page, path).then((list) => {
      // "Never during checkout" popups are dropped on checkout pages, whatever they target.
      if (live) setCandidates(list.filter((p) => !alreadySeen(p) && !(p.excludeCheckout && checkout)))
    })
    return () => { live = false }
  }, [view]) // eslint-disable-line react-hooks/exhaustive-deps -- the view key is page + path

  // Page steps: heard as they fire, and remembered per path by lib/popups, so one fired
  // before the list arrived -- or as the page key changed -- still counts.
  useEffect(() => {
    function onStep(e: Event) {
      const name = (e as CustomEvent<string>).detail
      if (typeof name !== 'string') return
      setReady((r) => [...r, ...candidates.filter((c) => c.triggerType === 'STEP' && c.triggerEvent === name)
        .map((c) => c.id).filter((id) => !r.includes(id))])
    }
    window.addEventListener(POPUP_STEP_EVENT, onStep)
    return () => window.removeEventListener(POPUP_STEP_EVENT, onStep)
  }, [candidates])

  // Arm each candidate's trigger.
  useEffect(() => {
    const cleanups: Array<() => void> = []
    const fire = (id: string) => setReady((r) => (r.includes(id) ? r : [...r, id]))
    for (const c of candidates) {
      if (c.triggerType === 'PAGE_LOAD') fire(c.id)
      else if (c.triggerType === 'DELAY') {
        const timer = window.setTimeout(() => fire(c.id), Math.max(0, c.triggerValue ?? 0) * 1000)
        cleanups.push(() => window.clearTimeout(timer))
      }
      if (c.triggerType === 'DELAY_OR_SCROLL') {
        // Whichever comes first: the seconds, or the scroll.
        const timer = window.setTimeout(() => fire(c.id), Math.max(0, c.triggerValue ?? 0) * 1000)
        cleanups.push(() => window.clearTimeout(timer))
      }
      if (c.triggerType === 'SCROLL' || c.triggerType === 'DELAY_OR_SCROLL') {
        const percent = c.triggerType === 'SCROLL' ? c.triggerValue : c.triggerScroll
        const check = () => {
          const room = document.documentElement.scrollHeight - window.innerHeight
          const pct = room <= 0 ? 100 : (window.scrollY / room) * 100
          if (pct >= (percent ?? 0)) fire(c.id)
        }
        window.addEventListener('scroll', check, { passive: true })
        cleanups.push(() => window.removeEventListener('scroll', check))
      } else if (c.triggerType === 'STEP' && c.triggerEvent && stepFiredOn(c.triggerEvent, path)) {
        fire(c.id)
      }
    }
    return () => cleanups.forEach((f) => f())
  }, [candidates]) // eslint-disable-line react-hooks/exhaustive-deps -- the path is part of the view that loaded them

  // The slot: one popup, when none is showing, nothing blocks, and none was left open.
  useEffect(() => {
    if (current || spent || context.blocked) return
    const next = nextPopup(candidates.filter((c) => ready.includes(c.id)))
    if (!next) return
    setCurrent(next)
    setSpent(true)
    setCandidates((cs) => cs.filter((c) => c.id !== next.id))
    rememberSeen(next)
    reportPopup(next.id, 'VIEW')
  }, [current, spent, context.blocked, candidates, ready])

  // The payment step began with a popup up: it goes, and does not come back on this view.
  useEffect(() => {
    if (context.blocked && current) setCurrent(null)
  }, [context.blocked, current])

  const close = useCallback(() => {
    if (current) reportPopup(current.id, 'CLOSE')
    setCurrent(null)
    // Closed by the customer: one more may follow on this page view.
    setSpent(false)
  }, [current])

  const follow = useCallback((destination: string) => {
    if (current) reportPopup(current.id, 'CLICK')
    setCurrent(null)
    if (destination.startsWith('/')) navigate(destination)
    else window.location.assign(destination)
  }, [current, navigate])

  if (!current || context.blocked) return null
  const closeLabel = t.popups.close
  return current.type === 'CENTER_MODAL'
    ? <PopupModal popup={current} lang={lang} onClose={close} onButton={follow} closeLabel={closeLabel} facts={facts} />
    : <PlacedCard popup={current} lang={lang} onClose={close} onButton={follow} closeLabel={closeLabel} facts={facts} />
}

/** The card, placed clear of the chat button, sticky bars and checkout controls -- or hidden. */
function PlacedCard(props: Omit<Parameters<typeof PopupCard>[0], 'bottom' | 'hidden'>) {
  const [bottom, setBottom] = useState<number | null>(null)
  const size = useRef({ width: 340, height: 160 })

  const place = useMemo(() => () => {
    const card = document.querySelector<HTMLElement>('[data-popup-card]')
    if (card) {
      const r = card.getBoundingClientRect()
      if (r.width > 0) size.current = { width: r.width, height: r.height }
    }
    setBottom(cardPlacement(size.current.width, size.current.height))
  }, [])

  useLayoutEffect(() => {
    place()
    let frame = 0
    const later = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(place)
    }
    window.addEventListener('scroll', later, { passive: true })
    window.addEventListener('resize', later)
    // Layout moves without a scroll too: a step opening, a summary growing.
    const timer = window.setInterval(place, 1000)
    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('scroll', later)
      window.removeEventListener('resize', later)
      window.clearInterval(timer)
    }
  }, [place])

  return <PopupCard {...props} bottom={bottom ?? 0} hidden={bottom == null} />
}
