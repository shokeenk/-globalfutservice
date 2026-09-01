/**
 * Motion primitives.
 *
 * <p>There is no animation library in this bundle and there does not need to be.
 * Framer Motion is ~34KB gzipped and the entire vocabulary this site uses — reveal
 * on scroll, count a number up, offset a layer against scroll — is four hooks and
 * an IntersectionObserver. On the connections this audience actually browses on,
 * 34KB of animation runtime is a worse trade than the time it takes to write these.
 *
 * <p>Every hook here degrades to "final state, immediately" under
 * {@code prefers-reduced-motion}. Not "faster" — <em>off</em>. Someone who has set
 * that switch has usually done it because motion makes them ill, and a 40ms version
 * of the same slide is still a slide.
 */
import { useEffect, useRef, useState } from 'react'

/* ------------------------------------------------------------ preferences --- */

/**
 * Tracks the OS reduced-motion setting, live.
 *
 * <p>Read through a listener rather than once at mount: people change this
 * mid-session, usually because something on the page has just made them
 * uncomfortable, and a value frozen at import time cannot respond to that.
 */
export function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(
    () =>
      typeof window !== 'undefined' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  )

  useEffect(() => {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)')
    const onChange = () => setReduced(query.matches)
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])

  return reduced
}

/* ---------------------------------------------------------------- reveals --- */

/**
 * Reveals an element the first time it enters the viewport.
 *
 * <p>Unobserves on the first intersection. A reveal that re-fires when you scroll
 * back up is a party trick: content the reader has already seen should not perform
 * for them a second time, and keeping observers alive for every element on a long
 * page is work the main thread does not need.
 *
 * <p>The bottom margin is negative so the trigger sits ~12% above the fold. Firing
 * exactly at the viewport edge means the reader watches the animation happen; firing
 * slightly early means the content is simply <em>there</em>, already settled, by the
 * time it is comfortably in view.
 */
export function useReveal<T extends HTMLElement = HTMLDivElement>(
  options: { threshold?: number; rootMargin?: string } = {},
) {
  const { threshold = 0.15, rootMargin = '0px 0px -12% 0px' } = options
  const ref = useRef<T | null>(null)
  const reduced = useReducedMotion()
  const [shown, setShown] = useState(false)

  useEffect(() => {
    if (reduced) {
      setShown(true)
      return
    }
    const node = ref.current
    if (!node) return

    // No IntersectionObserver (very old Safari, some embedded webviews) means the
    // content must still be readable. Failing open shows everything; failing closed
    // would leave a blank page, which is the worse of the two bugs by a wide margin.
    if (typeof IntersectionObserver === 'undefined') {
      setShown(true)
      return
    }

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue
          setShown(true)
          observer.unobserve(entry.target)
        }
      },
      { threshold, rootMargin },
    )

    observer.observe(node)
    return () => observer.disconnect()
  }, [reduced, threshold, rootMargin])

  return { ref, shown }
}

/* --------------------------------------------------------------- counters --- */

/**
 * Counts a number up once the element is in view.
 *
 * <p>Eased rather than linear. A linear count reaches its final value at a constant
 * rate and reads as a loading bar; an eased one decelerates into place and reads as
 * a figure settling, which is the difference between a stat that looks animated and
 * one that looks alive.
 *
 * <p>Driven by {@code requestAnimationFrame} against wall-clock time, not by a fixed
 * per-frame increment. A frame-count animation runs at half speed on a 30fps device
 * and double on a 120Hz one; timestamps make the duration mean what it says
 * everywhere.
 */
export function useCountUp(
  target: number,
  options: { durationMs?: number; decimals?: number } = {},
) {
  const { durationMs = 1600, decimals = 0 } = options
  const { ref, shown } = useReveal<HTMLSpanElement>({ threshold: 0.5 })
  const reduced = useReducedMotion()
  const [value, setValue] = useState(reduced ? target : 0)

  useEffect(() => {
    if (!shown) return
    if (reduced) {
      setValue(target)
      return
    }

    let frame = 0
    const started = performance.now()

    const tick = (now: number) => {
      const progress = Math.min(1, (now - started) / durationMs)
      // Expo-out: fast departure, long settle. Matches the `out-expo` curve the rest
      // of the interface uses, so numbers and surfaces share one sense of physics.
      const eased = progress === 1 ? 1 : 1 - Math.pow(2, -10 * progress)
      setValue(target * eased)
      if (progress < 1) frame = requestAnimationFrame(tick)
    }

    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [shown, reduced, target, durationMs])

  const display =
    decimals > 0 ? value.toFixed(decimals) : Math.round(value).toLocaleString()
  return { ref, display }
}

/* --------------------------------------------------------------- parallax --- */

/**
 * Offsets an element against the scroll position.
 *
 * <p>Writes a CSS custom property instead of React state. Re-rendering a component
 * on every scroll frame is how parallax ends up janky — it drags the whole reconciler
 * into the frame budget. Setting {@code --parallax} straight on the node keeps the
 * work on the compositor, where a transform belongs.
 *
 * <p>{@code speed} is a fraction of scroll distance: 0.15 means the layer moves at
 * 15% of the page. Keep it low. Anything past ~0.3 stops reading as depth and starts
 * reading as a bug.
 */
export function useParallax<T extends HTMLElement = HTMLDivElement>(speed = 0.15) {
  const ref = useRef<T | null>(null)
  const reduced = useReducedMotion()

  useEffect(() => {
    const node = ref.current
    if (!node || reduced) return

    let frame = 0
    let queued = false

    const apply = () => {
      queued = false
      const rect = node.getBoundingClientRect()
      // Distance of the element's centre from the viewport's centre. Zero offset when
      // centred, so a parallax layer is never displaced from where it was authored —
      // it only drifts as it travels through the frame.
      const fromCentre = rect.top + rect.height / 2 - window.innerHeight / 2
      node.style.setProperty('--parallax', `${(-fromCentre * speed).toFixed(2)}px`)
    }

    const onScroll = () => {
      if (queued) return
      queued = true
      frame = requestAnimationFrame(apply)
    }

    apply()
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll, { passive: true })
    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', onScroll)
    }
  }, [speed, reduced])

  return ref
}

/* ------------------------------------------------------------ scroll flag --- */

/** True once the window has scrolled past {@code offset}. Used by the header. */
export function useScrolled(offset = 8): boolean {
  const [scrolled, setScrolled] = useState(false)
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > offset)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [offset])
  return scrolled
}

/**
 * Document scroll progress, 0 to 1, written to {@code --progress} on the node.
 *
 * <p>A custom property rather than state, for the same reason as the parallax hook:
 * a progress rail is a visual affordance and has no business triggering a render
 * tree walk sixty times a second.
 */
export function useScrollProgress<T extends HTMLElement = HTMLDivElement>() {
  const ref = useRef<T | null>(null)
  useEffect(() => {
    const node = ref.current
    if (!node) return
    let queued = false
    const apply = () => {
      queued = false
      const max = document.documentElement.scrollHeight - window.innerHeight
      const ratio = max <= 0 ? 0 : Math.min(1, window.scrollY / max)
      node.style.setProperty('--progress', ratio.toFixed(4))
    }
    const onScroll = () => {
      if (queued) return
      queued = true
      requestAnimationFrame(apply)
    }
    apply()
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', onScroll)
    }
  }, [])
  return ref
}

/* ---------------------------------------------------------- pointer aura --- */

/**
 * Track the pointer's bearing from an element's centre, as CSS custom properties.
 *
 * <p>Writes three values on the element and nothing else:
 *
 * <ul>
 *   <li><code>--aura-angle</code> — the bearing to the pointer in degrees, measured
 *       clockwise from twelve o'clock, which is the origin `conic-gradient` uses.</li>
 *   <li><code>--aura-near</code> — 0 when the pointer is beyond `radius`, rising to 1
 *       at the element's edge. Drives how bright the effect gets.</li>
 *   <li><code>--aura-lit</code> — the same value, but eased, for anything that should
 *       come up gently rather than linearly.</li>
 * </ul>
 *
 * <p><b>No React state.</b> A pointer that moves across the header fires dozens of
 * events a second; routing those through `useState` would re-render the header —
 * and everything under it — on every one. Writing custom properties straight onto
 * the node keeps the whole effect in CSS, off the React tree entirely. It is the
 * same approach `useParallax` and `useScrollProgress` already take here.
 *
 * <p><b>Coalesced to one write per frame.</b> `pointermove` can fire faster than the
 * display refreshes, and the extra writes are invisible work.
 *
 * <p><b>Fine pointers only.</b> On a touchscreen there is no cursor to follow, so the
 * listener is never attached and the ring keeps its resting appearance. Reduced
 * motion does the same: the ring still exists, it simply stops chasing anything.
 */
export function usePointerAura<T extends HTMLElement = HTMLDivElement>(radius = 420) {
  const ref = useRef<T | null>(null)
  const reduced = useReducedMotion()

  useEffect(() => {
    const node = ref.current
    if (!node || reduced) return
    if (!window.matchMedia('(hover: hover) and (pointer: fine)').matches) return

    let queued = false
    let px = 0
    let py = 0

    const apply = () => {
      queued = false
      const rect = node.getBoundingClientRect()
      // A hidden element reports a zero box; bearing from it is meaningless.
      if (rect.width === 0) return

      const cx = rect.left + rect.width / 2
      const cy = rect.top + rect.height / 2
      const dx = px - cx
      const dy = py - cy

      // atan2 gives radians anticlockwise from three o'clock. conic-gradient counts
      // clockwise from twelve, hence the swap and the 90-degree turn.
      const deg = (Math.atan2(dy, dx) * 180) / Math.PI + 90
      node.style.setProperty('--aura-angle', `${deg.toFixed(1)}deg`)

      const dist = Math.hypot(dx, dy)
      const edge = Math.max(rect.width, rect.height) / 2
      const near = 1 - Math.min(1, Math.max(0, (dist - edge) / radius))
      node.style.setProperty('--aura-near', near.toFixed(3))
      node.style.setProperty('--aura-lit', (near * near).toFixed(3))
    }

    const onMove = (event: PointerEvent) => {
      px = event.clientX
      py = event.clientY
      if (queued) return
      queued = true
      requestAnimationFrame(apply)
    }

    window.addEventListener('pointermove', onMove, { passive: true })
    window.addEventListener('scroll', apply, { passive: true })
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('scroll', apply)
    }
  }, [radius, reduced])

  return ref
}
