import { useState } from 'react'
import { BUSINESS } from '../content/business'
import { useT } from '../i18n'

/**
 * How a customer we cannot place on Discord reaches us about their order: a direct message
 * to our account, with their order reference in it.
 *
 * <p>The button is the site's own direct-message link ({@link BUSINESS.discordDm}), the one
 * every other Discord link here opens. The reference sits beside it with a copy button,
 * so it can be pasted into the message rather than retyped.
 */
export function DiscordMessageUs({ reference, align = 'start' }: { reference: string; align?: 'start' | 'center' }) {
  const t = useT()
  const [copied, setCopied] = useState(false)
  const center = align === 'center'

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(reference)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard access is refused in some browsers and every insecure context. The
      // reference is on screen and selectable, so this costs a convenience, not the step.
    }
  }

  return (
    <div className={center ? 'text-center' : ''} data-testid="discord-message-us">
      <p className="text-[13px] text-chalk">
        {t.discordDm.messageUs}{' '}
        <span className="font-semibold text-chalk">{BUSINESS.discordName}</span>
      </p>
      <div className={`mt-2 flex flex-wrap items-center gap-2 ${center ? 'justify-center' : ''}`}>
        <span className="text-[12.5px] text-chalk-muted">{t.discordDm.includeReference}</span>
        <code className="tnum rounded-edge bg-ink-700/60 px-2.5 py-1 text-[12.5px] font-semibold text-chalk">
          {reference}
        </code>
        <button
          type="button"
          onClick={() => { void copy() }}
          aria-label={t.discordDm.copyLabel(reference)}
          className="inline-flex h-8 shrink-0 items-center rounded-edge border border-ink-300 px-3 text-[12px]
                     font-semibold text-chalk-muted transition-colors duration-200 hover:text-chalk
                     focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                     focus-visible:outline-brand-400"
        >
          {copied ? t.discordDm.copied : t.discordDm.copy}
        </button>
      </div>
      <a
        href={BUSINESS.discordDm}
        target="_blank"
        rel="noreferrer"
        className={`mt-3 inline-flex min-h-[44px] items-center justify-center gap-2 rounded-edge bg-brand-500 px-5
                    text-[13px] font-semibold text-paper transition-colors duration-200 hover:bg-brand-400
                    focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                    focus-visible:outline-brand-400`}
      >
        {t.discordDm.button}
      </a>
    </div>
  )
}
