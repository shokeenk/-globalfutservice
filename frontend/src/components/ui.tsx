import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes,
  TextareaHTMLAttributes } from 'react'
import { forwardRef, useId } from 'react'
import { Link } from 'react-router-dom'
import { BrandRing, RingStep } from '../brand/Ring'
import { Reveal } from '../motion/Reveal'

/* ------------------------------------------------------------------ button --- */

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'invert'
type ButtonSize = 'sm' | 'md' | 'lg'

/*
 * The base carries the interaction character for every button on the site.
 *
 * `duration-200` on an expo curve rather than `duration-150` on the default ease:
 * a long decelerating arrival is the single clearest difference between a control
 * that feels default and one that feels designed. The press state scales very
 * slightly instead of nudging position — a 1px translate reflows nothing but reads
 * as a wobble, whereas a 1% scale reads as physical depression and stays on the
 * compositor.
 */
const BUTTON_BASE =
  'group/btn relative inline-flex select-none items-center justify-center gap-2 ' +
  'overflow-hidden whitespace-nowrap rounded-press font-semibold tracking-[-0.01em] ' +
  'transition-[transform,box-shadow,background-color,color,opacity] duration-200 ' +
  'ease-out-expo active:scale-[0.985] active:duration-75'

/**
 * A light sweeping across the face of a button on hover.
 *
 * <p>Fires once per hover rather than looping. A repeating shine is a casino
 * affordance — it demands attention continuously, which is why cheap sites use it.
 * A single pass reads as light catching a surface as the pointer moves over it, and
 * then it is done.
 *
 * <p>Rendered only for the primary variant. If every button on the page shines, the
 * effect stops marking the one action that matters, which was its entire job.
 */
function Sweep() {
  return (
    <span
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 overflow-hidden rounded-[inherit]"
    >
      <span
        className="absolute inset-y-0 -left-full w-1/2 bg-gradient-to-r from-transparent
                   via-paper/25 to-transparent opacity-0
                   group-hover/btn:animate-sweep group-hover/btn:opacity-100"
      />
    </span>
  )
}

/**
 * The hover gradient, as a second ramp fading in over the first.
 *
 * <p>The obvious way to brighten a gradient on hover is `filter: brightness()`, and
 * it is the wrong one twice over. It scales every channel by the same factor, so
 * the ramp keeps its exact shape and the button reads as "the same thing, turned
 * up" rather than as a surface catching more light. Worse, a filter on the button
 * applies to the label inside it too, which re-rasterises the text on every hover.
 *
 * <p>Two stacked gradients cross-faded by opacity solve both. The hot ramp lifts
 * the light end further than the dark end — which is what actually happens when a
 * light source moves closer to a curved surface — and opacity is a compositor
 * property, so the text is never touched.
 */
function Sheen() {
  return (
    <span
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 rounded-[inherit] bg-brand-sheen-hot
                 opacity-0 transition-opacity duration-300 ease-out-expo
                 group-hover/btn:opacity-100"
    />
  )
}

/**
 * An indeterminate band travelling across a button that is working.
 *
 * <p>A spinner says "something is happening"; on a payment button the customer also
 * wants to know it is happening *to this*. A band that crosses the whole face makes
 * the button itself the progress indicator, which is the only element they are
 * looking at after pressing it.
 *
 * <p>Indeterminate on purpose. Nothing here knows how long the API will take, and a
 * progress bar that guesses is a lie that gets caught at the 90% mark.
 *
 * <p>Hidden outright under `prefers-reduced-motion`. The blanket rule in index.css
 * collapses the duration rather than cancelling the animation, and with no fill
 * mode the band would settle back at its authored position — a bright static smear
 * across the left third of the button, which is worse than no band at all. The
 * spinner and the label carry the message instead.
 */
function BusyBand() {
  return (
    <span
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 overflow-hidden rounded-[inherit]"
    >
      <span
        className="absolute inset-y-0 left-0 w-1/3 animate-band motion-reduce:hidden
                   bg-gradient-to-r from-transparent via-paper/30 to-transparent"
      />
    </span>
  )
}

const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  /*
   * The primary action gets a specular sweep: a bright hairline along the top edge
   * of the gradient, which is what a lit convex surface actually does. It is drawn
   * with an inset shadow rather than a pseudo-element so it survives every context
   * the button is dropped into.
   */
  primary:
    'bg-brand-sheen text-paper ' +
    /*
     * The specular highlight stays: the button is still a red object, so a bright
     * hairline along its top edge still reads as light on a convex surface. What
     * changes is the shadow beneath it — a red glow at dark-theme strength bleeds
     * across white paper like wet ink, so it is pulled right back and paired with a
     * neutral contact shadow that actually sits the button on the page.
     */
    'shadow-glow ' +
    'hover:shadow-glow-lg ' +
    'active:shadow-pressed',
  /*
   * Secondary is an outline, not a second fill.
   *
   * It used to be a second coloured button — the comment here said "red leads, orange
   * follows" — which is exactly what stops a two-button row reading as a hierarchy: two
   * filled buttons are two primary actions. White ground, grey edge, dark label, so the
   * red beside it is unmistakably the thing to press.
   */
  secondary:
    'bg-white text-chalk border border-gray-300 shadow-xs ' +
    'hover:bg-gray-50 hover:border-gray-400 hover:shadow-sm ' +
    'active:shadow-pressed',
  /* Hover ground has to be a real step now: `ink-600` is white on a white page. */
  ghost: 'text-chalk-muted hover:bg-gray-100 hover:text-chalk',
  /*
   * For a button standing inside a red field, where the normal fill would be the
   * same colour as its ground.
   *
   * A real variant rather than utilities layered over `secondary`. That was the
   * first attempt and it resolved the background from the override and the text
   * from the variant — paper type on a paper fill, contrast 1.00, a button you
   * could not see. Conflicting utilities do not merge; one of each pair simply
   * wins, and which one is a fact about stylesheet order, not class order.
   */
  invert:
    'bg-paper text-brand-600 shadow-e2 ' +
    'hover:bg-ink-800 hover:text-brand-700 active:shadow-pressed',
  danger:
    'bg-ink-600 text-brand-400 hairline shadow-e1 ' +
    'hover:border-brand-500/40 hover:bg-brand-500/[0.14] hover:text-brand-200',
}

/*
 * Heights land on the 4px grid and every size clears the 44px touch minimum from
 * `md` upward. `sm` is 36px and is therefore only for pointer-dense surfaces such
 * as the admin console — never for a primary action on a phone.
 */
const BUTTON_SIZES: Record<ButtonSize, string> = {
  sm: 'h-9 px-3.5 text-body-sm',
  md: 'h-11 px-5 text-body',
  lg: 'h-[52px] px-7 text-body-lg',
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  loading?: boolean
  full?: boolean
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', loading = false, full = false, className = '',
    children, disabled, ...rest },
  ref,
) {
  const idle = !disabled && !loading

  return (
    <button
      ref={ref}
      // A loading button stays mounted rather than being swapped for a spinner,
      // which would make the layout jump and let an impatient customer click
      // whatever took its place. The label stays too: "Pay Rs 2,100" with a spinner
      // beside it tells them which action is in flight, where a bare spinner does
      // not. Every primary CTA that can load on this site is full-width, so the
      // spinner's own width costs nothing.
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={[
        BUTTON_BASE, BUTTON_VARIANTS[variant], BUTTON_SIZES[size],
        full ? 'w-full' : '',
        /*
         * Busy is not disabled, and they must not look alike.
         *
         * Dropping a primary CTA to 40% the instant it is pressed reads as "that
         * broke" at exactly the moment the customer is least willing to believe
         * otherwise — they have just committed to paying. So the busy button keeps
         * its full weight and colour and says what it is doing instead. It is still
         * `disabled` in the DOM, so the double-submit it was guarding against is
         * still impossible; only the appearance differs.
         */
        loading ? 'cursor-wait' : '',
        disabled && !loading ? 'pointer-events-none opacity-40' : '',
        className,
      ].join(' ')}
      {...rest}
    >
      {variant === 'primary' && idle && <Sheen />}
      {variant === 'primary' && idle && <Sweep />}
      {loading && <BusyBand />}
      {loading && <Spinner size={16} className="relative shrink-0" />}
      <span className="relative inline-flex items-center gap-2">{children}</span>
    </button>
  )
})

export function ButtonLink({
  to, variant = 'primary', size = 'md', full = false, className = '', children,
}: {
  to: string
  variant?: ButtonVariant
  size?: ButtonSize
  full?: boolean
  className?: string
  children: ReactNode
}) {
  return (
    <Link
      to={to}
      className={[
        BUTTON_BASE, BUTTON_VARIANTS[variant], BUTTON_SIZES[size],
        full ? 'w-full' : '', className,
      ].join(' ')}
    >
      {variant === 'primary' && <Sheen />}
      {variant === 'primary' && <Sweep />}
      <span className="relative inline-flex items-center gap-2">{children}</span>
    </Link>
  )
}

/**
 * Waiting, in the brand's own shape.
 *
 * <p>The generic arc-on-a-circle spinner is the single most anonymous element in any
 * interface. The brand ring turning is the same information and belongs to this
 * company — and it works precisely because the mark is already broken in four places,
 * so rotation is legible without inventing a gap that is not in the logo.
 *
 * <p>`size` is taken as a prop rather than from a Tailwind class because the ring is
 * drawn from a box, not from a stroke; passing `h-4 w-4` would size the wrapper and
 * leave the circle guessing.
 */
export function Spinner({ size = 20, className = '' }: { size?: number; className?: string }) {
  return (
    <BrandRing
      size={size}
      spin
      strokeClassName="text-current"
      className={className}
    />
  )
}

/* -------------------------------------------------------------------- card --- */

/**
 * A raised surface.
 *
 * <p>The default now uses the `.surface` treatment: a hairline, the e2 elevation,
 * and a near-invisible top-edge gradient. That gradient is two percent of white
 * over the first 40% of the card and it is the whole difference between a panel
 * that looks lit and one that looks pasted on — the eye reads it as quality without
 * being able to name it.
 *
 * <p>`interactive` adds a lift on hover for cards that are themselves links. It is
 * opt-in because a static card that rises when the pointer crosses it promises a
 * click that never happens.
 */
export function Card({
  className = '', children, glow = false, interactive = false,
}: {
  className?: string
  children: ReactNode
  glow?: boolean
  interactive?: boolean
}) {
  return (
    <div
      className={[
        glow ? 'rounded-panel border border-brand-500/40 bg-paper shadow-glow' : 'surface',
        interactive
          ? 'transition-[transform,box-shadow,border-color] duration-300 ease-out-expo ' +
            'hover:-translate-y-1 hover:border-ink-300 hover:shadow-e3'
          : '',
        className,
      ].join(' ')}
    >
      {children}
    </div>
  )
}

/* ------------------------------------------------------------------- badge --- */

export type BadgeTone = 'brand' | 'gold' | 'info' | 'attention' | 'neutral' | 'ok' | 'warn'

/*
 * The alphas are written as arbitrary values, not `/12`.
 *
 * 12 is not on Tailwind's opacity scale, and an off-scale modifier does not warn —
 * it compiles to nothing at all. These four tones spent that failure mode rendering
 * every badge on the site with a fully transparent fill, which looks like a design
 * choice rather than a bug and so was never reported. Anything off the 0/5/10/15…
 * scale has to be bracketed.
 */
const BADGE_TONES: Record<BadgeTone, string> = {
  // A pale red ground with the deep red on it. The previous pairing measured
  // 3.52:1 once the tint landed on a white card — the ground lightened and the
  // text did not follow it down.
  brand: 'bg-brand-50 text-brand-200 ring-brand-500/25',
  /*
   * Gold, as a solid rather than a tint.
   *
   * A pale ground with dark type on it is the safe way to build a badge, and it is
   * also why this chip kept disappearing: a wash of gold on a white card is just a
   * warmer white, so "POPULAR" read as another rectangle. The full-strength ground
   * is what makes it a chip.
   *
   * Ink on it, not paper. #FFC93C is light — white type on it measures 1.66:1 and
   * is unreadable, while the near-black measures 12.27:1. This is the one tone in
   * the set whose ground is brighter than its type, which is exactly why it draws
   * the eye without being red.
   */
  gold: 'bg-gold-500 text-chalk ring-gold-700/30',
  /*
   * Two tones added for status, where red had stopped being a signal.
   *
   * `brand` and `warn` are both red, and on the operations console most statuses
   * resolved to one or the other — so the column an operator actually scans came out
   * as a wall of pink that had to be read word by word. These give the two largest
   * non-red groups a colour of their own: work that is moving on its own, and work
   * that has stopped and needs a person. Red is then left meaning what it means
   * everywhere else on this site — act on this.
   */
  info: 'bg-deep/[0.10] text-deep ring-deep/30',
  attention: 'bg-gold-500/[0.18] text-gold-400 ring-gold-500/45',
  neutral: 'bg-ink-500 text-chalk-muted ring-ink-300',
  ok: 'bg-ok/[0.12] text-ok ring-ok/30',
  warn: 'bg-warn/[0.12] text-warn ring-warn/30',
}

export function Badge({
  tone = 'neutral', children, className = '',
}: {
  tone?: BadgeTone
  children: ReactNode
  className?: string
}) {
  return (
    <span
      className={[
        // Wider tracking than `tracking-wider`: uppercase text at 10.5px needs real
        // air between letters or it reads as a solid block rather than as a label.
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-[3px]',
        'text-[10.5px] font-semibold uppercase tracking-[0.13em]',
        'ring-1 ring-inset',
        BADGE_TONES[tone], className,
      ].join(' ')}
    >
      {children}
    </span>
  )
}

export function LiveDot({ className = '' }: { className?: string }) {
  return (
    <span className={`relative flex h-2 w-2 ${className}`}>
      <span className="absolute inline-flex h-full w-full animate-pulse-dot rounded-full bg-ok" />
      <span className="relative inline-flex h-2 w-2 rounded-full bg-ok" />
    </span>
  )
}

/* ------------------------------------------------------------------- forms --- */

export function Field({
  label, hint, error, children, required = false,
}: {
  label: string
  hint?: string
  error?: string
  children: (props: { id: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean }) => ReactNode
  required?: boolean
}) {
  const id = useId()
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined

  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-[13px] font-medium text-chalk-muted">
        {label}
        {required && <span className="ml-1 text-brand-400">*</span>}
      </label>
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {/* The error is announced, not just coloured: a red border alone is invisible
          to a screen reader and to a good number of sighted people. */}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-[12px] text-brand-400">
          {error}
        </p>
      )}
      {!error && hint && (
        <p id={`${id}-hint`} className="text-[12px] text-chalk-faint">
          {hint}
        </p>
      )}
    </div>
  )
}

const CONTROL =
  'w-full rounded-press border border-ink-400 bg-ink-700 px-3.5 text-sm text-chalk ' +
  'placeholder:text-chalk-faint transition-colors duration-200 ease-out-expo ' +
  // The field lifts half a step off the card while it has focus. The ring already
  // says where you are; this says the field is live and accepting, and it is the
  // cheapest possible way to make typing feel like it landed somewhere.
  'focus:border-brand-500/60 focus:bg-ink-600 ' +
  'aria-[invalid=true]:border-brand-500'

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = '', ...rest }, ref) {
    return <input ref={ref} className={`${CONTROL} h-11 ${className}`} {...rest} />
  },
)

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(
  function Textarea({ className = '', ...rest }, ref) {
    return <textarea ref={ref} className={`${CONTROL} py-3 ${className}`} {...rest} />
  },
)

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  function Select({ className = '', children, ...rest }, ref) {
    return (
      <select ref={ref} className={`${CONTROL} h-11 ${className}`} {...rest}>
        {children}
      </select>
    )
  },
)

export function Checkbox({
  checked, onChange, children, error,
}: {
  checked: boolean
  onChange: (next: boolean) => void
  children: ReactNode
  error?: string
}) {
  const id = useId()
  return (
    <div>
      <label htmlFor={id} className="flex cursor-pointer items-start gap-3">
        <input
          id={id}
          type="checkbox"
          checked={checked}
          onChange={(e) => onChange(e.target.checked)}
          aria-invalid={error ? true : undefined}
          className="mt-0.5 h-[18px] w-[18px] shrink-0 rounded border-ink-300 bg-ink-700
                     text-brand-500 focus:ring-brand-400 focus:ring-offset-ink"
        />
        <span className="text-[13px] leading-relaxed text-chalk-muted">{children}</span>
      </label>
      {error && (
        <p role="alert" className="ml-[30px] mt-1 text-[12px] text-brand-400">
          {error}
        </p>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ alerts --- */

export function Alert({
  tone = 'brand', title, children,
}: {
  tone?: 'brand' | 'ok' | 'warn' | 'neutral'
  title?: string
  children: ReactNode
}) {
  const tones = {
    brand: 'border-brand-500/35 bg-brand-50 text-brand-200',
    ok: 'border-ok/35 bg-ok/10 text-ok',
    warn: 'border-warn/35 bg-warn/10 text-warn',
    neutral: 'border-ink-400 bg-ink-600 text-chalk-muted',
  } as const

  return (
    <div role="alert" className={`rounded-panel border p-4 text-sm ${tones[tone]}`}>
      {title && <p className="mb-1 font-semibold">{title}</p>}
      <div className="text-[13px] leading-relaxed opacity-95">{children}</div>
    </div>
  )
}

/* ------------------------------------------------------------------ layout --- */

/**
 * A section, and the masthead that opens it.
 *
 * <p>The header is the site's most-repeated piece of design, so it carries the
 * identity: the red tick and small-caps stamp, a display heading, and an optional
 * action pushed to the far right on wide screens. Because every section on every
 * page opens this way, a reader who learns the mark in the hero recognises it on
 * the checkout screen — which is the mechanism by which a set of pages starts to
 * feel like one publication rather than one theme.
 *
 * <p>The header reveals on scroll and the body follows 90ms later. That stagger is
 * small enough to read as one movement with a leading edge, rather than as two
 * separate animations.
 */
export function Section({
  eyebrow, title, lead, action, children, className = '', id, wide = false,
}: {
  eyebrow?: string
  title?: ReactNode
  lead?: ReactNode
  /** Optional trailing control — a "view all" link, a filter, a count. */
  action?: ReactNode
  children?: ReactNode
  className?: string
  id?: string
  /** Opts into the wider measure for gallery and table layouts. */
  wide?: boolean
}) {
  return (
    <section
      id={id}
      /*
       * Wider gutters as the viewport grows. A single `px-5` from phone to desktop
       * leaves a 1440px page with the same 20px margin a 375px phone has, and
       * content pinned to the edge of a wide screen is one of the reliable tells of
       * a template. The max-width does the rest.
       */
      className={[
        'mx-auto w-full px-5 sm:px-8 lg:px-10',
        wide ? 'max-w-[1320px]' : 'max-w-6xl',
        className,
      ].join(' ')}
    >
      {(eyebrow || title || lead || action) && (
        <Reveal as="header" className="mb-[var(--rhythm-group)]">
          <div className="flex flex-col gap-6 md:flex-row md:items-end md:justify-between">
            <div className="max-w-2xl">
              {eyebrow && <p className="stamp mb-5">{eyebrow}</p>}
              {title && (
                /*
                 * `text-balance` distributes the last line rather than leaving one
                 * word stranded. On a two-line heading it is the difference between
                 * a deliberate break and an accident, and it is the browser's own
                 * heuristic — nothing is hardcoded, so it stays right when the copy
                 * is translated into Spanish or French.
                 */
                <h2 className="display text-balance text-display-xl text-sheen">{title}</h2>
              )}
              {lead && (
                <p className="measure mt-5 text-pretty text-body-lg text-chalk-muted">{lead}</p>
              )}
            </div>
            {action && <div className="flex shrink-0 items-center gap-3">{action}</div>}
          </div>
        </Reveal>
      )}
      {children}
    </section>
  )
}

export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`animate-pulse rounded-lg bg-ink-500/60 ${className}`} aria-hidden="true" />
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <Card className="p-10 text-center">
      <p className="display text-lg text-chalk">{title}</p>
      {children && <p className="mx-auto mt-2 max-w-md text-sm text-chalk-muted">{children}</p>}
    </Card>
  )
}

/* --------------------------------------------------------------- step card --- */

/**
 * One numbered step in a configurator.
 *
 * <p>The number is set in the margin rather than inline with the heading, so the
 * steps form a visible column down the left edge of the flow. A reader glancing at
 * a checkout wants to know how many steps there are and which one they are on before
 * they read a single word, and a vertical spine answers that instantly in a way
 * "Step 2 of 3" written into a heading does not.
 */
export function StepCard({
  step, title, aside, children, className = '',
}: {
  step: number
  title: string
  aside?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={`surface p-6 sm:p-7 ${className}`}>
      <header className="flex items-baseline justify-between gap-4">
        {/*
          The step number sits inside the brand ring.

          It was a bare numeral, which every checkout on the internet has. Ringed, it
          is the logo's own geometry doing structural work — the mark appears eight
          times on a checkout without the logo being pasted anywhere.
        */}
        <div className="flex items-center gap-3.5">
          <RingStep n={step} />
          <h2 className="display text-display-sm text-chalk">{title}</h2>
        </div>
        {aside}
      </header>
      <div className="mt-5">{children}</div>
    </section>
  )
}

/* ------------------------------------------------------------- select tile --- */

/**
 * A selectable option — platform, tier, package.
 *
 * <p>Selection is marked three ways at once: the border goes to brand, the ground
 * lifts, and a filled tick appears in the corner. That redundancy is deliberate.
 * Border colour alone fails WCAG 1.4.1 for anyone who cannot distinguish the hues,
 * and on a phone in daylight a 1px red border against a 1px grey one is genuinely
 * hard to see for everyone. The tick is what makes the state unambiguous.
 *
 * <p>Renders a real {@code <button>} with {@code aria-pressed} rather than a styled
 * div, so it is reachable by keyboard and announced as a toggle without any extra
 * work at the call site.
 */
export function SelectTile({
  active, onSelect, label, sub, badge, icon, disabled = false, className = '',
}: {
  active: boolean
  onSelect: () => void
  label: string
  sub?: ReactNode
  badge?: ReactNode
  /** Optional glyph, shown left of the label. Decorative — the label carries the name. */
  icon?: ReactNode
  disabled?: boolean
  className?: string
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={active}
      disabled={disabled}
      className={[
        'group relative overflow-hidden rounded-panel border p-4 text-left',
        'transition-[transform,border-color,background-color,box-shadow] duration-300 ease-out-expo',
        'disabled:pointer-events-none disabled:opacity-40',
        active
          ? 'border-brand-500 bg-brand-500/[0.10] shadow-glow'
          : 'border-ink-400 bg-paper hover:-translate-y-0.5 hover:border-ink-300 hover:bg-gray-50',
        className,
      ].join(' ')}
    >
      {badge && <span className="mb-2 block">{badge}</span>}

      {/*
        Icon and label on one line. The glyph is decorative — it speeds up
        recognition for someone who already knows their platform, and the text
        does the actual naming for everyone else.
      */}
      <span className="flex items-center gap-2.5 pr-6">
        {icon && (
          <span className={active ? 'text-brand-400' : 'text-chalk-muted'}>{icon}</span>
        )}
        <span className="text-sm font-semibold text-chalk">{label}</span>
      </span>
      {sub && <span className="tnum mt-1 block text-[12.5px] text-chalk-muted">{sub}</span>}

      {/* The tick. Scaled in rather than faded so the selection lands with a bit of
          physicality instead of dissolving into place. */}
      <span
        aria-hidden="true"
        className={[
          'absolute right-3 top-3 grid h-5 w-5 place-items-center rounded-full bg-brand-500',
          'transition-transform duration-300 ease-spring',
          active ? 'scale-100' : 'scale-0',
        ].join(' ')}
      >
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="white"
             strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M20 6 9 17l-5-5" />
        </svg>
      </span>
    </button>
  )
}
