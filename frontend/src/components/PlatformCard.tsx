import { PlatformBadge, platformAccent } from './PlatformIcon'

/**
 * A platform choice, as its own control rather than a generic select tile.
 *
 * <p>It earns the specialisation: this is the first decision on the checkout and
 * the one a returning customer makes without reading, so it is the place where a
 * few hundred bytes of interaction buy the most.
 *
 * <p>Three states, each carrying meaning in more than one channel — which is both
 * better design and WCAG 1.4.1, since a border colour alone is invisible to a
 * meaningful share of players:
 *
 * <ul>
 *   <li><b>Rest</b> — hairline, dim disc.</li>
 *   <li><b>Hover</b> — lifts 2px, border warms toward the platform hue, and a
 *       faint wash of that hue rises from the bottom edge. All transform and
 *       colour, so it runs on the compositor.</li>
 *   <li><b>Selected</b> — the platform's own colour on the border and a coloured
 *       glow beneath, the disc scales up, and a tick scales in on a spring. The
 *       glow is what makes the choice read as <i>lit</i> rather than outlined.</li>
 * </ul>
 *
 * <p>No library. The whole thing is CSS transitions on a real `<button>` with
 * `aria-pressed`, which keeps it keyboard-operable and screen-reader-correct for
 * free — a div with an onClick would have needed both rebuilt by hand.
 */
export function PlatformCard({
  platform,
  label,
  price,
  taxNote,
  active,
  onSelect,
}: {
  platform: string | null | undefined
  label: string
  /** Rendered small under the label — the per-million rate. */
  price: string
  /** e.g. "EA 5% tax included". Omitted where EA takes no cut. */
  taxNote?: string
  active: boolean
  onSelect: () => void
}) {
  const accent = platformAccent(platform)

  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={active}
      className={[
        'group relative overflow-hidden rounded-panel border p-4 text-left',
        'transition-[transform,border-color,background-color] duration-300 ease-out-expo',
        'active:scale-[0.98] active:duration-75',
        active
          ? `${accent.border} bg-paper`
          : 'border-ink-400 bg-paper hover:-translate-y-0.5 hover:border-ink-300',
      ].join(' ')}
      style={active ? { boxShadow: accent.glow } : undefined}
    >
      {/*
        A wash of the platform's colour rising from the bottom edge.

        One blurred block, faded in by opacity alone — nothing here can shift the
        layout or leave the compositor. Full strength when selected, softer on
        hover, so the two states stay distinguishable from each other rather than
        both just "lit".
      */}
      <span
        aria-hidden="true"
        className={[
          'pointer-events-none absolute inset-x-0 -bottom-9 h-24 blur-2xl',
          'transition-opacity duration-400 ease-out-expo',
          accent.wash,
          active ? 'opacity-100' : 'opacity-0 group-hover:opacity-55',
        ].join(' ')}
      />

      <span className="relative flex items-center gap-3">
        <PlatformBadge platform={platform} active={active} />
        <span className="min-w-0">
          <span className="block truncate text-sm font-semibold text-chalk">{label}</span>
          <span className="tnum mt-0.5 block text-[12.5px] text-chalk-muted">{price}</span>
          {/*
            Under every price, not just the total.

            EA's cut is the one thing that separates this listing from a competitor's
            identical-looking per-million figure, and a customer comparing two tabs is
            looking at exactly this number. Saying it once at checkout is too late to
            win that comparison.
          */}
          {taxNote && (
            <span className="mt-1 block text-[11px] font-semibold leading-tight text-ok">
              {taxNote}
            </span>
          )}
        </span>
      </span>

      {/*
        The tick. Scaled in on a spring rather than faded, so selection lands with
        a bit of physicality — and it is the second, non-colour channel that says
        "this one", which is the part that matters for anyone who cannot separate
        the three hues.
      */}
      <span
        aria-hidden="true"
        className={[
          'absolute right-3 top-3 grid h-5 w-5 place-items-center rounded-full',
          'transition-transform duration-300 ease-spring',
          active ? 'scale-100' : 'scale-0',
          accent.text,
        ].join(' ')}
        style={{ backgroundColor: 'currentColor' }}
      >
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#111114"
             strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M20 6 9 17l-5-5" />
        </svg>
      </span>
    </button>
  )
}
