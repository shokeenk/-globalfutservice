/**
 * Where to send somebody once they have signed in.
 *
 * <p>Ordering needs an account, so the configurator hands a customer to /login halfway
 * through an order -- a boosting tier, a coaching pack -- and the whole order lives in the
 * URL they left. Every way back from sign-in has to return them to that URL. Two of them
 * did not: the password form and the Google/Discord callback both sent everyone to
 * /account, whose only order button is a bare /order, which the configurator reads as
 * coins. So a customer who picked "15 wins" and signed in was put in front of a coin
 * slider, and it looked exactly like being routed to the trading checkout.
 *
 * <p><b>Router state for the password form, sessionStorage for social sign-in.</b> A
 * password sign-in never leaves the app, so the path rides in `location.state`. Google and
 * Discord are full-page redirects through another site, which drops router state, so
 * /login also writes the path here and /auth/callback reads it back. sessionStorage
 * rather than localStorage: it belongs to this tab and is gone when the tab closes, so an
 * order started today cannot hijack a sign-in in another tab next week.
 *
 * <p><b>Only ever a page on this site.</b> The value decides where the app navigates, so
 * the browser's own URL parser rules on it rather than a string check -- `//host`,
 * `/\host` and a path with a tab in it all look like paths to `startsWith` and like
 * another origin to a browser.
 */

const KEY = 'gfs.returnTo'

/** Returning to a sign-in page after signing in is a loop, not a destination. */
const NOT_A_DESTINATION = new Set(['/login', '/register', '/auth/callback'])

export function safeReturnPath(value: unknown): string | null {
  if (typeof value !== 'string' || !value.startsWith('/')) return null
  let url: URL
  try {
    url = new URL(value, window.location.origin)
  } catch {
    return null
  }
  if (url.origin !== window.location.origin) return null
  if (NOT_A_DESTINATION.has(url.pathname)) return null
  return `${url.pathname}${url.search}${url.hash}`
}

/** Stores the path for a social sign-in to come back to; `null` clears it. */
export function rememberReturnTo(value: string | null): void {
  try {
    const path = safeReturnPath(value)
    if (path) sessionStorage.setItem(KEY, path)
    else sessionStorage.removeItem(KEY)
  } catch {
    // Storage blocked. The password form still returns the customer via router state;
    // only a social sign-in falls back to /account.
  }
}

/**
 * Reads without removing, so it is safe inside a render or a state initialiser -- both
 * of which React runs twice in development. Clear it with `rememberReturnTo(null)`.
 */
export function peekReturnTo(): string | null {
  try {
    return safeReturnPath(sessionStorage.getItem(KEY))
  } catch {
    return null
  }
}
