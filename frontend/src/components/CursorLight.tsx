import { useEffect, useRef } from 'react'
import { useReducedMotion } from '../motion'

/** How far the light lags the cursor. Lower is heavier; 1 would be rigid. */
const FOLLOW = 0.16

/** The blob's box. Large enough that the outer falloff never shows a hard edge. */
const SIZE = 1100

/**
 * A light the cursor carries with it.
 *
 * <p>The page is lit by the `Atmosphere` floodlights, which are fixed where the
 * designer put them. This adds one more source that the visitor moves themselves —
 * which is the whole appeal: the ground stops being a flat backdrop and starts
 * behaving like a surface with something shining on it.
 *
 * <p><b>It sits behind the content, not over it.</b> The tempting version blends over
 * everything with `screen` or `plus-lighter` and looks better in a screenshot. It is
 * also the version that quietly ruins the contrast work: brightening the ground under
 * light-on-dark text narrows the gap between them, and it does it most exactly where
 * the reader is pointing. Behind the content, the light spills across the page ground
 * and through the translucent panels and can never touch a letterform.
 *
 * <p><b>What it costs per frame.</b> One `transform` write on one promoted element.
 * The blob is a fixed-size div rather than a full-viewport gradient, so moving it is
 * a composite rather than a repaint, and it never enters React state — a cursor
 * crossing the page re-renders nothing.
 *
 * <p><b>When it does not run at all.</b> No fine pointer (a touchscreen has no cursor
 * to carry a light), or `prefers-reduced-motion`. In both cases the component renders
 * nothing and no listener is attached.
 */
export function CursorLight() {
  const ref = useRef<HTMLDivElement | null>(null)
  const reduced = useReducedMotion()

  useEffect(() => {
    const node = ref.current
    if (!node || reduced) return
    if (!window.matchMedia('(hover: hover) and (pointer: fine)').matches) return

    // Target is where the pointer is; current is where the light has got to. The gap
    // between them is the whole effect — a light with a little mass reads as a lamp
    // being carried, where one locked to the cursor reads as a graphic stuck to it.
    let targetX = window.innerWidth / 2
    let targetY = window.innerHeight / 2
    let x = targetX
    let y = targetY

    let frame = 0
    let running = false
    let seen = false

    const place = () => {
      node.style.transform =
        `translate3d(${(x - SIZE / 2).toFixed(1)}px, ${(y - SIZE / 2).toFixed(1)}px, 0)`
    }

    const tick = () => {
      x += (targetX - x) * FOLLOW
      y += (targetY - y) * FOLLOW
      place()

      // Stop the loop once the light has caught up. Leaving a rAF running forever to
      // move something a hundredth of a pixel keeps a core awake and drains a laptop
      // for nothing.
      if (Math.abs(targetX - x) < 0.3 && Math.abs(targetY - y) < 0.3) {
        x = targetX
        y = targetY
        place()
        running = false
        return
      }
      frame = requestAnimationFrame(tick)
    }

    const start = () => {
      if (running) return
      running = true
      frame = requestAnimationFrame(tick)
    }

    const onMove = (event: PointerEvent) => {
      targetX = event.clientX
      targetY = event.clientY
      if (!seen) {
        // First sighting: put the light under the cursor rather than sliding it in
        // from the middle of the screen, which looks like something escaping.
        seen = true
        x = targetX
        y = targetY
        place()
        node.style.opacity = '1'
      }
      start()
    }

    // Leaving the window takes the light with it, so a parked cursor does not leave a
    // glow burned into a page nobody is looking at.
    const onLeave = (event: PointerEvent) => {
      if (event.relatedTarget === null) node.style.opacity = '0'
    }
    const onEnter = () => { if (seen) node.style.opacity = '1' }
    const onBlur = () => { node.style.opacity = '0' }

    place()
    window.addEventListener('pointermove', onMove, { passive: true })
    document.addEventListener('pointerout', onLeave)
    document.addEventListener('pointerover', onEnter)
    window.addEventListener('blur', onBlur)

    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('pointermove', onMove)
      document.removeEventListener('pointerout', onLeave)
      document.removeEventListener('pointerover', onEnter)
      window.removeEventListener('blur', onBlur)
    }
  }, [reduced])

  if (reduced) return null

  return (
    <div
      ref={ref}
      aria-hidden="true"
      className="cursor-light"
      style={{ width: SIZE, height: SIZE }}
    />
  )
}
