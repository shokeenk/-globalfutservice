/**
 * The FUT coin.
 *
 * <p><b>Struck in gold, as the coin actually is.</b> It spent a while in the palette's
 * red on the argument that the shape carries the meaning and the hue does not. That was
 * wrong in practice: a red disc beside a coin total reads as a status dot or a warning
 * badge, and the one thing this mark has to say instantly is "money".
 *
 * <p>The gold is {@code #FFC93C} — not a new colour, but the same yellow the trading
 * cards already wear, which is the service this coin counts. So the disc belongs to the
 * page rather than importing a metallic scheme the rest of the site abandoned, and the
 * wordmark takes {@code chalk} for the same reason it does on those cards: on a ground
 * this light, paper would vanish.
 *
 * <p>Drawn rather than shipped as a PNG. The supplied artwork is flat vector shapes —
 * three stacked discs and a wordmark — which is exactly the case where SVG wins: it is
 * about a kilobyte inline, costs no network request, stays crisp at every size, and can
 * take a size without a second asset for retina.
 *
 * <p><b>The stack is the whole idea.</b> A single flat circle reads as a token or a
 * button; three offset discs read as money, because that is what a pile of coins looks
 * like from a low angle. The offset is down and to the right so the light implied by the
 * rest of the site — which comes from the upper left, see the elevation shadows — stays
 * consistent.
 *
 * <p>The wordmark is set in the display face rather than drawn as paths. Below about
 * 28px it stops being legible and becomes texture, which is what the original does too;
 * above that it reads. Paths would have been sharper and would also have meant the coin
 * could never be relabelled.
 */
export function CoinIcon({
  size = 28,
  className = '',
  /** Hide the wordmark. Correct below ~22px, where it is noise rather than texture. */
  bare = false,
}: {
  size?: number
  className?: string
  bare?: boolean
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 100 100"
      /*
       * `select-none pointer-events-none` because the wordmark is a real <text>
       * node. Without it, selecting the amount on the order page and copying gives
       * "FUT 3M coins" — the icon smuggling itself into the clipboard. `aria-hidden`
       * already keeps it out of the accessibility tree; this keeps it out of the
       * selection too.
       */
      className={`pointer-events-none shrink-0 select-none ${className}`}
      aria-hidden="true"
      focusable="false"
    >
      {/* Deepest disc: the edge of the coin below, and the reason this reads as a stack. */}
      <ellipse cx="52" cy="56" rx="46" ry="44" fill="#9A6B00" />
      {/* Middle disc. */}
      <ellipse cx="50" cy="52" rx="46" ry="44" fill="#C89412" />
      {/*
        Face, with a rim.
        A gold disc measures 1.42:1 against this page — near-white lilac and a light
        yellow are almost the same value, so without an edge the coin dissolves into the
        background and only the wordmark survives. The darkest amber in the stack, used
        as a stroke, gives the silhouette 4.34:1 and costs nothing visually: it reads as
        the milled edge a coin has anyway.
      */}
      <ellipse cx="49" cy="48" rx="43" ry="41" fill="#FFC93C" stroke="#9A6B00" strokeWidth="3" />
      {/*
        The lit crescent along the lower left. One arc at low opacity — it is what stops
        the face reading as a flat fill, and it is the only shading in the whole mark.
      */}
      <path
        d="M11 56a38 38 0 0 0 30 33A38 38 0 0 1 14 41a38 38 0 0 0-3 15Z"
        fill="#FFE9A8"
        opacity="0.9"
      />
      {/* Inner face, a half-step down so the wordmark has a ground of its own. */}
      <ellipse cx="49" cy="46" rx="35" ry="33" fill="#F0B92E" />

      {!bare && (
        <text
          x="49"
          y="47"
          textAnchor="middle"
          dominantBaseline="central"
          /*
            Poppins has no width axis, so the three letters are fitted by size and
            tracking instead. Left at 84% it would have looked correct in the source
            and rendered full-width on the disc.
          */
          style={{
            fontFamily: 'Poppins, ui-sans-serif, system-ui, sans-serif',
            fontWeight: 800,
            fontSize: '27px',
            letterSpacing: '-0.02em',
          }}
          fill="#111114"
        >
          FUT
        </text>
      )}
    </svg>
  )
}
