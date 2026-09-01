import type { CSSProperties, ElementType, ReactNode } from 'react'
import { useCountUp, useReveal } from './index'

/**
 * Declarative scroll reveal.
 *
 * <p>Wraps {@link useReveal} so a page can express "this settles into place when it
 * is reached" without every section repeating the same ref plumbing. The element
 * starts displaced and transparent, and transitions to rest — {@code transform} and
 * {@code opacity} only, so the whole thing runs on the compositor and never triggers
 * layout.
 *
 * <p>Note the {@code willChange} is applied only while the element is still waiting.
 * Leaving {@code will-change} on permanently promotes every revealed element to its
 * own compositor layer for the life of the page, which on a long page is how you run
 * a phone out of GPU memory.
 */
export function Reveal({
  children,
  as: Tag = 'div',
  delay = 0,
  distance = 18,
  direction = 'up',
  duration = 700,
  className = '',
  style,
  ...rest
}: {
  children: ReactNode
  /** Rendered element. Use a semantic tag rather than nesting a div inside one. */
  as?: ElementType
  /** Stagger offset in ms. Keep the step between siblings around 60–90ms. */
  delay?: number
  /** Travel distance in px. Small: a reveal is a settle, not a flight. */
  distance?: number
  direction?: 'up' | 'down' | 'left' | 'right' | 'none'
  duration?: number
  className?: string
  style?: CSSProperties
  /*
   * Everything else is forwarded to the rendered element.
   *
   * `as` is not much use without this. A wrapper that can be told to render a
   * `<form>` but then swallows `onSubmit` forces the call site to nest a real form
   * inside it, which is how you end up with a stray div between a fieldset and its
   * form for no reason other than an animation helper.
   *
   * Typed loosely on purpose: making this fully generic over `as` costs a
   * `ComponentPropsWithoutRef<T>` dance that would make every call site's error
   * messages unreadable, for a component with about a dozen uses.
   */
  [prop: string]: unknown
}) {
  const { ref, shown } = useReveal<HTMLDivElement>()

  const offset =
    direction === 'none'
      ? 'none'
      : direction === 'up'
        ? `translate3d(0, ${distance}px, 0)`
        : direction === 'down'
          ? `translate3d(0, ${-distance}px, 0)`
          : direction === 'left'
            ? `translate3d(${distance}px, 0, 0)`
            : `translate3d(${-distance}px, 0, 0)`

  return (
    <Tag
      {...rest}
      ref={ref}
      className={className}
      style={{
        opacity: shown ? 1 : 0,
        transform: shown ? 'none' : offset,
        transition: `opacity ${duration}ms cubic-bezier(0.16,1,0.3,1) ${delay}ms, transform ${duration}ms cubic-bezier(0.16,1,0.3,1) ${delay}ms`,
        willChange: shown ? undefined : 'transform, opacity',
        ...style,
      }}
    >
      {children}
    </Tag>
  )
}

/**
 * A figure that counts up when it is scrolled into view.
 *
 * <p>Always renders inside {@code .tnum}. A counting number set in proportional
 * figures changes width on almost every frame, which shoves whatever sits beside it
 * back and forth — the exact layout shift the animation was supposed to feel
 * expensive instead of causing.
 */
export function CountUp({
  to,
  prefix = '',
  suffix = '',
  decimals = 0,
  className = '',
}: {
  to: number
  prefix?: string
  suffix?: string
  decimals?: number
  className?: string
}) {
  const { ref, display } = useCountUp(to, { decimals })
  const final = `${prefix}${decimals > 0 ? to.toFixed(decimals) : to.toLocaleString()}${suffix}`

  return (
    <span ref={ref} className={`tnum ${className}`}>
      {/*
        The animated figure is hidden from assistive technology and the final value
        is exposed instead.

        A counter that starts at zero is a lie for as long as it is running, and a
        screen reader arriving mid-animation reads "0 days of cover after delivery" —
        which on this particular page is not a cosmetic problem, it is the guarantee
        stated wrongly. The same applies to any indexer that reads the DOM before the
        observer fires.
      */}
      <span aria-hidden="true">
        {prefix}
        {display}
        {suffix}
      </span>
      <span className="sr-only">{final}</span>
    </span>
  )
}
