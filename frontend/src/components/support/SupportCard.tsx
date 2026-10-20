import { useT } from '../../i18n'
import { ButtonLink } from '../ui'

/** Which support page an order has: the copy, the button and the route follow it. */
export type SupportMode = 'BOOSTING' | 'COINS' | 'COACHING'

/**
 * The mode for the card's copy. The support page itself takes its mode from the server;
 * this only decides which words the card on the order uses.
 */
export function supportModeFor(sku: string): SupportMode {
  if (sku === 'COACHING') return 'COACHING'
  if (sku === 'TRADING_SERVICE') return 'COINS'
  return 'BOOSTING'
}

/** The support page on this site for the order. Never anything off the GFS domain. */
export function supportPath(mode: SupportMode, reference: string): string {
  const ref = encodeURIComponent(reference)
  return mode === 'COACHING' ? `/coaching/${ref}/support` : `/orders/${ref}/support`
}

/** An order's support page: /orders/<ref>/support or /coaching/<ref>/support. */
export function isSupportPage(pathname: string): boolean {
  return /^\/(orders|coaching)\/[^/]+\/support\/?$/.test(pathname)
}

/** The card's words, per mode: the client's copy, word for word. */
export function useSupportCopy(mode: SupportMode) {
  const s = useT().orderSupport
  return {
    title: mode === 'COACHING' ? s.cardTitleCoach : s.cardTitleOrder,
    body: mode === 'COACHING' ? s.cardBodyCoaching : mode === 'COINS' ? s.cardBodyCoins : s.cardBodyBoosting,
    cta: mode === 'COACHING' ? s.ctaCoaching : mode === 'COINS' ? s.ctaCoins : s.ctaBoosting,
  }
}

function ChatIcon() {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 12a8.5 8.5 0 0 1-12.4 7.6L3 21l1.4-5.6A8.5 8.5 0 1 1 21 12Z" />
      <path d="M8.5 12h.01M12 12h.01M15.5 12h.01" />
    </svg>
  )
}

/**
 * Connect with GFS, or with your coach: the way from an order to its support page.
 *
 * <p>The button stays on this site: it opens the order's support page, where the live chat
 * sits inside the page. Support is the live chat only -- never Discord.
 */
export function SupportCard({ reference, sku }: { reference: string; sku: string }) {
  const mode = supportModeFor(sku)
  const copy = useSupportCopy(mode)
  return (
    <div className="rounded-panel border border-brand-500/25 bg-brand-500/[0.05] p-4 text-left"
         data-testid="support-card">
      <div className="flex flex-wrap items-center gap-3">
        <span aria-hidden="true"
              className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-brand-500 text-paper">
          <ChatIcon />
        </span>
        {/*
          A 14rem floor on the text, so on a phone the button wraps onto its own line
          instead of the text being squeezed to a word per line beside it.
        */}
        <div className="min-w-0 flex-1 basis-56">
          <p className="text-body-sm font-semibold text-chalk">{copy.title}</p>
          <p className="mt-1 text-[12.5px] leading-relaxed text-chalk-muted">{copy.body}</p>
        </div>
        <ButtonLink to={supportPath(mode, reference)} size="md" className="w-full sm:w-auto">{copy.cta}</ButtonLink>
      </div>
    </div>
  )
}

/** The live chat for any order, signed in or not: /support/chat with the order's reference. */
export function guestChatPath(reference: string): string {
  return `/support/chat?ref=${encodeURIComponent(reference)}`
}

/**
 * For an order placed without an account: the same live chat, by the guest chat page, with
 * the reference filled in. The email the guest looked the order up with travels in router
 * state, never in the address, so they are not asked for it twice.
 */
export function GuestSupportCard({ reference, email }: { reference: string; email?: string }) {
  const s = useT().orderSupport
  return (
    <div className="rounded-panel border border-ink-400 bg-paper p-4 text-left" data-testid="guest-support-card">
      <p className="text-body-sm font-semibold text-chalk">{s.guestTitle}</p>
      <p className="mt-1 text-[12.5px] leading-relaxed text-chalk-muted">{s.guestBody(reference)}</p>
      <ButtonLink to={guestChatPath(reference)} state={email ? { email } : undefined} size="md" className="mt-3">
        {s.guestCta}
      </ButtonLink>
    </div>
  )
}
