import { FaXbox } from 'react-icons/fa6'
import { SiPlaystation } from 'react-icons/si'

/**
 * Platform marks for the checkouts.
 *
 * <p><b>The official logos, at the owner's instruction.</b> These were hand-drawn
 * controller glyphs for a while, because the PlayStation wordmark and the Xbox sphere are
 * Sony and Microsoft trademarks with published usage rules and approximating them by hand
 * breaks every one of those rules. The owner asked for the real marks; they now come from
 * react-icons, which ships the vector artwork rather than a redrawing of it, so at least
 * the shapes are right.
 *
 * <p>They are trademarks of their owners and this business is not affiliated with either —
 * the footer says so on every page. Used here only to label which platform a customer is
 * buying for, never as a badge of endorsement.
 *
 * <p>PC keeps its monitor: there is no PC trademark to show, and a monitor needs no
 * explanation. It is still stroked in the site's own weight, which is why it is drawn here
 * rather than imported.
 *
 * <p><b>The API is unchanged.</b> Same props, same default size, same {@code currentColor}
 * — the brand marks are solid fills where the monitor is a stroke, so a selected tile's
 * colour carries through either way without a second set of rules.
 */
export function PlatformIcon({
  platform,
  size = 22,
  className = '',
}: {
  /** `PC`, `PLAYSTATION` or `XBOX`, as the catalogue sends it. */
  platform: string | null | undefined
  size?: number
  className?: string
}) {
  const key = (platform ?? '').toUpperCase()
  /*
   * The brand's own colour by default, everywhere the mark appears -- a card, a cart
   * line, a summary row. `className` still wins where a caller passes its own text
   * colour, because it comes last and Tailwind emits both at the same specificity.
   */
  const tone = `${platformAccent(platform).text} ${className}`.trim()

  if (key === 'PLAYSTATION') {
    return <SiPlaystation size={size} className={tone} aria-hidden="true" />
  }

  if (key === 'XBOX') {
    // Simple Icons has no Xbox mark; Font Awesome's is the same sphere.
    return <FaXbox size={size} className={tone} aria-hidden="true" />
  }

  if (key === 'PC') {
    return (
      <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={1.7}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className={tone}
      >
        {/* Monitor and stand. The one platform that needs no disambiguation. */}
        <rect x="2.5" y="4" width="19" height="12.5" rx="1.6" />
        <path d="M12 16.5v3" />
        <path d="M8 19.5h8" />
      </svg>
    )
  }

  // Unknown platform: render nothing rather than a wrong icon. A missing glyph is
  // a gap; the wrong glyph is a lie about what the customer is buying.
  return null
}

/**
 * The colour each platform owns, as Tailwind classes.
 *
 * <p>Returned as a set rather than a single hue because the same identity has to
 * appear as an icon colour, a tinted disc behind it, a border on the selected
 * card and a glow — and if those four are assembled ad hoc at each call site
 * they drift. One function, one source of truth per platform.
 *
 * <p>The glow is a raw rgba rather than a token because Tailwind cannot build an
 * arbitrary-alpha box-shadow from a colour name, and a coloured shadow is the
 * one thing that makes a selected card read as lit rather than merely outlined.
 */
export function platformAccent(platform: string | null | undefined): {
  text: string
  disc: string
  /** A soft ground for the hover/selected wash behind the card. */
  wash: string
  border: string
  glow: string
} {
  switch ((platform ?? '').toUpperCase()) {
    case 'PC':
      return {
        text: 'text-platform-pc',
        disc: 'bg-platform-pc/[0.14] ring-platform-pc/25',
        wash: 'bg-platform-pc/25',
        border: 'border-platform-pc',
        glow: '0 0 0 1px rgb(29 22 54 / 0.5), 0 14px 34px -14px rgb(29 22 54 / 0.55)',
      }
    case 'PLAYSTATION':
      return {
        text: 'text-platform-ps',
        disc: 'bg-platform-ps/[0.14] ring-platform-ps/25',
        wash: 'bg-platform-ps/25',
        border: 'border-platform-ps',
        glow: '0 0 0 1px rgb(0 112 209 / 0.5), 0 14px 34px -14px rgb(0 112 209 / 0.55)',
      }
    case 'XBOX':
      return {
        text: 'text-platform-xbox',
        disc: 'bg-platform-xbox/[0.14] ring-platform-xbox/25',
        wash: 'bg-platform-xbox/25',
        border: 'border-platform-xbox',
        glow: '0 0 0 1px rgb(16 124 16 / 0.5), 0 14px 34px -14px rgb(16 124 16 / 0.55)',
      }
    default:
      return {
        text: 'text-chalk-muted',
        disc: 'bg-ink-500 ring-ink-300',
        wash: 'bg-ink-400/40',
        border: 'border-ink-300',
        glow: 'none',
      }
  }
}

/**
 * The icon inside its tinted disc.
 *
 * <p>The disc is what makes three glyphs distinguishable at a glance rather than
 * on inspection — colour resolves before shape does, so a returning customer
 * finds their platform by hue and never reads the label at all.
 */
export function PlatformBadge({
  platform,
  size = 38,
  active = false,
}: {
  platform: string | null | undefined
  size?: number
  active?: boolean
}) {
  const accent = platformAccent(platform)
  return (
    <span
      aria-hidden="true"
      className={[
        'grid shrink-0 place-items-center rounded-full ring-1 ring-inset',
        'transition-transform duration-300 ease-out-expo',
        accent.disc,
        accent.text,
        // A small lift on selection. Scale rather than size so it stays on the
        // compositor and never reflows the row of cards.
        active ? 'scale-110' : 'scale-100',
      ].join(' ')}
      style={{ width: size, height: size }}
    >
      <PlatformIcon platform={platform} size={Math.round(size * 0.58)} />
    </span>
  )
}
