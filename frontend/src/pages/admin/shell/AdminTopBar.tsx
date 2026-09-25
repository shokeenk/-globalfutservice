import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { LuBell, LuChevronDown, LuExternalLink, LuLogOut, LuMenu, LuSearch } from 'react-icons/lu'
import { api } from '../../../lib/api'
import { useAuth } from '../../../state/AuthContext'

/**
 * The console's top bar: slogan, search, the bell, and who is signed in.
 */
export function AdminTopBar({
  onOpenMenu, menuOpen,
}: {
  /** Opens the navigation drawer. Only offered below the width the rail appears at. */
  onOpenMenu: () => void
  menuOpen: boolean
}) {
  return (
    <header className="sticky top-0 z-30 flex h-[57px] items-center gap-3 border-b border-admin-line
                       bg-white px-4 sm:px-6 lg:pl-[34px]">
      <button
        type="button"
        onClick={onOpenMenu}
        aria-label="Open menu"
        aria-expanded={menuOpen}
        aria-controls="admin-drawer"
        className="grid h-10 w-10 shrink-0 place-items-center rounded-admin-control text-admin-ink
                   hover:bg-admin-page focus-visible:outline-none focus-visible:ring-2
                   focus-visible:ring-admin-red md:hidden"
      >
        <LuMenu aria-hidden="true" className="h-5 w-5" />
      </button>

      <p className="hidden shrink-0 text-[10.5px] font-medium uppercase tracking-[0.37em] text-[#4A4E57] lg:block">
        Play more <span aria-hidden="true" className="px-3">·</span> Worry less
      </p>

      <OrderSearch />

      <div className="ml-auto flex shrink-0 items-center gap-2 sm:gap-5 md:ml-0">
        <Bell />
        <AccountMenu />
      </div>
    </header>
  )
}

/**
 * Search, over the one thing the console can search: orders.
 *
 * <p>The reference draws a general "Search..." box. The only searchable data behind the
 * console is the order queue's reference-or-email filter, so that is what this does: it
 * opens the Orders page with the query applied, exactly as if it had been typed into the
 * filter bar there. The visible placeholder stays as drawn; the label says what it
 * actually searches, because a screen reader has no placeholder to squint at.
 *
 * <p>Ctrl+K (Cmd+K on a Mac) focuses it. The storefront binds the same keys to its own
 * search dialog, but that handler lives in the public header, which is not mounted here,
 * so the two can never both fire.
 */
function OrderSearch() {
  const navigate = useNavigate()
  const input = useRef<HTMLInputElement>(null)
  const [query, setQuery] = useState('')
  const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 'k') return
      event.preventDefault()
      input.current?.focus()
      input.current?.select()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const q = query.trim()
    navigate(q ? `/admin/orders?search=${encodeURIComponent(q)}` : '/admin/orders')
  }

  return (
    <form
      role="search"
      onSubmit={submit}
      className="min-w-0 flex-1 md:ml-auto md:max-w-[283px] md:flex-none md:basis-[283px] xl:mr-[76px]"
    >
      <label htmlFor="admin-search" className="sr-only">Search orders by reference or email</label>
      <div className="flex h-[31px] items-center gap-2 rounded-admin-control border border-admin-line bg-white
                      px-2.5 transition-shadow focus-within:border-admin-red focus-within:ring-2
                      focus-within:ring-admin-red/20">
        <LuSearch aria-hidden="true" className="h-4 w-4 shrink-0 text-admin-faint" />
        <input
          ref={input}
          id="admin-search"
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search..."
          autoComplete="off"
          className="min-w-0 flex-1 bg-transparent text-[13px] text-admin-ink placeholder:text-admin-faint
                     focus:outline-none"
        />
        <kbd
          aria-hidden="true"
          className="hidden shrink-0 rounded-[4px] border border-admin-line px-1.5 py-px font-sans
                     text-[10.5px] text-admin-faint sm:block"
        >
          {isMac ? '⌘' : 'Ctrl'} K
        </kbd>
      </div>
    </form>
  )
}

/**
 * The bell counts payments waiting to be checked.
 *
 * <p>Staff have no notification feed — the storefront's bell is a customer feature — so
 * the reference's bell needed something to count. Payment claims are the one queue in the
 * console where each item is somebody who has already sent money and cannot be served
 * until a person looks, which is what a bell is for. It reads the same endpoint the
 * "Payments to check" panel does and links to the page that panel is on.
 *
 * <p>A failed read shows no badge rather than a zero: "nothing to check" and "could not
 * find out" must not look the same.
 */
function Bell() {
  const [count, setCount] = useState<number | null>(null)

  useEffect(() => {
    let alive = true
    const load = async () => {
      try {
        const claims = await api.get<unknown[]>('/api/v1/admin/payment-claims')
        if (alive) setCount(claims.length)
      } catch {
        if (alive) setCount(null)
      }
    }
    void load()
    const timer = setInterval(() => void load(), 30_000)
    return () => { alive = false; clearInterval(timer) }
  }, [])

  const label = count && count > 0
    ? `${count} payment${count === 1 ? '' : 's'} to check`
    : 'No payments to check'

  return (
    <Link
      to="/admin/orders"
      aria-label={label}
      title={label}
      className="relative grid h-10 w-10 place-items-center rounded-admin-control text-admin-ink
                 hover:bg-admin-page focus-visible:outline-none focus-visible:ring-2
                 focus-visible:ring-admin-red"
    >
      <LuBell aria-hidden="true" className="h-[21px] w-[21px]" />
      {count !== null && count > 0 && (
        <span
          aria-hidden="true"
          className="absolute right-1 top-1 grid h-4 min-w-4 place-items-center rounded-full bg-admin-red
                     px-1 text-[10px] font-bold leading-none text-white"
        >
          {count > 9 ? '9+' : count}
        </span>
      )}
    </Link>
  )
}

function initialsOf(name: string | null | undefined, email: string | undefined): string {
  const source = name?.trim() || email?.split('@')[0] || ''
  const words = source.split(/[\s._-]+/).filter(Boolean)
  const letters = words.length >= 2
    ? `${words[0]![0]}${words[1]![0]}`
    : source.slice(0, 2)
  return letters.toUpperCase() || '?'
}

/**
 * Who is signed in, and the two things you can do about it.
 *
 * <p>The reference draws a chevron and no menu. "View site" and "Sign out" are what a
 * console's account control is for; nothing else was invented to fill it.
 */
function AccountMenu() {
  const { account, logout } = useAuth()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const firstItem = useRef<HTMLAnchorElement>(null)

  useEffect(() => {
    if (!open) return
    firstItem.current?.focus()
    const onPointer = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
        trigger.current?.focus()
      }
    }
    document.addEventListener('pointerdown', onPointer)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onPointer)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  if (!account) return null
  const name = account.displayName?.trim() || account.email
  const role = account.role === 'ADMIN' ? 'Admin' : 'Operator'

  const signOut = async () => {
    setOpen(false)
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div ref={root} className="relative">
      <button
        ref={trigger}
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        className="flex items-center gap-3 rounded-admin-control py-1 pl-1 pr-1.5 text-left
                   hover:bg-admin-page focus-visible:outline-none focus-visible:ring-2
                   focus-visible:ring-admin-red"
      >
        <span
          aria-hidden="true"
          className="grid h-[38px] w-[38px] shrink-0 place-items-center rounded-full bg-[#EEF0F3]
                     text-[13px] font-bold text-admin-ink"
        >
          {initialsOf(account.displayName, account.email)}
        </span>
        <span className="hidden min-w-0 leading-tight sm:block">
          <span className="block max-w-[160px] truncate text-[13px] font-semibold text-admin-ink">{name}</span>
          <span className="mt-0.5 block text-[11.5px] text-admin-faint">{role}</span>
        </span>
        <span className="sr-only">Account menu for {name}, {role}</span>
        <LuChevronDown aria-hidden="true" className="h-4 w-4 shrink-0 text-admin-ink" />
      </button>

      {open && (
        <div
          role="menu"
          aria-label="Account"
          className="absolute right-0 top-[calc(100%+6px)] z-40 w-48 overflow-hidden rounded-admin-control
                     border border-admin-line bg-white py-1 shadow-md"
        >
          <Link
            ref={firstItem}
            to="/"
            role="menuitem"
            onClick={() => setOpen(false)}
            className="flex items-center gap-2.5 px-3.5 py-2.5 text-[13px] text-admin-ink
                       hover:bg-admin-page focus-visible:bg-admin-page focus-visible:outline-none"
          >
            <LuExternalLink aria-hidden="true" className="h-4 w-4 text-admin-faint" />
            View site
          </Link>
          <button
            type="button"
            role="menuitem"
            onClick={() => void signOut()}
            className="flex w-full items-center gap-2.5 px-3.5 py-2.5 text-left text-[13px] text-admin-ink
                       hover:bg-admin-page focus-visible:bg-admin-page focus-visible:outline-none"
          >
            <LuLogOut aria-hidden="true" className="h-4 w-4 text-admin-faint" />
            Sign out
          </button>
        </div>
      )}
    </div>
  )
}
