import { useEffect, useRef, type ReactNode } from 'react'
import { LuX } from 'react-icons/lu'

/**
 * A dialog over the page, for a form that has to be finished or abandoned.
 *
 * <p>Focus moves in and stays in until it closes, Escape and the backdrop both close it,
 * and focus returns to whatever opened it. The page behind does not scroll meanwhile.
 */
export function Modal({
  title, onClose, children, width = 'max-w-[480px]',
}: {
  title: string
  onClose: () => void
  children: ReactNode
  width?: string
}) {
  const panel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null
    const focusables = () => Array.from(panel.current?.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]), select, textarea') ?? [])
    // The first field rather than the close button, which comes first in the markup.
    const initial = focusables()
    ;(initial[1] ?? initial[0])?.focus()
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onClose()
        return
      }
      if (event.key !== 'Tab') return
      const items = focusables()
      if (items.length === 0) return
      const first = items[0]!
      const last = items[items.length - 1]!
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = previous
      opener?.focus?.()
    }
  }, [onClose])

  return (
    <div className="fixed inset-0 z-[60] grid place-items-center p-4">
      <div aria-hidden="true" onClick={onClose} className="absolute inset-0 bg-black/45" />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
        className={`relative max-h-[90vh] w-full ${width} overflow-y-auto rounded-admin-card bg-white shadow-admin-pop`}
      >
        <div className="flex items-center justify-between gap-3 border-b border-admin-line px-5 py-4">
          <h2 id="modal-title" className="text-[16px] font-semibold text-admin-ink">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="grid h-8 w-8 place-items-center rounded-admin-control text-admin-ink hover:bg-admin-page
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red"
          >
            <LuX aria-hidden="true" className="h-5 w-5" />
          </button>
        </div>
        <div className="p-5">{children}</div>
      </div>
    </div>
  )
}
