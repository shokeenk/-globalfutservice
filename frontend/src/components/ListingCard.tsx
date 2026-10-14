import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Badge } from './ui'

/**
 * One card on the Boosting page: a Champs tier, a Rivals climb, an Objective or a
 * Tournament. Every category uses this one card, so a listing the team writes in Service
 * Listings looks like it belongs next to the tiers rather than like a page of its own.
 *
 * <p><b>The whole card is one link.</b> It was once a card holding a small button, which
 * put the 44px target inside a 200px card the customer had already aimed at. Making the
 * card itself the control removes the aiming, and the arrow just indicates direction.
 *
 * <p>Two kinds of art. A tier wears its rank crest in the corner as a decoration. A listing
 * shows the PNG the team uploaded beside its title, scaled to fit its box whole -- never
 * stretched, never cropped -- whatever proportions it was drawn at.
 */
export function ListingCard({
  to, title, subtitle, price, best = false, bestLabel, accent, corner, artwork, footnote, chooseLabel,
}: {
  to: string
  title: string
  subtitle?: string | null
  /** Already formatted, in the currency being shown. */
  price: string
  /** The Best Value tier: the warm ground and the gold tag. */
  best?: boolean
  bestLabel?: string
  /** A 3px hairline along the top edge, as a class, or nothing. */
  accent?: string | null
  /** A decoration for the top-right corner: a rank crest, or the tier's number. */
  corner?: ReactNode
  /** The listing's own artwork, beside the title. */
  artwork?: { src: string; width?: number | null; height?: number | null } | null
  /** A line between the title and the price, such as a success rate. */
  footnote?: ReactNode
  chooseLabel: string
}) {
  return (
    <Link
      to={to}
      className={[
        'group relative flex h-full flex-col overflow-hidden rounded-panel border p-6',
        'transition-[transform,border-color,background-color,box-shadow] duration-500',
        /*
          A shadow at rest, not only on hover. The card lifted when you pointed at it and
          lay perfectly flat otherwise, so the whole grid read as painted onto the page
          until you touched it -- and on a phone, where there is no hover, it never lifted.
        */
        'ease-out-expo shadow-e2 hover:-translate-y-1.5 hover:shadow-e3',
        // Opaque grounds: the translucent ones composited to within a percent of the page
        // and left the border doing all the work.
        best
          ? 'border-brand-500/50 bg-[#FDF3F2] hover:border-brand-500'
          : 'border-ink-400 bg-paper hover:border-ink-300 hover:bg-gray-50',
      ].join(' ')}
    >
      {accent && <span aria-hidden="true" className={`absolute inset-x-0 top-0 h-[3px] ${accent}`} />}

      {corner}

      {best && bestLabel && (
        <Badge tone="gold" className="relative mb-3 self-start">
          {bestLabel}
        </Badge>
      )}

      {artwork ? (
        <div className="relative flex items-start gap-3">
          <div className="min-w-0 flex-1">
            <p className="display text-display-sm text-chalk">{title}</p>
            {subtitle && <p className="mt-1.5 text-[13px] leading-snug text-chalk-muted">{subtitle}</p>}
          </div>
          {/*
            A fixed box and `object-contain`: a tall card portrait and a square badge both fit
            whole, at their own proportions, and the text column never moves.
          */}
          <span className="grid h-[132px] w-[104px] shrink-0 place-items-center" data-testid="listing-artwork">
            <img
              src={artwork.src}
              width={artwork.width ?? undefined}
              height={artwork.height ?? undefined}
              alt=""
              aria-hidden="true"
              loading="lazy"
              decoding="async"
              className="h-full w-full object-contain drop-shadow-md transition-transform duration-500
                         ease-out-expo group-hover:-translate-y-0.5 group-hover:scale-[1.03]"
            />
          </span>
        </div>
      ) : (
        <>
          <p className="display relative text-display-sm text-chalk">{title}</p>
          {subtitle && <p className="relative mt-1.5 text-[13px] leading-snug text-chalk-muted">{subtitle}</p>}
        </>
      )}

      {footnote}

      <p className={`tnum display relative text-display-md text-brand-400 ${footnote ? 'mt-1' : 'mt-auto pt-6'}`}>
        {price}
      </p>

      <span className="relative mt-4 inline-flex items-center gap-2 border-t border-ink-400 pt-4 text-[13px] font-semibold text-chalk">
        {chooseLabel}
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"
             strokeLinejoin="round" aria-hidden="true"
             className="text-brand-400 transition-transform duration-300 ease-out-expo group-hover:translate-x-1.5">
          <path d="M5 12h14" />
          <path d="m12 5 7 7-7 7" />
        </svg>
      </span>
    </Link>
  )
}
