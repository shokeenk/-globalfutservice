/**
 * The shields a boosting tier is actually bought for.
 *
 * <p>A tier label is accurate and completely abstract. "Division 2 to 1" and "15 wins ·
 * Elite I" describe a transaction; the shield is the thing the customer has pictured,
 * and it is what they screenshot afterwards.
 *
 * <p><b>Three kinds of artwork, one question.</b> Champs tiers show the rank they
 * reach — the crimson Champion crest at nine and ten wins, the dark Elite crest above
 * that. Rivals tiers show the division they promote you <em>into</em> — a tier called
 * "Division 5 to 4" could reasonably show either, and showing the 5 would be showing
 * somebody the badge they are paying to leave. The extra-wins add-on shows the Rivals
 * mark, because it promotes you nowhere: it tops up wins inside whatever division you
 * are already in, so a numbered shield would promise a rank the product does not sell.
 *
 * <p>Two orderings run opposite to intuition and are worth stating rather than
 * rediscovering. On the Champs side <b>more wins is a lower rank number</b> — 15 wins is
 * Elite I. On the Rivals side <b>a lower division is better</b>, so promotion counts
 * downward, 5 to 4 to 3, and out into Elite at the end.
 */

/**
 * Variant to artwork, as complete paths.
 *
 * <p>Written out rather than assembled from a prefix and a token. The first version
 * stored bare keys and built `/brand/divisions/div-${key}.png` around them, which
 * worked for the five numbered shields and silently produced `div-rivals.png` — a file
 * that does not exist — the moment a sixth entry did not follow the pattern. A literal
 * path per row cannot drift from the filename on disk.
 */
const BADGE_BY_VARIANT: Record<string, string> = {
  /*
   * Champs, in two crests that are easy to confuse.
   *
   * Champion is the crimson shield; Elite is the darker one that fades to black and
   * carries a laurel under it. Both series count *down* toward I, so `champion-2`
   * and `elite-2` are two different shields both stamped "II" — the numeral alone
   * does not identify the file, and picking by numeral is exactly how Elite V and
   * Elite II ended up wearing Champion artwork.
   */
  WINS_10: '/brand/ranks/champion-1.png', // Champion I
  WINS_9: '/brand/ranks/champion-2.png', // Champion II

  // Elite. Rank 1 is the best, and takes the most wins.
  WINS_15: '/brand/ranks/elite-1.png', // Rank 1 · Elite I
  WINS_14: '/brand/ranks/elite-2.png', // Rank 2 · Elite II
  WINS_13: '/brand/ranks/elite-3.png', // Rank 3 · Elite III
  WINS_12: '/brand/ranks/elite-4.png', // Rank 4 · Elite IV
  WINS_11: '/brand/ranks/elite-5.png', // Rank 5 · Elite V

  // Rivals — the division you arrive in, not the one you start from.
  DIV_5_TO_4: '/brand/divisions/div-4.png',
  DIV_4_TO_3: '/brand/divisions/div-3.png',
  DIV_3_TO_2: '/brand/divisions/div-2.png',
  DIV_2_TO_1: '/brand/divisions/div-1.png',
  // The division above Division 1 has no number, which is rather the point of it.
  DIV_1_TO_ELITE: '/brand/divisions/div-elite.png',

  // The add-on, which changes your win count and not your division.
  WINS_EXTRA_8: '/brand/divisions/rivals.png',
}

/**
 * The image a variant should show, or null where there is none.
 *
 * <p>Every tier the storefront currently sells has artwork, so in practice this
 * returns a path. It still returns null rather than a placeholder for anything it
 * does not know: borrowing a neighbouring shield to fill the space would mislead —
 * somebody buying "Champion I" would be shown a badge they are not going to get.
 * Callers fall back to the tier's ordinal, which claims nothing.
 */
export function badgeSrcFor(variant: string | null | undefined): string | null {
  if (!variant) return null
  return BADGE_BY_VARIANT[variant] ?? null
}

/** True when a variant has artwork. */
export function hasBadge(variant: string | null | undefined): boolean {
  return badgeSrcFor(variant) !== null
}

export function RankBadge({
  variant,
  size = 72,
  className = '',
}: {
  variant: string | null | undefined
  size?: number
  className?: string
}) {
  const src = badgeSrcFor(variant)
  if (src === null) return null

  return (
    <img
      src={src}
      width={size}
      height={size}
      /*
       * Decorative. The tier name sits directly beside it and says "Elite I" or
       * "Division 2 to 1" in words, so a screen reader announcing the shield as well
       * would read the same thing twice. An empty alt is the correct answer when an
       * image restates its own label.
       */
      alt=""
      aria-hidden="true"
      loading="lazy"
      decoding="async"
      className={`select-none ${className}`}
      style={{ width: size, height: size }}
    />
  )
}

/**
 * The colour a tier's card should echo, taken from the crest it carries.
 *
 * <p>The badge artwork already carries the only colour these cards have — gold for the
 * Champion crests, a deeper maroon for the Elite shields — while the card behind it was
 * a plain white box the badge floated on top of. Keying a hairline on the card's top
 * edge to the same family makes the card and its crest read as one object.
 *
 * <p>Returns a Tailwind class rather than a hex so the value stays in the palette and
 * cannot drift; `null` for the tiers with no crest, which keep the neutral hairline
 * they already had rather than inventing a third colour for them.
 */
export function tierAccent(variant: string | null | undefined): string | null {
  if (!variant) return null
  if (variant === 'WINS_10' || variant === 'WINS_9') return 'bg-gold-500'
  if (/^WINS_1[1-5]$/.test(variant)) return 'bg-brand-700'
  return null
}
