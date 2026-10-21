import { useEffect, useId, useRef } from 'react'
import { fillTokens, localized, popupImageUrl } from '../../lib/popups'
import type { PopupFacts } from './popupFacts'
import type { PublicPopup } from '../../lib/popups'

/**
 * The two popup shapes. The storefront host shows them over the page; the admin Popup
 * Manager shows the same components in a box, at desktop or phone size, as its preview.
 *
 * @param preview drawn inside its container instead of over the page, with no focus trap
 * @param phone   for the preview: draw as a phone would (mobile image, compact card)
 */
type ViewProps = {
  popup: Pick<PublicPopup, 'id' | 'copy' | 'buttons' | 'desktopImage' | 'mobileImage'> & Pick<Partial<PublicPopup>, 'preset'>
  /** Live numbers from the catalogue: the point value, and the tiers for the membership popup. */
  facts?: PopupFacts
  lang: string
  onClose: () => void
  onButton: (destination: string) => void
  preview?: boolean
  phone?: boolean
  closeLabel: string
}

function Picture({ popup, phone, className }: { popup: ViewProps['popup']; phone?: boolean; className: string }) {
  const desktop = popup.desktopImage
  const mobile = popup.mobileImage
  const shown = phone && mobile ? mobile : desktop
  if (!shown) return null
  return (
    <picture>
      {/* The mobile image, where there is one, on a phone-width screen. */}
      {!phone && mobile && <source media="(max-width: 640px)" srcSet={popupImageUrl(mobile.url)} />}
      <img src={popupImageUrl(shown.url)} width={shown.width} height={shown.height} alt=""
           className={className} loading="eager" decoding="async" />
    </picture>
  )
}

/** A line of copy as paragraphs: a blank line in the text starts a new one. */
function Paragraphs({ text, className }: { text: string; className: string }) {
  return <>{text.split(/\n\s*\n/).map((p, i) => <p key={i} className={className}>{p.trim()}</p>)}</>
}

/**
 * The membership popup's tier ladder, from the catalogue. Percentages only while tier
 * discounts are switched on, and only for a tier that has one.
 */
function Tiers({ facts }: { facts?: PopupFacts }) {
  if (!facts?.tiers?.length) return null
  return (
    <ul className="flex flex-wrap gap-1.5 pt-1" data-testid="popup-tiers">
      {facts.tiers.map((t) => (
        <li key={t.name} className="rounded-full border border-ink-400 px-2.5 py-1 text-[12px] text-chalk">
          {t.name}{t.percent && <span className="ml-1 font-semibold text-gold-400">{t.percent}</span>}
        </li>
      ))}
    </ul>
  )
}

function Words({ popup, lang, onButton, compact, titleId, facts }: Pick<ViewProps, 'popup' | 'lang' | 'onButton' | 'facts'> & {
  compact?: boolean
  /** The id the dialog is labelled by: the visible headline itself. */
  titleId?: string
}) {
  const raw = localized(popup, lang)
  const tokens = { pointValue: facts?.pointValue }
  const w = { ...raw, body: fillTokens(raw.body, tokens), referral: fillTokens(raw.referral, tokens),
    trust: fillTokens(raw.trust, tokens) }
  return (
    <div className={compact ? 'space-y-1.5' : 'space-y-2.5'}>
      {w.headline && (
        <p id={titleId} className={`font-semibold text-chalk ${compact ? 'text-[14px] leading-snug' : 'text-[20px] leading-tight'}`}>
          {w.headline}
        </p>
      )}
      {w.body && <Paragraphs text={w.body} className={`text-chalk-muted ${compact ? 'text-[12.5px]' : 'text-[14px]'} leading-relaxed`} />}
      {popup.preset === 'MEMBERSHIP' && <Tiers facts={facts} />}
      {w.referral && <p className="text-[12.5px] font-medium text-gold-400">{w.referral}</p>}
      {w.buttons.length > 0 && (
        <div className={`flex flex-wrap gap-2 ${compact ? 'pt-1' : 'pt-2'}`}>
          {w.buttons.map((b, i) => (
            <button key={i} type="button" onClick={() => onButton(b.destination)}
                    className={`inline-flex min-h-[40px] items-center justify-center rounded-edge px-4 text-[13px] font-semibold
                                transition-colors ${i === 0 ? 'bg-brand-500 text-white hover:bg-brand-400'
                                  : 'border border-ink-400 text-chalk hover:bg-ink-600'}`}>
              {b.label}
            </button>
          ))}
        </div>
      )}
      {w.trust && <Paragraphs text={w.trust} className="pt-1 text-[11.5px] text-chalk-faint" />}
    </div>
  )
}

/**
 * The centred modal: a dim overlay, X at the top right, Esc closes, and keyboard focus
 * stays inside while it is open. Never wider than the screen less a 16px gutter each side,
 * and scrolls inside itself rather than the page sideways.
 */
export function PopupModal({ popup, lang, onClose, onButton, preview, phone, closeLabel, facts }: ViewProps) {
  const dialog = useRef<HTMLDivElement>(null)
  const titleId = useId()
  const w = localized(popup, lang)

  useEffect(() => {
    if (preview) return
    const previous = document.activeElement as HTMLElement | null
    const node = dialog.current
    node?.querySelector<HTMLElement>('[data-popup-close]')?.focus()
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
        return
      }
      if (e.key !== 'Tab' || !node) return
      const focusable = Array.from(node.querySelectorAll<HTMLElement>('button, a[href], [tabindex]:not([tabindex="-1"])'))
      if (focusable.length === 0) return
      const first = focusable[0]!
      const last = focusable[focusable.length - 1]!
      if (e.shiftKey && (document.activeElement === first || !node.contains(document.activeElement))) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && (document.activeElement === last || !node.contains(document.activeElement))) {
        e.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = overflow
      previous?.focus?.()
    }
  }, [preview, onClose])

  return (
    <div className={`${preview ? 'absolute' : 'fixed'} inset-0 z-[70] flex items-center justify-center bg-black/70 p-4`}
         onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }} data-testid="popup-overlay">
      <div ref={dialog} role="dialog" aria-modal="true" aria-labelledby={w.headline ? titleId : undefined}
           aria-label={w.headline ? undefined : 'Offer'} data-testid="popup-modal"
           className={`relative w-full overflow-y-auto overflow-x-hidden rounded-panel border border-ink-400 bg-ink-700
                       shadow-e3 ${phone ? 'max-w-[358px]' : 'max-w-[520px]'} max-h-[90dvh]`}>
        <button type="button" onClick={onClose} aria-label={closeLabel} data-popup-close
                className="absolute right-2.5 top-2.5 z-10 grid h-9 w-9 place-items-center rounded-full bg-black/55
                           text-white hover:bg-black/75 focus:outline-none focus:ring-2 focus:ring-brand-400">
          <CloseMark size={20} />
        </button>
        <Picture popup={popup} phone={phone} className="block h-auto w-full max-w-full" />
        <div className="p-5 sm:p-6">
          <Words popup={popup} lang={lang} onButton={onButton} titleId={titleId} facts={facts} />
        </div>
      </div>
    </div>
  )
}

/**
 * The bottom-right card: compact, with an X. The host decides where it sits -- above the
 * chat button and any sticky checkout bar -- and keeps it hidden while it would cover a
 * checkout control. On a phone it drops the picture to stay small.
 */
export function PopupCard({ popup, lang, onClose, onButton, preview, phone, closeLabel, facts, bottom = 96, hidden }: ViewProps & {
  bottom?: number
  hidden?: boolean
}) {
  return (
    <div role="dialog" aria-modal="false" aria-label={localized(popup, lang).headline ?? 'Offer'}
         aria-hidden={hidden || undefined} data-testid="popup-card" data-popup-card=""
         style={preview ? undefined : { bottom }}
         className={`${preview ? 'absolute bottom-4' : 'fixed'} right-4 z-40 w-[min(340px,calc(100%-32px))]
                     overflow-hidden rounded-panel border border-ink-400 bg-ink-700 shadow-e3
                     ${hidden ? 'invisible pointer-events-none' : ''}`}>
      <button type="button" onClick={onClose} aria-label={closeLabel} data-popup-close
              className="absolute right-2 top-2 z-10 grid h-8 w-8 place-items-center rounded-full bg-black/55 text-white
                         hover:bg-black/75 focus:outline-none focus:ring-2 focus:ring-brand-400">
        <CloseMark size={16} />
      </button>
      {!phone && <Picture popup={popup} className="hidden h-auto max-h-[150px] w-full object-cover sm:block" />}
      <div className="p-4 pr-10">
        <Words popup={popup} lang={lang} onButton={onButton} compact facts={facts} />
      </div>
    </div>
  )
}

/** Drawn here rather than imported: an icon package in the main bundle costs every page. */
function CloseMark({ size }: { size: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.25"
         strokeLinecap="round" aria-hidden="true">
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  )
}
