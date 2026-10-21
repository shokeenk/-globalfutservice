/**
 * tawk.to, the live chat: embedded in an order's support page, and behind the floating
 * chat button on the few pages that have one (see {@code components/LiveChatButton}).
 *
 * <p><b>Two widgets, one at a time.</b> tawk.to runs one widget per page load -- its
 * {@code Tawk_API} is a single global. So a page load is either embedded (the order
 * support page) or floating (the button), never both: whichever starts second asks for a
 * fresh page load, which tawk.to resumes from its server.
 *
 * <p><b>Loaded on demand.</b> The embedded chat loads as the order support page opens; the
 * floating one when the customer presses the chat button, never before. No other page
 * loads anything from tawk.to.
 *
 * <p><b>Embedded, never floating.</b> tawk.to reads {@code Tawk_API.embedded} once, when it
 * starts, and renders into the element with that id -- already open, since an embedded
 * widget has no minimised state. That element is made here: the support page puts it in
 * place, and takes it out of the page when the customer leaves, so nothing of the chat is
 * left on other pages.
 *
 * <p><b>The conversation is never ended from here.</b> Leaving takes the chat out of view
 * and does nothing else. This module never asks tawk.to to end the chat, disconnect or sign
 * the visitor out; the conversation stays open on tawk.to's side.
 *
 * <p><b>Coming back loads it afresh.</b> Once tawk.to has drawn the chat, its window is an
 * iframe it fills only once, and an iframe taken out of the page and put back comes back
 * empty. So the drawn element is never put back: the page reloads instead, and tawk.to,
 * starting again for the same visitor, fetches the conversation from its server and shows
 * it where it left off. Before anything is drawn -- React's development double mount, or
 * leaving and coming back within a moment -- the empty element is simply put back.
 *
 * <p><b>One order per page load.</b> tawk.to's name, email and order fields belong to the
 * visitor, and an attribute cannot be removed once set. So the chat is started for one
 * order in a page load, and opening another order's support page reloads the page rather
 * than leave the first order's details in the chat.
 *
 * <p><b>Only the allowlist.</b> The server builds the chat's fields from a fixed list; the
 * same list is enforced again here, so nothing else reaches tawk.to even if the server
 * ever sent it.
 */

/** The chat's fields: the same allowlist the server builds from. */
export const TAWK_ATTRIBUTES = [
  'order-id', 'service', 'platform', 'order-status', 'coin-amount', 'session-id', 'coach',
] as const

export interface TawkChat {
  name?: string | null
  email?: string | null
  /** Secure Mode: the email signed by the server. Absent when Secure Mode is not set up. */
  hash?: string | null
  attributes: Record<string, string>
}

export interface TawkConfig {
  propertyId: string
  widgetId: string
}

interface TawkApi {
  embedded?: string
  visitor?: { name?: string; email?: string; hash?: string }
  onLoad?: () => void
  onChatMinimized?: () => void
  maximize?: () => void
  minimize?: () => void
  showWidget?: () => void
  hideWidget?: () => void
  setAttributes?: (attributes: Record<string, string>, callback: (error?: unknown) => void) => void
  addEvent?: (name: string, metadata: Record<string, string>, callback: (error?: unknown) => void) => void
}

declare global {
  interface Window {
    Tawk_API?: TawkApi
    Tawk_LoadStart?: Date
  }
}

/** tawk.to's ids are plain tokens; anything else is treated as not configured rather than put in a URL. */
const ID = /^[A-Za-z0-9_-]{1,64}$/

/** The property and Embed Widget ids, or null when either is missing or malformed. */
export function tawkConfig(env: Record<string, string | undefined> = import.meta.env): TawkConfig | null {
  const propertyId = env.VITE_TAWK_PROPERTY_ID?.trim() ?? ''
  const widgetId = env.VITE_TAWK_EMBED_WIDGET_ID?.trim() ?? ''
  if (!ID.test(propertyId) || !ID.test(widgetId)) return null
  return { propertyId, widgetId }
}

/**
 * The floating widget's ids: the same property, and a widget of the Floating type. Null when
 * either is missing or malformed, and then no chat button is shown.
 */
export function floatingTawkConfig(env: Record<string, string | undefined> = import.meta.env): TawkConfig | null {
  const propertyId = env.VITE_TAWK_PROPERTY_ID?.trim() ?? ''
  const widgetId = env.VITE_TAWK_FLOATING_WIDGET_ID?.trim() ?? ''
  if (!ID.test(propertyId) || !ID.test(widgetId)) return null
  return { propertyId, widgetId }
}

/** Which widget this page load has started: tawk.to runs one, and cannot switch. */
let mode: 'embedded' | 'floating' | null = null

/** Which order the chat was started for in this page load, if any. */
let startedFor: string | null = null
/** The element tawk.to renders into, kept while the page lives. */
let container: HTMLDivElement | null = null
/**
 * tawk.to has drawn the chat, or its script failed to load. Either way, only a fresh page
 * load shows it again.
 */
let settled = false

export function tawkStartedFor(): string | null {
  return startedFor
}

/** The order's fields, and only the allowlisted ones, as tawk.to will take them. */
export function allowedAttributes(attributes: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {}
  for (const key of TAWK_ATTRIBUTES) {
    const value = attributes[key]
    if (typeof value === 'string' && value.trim() !== '') out[key] = value.slice(0, 255)
  }
  return out
}

/**
 * tawk.to answers each call with an error code, or nothing. Without this a refusal is
 * silent, and the agent simply does not see the order. Only the code is logged, never
 * what was sent.
 */
function refused(what: string): (error?: unknown) => void {
  return (error) => {
    if (error) console.warn(`Live chat: tawk.to did not accept ${what}:`, String(error instanceof Error ? error.message : error))
  }
}

/**
 * Starts the chat for one order, inside {@code host}.
 *
 * @return 'started' when the script was added; 'attached' when it was already starting for
 *         this order and nothing had been drawn yet, so the element has been put back;
 *         'reload' when nothing was done and the caller reloads the page: the chat in this
 *         page load is another order's, or this order's and already drawn
 */
export function startTawk(config: TawkConfig, reference: string, chat: TawkChat, host: HTMLElement,
                          onError: () => void): 'started' | 'attached' | 'reload' {
  if (mode === 'floating') return 'reload'
  if (startedFor !== null && (startedFor !== reference || drawn())) return 'reload'
  if (!container) {
    container = document.createElement('div')
    container.id = `tawk_${config.propertyId}`
    container.className = 'h-full w-full'
  }
  host.appendChild(container)
  if (startedFor === reference) return 'attached'
  startedFor = reference
  mode = 'embedded'

  const api: TawkApi = (window.Tawk_API = window.Tawk_API || {})
  api.embedded = container.id
  // Read once, before the script loads: tawk.to ignores a visitor set later.
  const visitor: { name?: string; email?: string; hash?: string } = {}
  if (chat.name) visitor.name = chat.name
  if (chat.email) visitor.email = chat.email
  if (chat.hash) visitor.hash = chat.hash
  api.visitor = visitor
  const attributes = allowedAttributes(chat.attributes)
  api.onLoad = () => {
    settled = true
    api.setAttributes?.(attributes, refused('the order details'))
    // Marks the conversation with the order it was opened from, for the agent.
    api.addEvent?.('order-support-opened', { 'order-id': attributes['order-id'] ?? reference },
      refused('the order event'))
  }
  window.Tawk_LoadStart = new Date()

  const script = document.createElement('script')
  script.async = true
  script.src = `https://embed.tawk.to/${config.propertyId}/${config.widgetId}`
  script.charset = 'UTF-8'
  script.setAttribute('crossorigin', '*')
  script.dataset.gfsTawk = 'true'
  script.onerror = () => {
    settled = true
    onError()
  }
  document.body.appendChild(script)
  return 'started'
}

/** Whether tawk.to has put anything in the element: its load callback ran, or its frames are there. */
function drawn(): boolean {
  return settled || (container?.childElementCount ?? 0) > 0
}

/**
 * Takes the chat off the page, when the customer leaves it. Out of view and nothing more:
 * the conversation is not ended, and comes back with the page.
 */
export function detachTawk(): void {
  container?.remove()
}

/** A fresh page load: how the chat moves from one order to another. Replaced in tests. */
export function reloadPage(): void {
  window.location.reload()
}

// ------------------------------------------------------------- floating ---

/** An order reference as the site writes it; anything else is not given to the chat. */
const ORDER_REF = /^[A-Za-z0-9-]{4,40}$/

/** The floating chat's progress in this page load. */
let floating: 'idle' | 'loading' | 'ready' | 'failed' = 'idle'
/** Who is waiting for the floating chat to finish loading: each press while it loads. */
let waiting: { ready: () => void; error: () => void }[] = []
/** The order the page in view is about, to show staff in the chat. Never more than its reference. */
let floatingOrder: string | null = null

/**
 * The order the page in view is about, or null. Passed to tawk.to as the {@code order-id}
 * attribute -- the only thing the floating chat ever sends it. Never an email, a sign-in
 * or backup codes: the visitor is anonymous to tawk.to until they type something.
 */
export function setFloatingOrder(reference: string | null): void {
  const ref = reference && ORDER_REF.test(reference.trim()) ? reference.trim().toUpperCase() : null
  if (ref === floatingOrder) return
  floatingOrder = ref
  if (floating === 'ready' && ref) sendFloatingOrder()
}

function sendFloatingOrder(): void {
  if (!floatingOrder) return
  window.Tawk_API?.setAttributes?.({ 'order-id': floatingOrder }, refused('the order reference'))
}

/**
 * Opens the floating chat over the page: loads tawk.to the first time, then opens its
 * window. tawk.to's own launcher is kept hidden -- the site's red button is the way in -- and
 * goes again whenever the customer minimises the window.
 *
 * @return 'opening' when the chat is opening or loading; 'reload' when this page load has
 *         the embedded chat, and only a fresh page load can switch widgets
 */
export function openFloatingTawk(config: TawkConfig, onReady: () => void, onError: () => void): 'opening' | 'reload' {
  if (mode === 'embedded') return 'reload'
  const api: TawkApi = (window.Tawk_API = window.Tawk_API || {})
  if (floating === 'ready') {
    api.showWidget?.()
    api.maximize?.()
    onReady()
    return 'opening'
  }
  waiting.push({ ready: onReady, error: onError })
  if (floating === 'loading') return 'opening'
  floating = 'loading'
  mode = 'floating'
  api.onLoad = () => {
    floating = 'ready'
    sendFloatingOrder()
    api.showWidget?.()
    api.maximize?.()
    settle((w) => w.ready())
  }
  api.onChatMinimized = () => api.hideWidget?.()
  window.Tawk_LoadStart = new Date()

  const script = document.createElement('script')
  script.async = true
  script.src = `https://embed.tawk.to/${config.propertyId}/${config.widgetId}`
  script.charset = 'UTF-8'
  script.setAttribute('crossorigin', '*')
  script.dataset.gfsTawk = 'true'
  script.onerror = () => {
    floating = 'failed'
    script.remove()
    settle((w) => w.error())
  }
  document.body.appendChild(script)
  return 'opening'
}

function settle(notify: (w: { ready: () => void; error: () => void }) => void): void {
  const now = waiting
  waiting = []
  now.forEach(notify)
}

/**
 * The customer has moved to a page without the chat button: the window closes and tawk.to's
 * launcher stays hidden. The conversation itself is untouched and comes back with the button.
 */
export function hideFloatingTawk(): void {
  if (floating !== 'ready') return
  window.Tawk_API?.minimize?.()
  window.Tawk_API?.hideWidget?.()
}

/** Which widget this page load has started, for the chat button. */
export function tawkMode(): 'embedded' | 'floating' | null {
  return mode
}

/** For tests: forget everything, as a fresh page load would. */
export function resetTawkForTests(): void {
  mode = null
  floating = 'idle'
  floatingOrder = null
  waiting = []
  startedFor = null
  settled = false
  container?.remove()
  container = null
  delete window.Tawk_API
  delete window.Tawk_LoadStart
  document.querySelectorAll('script[data-gfs-tawk]').forEach((s) => s.remove())
}
