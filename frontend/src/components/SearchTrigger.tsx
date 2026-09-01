import { useEffect, useState } from 'react'
import { useT } from '../i18n'

/**
 * The controls that open the search, kept apart from the search itself.
 *
 * <p>This split is the whole reason the dialog is not in the initial bundle. The header
 * is on every page, so anything it imports is downloaded by everyone — and the palette,
 * its matcher and its corpus are around twenty kilobytes that most visitors never open.
 * Importing the trigger from here and the dialog lazily keeps the cost on the people who
 * actually use it.
 */
export function MagnifierIcon({ className = 'h-4 w-4' }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.6-3.6" />
    </svg>
  )
}

/**
 * The desktop trigger.
 *
 * <p>Shaped like an input rather than drawn as a bare magnifier. The icon is smaller,
 * but on a storefront most people never learn that an icon is search until they click
 * it — and the fake field is what carries the keyboard shortcut, which is the only way
 * the shortcut is ever discovered.
 */
export function SearchTrigger({
  onOpen, onPrefetch,
}: {
  onOpen: () => void
  onPrefetch: () => void
}) {
  const t = useT()
  const [isMac, setIsMac] = useState(false)

  // Read after mount rather than during render: `navigator` does not exist if this
  // is ever prerendered, and the wrong modifier key is worse than a frame of Ctrl.
  useEffect(() => {
    setIsMac(/mac|iphone|ipad|ipod/i.test(navigator.userAgent))
  }, [])

  return (
    <button
      type="button"
      onClick={onOpen}
      /* Fetch the dialog chunk when the pointer arrives or focus lands, so it is
         already in memory by the time the click happens. */
      onMouseEnter={onPrefetch}
      onFocus={onPrefetch}
      className="hidden h-9 items-center gap-2 rounded-edge border border-ink-400
                 bg-paper pl-2.5 pr-2 text-[13px] text-chalk-faint
                 transition-colors duration-200 hover:border-ink-300 hover:text-chalk-muted
                 lg:flex"
    >
      <MagnifierIcon className="h-[15px] w-[15px]" />
      <span className="pr-6">{t.search.open}</span>
      <kbd className="hidden items-center gap-0.5 rounded-[4px] border border-ink-300
                      bg-ink-600 px-1.5 py-0.5 font-sans text-[10.5px] text-chalk-faint xl:flex">
        {isMac ? '⌘' : 'Ctrl'} K
      </kbd>
    </button>
  )
}

/**
 * The narrow-screen trigger, where the fake field does not fit.
 *
 * <p>Given a real `aria-label` rather than a `title`. A tooltip is invisible to touch
 * and unreliable to screen readers, which between them is most of the people who will
 * ever press this button.
 */
export function SearchIconButton({
  onOpen, onPrefetch,
}: {
  onOpen: () => void
  onPrefetch: () => void
}) {
  const t = useT()
  return (
    <button
      type="button"
      onClick={onOpen}
      onFocus={onPrefetch}
      aria-label={t.search.open}
      className="grid h-11 w-11 place-items-center rounded-edge hairline bg-paper
                 text-chalk backdrop-blur-sm lg:hidden"
    >
      <MagnifierIcon className="h-[18px] w-[18px]" />
    </button>
  )
}
