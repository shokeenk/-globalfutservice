import type { ApiErrorBody } from './types'

/*
 * Where the API lives.
 *
 * `VITE_API_BASE_URL` is baked in at build time and is how every real deployment
 * points the bundle at its backend.
 *
 * The fallback is split by mode on purpose, because the two modes want opposite
 * things from a missing value. In development there is no dev-server proxy, so the
 * backend really is at another origin and hardcoding it is what lets `npm run dev`
 * work with no setup. In a production build that same default is actively harmful:
 * it ships a bundle that sends every visitor's browser to a port on *their own*
 * machine, which fails in a way that looks like the site is down rather than like
 * it is misconfigured. Same-origin is the only defensible production default — it
 * is correct for the usual arrangement where one reverse proxy fronts both halves,
 * and where it is wrong it fails against a host that actually exists.
 */
const BASE_URL =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ??
  (import.meta.env.DEV ? 'http://localhost:8080' : '')

/**
 * The access token lives in a module-scoped variable — in memory, for the lifetime
 * of the tab — and deliberately not in localStorage.
 *
 * A token in localStorage is readable by every script on the page, so one XSS bug
 * (in our code, or in any dependency that ends up in the bundle) yields a token an
 * attacker can use from their own machine for as long as it is valid. Keeping it
 * here means it dies with the tab. The long-lived half of the session is an
 * HttpOnly cookie the page cannot read at all, and this module silently exchanges
 * it for a new access token when one expires.
 */
let accessToken: string | null = null
let refreshInFlight: Promise<boolean> | null = null

export function setAccessToken(token: string | null) {
  accessToken = token
}

export function hasAccessToken(): boolean {
  return accessToken !== null
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details?: Record<string, string[]>
  readonly traceId?: string

  constructor(status: number, body: ApiErrorBody) {
    super(body.message || 'Something went wrong.')
    this.name = 'ApiError'
    this.status = status
    this.code = body.error ?? 'unknown'
    this.details = body.details
    this.traceId = body.traceId
  }

  /**
   * The server's per-field messages, flattened to one per field.
   *
   * <p>The API sends these on every validation failure — `{"password": ["Use at least
   * 12 characters"]}` — alongside a summary that says "check the highlighted fields".
   * Callers that ignore this render that summary next to nothing highlighted, which
   * tells the customer there is a problem and refuses to say where. Forms should pass
   * these straight into `Field error=` so the sentence becomes true.
   *
   * <p>First message per field: the server can report several, but a form shows one
   * line under one input, and the first is the one that failed first.
   */
  get fieldErrors(): Record<string, string> {
    const out: Record<string, string> = {}
    for (const [field, messages] of Object.entries(this.details ?? {})) {
      const first = messages?.[0]
      if (first) out[field] = first
    }
    return out
  }

  /** True when re-quoting or refreshing the page is the right recovery. */
  get isStale(): boolean {
    return this.code === 'quote_expired' || this.code === 'insufficient_points'
  }
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'
  body?: unknown
  /** Skip the automatic refresh-and-retry. Used by the refresh call itself. */
  noRetry?: boolean
  signal?: AbortSignal
}

async function raw(path: string, options: RequestOptions = {}): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (accessToken) {
    headers['Authorization'] = `Bearer ${accessToken}`
  }

  try {
    return await fetch(`${BASE_URL}${path}`, {
      method: options.method ?? 'GET',
      headers,
      // Sends the HttpOnly refresh cookie. The API allows exact origins only, so
      // this is not the blanket "allow any origin with credentials" that would make
      // it meaningless.
      credentials: 'include',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (cause) {
    /*
     * fetch rejects rather than resolving when the server cannot be reached at all —
     * it is down, the wrong port, or CORS refused the request. Without this, every
     * caller's `e instanceof ApiError` check fails and the page falls back to its
     * generic message, so "the API is not running" renders as "Something went wrong.
     * Please try again." on a form that is perfectly valid. Naming the real cause
     * turns a mystery into a one-line diagnosis.
     */
    if ((cause as Error)?.name === 'AbortError') {
      throw cause
    }
    throw new ApiError(0, {
      error: 'network_unreachable',
      message: 'Cannot reach the server. Check that the API is running, then try again.',
    })
  }
}

/**
 * Refreshes the access token, coalescing concurrent attempts.
 *
 * Without the coalescing, a page that fires four requests at once on load would
 * fire four refreshes — and since refresh tokens rotate and reuse revokes the whole
 * family, three of those would look exactly like a stolen token and sign the user
 * out. This is the kind of bug that only shows up under real network conditions.
 */
async function refresh(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const response = await raw('/api/v1/auth/refresh', { method: 'POST', noRetry: true })
        if (!response.ok) {
          accessToken = null
          return false
        }
        const data = (await response.json()) as { accessToken: string }
        accessToken = data.accessToken
        return true
      } catch {
        accessToken = null
        return false
      } finally {
        // Cleared on the next tick so callers awaiting this promise all observe
        // the same result before a new attempt can start.
        setTimeout(() => {
          refreshInFlight = null
        }, 0)
      }
    })()
  }
  return refreshInFlight
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response = await raw(path, options)

  if (response.status === 401 && !options.noRetry) {
    const refreshed = await refresh()
    if (refreshed) {
      response = await raw(path, options)
    }
  }

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  const payload = text ? safeParse(text) : null

  if (!response.ok) {
    throw new ApiError(
      response.status,
      (payload as ApiErrorBody | null) ?? {
        error: 'network',
        message: 'We could not reach the server. Please try again.',
      },
    )
  }

  return payload as T
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

export const api = {
  get: <T,>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T,>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  del: <T,>(path: string) => request<T>(path, { method: 'DELETE' }),
  baseUrl: BASE_URL,
}
