import { Suspense, useCallback, useEffect, useRef, useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { LuX } from 'react-icons/lu'
import { RouteErrorBoundary } from '../../../components/ErrorBoundary'
import { Spinner } from '../../../components/ui'
import { AdminSidebar } from './AdminSidebar'
import { AdminTopBar } from './AdminTopBar'

/**
 * The frame around every console route: sidebar, top bar, and the page.
 *
 * <p>Rendered instead of the storefront's chrome rather than inside it. The public
 * header, footer, cursor light and help widget are all for customers, and a console
 * nested beneath a site navigation is a console that spends its first 80px on links to
 * the shop. The cookie notice stays, because it lives above both layouts in App.
 *
 * <p>Three widths. At 1280px and up the full sidebar from the reference. From 768px the
 * same navigation as an icon rail, because 197px of labels beside a seven-column order
 * table is where the table starts scrolling sideways. Below 768px no sidebar at all, and
 * the top bar's menu button opens it as a drawer.
 */
export default function AdminShell() {
  const [drawerOpen, setDrawerOpen] = useState(false)
  const { pathname } = useLocation()
  // Stable, because the drawer's focus handling runs whenever this identity changes and
  // would otherwise pull focus back to its first link on every render.
  const openDrawer = useCallback(() => setDrawerOpen(true), [])
  const closeDrawer = useCallback(() => setDrawerOpen(false), [])

  // Following a link out of the drawer should land on the page, not leave the menu over it.
  useEffect(() => { setDrawerOpen(false) }, [pathname])

  return (
    <div className="min-h-screen bg-admin-page font-sans text-admin-ink">
      <a
        href="#admin-main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-[70]
                   focus:rounded-admin-control focus:bg-white focus:px-4 focus:py-2 focus:text-[13px]
                   focus:font-semibold focus:text-admin-ink focus:shadow-md focus:outline-none
                   focus:ring-2 focus:ring-admin-red"
      >
        Skip to content
      </a>

      <aside className="fixed inset-y-0 left-0 z-40 hidden w-[197px] xl:block">
        <AdminSidebar variant="full" />
      </aside>
      <aside className="fixed inset-y-0 left-0 z-40 hidden w-[72px] md:block xl:hidden">
        <AdminSidebar variant="rail" onExpand={openDrawer} />
      </aside>

      {drawerOpen && <Drawer onClose={closeDrawer} />}

      <div className="md:pl-[72px] xl:pl-[197px]">
        <AdminTopBar onOpenMenu={openDrawer} menuOpen={drawerOpen} />
        <main id="admin-main" tabIndex={-1} className="px-4 pb-12 pt-3.5 focus:outline-none sm:pl-7 sm:pr-6">
          <RouteErrorBoundary>
            <Suspense
              fallback={
                <div className="grid min-h-[50vh] place-items-center text-admin-faint">
                  <Spinner size={28} />
                </div>
              }
            >
              <Outlet />
            </Suspense>
          </RouteErrorBoundary>
        </main>
      </div>
    </div>
  )
}

/**
 * The full navigation over the page, for widths too narrow to keep it beside it.
 *
 * <p>A modal in the proper sense: focus moves into it, Tab cannot leave it, Escape and the
 * backdrop both close it, and focus goes back to whatever opened it. The page behind is
 * not scrolled while it is open.
 */
function Drawer({ onClose }: { onClose: () => void }) {
  const panel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null
    const focusables = () => Array.from(
      panel.current?.querySelectorAll<HTMLElement>('a[href], button:not([disabled])') ?? [],
    ).filter((el) => el.offsetParent !== null)

    focusables()[0]?.focus()
    const previousOverflow = document.body.style.overflow
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
      document.body.style.overflow = previousOverflow
      opener?.focus?.()
    }
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50">
      <div aria-hidden="true" onClick={onClose} className="absolute inset-0 bg-black/45" />
      <div
        ref={panel}
        id="admin-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="Admin menu"
        className="absolute inset-y-0 left-0 flex w-[280px] max-w-[85vw] flex-col shadow-lg"
      >
        <button
          type="button"
          onClick={onClose}
          aria-label="Close menu"
          className="absolute right-2 top-2 z-10 grid h-9 w-9 place-items-center rounded-admin-control
                     text-admin-sidebar-text hover:bg-white/[0.07] focus-visible:outline-none
                     focus-visible:ring-2 focus-visible:ring-white/80"
        >
          <LuX aria-hidden="true" className="h-5 w-5" />
        </button>
        <AdminSidebar variant="full" />
      </div>
    </div>
  )
}
