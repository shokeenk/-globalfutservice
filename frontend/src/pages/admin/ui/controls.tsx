import {
  forwardRef, useCallback, useEffect, useId, useRef, useState,
  type ButtonHTMLAttributes, type ReactNode,
} from 'react'
import { LuEllipsis } from 'react-icons/lu'

/**
 * The console's buttons.
 *
 * <p>{@code primary} is the red fill the references use for the one main action on a page.
 * {@code outline} is every secondary control. {@code soft} is the tinted "View" beside each
 * row. {@code attention} is the Orders page's Needs Attention: red on pink, because it is
 * a count of things waiting rather than an action.
 */
type Variant = 'primary' | 'outline' | 'soft' | 'attention'

const VARIANTS: Record<Variant, string> = {
  primary: 'border border-admin-red bg-admin-red text-white hover:bg-[#C21420]',
  outline: 'border border-admin-line bg-white text-admin-ink hover:bg-admin-page',
  soft: 'border border-[#F6D3D6] bg-[#FDF3F4] text-admin-red-text hover:bg-admin-red-tint',
  attention: 'border border-[#F3B9BE] bg-[#FDF1F2] text-admin-red-text hover:bg-admin-red-tint',
}

/** A button's classes, for the few places that draw a button without this component. */
export function buttonClasses(variant: Variant = 'outline', size: 'sm' | 'md' = 'md'): string {
  return [
    'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-admin-control font-medium',
    'transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red',
    'focus-visible:ring-offset-1 disabled:cursor-not-allowed disabled:opacity-60',
    size === 'sm' ? 'h-8 px-3 text-[12.5px]' : 'h-10 px-4 text-[13.5px]',
    VARIANTS[variant],
  ].join(' ')
}

export const AdminButton = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant
  size?: 'sm' | 'md'
}>(function AdminButton({ variant = 'outline', size = 'md', className = '', type = 'button', ...rest }, ref) {
  return <button ref={ref} type={type} className={`${buttonClasses(variant, size)} ${className}`} {...rest} />
})

/**
 * A button that opens a panel beneath it: the Saved Views list, the date range, a row's
 * "…" menu.
 *
 * <p>Closes on Escape, on a click outside, and when the panel asks it to. Focus goes into
 * the panel on opening and back to the button on closing, so the keyboard never ends up
 * stranded behind a panel it cannot see.
 */
export function Popover({
  trigger, children, align = 'left', label, buttonClassName, panelClassName = '',
}: {
  /** The button's contents. */
  trigger: (open: boolean) => ReactNode
  children: (close: () => void) => ReactNode
  align?: 'left' | 'right'
  /** The button's accessible name, when its contents are only an icon. */
  label?: string
  buttonClassName: string
  panelClassName?: string
}) {
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLDivElement>(null)
  const id = useId()

  const close = useCallback(() => {
    setOpen(false)
    button.current?.focus()
  }, [])

  useEffect(() => {
    if (!open) return
    const first = panel.current?.querySelector<HTMLElement>(
      'button:not([disabled]), a[href], input, select, [tabindex="0"]')
    first?.focus()
    const onPointer = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        close()
      }
    }
    document.addEventListener('pointerdown', onPointer)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onPointer)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, close])

  return (
    <div ref={root} className="relative">
      <button
        ref={button}
        type="button"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        aria-label={label}
        onClick={() => setOpen((o) => !o)}
        className={buttonClassName}
      >
        {trigger(open)}
      </button>
      {open && (
        <div
          ref={panel}
          id={id}
          className={`absolute top-[calc(100%+6px)] z-40 min-w-[180px] rounded-admin-control border
                      border-admin-line bg-white shadow-admin-pop ${align === 'right' ? 'right-0' : 'left-0'}
                      ${panelClassName}`}
        >
          {children(close)}
        </div>
      )}
    </div>
  )
}

/** One entry in a Popover's list. */
export function MenuItem({
  onSelect, children, tone = 'normal', disabled = false,
}: {
  onSelect: () => void
  children: ReactNode
  tone?: 'normal' | 'danger'
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onSelect}
      className={`flex w-full items-center gap-2.5 px-3.5 py-2.5 text-left text-[13px] hover:bg-admin-page
                  focus-visible:bg-admin-page focus-visible:outline-none disabled:opacity-50
                  ${tone === 'danger' ? 'text-admin-red-text' : 'text-admin-ink'}`}
    >
      {children}
    </button>
  )
}

/** The "…" beside a row. */
export function RowMenu({ label, children }: { label: string; children: (close: () => void) => ReactNode }) {
  return (
    <Popover
      align="right"
      label={label}
      buttonClassName="grid h-8 w-8 place-items-center rounded-admin-control text-admin-ink hover:bg-admin-page
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red"
      trigger={() => <LuEllipsis aria-hidden="true" className="h-5 w-5" />}
      panelClassName="py-1"
    >
      {children}
    </Popover>
  )
}
