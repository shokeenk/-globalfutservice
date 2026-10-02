/**
 * tawk.to, the live chat on order support pages -- and only there.
 *
 * <p><b>Loaded once, by the support page, after the customer asks.</b> Nothing here runs on
 * any other page: no other module imports this one, and the script is only added when the
 * customer presses Start chat. tawk.to's own loader guards against a second copy, and so
 * does this one, so going back and forth between support pages never adds it twice.
 *
 * <p><b>Embedded, never floating.</b> tawk.to reads {@code Tawk_API.embedded} once, when it
 * starts, and renders into the element with that id. That element is made here and kept for
 * the life of the page: the support page puts it in place, and takes it out of the page
 * when the customer leaves, so nothing of the chat is left on other pages. Coming back to
 * the same order puts the same element back.
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

/** Which order the chat was started for in this page load, if any. */
let startedFor: string | null = null
/** The element tawk.to renders into, kept while the page lives. */
let container: HTMLDivElement | null = null

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
 * @return 'started' when the script was added; 'attached' when it was already running for
 *         this order and has been put back; 'reload' when it is running for another order,
 *         in which case nothing was done and the caller reloads the page
 */
export function startTawk(config: TawkConfig, reference: string, chat: TawkChat, host: HTMLElement,
                          onError: () => void): 'started' | 'attached' | 'reload' {
  if (startedFor !== null && startedFor !== reference) return 'reload'
  if (!container) {
    container = document.createElement('div')
    container.id = `tawk_${config.propertyId}`
    container.className = 'h-full w-full'
  }
  host.appendChild(container)
  if (startedFor === reference) return 'attached'
  startedFor = reference

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
  script.onerror = onError
  document.body.appendChild(script)
  return 'started'
}

/** Puts the running chat back on the page, for the order it was started for. */
export function attachTawk(host: HTMLElement): void {
  if (container) host.appendChild(container)
}

/** Takes the chat off the page. It is kept, out of sight, in case the customer comes back. */
export function detachTawk(): void {
  container?.remove()
}

/** A fresh page load: how the chat moves from one order to another. Replaced in tests. */
export function reloadPage(): void {
  window.location.reload()
}

/** For tests: forget everything, as a fresh page load would. */
export function resetTawkForTests(): void {
  startedFor = null
  container?.remove()
  container = null
  delete window.Tawk_API
  delete window.Tawk_LoadStart
  document.querySelectorAll('script[data-gfs-tawk]').forEach((s) => s.remove())
}
