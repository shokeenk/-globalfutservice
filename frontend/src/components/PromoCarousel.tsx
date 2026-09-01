import { useEffect, useRef, useState } from 'react'
import { useReducedMotion } from '../motion'

/**
 * The campaign banners, as a panel on the page.
 *
 * <p>These are finished artwork — each one already carries its own headline, its own
 * offer and its own logos. Behind the hero they were competing with the site's headline
 * for the same rectangle, and the reader had two things shouting at once. As a panel of
 * their own they get to be what they are: an advert, framed, with the page around it.
 *
 * <p><b>It slides rather than crossfades.</b> A crossfade is right when images are a
 * backdrop and should not draw attention to the change. These are the content, and a
 * horizontal move is the gesture that says "there is another one" — it is also what
 * makes the dots legible as position rather than decoration.
 *
 * <p>One track, moved by a transform. Three absolutely-positioned layers fading over
 * each other would each need their own paint; a single translated strip is one
 * composited layer, which is the difference between a smooth slide and a stutter on a
 * mid-range phone.
 */
type Slide = { src: string; key: string }

const SLIDES: Slide[] = [
  { src: '/brand/hero-slide-1.webp', key: 'discount' },
  { src: '/brand/hero-slide-2.webp', key: 'boosting' },
  { src: '/brand/hero-slide-3.webp', key: 'social' },
]

/*
 * Three seconds a slide.
 *
 * Short for a banner carrying this much copy, so the two escapes matter more than
 * they did at six: hovering or focusing anywhere in the panel stops it, and clicking
 * a dot stops it for good. Without those, a reader who starts a sentence loses it
 * mid-word — which is the usual reason fast carousels are read as broken rather than
 * as lively. WCAG 2.2.2 asks for exactly that pause mechanism on anything that
 * auto-advances beside other content.
 */
const INTERVAL_MS = 3000

export function PromoCarousel({ labels, label }: { labels: string[]; label: string }) {
  const reduceMotion = useReducedMotion()
  const [index, setIndex] = useState(0)
  /*
   * Two kinds of "not now", kept apart because they end differently.
   *
   * `hovered` is somebody reading, and it lasts exactly as long as the pointer or the
   * focus is inside the panel. `stopped` is somebody having chosen a slide, and it is
   * final for the visit.
   *
   * They used to be one `paused` flag that only ever got set to true — mouse-enter and
   * focus turned it on and nothing turned it off. So the first time a cursor crossed the
   * banner, autoplay ended for good. On a laptop the hero carousel sits about where the
   * pointer already is, which is why it looked intermittent rather than broken: load and
   * don't touch it and it slides, brush past it once and it never moves again.
   */
  const [hovered, setHovered] = useState(false)
  const [stopped, setStopped] = useState(false)
  const timer = useRef<number | null>(null)

  useEffect(() => {
    if (reduceMotion || hovered || stopped) return

    const stop = () => {
      if (timer.current !== null) {
        window.clearInterval(timer.current)
        timer.current = null
      }
    }
    const start = () => {
      stop()
      timer.current = window.setInterval(
        () => setIndex((i) => (i + 1) % SLIDES.length),
        INTERVAL_MS,
      )
    }

    /*
     * A hidden tab still runs timers, and one repainting a full-width banner every six
     * seconds is pure battery cost for something nobody is looking at.
     */
    const onVisibility = () => (document.hidden ? stop() : start())
    if (!document.hidden) start()
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      stop()
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [reduceMotion, hovered, stopped])

  const go = (i: number) => {
    setIndex(i)
    // Deliberate and final: a carousel that moves on again a second after you pick a
    // slide is worse than one that never moved. Unlike hover, this is an explicit
    // choice, so it is the one pause that does not resume.
    setStopped(true)
  }

  return (
    <section
      aria-roledescription="carousel"
      aria-label={label}
      /*
        No padding of its own. This was written as a full-width band and carried the
        page gutter with it; as a grid child inside the hero, that gutter is applied
        twice and the panel sits inset from a column that is already inset.
      */
      className="w-full"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocusCapture={() => setHovered(true)}
      /*
        `relatedTarget` is where focus is going. Without the containment check, tabbing
        from one dot to the next fires a blur that would resume autoplay while the
        keyboard is still inside the panel — the exact thing hover-pause exists to prevent.
      */
      onBlurCapture={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setHovered(false)
      }}
    >
      {/*
        The artwork is the panel. It had a lilac border and a 4px plate behind it,
        which framed a thing that already has its own frame — every one of these
        banners is a finished composition with its own edge. Removing the border and
        the padding is what makes it read as the advert rather than as an advert
        mounted on the page.
      */}
      {/*
        `media`, not the page ladder. This panel sits on the hero photograph, where a
        6%-black shadow has nothing lighter behind it to darken and so does nothing at
        all. `media` carries the strength a dark ground needs plus a hairline of light
        along the top edge — that inset is what makes it read as a raised object rather
        than a rectangle pasted on the image.
      */}
      <div className="relative overflow-hidden rounded-panel shadow-media
                      transition-shadow duration-300 ease-out-expo hover:shadow-media-lg">
        <div
          className={[
            'flex',
            // No transition under reduced motion: the jump is the point, not the travel.
            reduceMotion ? '' : 'transition-transform duration-700 ease-out-expo',
          ].join(' ')}
          /*
            One slide fills the frame; nothing of its neighbours shows.

            This carried a 6% peek at either edge, on the argument that a sliver of the
            next slide invites a swipe in a way dots do not. That argument is sound in
            general and wrong here: it cropped both neighbours to a stripe of nonsense
            and put a seam either side of artwork whose own edge is part of the design.
            The indicator carries the "there is more" on its own.
          */
          style={{ transform: `translateX(-${index * 100}%)` }}
        >
          {SLIDES.map((slide, i) => (
            <div
              key={slide.key}
              className="w-full shrink-0"
              role="group"
              aria-roledescription="slide"
              aria-label={`${i + 1} of ${SLIDES.length}`}
              aria-hidden={i === index ? undefined : 'true'}
            >
              <img
                src={slide.src}
                alt={labels[i] ?? ''}
                draggable={false}
                /*
                 * The first banner is above the fold on a short screen and is what the
                 * panel looks like before anything moves; the other two are six seconds
                 * away and should not compete for that request.
                 */
                loading={i === 0 ? 'eager' : 'lazy'}
                fetchPriority={i === 0 ? 'high' : 'low'}
                decoding="async"
                /*
                 * The artwork's own ratio, so nothing is cut.
                 *
                 * A 2:1 frame looked right and quietly took eleven percent off the top
                 * and bottom of finished artwork — on slide three that is the row of
                 * guarantees along the bottom edge. All three sources are within a
                 * rounding error of 16:9, so this shows every one of them whole while
                 * still giving the track a single fixed height to slide.
                 */
                className="block aspect-[16/9] w-full rounded-edge object-cover object-center"
              />
            </div>
          ))}
        </div>

        {/*
          Centred on the artwork's lower edge.

          Bottom-left put them in the corner where these banners happen to stack their
          coin piles, so the dots landed on the busiest part of the image. Centred, they
          sit over the quiet strip every one of the three keeps along the bottom.
        */}
        <div className="pointer-events-none absolute inset-x-0 bottom-1 flex justify-center gap-1 sm:bottom-2">
          {SLIDES.map((slide, i) => (
            <button
              key={slide.key}
              type="button"
              onClick={() => go(i)}
              aria-label={labels[i] ?? `Slide ${i + 1}`}
              aria-current={i === index ? 'true' : undefined}
              className={[
                'pointer-events-auto grid h-11 w-11 place-items-center rounded-full',
                'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2',
                'focus-visible:outline-paper',
              ].join(' ')}
            >
              {/* 44px target; the visible dot is smaller than the thing you can hit. */}
              <span
                aria-hidden="true"
                className={[
                  'block h-2 rounded-full shadow-e1 transition-all duration-300 ease-out-expo',
                  i === index ? 'w-7 bg-paper' : 'w-2 bg-paper/60',
                ].join(' ')}
              />
            </button>
          ))}
        </div>
      </div>
    </section>
  )
}
