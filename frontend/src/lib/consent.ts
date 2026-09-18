/**
 * What this visitor has agreed the browser may remember.
 *
 * <p><b>There is exactly one thing to consent to here, and it is small.</b> The site sets
 * one cookie — the sign-in one, which is strictly necessary and only exists once somebody
 * signs in — and stores two preferences in the browser: the language and the currency.
 * There is no analytics, no advertising pixel and no third-party tracker, so a banner
 * offering to switch tracking off would be offering something that does not exist.
 *
 * <p>So "decline" means what it says: the two preferences are not written down, and they
 * last as long as the tab. Anything added later that wants storage asks this module first
 * rather than writing directly, which is what keeps the promise true as the site grows.
 *
 * <p><b>The choice itself is stored.</b> Remembering "no" is the only way not to ask
 * again on every page, and a banner that reappears after being declined is the worst of
 * both worlds. It is a single key holding one word.
 */

export type Consent = 'accepted' | 'declined'

const KEY = 'gfs.cookie-consent'

type Listener = (consent: Consent | null) => void
const listeners = new Set<Listener>()

/** The stored choice, or null when this visitor has not answered yet. */
export function readConsent(): Consent | null {
  if (typeof window === 'undefined') return null
  try {
    const stored = window.localStorage.getItem(KEY)
    return stored === 'accepted' || stored === 'declined' ? stored : null
  } catch {
    // Private browsing denies storage. Nothing is remembered, which is the same
    // outcome as declining — so behave as though they had.
    return null
  }
}

/** Whether the browser may keep preferences like the language and the currency. */
export function mayStorePreferences(): boolean {
  return readConsent() === 'accepted'
}

/**
 * Records the answer, and acts on it.
 *
 * <p>Declining clears what was already stored rather than only stopping future writes: a
 * customer who changes their mind means "forget it", not "keep what you have".
 */
export function setConsent(consent: Consent): void {
  try {
    window.localStorage.setItem(KEY, consent)
    if (consent === 'declined') forgetPreferences()
  } catch {
    /* Nothing can be stored anyway; the choice still applies to this session. */
  }
  for (const listener of listeners) listener(consent)
}

/** The preference keys this site writes. Kept here so "forget it" stays honest. */
const PREFERENCE_KEYS = ['gfs.language', 'gfs.currency']

function forgetPreferences(): void {
  for (const key of PREFERENCE_KEYS) {
    try {
      window.localStorage.removeItem(key)
    } catch {
      /* ignore */
    }
  }
}

/** Notifies when the answer changes, so the banner and the page agree. */
export function onConsentChange(listener: Listener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
