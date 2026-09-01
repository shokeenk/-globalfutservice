import { useEffect, useState } from 'react'
import { api } from '../lib/api'
import { useT } from '../i18n'

/**
 * "Continue with Google" / "Continue with Discord".
 *
 * <p><b>The buttons are served, not assumed.</b> The component asks which providers
 * this deployment actually has and renders nothing until it knows. A button for a
 * provider whose secret was never set looks like a working option and fails only after
 * the customer has left the site — the worst place to discover it. Rendering nothing
 * when the answer is nothing also means an install that never configures either one
 * shows a plain password form, with no gap where buttons used to be.
 *
 * <p><b>A real link, not a fetch.</b> OAuth is a browser redirect: the provider needs to
 * see the customer's own session at Google or Discord, which an XHR cannot carry. So
 * this is an anchor to the backend's authorization endpoint and the browser leaves.
 * That is also why it is not a `<Link>` — react-router would keep it inside the SPA.
 */
type Provider = 'google' | 'discord'

const ORDER: Provider[] = ['google', 'discord']

export function SocialSignIn({ className = '' }: { className?: string }) {
  const t = useT()
  const [available, setAvailable] = useState<Provider[] | null>(null)

  useEffect(() => {
    const ac = new AbortController()
    api
      .get<{ providers: string[] }>('/api/v1/auth/providers', ac.signal)
      .then((r) => {
        const known = ORDER.filter((p) => r.providers.includes(p))
        setAvailable(known)
      })
      /*
       * A failure here is not worth an error message. The password form below is
       * fully usable, and "we could not list sign-in providers" is a sentence that
       * helps nobody — so it degrades to no buttons.
       */
      .catch(() => setAvailable([]))
    return () => ac.abort()
  }, [])

  if (!available || available.length === 0) return null

  return (
    <div className={className}>
      <div className="flex flex-col gap-2.5">
        {available.map((p) => (
          <a
            key={p}
            /*
              Relative, deliberately. Sending the browser straight at the API host
              would set the refresh cookie on that domain, where the storefront cannot
              use it — the sign-in would appear to work and leave the customer signed
              out. The host proxies these paths so the whole flow stays first-party.
            */
            href={`/oauth2/authorization/${p}`}
            className="hairline inline-flex min-h-[46px] w-full items-center justify-center gap-2.5
                       rounded-edge bg-ink-700 px-4 text-body-sm font-semibold text-chalk
                       shadow-e1 transition-[background-color,box-shadow] duration-200
                       hover:bg-ink-500 hover:shadow-e2"
          >
            {p === 'google' ? <GoogleMark /> : <DiscordMark />}
            {p === 'google' ? t.auth.continueGoogle : t.auth.continueDiscord}
          </a>
        ))}
      </div>

      {/* The divider says which of the two routes the form below is. */}
      <div className="my-5 flex items-center gap-3">
        <span aria-hidden="true" className="h-px flex-1 bg-ink-400" />
        <span className="text-[11px] uppercase tracking-[0.14em] text-chalk-faint">
          {t.auth.orDivider}
        </span>
        <span aria-hidden="true" className="h-px flex-1 bg-ink-400" />
      </div>
    </div>
  )
}

/*
 * Both marks are drawn rather than fetched. Google and Discord both publish brand
 * guidance requiring their logo to keep its own colours, so these two are a deliberate
 * exception to the single-palette rule — a monochrome Google "G" is a trademark
 * violation, not a design choice. They are the only marks on the site that do this.
 */
function GoogleMark() {
  return (
    <svg width="17" height="17" viewBox="0 0 48 48" aria-hidden="true" className="shrink-0">
      <path fill="#EA4335" d="M24 9.5c3.5 0 6.6 1.2 9 3.6l6.7-6.7C35.6 2.6 30.2.5 24 .5 14.6.5 6.5 5.9 2.6 13.8l7.8 6c1.9-5.7 7.2-10.3 13.6-10.3Z" />
      <path fill="#4285F4" d="M46.6 24.6c0-1.6-.1-3.2-.4-4.6H24v9.1h12.7c-.5 3-2.2 5.5-4.7 7.2l7.5 5.8c4.4-4 6.9-10 6.9-17.5Z" />
      <path fill="#FBBC05" d="M10.4 28.2a14.4 14.4 0 0 1 0-9.1l-7.8-6a23.6 23.6 0 0 0 0 21.2l7.8-6Z" />
      <path fill="#34A853" d="M24 47.5c6.2 0 11.5-2 15.3-5.6l-7.5-5.8c-2 1.4-4.7 2.3-7.8 2.3-6.4 0-11.7-4.6-13.6-10.3l-7.8 6C6.5 42.1 14.6 47.5 24 47.5Z" />
    </svg>
  )
}

function DiscordMark() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="#5865F2" aria-hidden="true" className="shrink-0">
      <path d="M20.3 4.4A19.8 19.8 0 0 0 15.4 3l-.24.5a18.3 18.3 0 0 1 4.3 1.4c-2-1.1-4.1-1.6-6.4-1.6-2.3 0-4.4.5-6.4 1.6A18.3 18.3 0 0 1 11 3.5L10.7 3a19.8 19.8 0 0 0-4.9 1.4C2.6 9.1 1.7 13.7 2.1 18.2a19.9 19.9 0 0 0 6 3c.5-.65.9-1.35 1.25-2.1-.7-.25-1.35-.55-1.95-.9.16-.12.32-.25.47-.38a14.2 14.2 0 0 0 12.2 0c.16.14.31.26.47.38-.62.36-1.27.66-1.96.9.36.75.78 1.45 1.25 2.1a19.8 19.8 0 0 0 6-3c.5-5.2-.85-9.75-3.5-13.8ZM8.7 15.4c-1.18 0-2.15-1.07-2.15-2.4S7.5 10.6 8.7 10.6s2.17 1.08 2.15 2.4c0 1.33-.96 2.4-2.15 2.4Zm6.6 0c-1.18 0-2.15-1.07-2.15-2.4s.95-2.4 2.15-2.4 2.17 1.08 2.15 2.4c0 1.33-.95 2.4-2.15 2.4Z" />
    </svg>
  )
}
