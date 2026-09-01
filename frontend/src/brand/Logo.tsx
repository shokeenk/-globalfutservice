import { useState } from 'react'
import { usePointerAura } from '../motion'

/**
 * The brand mark.
 *
 * <p>Drawn as an SVG rather than shipped as a PNG so it stays sharp on every
 * display, recolours with the theme, costs about a kilobyte, and needs no network
 * request on first paint.
 *
 * <p>It is a reading of the client's badge — a red circular crest with the company
 * name around it — rebuilt as a hexagonal shield inside a ring, which is the shape
 * language esports brands use.
 *
 * <p>The real artwork has since arrived and {@link BrandBadge} renders it
 * everywhere the logo appears. This survives as the offline fallback: it is pure
 * SVG, already in the bundle, and needs no network — so it is what shows if the
 * badge image ever fails to load.
 */

type MarkProps = {
  size?: number
  className?: string
}

export function Mark({ size = 40, className = '' }: MarkProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 64 64"
      className={className}
      role="img"
      aria-label="Global FUT Services"
    >
      <defs>
        <linearGradient id="gfs-badge" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#C1281B" />
          <stop offset="0.48" stopColor="#AE2418" />
          <stop offset="1" stopColor="#9A2016" />
        </linearGradient>
        <radialGradient id="gfs-sheen" cx="30%" cy="22%" r="72%">
          <stop offset="0" stopColor="#FFFFFF" stopOpacity="0.32" />
          <stop offset="1" stopColor="#FFFFFF" stopOpacity="0" />
        </radialGradient>
      </defs>

      <circle cx="32" cy="32" r="30" fill="url(#gfs-badge)" />
      <circle cx="32" cy="32" r="30" fill="url(#gfs-sheen)" />
      <circle cx="32" cy="32" r="24.5" fill="none" stroke="#FFFFFF" strokeOpacity="0.85" strokeWidth="1.4" />

      {/* Shield */}
      <path
        d="M32 15.5 L45.5 22.2 L43 39.6 L32 47.6 L21 39.6 L18.5 22.2 Z"
        fill="none"
        stroke="#FFFFFF"
        strokeWidth="2.3"
        strokeLinejoin="round"
      />
      {/* Pentagon panel — the ball, abstracted */}
      <path d="M32 24.6 L37.9 28.5 L35.7 35.3 L28.3 35.3 L26.1 28.5 Z" fill="#FFFFFF" />
    </svg>
  )
}

type LogoProps = {
  /**
   * 'full' is the badge and wordmark together — the header lockup.
   * 'mark' is the badge alone.
   * 'wordmark' is the type alone, for somewhere the crest is already on screen
   * at a larger size and repeating it would just be the same mark twice.
   */
  variant?: 'full' | 'mark' | 'wordmark'
  size?: number
  className?: string
}

export function Logo({ variant = 'full', size = 40, className = '' }: LogoProps) {
  if (variant === 'mark') {
    return <BrandBadge size={size} className={className} priority small />
  }
  if (variant === 'wordmark') {
    return (
      <span className={`inline-flex items-center ${className}`}>
        <Wordmark />
      </span>
    )
  }
  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      {/*
        The real crest, not the SVG reading below it.

        At this size the ring's inner type is texture rather than words — which is
        exactly what a lockup wants from a mark. The name is carried by the
        wordmark beside it, so nothing depends on being able to read the badge,
        and the emblem does the job emblems do: recognition at a glance.
      */}
      <BrandBadge size={size} priority small />
      <Wordmark large />
    </span>
  )
}

/**
 * The name, set as type. Extracted so the two variants cannot drift apart.
 *
 * <p>`large` is the header / mobile-nav lockup size, paired with the enlarged
 * badge. The default (unflagged) size is the footer's `wordmark` variant, which
 * sits under a full-size crest and must not grow with it.
 */
function Wordmark({ large = false }: { large?: boolean }) {
  /*
   * One line, not two.
   *
   * Stacked, the lockup was as tall as the badge beside it and set the height of the
   * whole bar — the header could not get shorter than the wordmark. On one line it
   * is a single cap-height, so the bar is free to be whatever the controls need.
   *
   * The two-tone stays: the name in ink, the word that says what the business does in
   * red. `whitespace-nowrap` because "GLOBAL FUT SERVICES" breaking across two lines
   * on a narrow header is the exact thing this change is undoing.
   */
  return (
    <span
      /*
        Smaller on a phone. One line is wider than two by definition, and at 19px the
        lockup plus the burger cluster came to two pixels more than a 375px viewport —
        enough for the page to scroll sideways, which is the one thing it must never
        do. It steps up at `sm`, where there is room.
      */
      className={`display whitespace-nowrap leading-none text-chalk ${
        large ? 'text-[15.5px] sm:text-[19px]' : 'text-[13px]'
      }`}
    >
      GLOBAL FUT{' '}
      <span
        className={`font-semibold uppercase text-brand-400 ${
          large
            ? 'text-[10.5px] tracking-[0.14em] sm:text-[13px] sm:tracking-[0.16em]'
            : 'text-[9.5px] tracking-[0.14em]'
        }`}
      >
        Services
      </span>
    </span>
  )
}

/**
 * The real badge, as supplied by the client.
 *
 * <p>This is the actual artwork rather than the SVG reading above — the red ring
 * with GLOBAL FUT SERVICES set inside it. The file shipped as red ink printed on a
 * photographed sheet of crumpled white paper. The photograph is gone; the paper is
 * not, and that turns out to matter.
 *
 * <p><b>The mark keeps its light ground.</b> Dropping the paper and keeping the ink
 * left red-on-transparent, which on a near-black page is a red mark — thin red rings
 * and red type on black, at a contrast the logo was never drawn for. The badge was
 * designed as red ink on white and only reads as itself that way, so the paper comes
 * back as a plate: a near-white disc under the artwork, shaded very slightly from
 * centre to edge the way the photographed sheet was. On the dark page it reads as an
 * enamel pin sitting on the surface, which is the right object for a crest.
 *
 * <p><b>Two source files, chosen by `small`.</b> The 512px asset is right for the
 * footer and the auth screen; asking a 38px header slot to download it means 80KB
 * on every page for something the width of a fingernail. `small` swaps in a 128px
 * copy, which covers 38px even at 3x.
 *
 * <p>Used small, the ring's inner type becomes texture rather than words. That is
 * fine where a wordmark sits beside it — an emblem is for recognition, not for
 * reading — and it is why the header pairs the two.
 *
 * <p>Width and height are set explicitly so the browser reserves the box before
 * the image arrives. Without them a 512px PNG landing late shoves everything
 * below it down the page, which is the single most common cause of a bad
 * Cumulative Layout Shift score.
 *
 * <p>If the image cannot load, {@link Mark} renders in its place. That is not
 * defensive padding: this component is now in the header, on every page, and a
 * broken-image glyph where the logo should be is the single worst thing a visitor
 * can see first. The SVG needs no network and is already in the bundle.
 */
export function BrandBadge({
  size = 104,
  className = '',
  priority = false,
  small = false,
  aura = false,
  plated = false,
}: {
  size?: number
  className?: string
  /** Set on anything above the fold, so it is not deferred behind the viewport. */
  priority?: boolean
  /** Use the 128px source. Correct for anything rendered under ~48px. */
  small?: boolean
  /**
   * Light a colour ring around the plate and let it follow the pointer.
   *
   * <p>Off by default. The effect earns its place where the badge is the object you
   * are looking at — the footer crest, the sign-in screen — and would be noise on a
   * 38px mark in the header that you are trying to click past.
   */
  aura?: boolean
  /**
   * Put a light disc behind the mark.
   *
   * <p>`.brand-plate` is transparent, which is right on a light page — the mark is
   * red ink and the page is the paper. On a saturated ground that same transparency
   * leaves red ink on a coloured field, and on the red section it left red on red:
   * a logo that was technically present and completely invisible. This gives it back
   * the paper it was drawn for.
   */
  plated?: boolean
}) {
  const [failed, setFailed] = useState(false)
  const auraRef = usePointerAura<HTMLSpanElement>(360)

  if (failed) return <Mark size={size} className={className} />

  const plate = (
    <span
      className={`brand-plate relative inline-grid shrink-0 place-items-center rounded-full${
        plated ? ' bg-paper shadow-e2' : ''
      }`}
      style={{ width: size, height: size }}
    >
      <img
        src={small ? '/brand/gfs-mark-128.png' : '/brand/gfs-mark.png'}
        width={plated ? Math.round(size * 0.84) : size}
        height={plated ? Math.round(size * 0.84) : size}
        alt="Global FUT Services"
        loading={priority ? 'eager' : 'lazy'}
        decoding="async"
        onError={() => setFailed(true)}
        className="block"
        style={{ width: plated ? Math.round(size * 0.84) : size, height: plated ? Math.round(size * 0.84) : size }}
      />
    </span>
  )

  if (!aura) {
    return <span className={`inline-flex ${className}`}>{plate}</span>
  }

  /*
   * The ring lives on a wrapper rather than on the plate itself.
   *
   * Both layers of the aura are pseudo-elements, and a pseudo-element paints above
   * its own host's background — so hung off the plate, the blurred spill would wash
   * over the white disc it is supposed to be lighting from behind. Pushing it back
   * with `z-index: -1` does not fix that; it drops the glow behind the *page* instead.
   * A wrapper gives the two layers somewhere to sit that is genuinely underneath.
   */
  return (
    <span
      ref={auraRef}
      className={`brand-aura relative inline-flex ${className}`}
      style={{ width: size, height: size }}
    >
      {plate}
    </span>
  )
}
