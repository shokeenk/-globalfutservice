import type { ReactNode } from 'react'

/**
 * The brand ring, measured off the artwork rather than eyeballed.
 *
 * <p>The logo is a hairline circle broken in four places, and those breaks are not
 * decorative — they are the single most recognisable thing about the mark at small
 * sizes, where the lettering inside has already dissolved into texture. Pulling that
 * geometry out into a primitive means the site can carry the brand in places a logo
 * would be absurd: around a step number, around a coach's initials, around a spinner.
 *
 * <p><b>The numbers come from the file.</b> Sampling `gfs-mark.png` at one-tenth of a
 * degree gives an outer radius of 45.9% of the canvas, a stroke of 1.28% of the
 * diameter, and four gaps of roughly 2.6 degrees centred at 35, 145, 215 and 325
 * degrees clockwise from twelve. Those four sit at ±35 degrees from the vertical axis,
 * top and bottom — a symmetry worth preserving, because it is what stops the ring
 * reading as a dashed circle and makes it read as *this* ring.
 *
 * <p>Drawn with `stroke-dasharray` on a single circle rather than four arc paths. One
 * element, no path arithmetic, and the whole thing scales by changing a viewBox
 * number instead of regenerating geometry.
 */

/** Outer radius as a fraction of the box. From the artwork. */
const R = 0.459
/** Stroke width as a fraction of the diameter. From the artwork. */
const STROKE = 0.0128
/** Half-width of each break, in degrees. From the artwork. */
const GAP = 2.6
/** Break centres, clockwise from twelve o'clock. From the artwork. */
const BREAKS = [35, 145, 215, 325]

/**
 * Build the dash pattern that leaves a gap at each break.
 *
 * <p>SVG dashes start at three o'clock and run clockwise, so every angle is rotated
 * by 90 degrees to put zero at the top where the measurements were taken.
 */
function dashArray(circumference: number): string {
  const segments: number[] = []
  const points = BREAKS.map((b) => (b + 360) % 360).sort((a, b) => a - b)

  let cursor = 0
  for (const centre of points) {
    const start = centre - GAP / 2
    const end = centre + GAP / 2
    // Drawn run up to this break, then the break itself.
    segments.push(((start - cursor) / 360) * circumference)
    segments.push((GAP / 360) * circumference)
    cursor = end
  }
  // Close the loop back to the first break.
  segments.push(((360 - cursor) / 360) * circumference)
  return segments.map((n) => n.toFixed(3)).join(' ')
}

export function BrandRing({
  size = 40,
  className = '',
  strokeClassName = 'text-brand-500',
  spin = false,
  children,
}: {
  size?: number
  className?: string
  /** Colour of the ring itself, as a text-colour class — it strokes in currentColor. */
  strokeClassName?: string
  /** Rotate continuously. The breaks are what make the rotation legible. */
  spin?: boolean
  /** Whatever sits inside the ring: a numeral, initials, an icon. */
  children?: ReactNode
}) {
  const VIEW = 100
  const r = VIEW * R
  const circumference = 2 * Math.PI * r
  // The dash pattern is authored from twelve o'clock; SVG starts at three.
  const rotate = -90

  return (
    <span
      className={`relative inline-grid shrink-0 place-items-center ${className}`}
      style={{ width: size, height: size }}
    >
      <svg
        viewBox={`0 0 ${VIEW} ${VIEW}`}
        className={[
          'absolute inset-0 h-full w-full',
          strokeClassName,
          spin ? 'motion-safe:animate-spin-slow' : '',
        ].join(' ')}
        fill="none"
        aria-hidden="true"
      >
        <circle
          cx={VIEW / 2}
          cy={VIEW / 2}
          r={r}
          stroke="currentColor"
          /*
           * One deviation from the artwork, and it is deliberate.
           *
           * 1.28% of the diameter is right at the size the logo actually ships — a
           * 1.5px hairline on a 120px mark. Held strictly proportional it would be
           * 0.44px inside a 34px step marker, which is to say invisible. So the
           * stroke is pinned to a crisp hairline at every size instead of being
           * allowed to disappear, which is what the ratio was expressing anyway.
           */
          strokeWidth={VIEW * STROKE}
          strokeDasharray={dashArray(circumference)}
          strokeLinecap="butt"
          transform={`rotate(${rotate} ${VIEW / 2} ${VIEW / 2})`}
          vectorEffect="non-scaling-stroke"
        />
      </svg>
      {children != null && <span className="relative leading-none">{children}</span>}
    </span>
  )
}

/**
 * A numeral inside the ring — the step marker.
 *
 * <p>Replaces a bare "01" with something that belongs to the brand. It costs one
 * element and it is the difference between a numbered list and a numbered list that
 * could only be this company's.
 */
export function RingStep({ n, size = 34 }: { n: number; size?: number }) {
  return (
    <BrandRing size={size} strokeClassName="text-brand-500">
      <span className="tnum display text-[11px] font-bold leading-none text-brand-400">
        {String(n).padStart(2, '0')}
      </span>
    </BrandRing>
  )
}
