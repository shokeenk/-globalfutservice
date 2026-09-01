import { useEffect, useRef } from 'react'
import { useReducedMotion } from '../motion'

/**
 * The stadium atmosphere layer.
 *
 * <p>This is what a background video would have been. It is not one, for two
 * reasons that both matter more than the brief for footage did:
 *
 * <ol>
 *   <li>EA's gameplay is EA's. Shipping their footage as a storefront backdrop is a
 *       takedown waiting to happen, and this site takes people's money — it cannot
 *       afford to be the kind of business that borrows assets.</li>
 *   <li>A cinematic loop that survives compression is 8–14MB. This audience browses
 *       on phones, on Indian mobile data. The hero would be a blank rectangle for
 *       four seconds on exactly the visit that matters most.</li>
 * </ol>
 *
 * <p>So the atmosphere is generated instead. Total cost is under 5KB of code and one
 * canvas: floodlight bloom and haze in CSS where the GPU already does gradients for
 * free, and airborne motes on a canvas because that is the one part CSS genuinely
 * cannot do. It never loads, never pops in, and looks the same on a ₹8,000 Android
 * as on a studio display.
 */
export function Atmosphere({
  intensity = 1,
  motes = true,
  className = '',
}: {
  /** Scales the light. 1 for the hero; 0.4–0.6 for interior page headers. */
  intensity?: number
  motes?: boolean
  className?: string
}) {
  return (
    <div
      aria-hidden="true"
      className={`pointer-events-none absolute inset-0 overflow-hidden ${className}`}
      style={{ ['--intensity' as string]: intensity }}
    >
      <Floodlights />
      <PitchGeometry />
      {motes && <Motes />}
      {/*
        Grain sits on top of everything.

        Wide near-black gradients band badly on 8-bit panels — you can count the
        steps across the hero. A little noise dithers those steps into invisibility,
        and it is the same reason film grain survived into digital colour grading.
      */}
      <div className="grain absolute inset-0" />
    </div>
  )
}

/* ----------------------------------------------------------- floodlights --- */

/**
 * Stadium lighting, in three layers.
 *
 * <p>Real floodlights are hard, high and slightly cold; the pitch bounces a warmer,
 * softer light back up. Both are here, plus one brand-coloured wash — the red is the
 * only saturated thing in the frame, so it has to be the weakest of the three or the
 * page turns into a nightclub.
 */
function Floodlights() {
  return (
    <>
      {/*
        Ink, not light.

        Floodlights were the right metaphor on a black page: three coloured beams
        lifting a dark ground. On white there is nothing to lift — adding light to
        paper does nothing at all. So the same three layers now *deposit* colour
        instead of emitting it, the way ink bleeds into a sheet, and the brand red
        that had to be the quietest layer on black can lead here because it is the
        only thing the page has.

        Every value is far weaker than its dark-theme counterpart. A wash that reads
        as atmospheric on near-black reads as a stain on white.
      */}

      {/* The brand wash, upper right. The dominant layer now. */}
      <div
        className="absolute -top-[30%] right-[-12%] h-[80%] w-[65%] rounded-full blur-[140px] animate-breathe-slow"
        style={{
          background:
            'radial-gradient(closest-side, rgba(193,40,27,calc(0.022 * var(--intensity))), transparent 72%)',
        }}
      />
      {/* A cool counterweight, upper left, so the page is not uniformly warm. */}
      <div
        className="absolute -top-[38%] left-[-8%] h-[85%] w-[70%] rounded-full blur-[130px] animate-breathe"
        style={{
          background:
            'radial-gradient(closest-side, rgba(63,63,70,calc(0.009 * var(--intensity))), transparent 70%)',
        }}
      />
      {/* A soft shade along the bottom, so the sheet has a floor rather than
          fading to nothing at the fold. */}
      <div
        className="absolute inset-x-[-10%] bottom-[-32%] h-[55%] rounded-[50%] blur-[120px]"
        style={{
          background:
            'radial-gradient(closest-side, rgba(17,17,20,calc(0.045 * var(--intensity))), transparent 70%)',
        }}
      />
      {/* The drifting band. On paper it darkens rather than brightens, which is what
          keeps the washes above from reading as flat CSS blobs. */}
      <div
        className="absolute inset-x-[-50%] top-[26%] h-[36%] animate-drift"
        style={{
          background:
            'linear-gradient(180deg, transparent, rgba(17,17,20,calc(0.016 * var(--intensity))) 45%, transparent)',
          filter: 'blur(38px)',
        }}
      />
    </>
  )
}

/* -------------------------------------------------------- pitch geometry --- */

/**
 * Pitch markings, at the threshold of visibility.
 *
 * <p>Drawn in perspective — the lines converge toward a vanishing point above the
 * frame, the way a broadcast camera behind the goal sees them. A flat overhead grid
 * would read as generic tech-website scaffolding; convergence is what makes it read
 * as a place.
 *
 * <p>Masked to fade out before it reaches any text. Background texture that competes
 * with a headline is not texture, it is noise.
 */
function PitchGeometry() {
  return (
    <svg
      className="absolute inset-x-0 bottom-0 h-[58%] w-full"
      viewBox="0 0 1200 420"
      preserveAspectRatio="none"
      style={{
        maskImage: 'linear-gradient(180deg, transparent, black 55%, transparent)',
        WebkitMaskImage: 'linear-gradient(180deg, transparent, black 55%, transparent)',
      }}
    >
      <defs>
        <linearGradient id="pitch-line" x1="0" y1="1" x2="0" y2="0">
          {/* Pitch markings were white chalk on a dark field. On paper they become
              ruled ink — same geometry, opposite polarity, and much weaker, because
              a line that reads as a suggestion on black reads as a diagram on white. */}
          <stop offset="0%" stopColor="rgba(17,17,20,0.055)" />
          <stop offset="100%" stopColor="rgba(17,17,20,0)" />
        </linearGradient>
      </defs>
      <g stroke="url(#pitch-line)" strokeWidth="1" fill="none">
        {/* Touchlines converging on a vanishing point at (600, -140). */}
        {[-560, -300, -80, 80, 300, 560].map((offset) => (
          <line key={offset} x1={600 + offset * 2.4} y1="420" x2={600 + offset * 0.22} y2="0" />
        ))}
        {/* Horizontal bands, spaced logarithmically so they compress with distance. */}
        {[420, 300, 214, 152, 106, 72, 46].map((y, index) => (
          <line key={y} x1="0" y1={y} x2="1200" y2={y} opacity={0.9 - index * 0.11} />
        ))}
        {/* The centre circle, elliptical because we are looking along the pitch. */}
        <ellipse cx="600" cy="214" rx="150" ry="34" opacity="0.55" />
      </g>
    </svg>
  )
}

/* ------------------------------------------------------------------ motes --- */

/**
 * Airborne motes caught in the floodlights.
 *
 * <p>The one element that earns a canvas. Doing this with DOM nodes means ~60
 * absolutely-positioned divs each running its own CSS animation, which the compositor
 * will happily accept and then spend the rest of the session paying for.
 *
 * <p>Three things keep it honest on a mid-range phone:
 * <ul>
 *   <li>Density scales with area and is capped, so a 4K monitor does not get 400
 *       particles.</li>
 *   <li>The loop stops entirely when the canvas leaves the viewport or the tab goes
 *       to the background. An animation nobody is looking at is pure battery drain,
 *       and this one sits in a hero that scrolls away within one screen.</li>
 *   <li>Under reduced motion it draws a single static frame and shuts down — the
 *       texture survives, the movement does not.</li>
 * </ul>
 */
function Motes() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const reduced = useReducedMotion()

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const context = canvas.getContext('2d')
    if (!context) return

    // Cap the backing store at 2x. Beyond that the extra fill rate buys nothing the
    // eye can resolve on out-of-focus dust, and phones with 3x screens are exactly
    // the ones that cannot afford it.
    const ratio = Math.min(window.devicePixelRatio || 1, 2)

    type Mote = { x: number; y: number; r: number; drift: number; rise: number; phase: number; alpha: number }
    let motes: Mote[] = []
    let width = 0
    let height = 0

    const build = () => {
      const rect = canvas.getBoundingClientRect()
      width = rect.width
      height = rect.height
      canvas.width = Math.round(width * ratio)
      canvas.height = Math.round(height * ratio)
      context.setTransform(ratio, 0, 0, ratio, 0, 0)

      // One mote per ~14,000px², bounded. A hero on a laptop lands near 55.
      const count = Math.max(18, Math.min(70, Math.round((width * height) / 14000)))
      motes = Array.from({ length: count }, () => ({
        x: Math.random() * width,
        y: Math.random() * height,
        r: 0.5 + Math.random() * 1.5,
        drift: 0.10 + Math.random() * 0.22,
        rise: 0.06 + Math.random() * 0.20,
        phase: Math.random() * Math.PI * 2,
        alpha: 0.16 + Math.random() * 0.44,
      }))
    }

    const draw = (time: number) => {
      context.clearRect(0, 0, width, height)
      for (const mote of motes) {
        // Sway is a sine of time and the mote's own phase, so no two particles ever
        // travel in lockstep — the thing that makes generated motion look generated.
        const sway = Math.sin(time / 2600 + mote.phase) * 16
        const x = mote.x + sway * mote.drift
        const y = mote.y

        // Motes brighten toward the top of the frame, where the light is.
        const lit = mote.alpha * (0.35 + 0.65 * (1 - y / height))
        context.beginPath()
        context.arc(x, y, mote.r, 0, Math.PI * 2)
        // Dust catching light becomes dust settling on a sheet: dark specks,
        // and far fainter, since a mote that twinkles on black smears on white.
        context.fillStyle = `rgba(17,17,20,${(lit * 0.55).toFixed(3)})`
        context.fill()
      }
    }

    build()

    if (reduced) {
      draw(0)
      const onResizeStatic = () => {
        build()
        draw(0)
      }
      window.addEventListener('resize', onResizeStatic)
      return () => window.removeEventListener('resize', onResizeStatic)
    }

    let frame = 0
    let running = false
    let last = 0

    const loop = (now: number) => {
      const delta = last === 0 ? 16 : Math.min(48, now - last)
      last = now
      for (const mote of motes) {
        // Rise, then wrap. Dust in a floodlight drifts upward on convection; falling
        // particles read as snow, which is a different scene entirely.
        mote.y -= mote.rise * (delta / 16)
        if (mote.y < -4) {
          mote.y = height + 4
          mote.x = Math.random() * width
        }
      }
      draw(now)
      if (running) frame = requestAnimationFrame(loop)
    }

    const start = () => {
      if (running) return
      running = true
      last = 0
      frame = requestAnimationFrame(loop)
    }
    const stop = () => {
      running = false
      cancelAnimationFrame(frame)
    }

    // Visibility gating. Off-screen or backgrounded means no frames at all.
    const observer =
      typeof IntersectionObserver === 'undefined'
        ? null
        : new IntersectionObserver(
            (entries) => {
              const visible = entries.some((entry) => entry.isIntersecting)
              if (visible && !document.hidden) start()
              else stop()
            },
            { threshold: 0 },
          )
    if (observer) observer.observe(canvas)
    else start()

    const onVisibility = () => (document.hidden ? stop() : start())
    const onResize = () => build()

    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('resize', onResize)

    return () => {
      stop()
      observer?.disconnect()
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('resize', onResize)
    }
  }, [reduced])

  return <canvas ref={canvasRef} className="absolute inset-0 h-full w-full" />
}
