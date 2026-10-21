import { useEffect, useRef } from 'react'

/**
 * Cloudflare Turnstile, the human check: on sign-up, forgot-password, and sign-in after
 * repeated failures. Renders nothing without a site key, which is how it stays off until the
 * keys are set.
 *
 * <p>The token it hands back is checked on the server; the widget alone proves nothing. A
 * token is good once, so the form remounts this (a new `key`) after every submit that used
 * one.
 */

interface TurnstileApi {
  render: (el: HTMLElement, options: Record<string, unknown>) => string
  remove: (id: string) => void
}

declare global {
  interface Window {
    turnstile?: TurnstileApi
  }
}

export const TURNSTILE_SCRIPT = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'

let loading: Promise<TurnstileApi> | null = null

function loadScript(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile)
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = TURNSTILE_SCRIPT
      script.async = true
      script.defer = true
      script.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error('turnstile')))
      script.onerror = () => {
        loading = null
        script.remove()
        reject(new Error('turnstile'))
      }
      document.head.appendChild(script)
    })
  }
  return loading
}

export function Turnstile({ siteKey, onToken, language }: {
  siteKey: string | null
  /** The token, or null when it expired or failed and the customer has to do it again. */
  onToken: (token: string | null) => void
  language: string
}) {
  const host = useRef<HTMLDivElement>(null)
  const callback = useRef(onToken)
  callback.current = onToken

  useEffect(() => {
    if (!siteKey || !host.current) return
    let id: string | null = null
    let live = true
    loadScript()
      .then((api) => {
        if (!live || !host.current) return
        id = api.render(host.current, {
          sitekey: siteKey,
          language,
          theme: 'dark',
          callback: (token: string) => callback.current(token),
          'expired-callback': () => callback.current(null),
          'error-callback': () => callback.current(null),
        })
      })
      // Could not load: the form submits without a token and the server says whether that
      // was all right, rather than the page guessing.
      .catch(() => {})
    return () => {
      live = false
      if (id && window.turnstile) window.turnstile.remove(id)
    }
  }, [siteKey, language])

  if (!siteKey) return null
  return <div ref={host} data-testid="turnstile" className="min-h-[65px]" />
}

/** For tests: forget the loaded script. */
export function resetTurnstileForTests(): void {
  loading = null
  delete window.turnstile
}
