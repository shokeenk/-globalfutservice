import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useT } from '../i18n'
import { highlightRanges } from '../search/match'
import { useSearch } from '../search/useSearch'
import { MagnifierIcon } from './SearchTrigger'
import type { SearchEntry, SearchGroup } from '../search/corpus'

/* ------------------------------------------------------------------ icons --- */

/**
 * A small glyph per result group.
 *
 * <p>Shape, not colour. The groups are already labelled in text, so this is a scanning
 * aid rather than the only channel — and it stays legible for anyone who cannot
 * separate the hues the rest of the site uses.
 */
function GroupIcon({ group }: { group: SearchGroup }) {
  const common = {
    className: 'h-[15px] w-[15px]',
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.7,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
  }
  if (group === 'faq') {
    return (
      <svg {...common}>
        <circle cx="12" cy="12" r="9" />
        <path d="M9.6 9.4a2.5 2.5 0 1 1 3.3 2.4c-.6.2-.9.8-.9 1.4v.4" />
        <path d="M12 17.2h.01" />
      </svg>
    )
  }
  if (group === 'service') {
    return (
      <svg {...common}>
        <path d="M4 8.5 12 4l8 4.5v7L12 20l-8-4.5Z" />
        <path d="M12 12v8" /><path d="m4 8.5 8 3.5 8-3.5" />
      </svg>
    )
  }
  if (group === 'action') {
    return (
      <svg {...common}>
        <circle cx="12" cy="12" r="8.5" />
        <path d="M12 7.5V12l2.8 1.7" />
      </svg>
    )
  }
  return (
    <svg {...common}>
      <path d="M6 3.5h7.5L18 8v12.5H6Z" />
      <path d="M13 3.5V8h5" />
    </svg>
  )
}

/* ------------------------------------------------------------- highlights --- */

/**
 * The matched runs, marked.
 *
 * <p>Built as React nodes rather than an HTML string. The strings here come from the
 * dictionary and from the catalogue, and the catalogue is server data — assembling
 * markup from it and handing that to `dangerouslySetInnerHTML` would turn a display
 * nicety into an injection surface for the sake of two `<mark>` tags.
 */
function Highlight({ text, queryTerms }: { text: string; queryTerms: string[] }) {
  const ranges = useMemo(() => highlightRanges(text, queryTerms), [text, queryTerms])
  if (ranges.length === 0) return <>{text}</>

  const nodes: React.ReactNode[] = []
  let cursor = 0
  ranges.forEach(([start, end], i) => {
    if (start > cursor) nodes.push(text.slice(cursor, start))
    nodes.push(
      <mark key={i} className="rounded-[2px] bg-brand-500/25 px-px text-chalk">
        {text.slice(start, end)}
      </mark>,
    )
    cursor = end
  })
  if (cursor < text.length) nodes.push(text.slice(cursor))
  return <>{nodes}</>
}

/* ----------------------------------------------------------------- dialog --- */

/**
 * The search palette.
 *
 * <p>Mounted only while it is open — there is no `open` prop, because the header does
 * not render this at all until someone asks for it. That is what lets the whole module,
 * and the matcher and corpus behind it, load lazily; it also means the focus-restore and
 * scroll-lock teardown run on unmount, which is exactly when they should.
 */
export default function SearchDialog({ onClose }: { onClose: () => void }) {
  const t = useT()
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)

  const { flat, groups, queryTerms } = useSearch(query)

  const inputRef = useRef<HTMLInputElement | null>(null)
  const listRef = useRef<HTMLDivElement | null>(null)
  const returnRef = useRef<Element | null>(null)
  const baseId = useId()
  const listId = `${baseId}-list`
  const optionId = (i: number) => `${baseId}-opt-${i}`

  /* A new query is a new list; the cursor goes back to the top result. */
  useEffect(() => setActive(0), [query])

  /* Scroll lock, focus capture, focus return — the same contract the mobile nav
     sheet keeps, for the same reasons. Each open is a fresh mount, so the box starts
     empty without needing to be reset. */
  useEffect(() => {
    returnRef.current = document.activeElement

    const { overflow } = document.body.style
    document.body.style.overflow = 'hidden'
    const focusTimer = window.setTimeout(() => inputRef.current?.focus(), 40)

    return () => {
      document.body.style.overflow = overflow
      window.clearTimeout(focusTimer)
      if (returnRef.current instanceof HTMLElement) returnRef.current.focus()
    }
  }, [])

  const go = useCallback(
    (entry: SearchEntry | undefined) => {
      if (!entry) return
      onClose()
      navigate(entry.to)
    },
    [navigate, onClose],
  )

  /* Keep the active row in view when the arrows walk past the fold. `nearest` rather
     than `center`, so a list that already fits does not jump on every keypress. */
  useEffect(() => {
    const el = listRef.current?.querySelector<HTMLElement>(`#${CSS.escape(optionId(active))}`)
    el?.scrollIntoView({ block: 'nearest' })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active])

  function onKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
      return
    }
    if (flat.length === 0) return

    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setActive((i) => (i + 1) % flat.length)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setActive((i) => (i - 1 + flat.length) % flat.length)
    } else if (event.key === 'Home') {
      event.preventDefault()
      setActive(0)
    } else if (event.key === 'End') {
      event.preventDefault()
      setActive(flat.length - 1)
    } else if (event.key === 'Enter') {
      event.preventDefault()
      go(flat[active])
    }
  }

  let flatIndex = -1

  return (
    <div
      className="fixed inset-0 flex items-start justify-center px-4 pb-8 pt-[10vh] sm:pt-[14vh]"
      style={{ zIndex: 'var(--z-modal)' }}
    >
      {/* Scrim. Clicking it closes — the affordance every command palette has, and the
          one people reach for before they find Escape. */}
      <button
        type="button"
        aria-label={t.search.close}
        onClick={onClose}
        className="absolute inset-0 cursor-default bg-[rgba(17,17,20,0.42)] backdrop-blur-md"
      />

      <div
        role="dialog"
        aria-modal="true"
        aria-label={t.search.label}
        onKeyDown={onKeyDown}
        className="relative flex max-h-[76vh] w-full max-w-[620px] animate-rise flex-col
                   overflow-hidden rounded-panel border border-ink-400 bg-ink-700
                   shadow-lg"
      >
        {/* ------------------------------------------------------------ input --- */}
        <div className="flex items-center gap-3 border-b border-ink-400 px-4">
          <span className="text-chalk-faint"><MagnifierIcon className="h-[18px] w-[18px]" /></span>

          <input
            ref={inputRef}
            type="text"
            role="combobox"
            aria-expanded={flat.length > 0}
            aria-controls={listId}
            aria-activedescendant={flat.length > 0 ? optionId(active) : undefined}
            aria-autocomplete="list"
            aria-label={t.search.label}
            autoComplete="off"
            spellCheck={false}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t.search.placeholder}
            className="h-14 w-full bg-transparent text-body-lg text-chalk outline-none
                       placeholder:text-chalk-faint"
          />

          {query && (
            <button
              type="button"
              onClick={() => { setQuery(''); inputRef.current?.focus() }}
              aria-label={t.search.clear}
              className="grid h-8 w-8 shrink-0 place-items-center rounded-edge text-chalk-faint
                         transition-colors hover:bg-ink-500 hover:text-chalk"
            >
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                   strokeWidth="2" strokeLinecap="round" aria-hidden="true">
                <path d="M18 6 6 18" /><path d="m6 6 12 12" />
              </svg>
            </button>
          )}
        </div>

        {/* ---------------------------------------------------------- results --- */}
        <div ref={listRef} className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-2">
          {query.trim() === '' ? (
            <p className="px-3 py-8 text-center text-body-sm text-chalk-muted">
              {t.search.empty}
            </p>
          ) : flat.length === 0 ? (
            <div className="px-3 py-8 text-center">
              <p className="text-body-sm text-chalk">{t.search.noResults(query.trim())}</p>
              <p className="mt-1.5 text-[12.5px] text-chalk-faint">{t.search.noResultsHint}</p>
            </div>
          ) : (
            <div id={listId} role="listbox" aria-label={t.search.label}>
              {groups.map((group) => (
                <div key={group.group} className="mb-1 last:mb-0">
                  {/*
                    A group heading inside a listbox has to be presentational. Left as
                    a real heading it becomes an option as far as the accessibility
                    tree is concerned, and the arrow keys and the screen reader stop
                    agreeing about how many results there are.
                  */}
                  <p role="presentation" className="eyebrow px-3 pb-1.5 pt-3">
                    {group.label}
                  </p>

                  {group.entries.map((item) => {
                    flatIndex += 1
                    const index = flatIndex
                    const selected = index === active
                    return (
                      <div
                        key={item.id}
                        id={optionId(index)}
                        role="option"
                        aria-selected={selected}
                        tabIndex={-1}
                        onClick={() => go(item)}
                        /* mousemove, not mouseenter: a list that re-renders under a
                           stationary cursor would otherwise steal the selection from
                           the keyboard on every keystroke. */
                        onMouseMove={() => setActive(index)}
                        className={[
                          'flex cursor-pointer items-center gap-3 rounded-edge px-3 py-2.5',
                          'transition-colors duration-150',
                          selected ? 'bg-ink-500' : 'hover:bg-ink-600',
                        ].join(' ')}
                      >
                        <span
                          className={[
                            'grid h-8 w-8 shrink-0 place-items-center rounded-edge',
                            selected ? 'bg-brand-500/[0.16] text-brand-400' : 'bg-ink-600 text-chalk-faint',
                          ].join(' ')}
                        >
                          <GroupIcon group={item.group} />
                        </span>

                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-[13.5px] font-semibold text-chalk">
                            <Highlight text={item.title} queryTerms={queryTerms} />
                          </span>
                          {item.body && (
                            <span className="mt-0.5 block truncate text-[12px] text-chalk-muted">
                              <Highlight text={item.body} queryTerms={queryTerms} />
                            </span>
                          )}
                        </span>

                        {item.meta && (
                          <span className="tnum shrink-0 text-[12px] text-chalk-faint">
                            {item.meta}
                          </span>
                        )}
                      </div>
                    )
                  })}
                </div>
              ))}
            </div>
          )}
        </div>

        {/* ------------------------------------------------------------- feet --- */}
        <div className="flex items-center gap-4 border-t border-ink-400 bg-ink-800 px-4 py-2.5
                        text-[11.5px] text-chalk-faint">
          <span className="flex items-center gap-1.5"><Key>↑</Key><Key>↓</Key>{t.search.hintMove}</span>
          <span className="flex items-center gap-1.5"><Key>↵</Key>{t.search.hintOpen}</span>
          <span className="ml-auto flex items-center gap-1.5"><Key>esc</Key>{t.search.hintClose}</span>
        </div>
      </div>

      {/*
        The result count, for anyone who cannot see the list change.

        Polite, and deliberately not `assertive`: it fires on every keystroke, and an
        assertive region would interrupt the screen reader mid-word each time.
      */}
      <p aria-live="polite" className="sr-only">
        {query.trim() === '' ? '' : t.search.results(flat.length)}
      </p>
    </div>
  )
}

function Key({ children }: { children: React.ReactNode }) {
  return (
    <kbd className="grid h-[18px] min-w-[18px] place-items-center rounded-[4px] border
                    border-ink-300 bg-ink-600 px-1 font-sans text-[10.5px] text-chalk-muted">
      {children}
    </kbd>
  )
}
