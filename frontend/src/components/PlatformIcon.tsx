/**
 * Platform glyphs for the coin configurator.
 *
 * <p><b>These are not the official logos, deliberately.</b> The PlayStation
 * wordmark and the Xbox sphere are Sony and Microsoft trademarks with published
 * usage rules — no recolouring, no redrawing, minimum clear space. Approximating
 * them by hand in path data would break every one of those rules and produce a
 * worse drawing than the real asset, on a site that already has one rights
 * question hanging over it.
 *
 * <p>What actually separates the two consoles at a glance is not the logo, it is
 * the <b>stick layout</b>: PlayStation puts both sticks low and symmetrical,
 * Xbox offsets them diagonally. That is industrial design rather than a
 * trademark, it survives being drawn at 20px, and any player reads it instantly.
 * PC gets a monitor, which needs no explanation at all.
 *
 * <p>If the official artwork is wanted, it should come from Sony's and
 * Microsoft's partner brand portals as supplied files — not from me redrawing
 * them. Swapping these for real assets is a one-component change.
 *
 * <p>Everything is stroked in `currentColor` at the same 1.7 weight the rest of
 * the site's icons use, so a selected tile's colour carries through without a
 * second set of rules.
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

  const common = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.7,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    className,
  }

  if (key === 'PC') {
    return (
      <svg {...common}>
        {/* Monitor and stand. The one platform that needs no disambiguation. */}
        <rect x="2.5" y="4" width="19" height="12.5" rx="1.6" />
        <path d="M12 16.5v3" />
        <path d="M8 19.5h8" />
      </svg>
    )
  }

  if (key === 'PLAYSTATION') {
    return (
      <svg {...common}>
        <GamepadBody />
        {/* D-pad, left. */}
        <path d="M7.4 12.2h2.2M8.5 11.1v2.2" />
        {/* Face buttons, right. */}
        <circle cx="15.6" cy="11.2" r="0.7" fill="currentColor" stroke="none" />
        <circle cx="17.1" cy="12.4" r="0.7" fill="currentColor" stroke="none" />
        {/*
          Both sticks low and side by side — the PlayStation arrangement, and the
          whole reason this glyph is distinguishable from the next one.
        */}
        <circle cx="10.6" cy="14.4" r="1.15" />
        <circle cx="13.9" cy="14.4" r="1.15" />
      </svg>
    )
  }

  if (key === 'XBOX') {
    return (
      <svg {...common}>
        <GamepadBody />
        {/* Left stick high, right stick low: the Xbox diagonal offset. */}
        <circle cx="8.6" cy="11.6" r="1.15" />
        <circle cx="14.6" cy="14.3" r="1.15" />
        {/* D-pad sits under the left stick. */}
        <path d="M10.6 14.3h1.8M11.5 13.4v1.8" />
        {/* Face buttons, upper right. */}
        <circle cx="15.9" cy="11.2" r="0.7" fill="currentColor" stroke="none" />
        <circle cx="17.4" cy="12.3" r="0.7" fill="currentColor" stroke="none" />
      </svg>
    )
  }

  // Unknown platform: render nothing rather than a wrong icon. A missing glyph is
  // a gap; the wrong glyph is a lie about what the customer is buying.
  return null
}

/**
 * The shared controller outline.
 *
 * <p>Identical for both consoles on purpose — the pads really are close to the
 * same silhouette, and inventing a difference here would just make both glyphs
 * slightly wrong. The stick positions carry the distinction.
 */
function GamepadBody() {
  return (
    <path
      d="M8.4 8.4h7.2c2.4 0 4.4 2.1 4.4 4.8 0 2.2-.9 3.9-2.3 3.9-1.3 0-1.8-1.6-3.2-1.6H9.5
         c-1.4 0-1.9 1.6-3.2 1.6-1.4 0-2.3-1.7-2.3-3.9 0-2.7 2-4.8 4.4-4.8Z"
    />
  )
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
        glow: '0 0 0 1px rgb(174 36 24 / 0.5), 0 14px 34px -14px rgb(174 36 24 / 0.55)',
      }
    case 'XBOX':
      return {
        text: 'text-platform-xbox',
        disc: 'bg-platform-xbox/[0.14] ring-platform-xbox/25',
        wash: 'bg-platform-xbox/25',
        border: 'border-platform-xbox',
        glow: '0 0 0 1px rgb(95 81 144 / 0.5), 0 14px 34px -14px rgb(95 81 144 / 0.55)',
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
