import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { useT } from '../i18n'
import { SEASON } from '../lib/seo'
import { NavIcon } from './NavIcon'
import { SERVICE_SKINS, SKIN_FOR_SERVICE } from '../content/serviceSkins'
import { Button } from './ui'

/**
 * Which of the three services the visitor actually wants.
 *
 * <p>"Start an order" used to go straight to the coin configurator. That is a guess,
 * and it was wrong a third of the time — somebody who came for Champs wins or a
 * coaching session landed on a platform picker and a coin slider, and had to work out
 * for themselves that they were in the wrong place. The three services are already
 * described on the home page; this puts that same choice in front of the button that
 * was quietly making it for them.
 *
 * <p>The cards on the home page keep their direct links. They are the choice already —
 * asking someone to choose twice would be worse than choosing for them.
 *
 * <p>Copy is reused wholesale from `home.services`, so the dialog and the section can
 * never describe the same product differently.
 */

type Service = {
  key: string
  tag: string
  title: string
  body: string
  cta: string
  to: string
}

function Arrow() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M5 12h13" /><path d="m12 5 7 7-7 7" />
    </svg>
  )
}

export function ServicePickerDialog({ onClose }: { onClose: () => void }) {
  const t = useT()
  const panelRef = useRef<HTMLDivElement | null>(null)
  const closeRef = useRef<HTMLButtonElement | null>(null)
  const returnRef = useRef<Element | null>(null)

  const s = t.home.services
  const services: Service[] = [
    { key: 'trading', tag: s.tradingTag, title: s.tradingTitle, body: s.tradingBody,
      cta: s.tradingCta, to: '/order' },
    { key: 'boosting', tag: s.boostTag, title: s.boostTitle, body: s.boostBody,
      cta: s.boostCta, to: '/boosting' },
    { key: 'coaching', tag: s.coachTag, title: s.coachTitle, body: s.coachBody,
      cta: s.coachCta, to: '/coaching' },
  ]

  /* Scroll lock, focus capture, focus return — the same contract the search palette
     and the mobile nav sheet keep. Each open is a fresh mount. */
  useEffect(() => {
    returnRef.current = document.activeElement
    const { overflow } = document.body.style
    document.body.style.overflow = 'hidden'
    const timer = window.setTimeout(() => closeRef.current?.focus(), 40)

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)

    return () => {
      document.body.style.overflow = overflow
      window.clearTimeout(timer)
      document.removeEventListener('keydown', onKey)
      if (returnRef.current instanceof HTMLElement) returnRef.current.focus()
    }
  }, [onClose])

  /*
   * Rendered into `document.body`, not where it is written.
   *
   * The trigger sits inside a `Reveal`, and `Reveal` animates with a transform. A
   * transformed ancestor becomes the containing block for `position: fixed`, so the
   * scrim and panel were sizing themselves against that wrapper instead of the
   * viewport — measured at 116px tall inside a 760px screen. It looked survivable on
   * a desktop and put the dialog off the bottom of a phone. A portal is the only
   * reliable fix: no ancestor can capture what is not a descendant.
   */
  return createPortal(
    <div
      className="fixed inset-0 flex items-center justify-center overflow-y-auto p-4 sm:p-6"
      style={{ zIndex: 'var(--z-modal)' }}
    >
      <button
        type="button"
        aria-label={t.common.close}
        onClick={onClose}
        className="absolute inset-0 cursor-default bg-ink/80 backdrop-blur-md"
      />

      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="service-picker-title"
        className="relative my-auto w-full max-w-[880px] animate-rise rounded-panel border
                   border-ink-400 bg-ink-700 p-6 shadow-lg
                   sm:p-8"
      >
        <div className="flex items-start justify-between gap-6">
          <div>
            <p className="eyebrow">{s.eyebrow(SEASON)}</p>
            <h2 id="service-picker-title" className="display mt-2 text-display-md text-chalk">
              {s.pickTitle}
            </h2>
            <p className="measure mt-2 text-body-sm text-chalk-muted">{s.pickLead}</p>
          </div>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            aria-label={t.common.close}
            className="grid h-9 w-9 shrink-0 place-items-center rounded-edge text-chalk-faint
                       transition-colors hover:bg-ink-500 hover:text-chalk"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 strokeWidth="2" strokeLinecap="round" aria-hidden="true">
              <path d="M18 6 6 18" /><path d="m6 6 12 12" />
            </svg>
          </button>
        </div>

        <div className="mt-7 grid gap-4 md:grid-cols-3">
          {services.map((service) => {
            const skin = SERVICE_SKINS[SKIN_FOR_SERVICE[service.key] ?? 'sun']
            return (
            <Link
              key={service.key}
              to={service.to}
              onClick={onClose}
              className={`group relative flex flex-col overflow-hidden rounded-panel border p-5
                         shadow-e1 transition-[transform,background-color,box-shadow]
                         duration-300 ease-out-expo shadow-e2 hover:-translate-y-1 hover:shadow-e3
                         focus-visible:-translate-y-1 ${skin.panel}`}
            >
              {/*
                One glyph per card, from the same stroked set the nav uses — trading,
                boosting and coaching. All three sit in the same corner at the same size
                and lift together on hover, so the row reads as one set rather than three
                found objects.

                Drawn glyphs, not the OS emoji this briefly used: 🎯 is a red-and-white
                dartboard on Windows and a different palette again on Android, so an emoji
                is the one mark whose colour the page cannot set. These take `currentColor`
                and stay on the single-palette rule. Keyed on `key`, which is already the
                NavIcon name.
              */}
              <span
                aria-hidden="true"
                className={`pointer-events-none absolute -right-2 -top-2 grid h-[58px] w-[58px]
                           place-items-center transition-transform duration-500 ease-out-expo
                           group-hover:-translate-y-0.5 group-hover:scale-105 ${skin.mark}`}
              >
                <NavIcon name={service.key} size={38} />
              </span>

              {/* The chip takes the card's foreground: `.stamp` is red, which is
                  invisible on the red card and foreign on the other two. */}
              <span className={`self-start rounded-pill px-2.5 py-1 text-[10.5px] font-semibold
                               uppercase tracking-[0.12em] ${skin.chip}`}>
                {service.tag}
              </span>
              <span className={`display mt-3 text-display-sm ${skin.title}`}>{service.title}</span>
              <span className={`mt-3 flex-1 text-body-sm leading-relaxed ${skin.body}`}>
                {service.body}
              </span>
              <span className={`mt-5 inline-flex items-center gap-2 text-body-sm font-semibold
                               ${skin.cta}`}>
                {service.cta}
                <span className="transition-transform duration-300 ease-out-expo
                                 group-hover:translate-x-1">
                  <Arrow />
                </span>
              </span>
            </Link>
            )
          })}
        </div>
      </div>
    </div>,
    document.body,
  )
}

/**
 * The primary call to action, with the choice attached.
 *
 * <p>Renders as a real `<button>` rather than a link, because it no longer navigates —
 * it asks. Anything that still knows where it is going should keep using `ButtonLink`.
 */
export function StartOrderButton({
  label,
  size = 'lg',
  full = false,
  className = '',
  /**
   * Render as paper-on-red rather than red-on-paper.
   *
   * For the one place the button sits inside a red field, where the default fill
   * would be the same colour as its ground and the button would simply vanish.
   */
  invert = false,
}: {
  label: string
  size?: 'sm' | 'md' | 'lg'
  full?: boolean
  className?: string
  invert?: boolean
}) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <Button
        size={size}
        full={full}
        variant={invert ? 'invert' : 'primary'}
        className={className}
        onClick={() => setOpen(true)}
      >
        {label}
        <Arrow />
      </Button>
      {open && <ServicePickerDialog onClose={() => setOpen(false)} />}
    </>
  )
}
