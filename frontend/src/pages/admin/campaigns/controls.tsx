import { useRef, type KeyboardEvent, type ReactNode } from 'react'
import type { IconType } from 'react-icons'
import { FaBullhorn, FaCoins, FaUser } from 'react-icons/fa6'
import { LuShieldCheck } from 'react-icons/lu'
import { lengthOf, type CampaignType } from './wizard'

/**
 * "32/100", read against the same limit the server enforces.
 *
 * <p>Not a live region. Announcing every keystroke would drown a screen reader; the input
 * points at this with aria-describedby, so the count is read when the field is focused,
 * and going over is reported by the field's own error on save.
 */
export function CharCount({ id, value, limit }: { id: string; value: string; limit: number }) {
  const over = lengthOf(value) > limit
  return (
    <span
      id={id}
      className={`tnum text-[11.5px] ${over ? 'font-semibold text-admin-red-text' : 'text-admin-faint'}`}
    >
      {lengthOf(value)}/{limit}
      {over && <span className="sr-only"> — over the limit</span>}
    </span>
  )
}

/**
 * One of the "Additional Options" switches.
 *
 * <p>A button with role="switch", so it is announced as on or off rather than as a
 * checkbox, and the label is part of the button, so the whole row is the target — as
 * in the reference, where the text sits beside the switch with no gap between them.
 */
export function Switch({
  checked, onChange, children,
}: {
  checked: boolean
  onChange: (next: boolean) => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className="group flex w-full items-center gap-2.5 rounded-admin-control py-1 text-left
                 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red
                 focus-visible:ring-offset-2"
    >
      <span
        aria-hidden="true"
        className={`relative inline-flex h-[16px] w-[28px] shrink-0 rounded-full transition-colors
                    duration-150 ${checked ? 'bg-admin-red' : 'bg-[#C9CDD4]'}`}
      >
        <span
          className={`absolute top-[2px] h-[12px] w-[12px] rounded-full bg-white shadow-sm
                      transition-transform duration-150 ${checked ? 'translate-x-[14px]' : 'translate-x-[2px]'}`}
        />
      </span>
      <span className="text-[11px] leading-snug text-admin-ink">{children}</span>
    </button>
  )
}

const TYPES: { value: CampaignType; label: string; detail: string; Icon: IconType }[] = [
  { value: 'COINS', label: 'Coins', detail: 'Sales & Offers', Icon: FaCoins },
  { value: 'BOOSTING', label: 'FUT Champs', detail: 'Boosting Services', Icon: LuShieldCheck },
  { value: 'COACHING', label: 'Coaching', detail: '1-to-1 Coaching', Icon: FaUser },
  { value: 'GENERAL', label: 'General', detail: 'News & Updates', Icon: FaBullhorn },
]

/**
 * The four campaign-type cards.
 *
 * <p>A radio group, because that is what it is: one of four, always one chosen. Arrow
 * keys move the choice and Tab moves past the group, which is how a native radio group
 * behaves and what a keyboard user will expect.
 */
export function TypeTiles({
  value, onChange, labelledBy,
}: {
  value: CampaignType
  onChange: (next: CampaignType) => void
  labelledBy: string
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])

  const move = (event: KeyboardEvent, index: number) => {
    const step = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1
      : event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 0
    if (!step) return
    event.preventDefault()
    const next = (index + step + TYPES.length) % TYPES.length
    onChange(TYPES[next]!.value)
    refs.current[next]?.focus()
  }

  return (
    <div role="radiogroup" aria-labelledby={labelledBy} className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
      {TYPES.map(({ value: v, label, detail, Icon }, i) => {
        const selected = v === value
        return (
          <button
            key={v}
            ref={(el) => { refs.current[i] = el }}
            type="button"
            role="radio"
            aria-checked={selected}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(v)}
            onKeyDown={(e) => move(e, i)}
            className={`flex min-h-[76px] flex-col items-center justify-center rounded-admin-control border
                        px-2 py-3 text-center transition-colors focus-visible:outline-none
                        focus-visible:ring-2 focus-visible:ring-admin-red focus-visible:ring-offset-2
                        ${selected
                          ? 'border-[#E8A5AE] bg-admin-pink-card text-admin-red-text'
                          : 'border-admin-line bg-[#FAFBFC] text-admin-ink hover:border-[#D5D8DE]'}`}
          >
            <Icon aria-hidden="true" className={`h-[20px] w-[20px] ${selected ? '' : 'text-admin-ink'}`} />
            <span className="mt-1.5 text-[13px] font-semibold leading-tight">{label}</span>
            <span className={`mt-1 text-[11.5px] leading-tight ${selected ? '' : 'text-admin-muted'}`}>
              {detail}
            </span>
          </button>
        )
      })}
    </div>
  )
}

const STEPS: [string, string][] = [
  ['Campaign Details', 'Basic information'],
  ['Email Content', 'Write your message'],
  ['Design & Preview', 'Customize and preview'],
  ['Audience', 'Select recipients'],
  ['Review & Send', 'Confirm and send'],
]

/**
 * The five-step progress strip.
 *
 * <p>Steps that can be reached are buttons, so the strip doubles as navigation; the
 * current step is marked with aria-current="step"; steps not yet reachable are plain
 * text, so nothing offers a door that will not open. Moving saves the step being left,
 * which is the page's job, not this component's.
 */
export function StepIndicator({
  current, reachable = current, onSelect,
}: {
  current: number
  /** The furthest step that can be opened. */
  reachable?: number
  onSelect?: (step: number) => void
}) {
  return (
    <nav aria-label="Campaign steps" className="mb-4 rounded-[10px] border border-admin-line bg-white
                                               shadow-admin-card">
      <ol className="grid grid-cols-1 divide-y divide-admin-line sm:grid-cols-5 sm:divide-x sm:divide-y-0">
        {STEPS.map(([title, detail], i) => {
          const n = i + 1
          const active = n === current
          const inner = (
            <>
              <span
                aria-hidden="true"
                className={`grid h-[37px] w-[37px] shrink-0 place-items-center rounded-full text-[14px]
                            ${active
                              ? 'bg-admin-red font-semibold text-white'
                              : 'border border-[#E3E5E9] bg-white font-medium text-admin-ink'}`}
              >
                {n}
              </span>
              <span className="min-w-0 leading-tight">
                <span className="block truncate text-[12.5px] font-semibold text-admin-ink">
                  <span className="sr-only">Step {n}: </span>{title}
                </span>
                <span className="mt-0.5 block truncate text-[11px] text-admin-muted">{detail}</span>
              </span>
            </>
          )
          const layout = 'flex w-full items-center gap-3 px-4 py-2.5 text-left sm:my-2.5 sm:py-0'
          return (
            <li key={title} aria-current={active ? 'step' : undefined}>
              {!active && onSelect && n <= reachable ? (
                <button
                  type="button"
                  onClick={() => onSelect(n)}
                  className={`${layout} rounded-admin-control focus-visible:outline-none focus-visible:ring-2
                              focus-visible:ring-admin-red hover:[&_span]:text-admin-red-text`}
                >
                  {inner}
                </button>
              ) : (
                <div className={`${layout} ${!active && n > reachable ? 'opacity-60' : ''}`}>{inner}</div>
              )}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
