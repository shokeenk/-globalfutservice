import { api } from './api'
import { mayStorePreferences } from './consent'

/**
 * Popups: what the server sends, how often a browser is shown one, and the page steps the
 * storefront announces. The host that shows them is components/popups/PopupHost.
 *
 * <p>A popup never changes a price, a coupon, points or checkout state. It is words, a
 * picture and at most two links.
 */

export type PopupType = 'CENTER_MODAL' | 'BOTTOM_RIGHT'
export type PopupTrigger = 'PAGE_LOAD' | 'DELAY' | 'SCROLL' | 'STEP'
export type PopupFrequency = 'SESSION' | 'DAYS' | 'EVERY_VISIT'
export type PopupPage = 'HOME' | 'COINS' | 'COINS_CHECKOUT' | 'CHAMPS_LISTING' | 'CHAMPS_CHECKOUT' | 'REWARDS'

export const POPUP_PAGES: PopupPage[] = ['HOME', 'COINS', 'COINS_CHECKOUT', 'CHAMPS_LISTING', 'CHAMPS_CHECKOUT', 'REWARDS']

export interface PopupCopy {
  headline?: string | null
  body?: string | null
  referral?: string | null
  trust?: string | null
}

export interface PopupButton {
  label: { en?: string | null; es?: string | null; fr?: string | null }
  destination: string
}

export interface PopupImage {
  url: string
  width: number
  height: number
}

export interface PublicPopup {
  id: string
  version: number
  type: PopupType
  desktopImage?: PopupImage | null
  mobileImage?: PopupImage | null
  copy: { en: PopupCopy; es?: PopupCopy | null; fr?: PopupCopy | null }
  buttons: PopupButton[]
  targetPages: PopupPage[]
  targetPaths: string[]
  triggerType: PopupTrigger
  triggerValue?: number | null
  triggerEvent?: string | null
  frequency: PopupFrequency
  frequencyDays?: number | null
  priority: number
}

/** The words in the visitor's language, each one falling back to English where empty. */
export function localized(p: Pick<PublicPopup, 'copy' | 'buttons'>, lang: string) {
  const own = (lang === 'es' ? p.copy.es : lang === 'fr' ? p.copy.fr : null) ?? {}
  const pick = (k: keyof PopupCopy) => (own[k]?.trim() ? own[k] : p.copy.en?.[k]) ?? null
  return {
    headline: pick('headline'),
    body: pick('body'),
    referral: pick('referral'),
    trust: pick('trust'),
    buttons: p.buttons.map((b) => ({
      label: ((lang === 'es' ? b.label.es : lang === 'fr' ? b.label.fr : null)?.trim() || b.label.en) ?? '',
      destination: b.destination,
    })),
  }
}

/** The page key a route is, before a page says otherwise (the coin order says COINS_CHECKOUT past step one). */
export function pageKeyFor(pathname: string): PopupPage | null {
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname
  switch (path) {
    case '/': return 'HOME'
    case '/order': return 'COINS'
    case '/boosting': return 'CHAMPS_LISTING'
    case '/boosting/checkout': return 'CHAMPS_CHECKOUT'
    case '/rewards': return 'REWARDS'
    default: return null
  }
}

/** An image URL from the API: same origin in production, the API host in development. */
export function popupImageUrl(url: string): string {
  return url.startsWith('/') ? `${api.baseUrl}${url}` : url
}

// ------------------------------------------------------------------ frequency

/**
 * Remembered in the browser only -- no cookie -- keyed by campaign id and version, so an
 * edited campaign can be shown again. Once per session lives in sessionStorage; every N
 * days in localStorage, but only where the visitor agreed to preferences being stored (the
 * cookie notice). Otherwise it falls back to the session, which is the most this site
 * keeps for somebody who said no.
 */
const PREFIX = 'gfs.popup.'

function key(p: Pick<PublicPopup, 'id' | 'version'>): string {
  return `${PREFIX}${p.id}.v${p.version}`
}

function read(storage: Storage | undefined, k: string): number | null {
  try {
    const raw = storage?.getItem(k)
    const n = raw == null ? NaN : Number(raw)
    return Number.isFinite(n) ? n : null
  } catch {
    return null
  }
}

function write(storage: Storage | undefined, k: string, at: number): void {
  try {
    storage?.setItem(k, String(at))
  } catch {
    // Private browsing or a full quota: it will simply show again. Better than a crash.
  }
}

const session = () => (typeof window === 'undefined' ? undefined : window.sessionStorage)
const local = () => (typeof window === 'undefined' ? undefined : window.localStorage)

/** Whether this browser should not see it again yet. */
export function alreadySeen(p: Pick<PublicPopup, 'id' | 'version' | 'frequency' | 'frequencyDays'>,
                            now = Date.now()): boolean {
  if (p.frequency === 'EVERY_VISIT') return false
  const k = key(p)
  if (p.frequency === 'SESSION') return read(session(), k) != null
  const days = Math.max(1, p.frequencyDays ?? 1)
  const at = (mayStorePreferences() ? read(local(), k) : null) ?? read(session(), k)
  return at != null && now - at < days * 86_400_000
}

/** It has just been shown. */
export function rememberSeen(p: Pick<PublicPopup, 'id' | 'version' | 'frequency'>, now = Date.now()): void {
  if (p.frequency === 'EVERY_VISIT') return
  const k = key(p)
  if (p.frequency === 'DAYS' && mayStorePreferences()) write(local(), k, now)
  else write(session(), k, now)
}

// ---------------------------------------------------------------- page steps

/** The window event a page step travels on. */
export const POPUP_STEP_EVENT = 'gfs:popup-step'

/**
 * A page announcing a step a popup may wait for: coins:continue when the coin order moves
 * past choosing an amount, champs:checkout when a boosting checkout opens. Anything that
 * listens, listens; nothing waits for it.
 */
export function firePopupStep(name: string): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent<string>(POPUP_STEP_EVENT, { detail: name }))
}

/** A view, close or button click, counted on the server. Nothing about the visitor is sent. */
export function reportPopup(id: string, event: 'VIEW' | 'CLOSE' | 'CLICK'): void {
  api.post(`/api/v1/popups/${encodeURIComponent(id)}/events`, { event }).catch(() => {})
}
