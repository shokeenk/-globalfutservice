import { Suspense, lazy, useCallback, useEffect, useRef, useState } from 'react'
import { Link, NavLink, useLocation } from 'react-router-dom'
import { Logo } from '../brand/Logo'
import { useT } from '../i18n'
import { useScrollProgress, useScrolled } from '../motion'
import { useAuth } from '../state/AuthContext'
import { CurrencySwitcher, LanguageSwitcher } from './LocaleSwitchers'
import { NavIcon } from './NavIcon'
import { SearchIconButton, SearchTrigger } from './SearchTrigger'
import { Button, ButtonLink } from './ui'

/*
 * The palette, its matcher and its corpus are about twenty kilobytes, and the header
 * that would carry them renders on every page. Loading them on demand keeps that off
 * the critical path for the large majority of visitors who never open search; the
 * triggers prefetch on hover and focus, so by the time anyone clicks it has arrived.
 */
const SearchDialog = lazy(() => import('./SearchDialog'))
const prefetchSearch = () => { void import('./SearchDialog') }

export function Header() {
  const { account, logout } = useAuth()
  const t = useT()

  // Built from the dictionary rather than a module constant, so the labels change
  // with the language instead of being frozen at import time.
  const NAV = [
    { to: '/order', label: t.nav.trading, icon: 'trading' },
    { to: '/boosting', label: t.nav.boosting, icon: 'boosting' },
    { to: '/coaching', label: t.nav.coaching, icon: 'coaching' },
    { to: '/rewards', label: t.nav.rewards, icon: 'rewards' },
    { to: '/track', label: t.nav.track, icon: 'track' },
    { to: '/help', label: t.nav.faqs, icon: 'faqs' },
  ]

  const [open, setOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const closeSearch = useCallback(() => setSearchOpen(false), [])
  const scrolled = useScrolled(8)
  const progressRef = useScrollProgress<HTMLDivElement>()
  const location = useLocation()

  useEffect(() => setOpen(false), [location.pathname])

  /*
   * Cmd-K / Ctrl-K, the shortcut people already have in their fingers.
   *
   * Bound on the window rather than on a field, because the whole point is that it
   * works from anywhere on the page. The guard matters as much as the binding: while
   * someone is typing into the coupon box or the support form, Ctrl-K belongs to
   * them, and a palette that steals it mid-sentence is worse than no shortcut.
   */
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'k' && event.key !== 'K') return
      if (!event.metaKey && !event.ctrlKey) return
      const el = document.activeElement
      const typing =
        el instanceof HTMLInputElement ||
        el instanceof HTMLTextAreaElement ||
        (el instanceof HTMLElement && el.isContentEditable)
      if (typing) return
      event.preventDefault()
      prefetchSearch()
      setSearchOpen(true)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return (
    <>
      {/* Keyboard users land here first. Without it, reaching the configurator on a
          page with a dozen nav links means a dozen tab presses, every time. */}
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50
                   focus:rounded-press focus:bg-brand-500 focus:px-4 focus:py-2 focus:text-sm
                   focus:font-semibold focus:text-paper"
      >
        {t.nav.skipToContent}
      </a>

      <header
        className={[
          'sticky top-0 z-40 w-full transition-all duration-500 ease-out-expo',
          /*
           * The bar materialises as you scroll rather than switching on. Heavier
           * blur, a saturation boost so colour passing underneath stays vivid
           * instead of turning to grey mush, and a hairline that is a highlight
           * rather than a border — light catching a raised edge, which is what a
           * floating surface does.
           */
          scrolled
            /*
             * On black the bar materialised as a lit edge: a white hairline along the
             * top of the shadow. On white that highlight is invisible, so the bar has
             * to separate the other way — a hairline of shade *below* it, plus a soft
             * drop. The blur and saturation stay; they are what keep colour passing
             * underneath from turning to mush.
             */
            ? 'bg-[rgba(255,255,255,0.82)] backdrop-blur-2xl backdrop-saturate-150 ' +
              'shadow-sm'
            : 'bg-transparent',
        ].join(' ')}
      >
        {/*
          Bar one: identity and the controls that decide what the whole site says —
          language, currency, who you are. Settings, not destinations.

          This row is 56px and the row below it is 64px. Their sum is duplicated in
          `index.css` as `--header-h`, which anchor scrolling reads so a jump to a
          section does not land underneath the header. Change a height here and
          change it there.

          Both shrank once the wordmark went to one line: stacked, it was as tall as
          the crest and the bar could not be shorter than it.
        */}
        <div className="mx-auto flex h-[56px] max-w-[1320px] items-center gap-6 px-5 sm:px-8 lg:px-10">
          <Link to="/" aria-label={t.nav.home} className="shrink-0">
            <Logo />
          </Link>

          <div className="ml-auto hidden items-center gap-3 lg:flex">
            <SearchTrigger onOpen={() => setSearchOpen(true)} onPrefetch={prefetchSearch} />

            {/* Ahead of the account controls: someone who lands on the wrong language
                or currency needs to fix that before anything else on the page is
                useful to them. */}
            <div className="flex items-center gap-1.5">
              <LanguageSwitcher />
              <CurrencySwitcher />
            </div>

            <span aria-hidden="true" className="h-5 w-px bg-ink-400" />

            {account ? (
              <>
                <Link
                  to={account.role === 'CUSTOMER' ? '/account' : '/admin'}
                  className="rounded-edge px-3 py-2 text-[13.5px] font-medium text-chalk-muted
                             transition-colors hover:text-chalk"
                >
                  {account.role === 'CUSTOMER' ? t.nav.myAccount : t.nav.console}
                </Link>
                <Button variant="ghost" size="sm" onClick={() => void logout()}>
                  {t.nav.signOut}
                </Button>
              </>
            ) : (
              <Link
                to="/login"
                className="rounded-edge px-3 py-2 text-[13.5px] font-medium text-chalk-muted
                           transition-colors hover:text-chalk"
              >
                {t.nav.signIn}
              </Link>
            )}

            {/*
              `md`, not `sm`. This is the site's primary conversion action and it was
              rendering at 32px — smaller than the nav links beside it, which inverts the
              hierarchy the colour is trying to establish. `sm` exists for dense pointer
              surfaces like the admin console, not for the one button the whole header
              is built around.
            */}
            <ButtonLink to="/order" size="md">
              {t.nav.buyCoins}
            </ButtonLink>
          </div>

          <div className="ml-auto flex items-center gap-2 lg:hidden">
          <SearchIconButton onOpen={() => setSearchOpen(true)} onPrefetch={prefetchSearch} />

          <button
            type="button"
            onClick={() => setOpen(true)}
            aria-expanded={open}
            aria-controls="mobile-nav"
            aria-label={t.nav.openMenu}
            className="grid h-11 w-11 place-items-center rounded-edge hairline
                       bg-paper text-chalk backdrop-blur-sm"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
              <path d="M3 6h18" />
              <path d="M3 12h18" />
              <path d="M3 18h18" />
            </svg>
          </button>
          </div>
        </div>

        {/*
          The navigation, on its own row.

          Two bars rather than one because the top row is doing a different job: it is
          the shop's identity plus the controls that decide what the whole site says —
          language, currency, who you are. Those are settings. The row below is where
          you are going. Mixed into one bar they compete for the same horizontal space,
          and the nav loses, which is how a six-item nav ends up as six 13px words
          squeezed between a logo and a login link.

          Desktop only. On a phone the same links live in the sheet behind the
          hamburger, at 56px a row, which is a better answer than six tiles at 60px
          wide.
        */}
        <div
          className={[
            'hidden border-t lg:block',
            scrolled ? 'border-ink-400/70 bg-paper/70' : 'border-ink-400/50 bg-paper',
            'backdrop-blur-xl transition-colors duration-500',
          ].join(' ')}
        >
          <nav
            aria-label="Main"
            className="mx-auto flex max-w-[1320px] items-stretch justify-center gap-1 px-5
                       sm:px-8 lg:px-10"
          >
            {NAV.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) =>
                  [
                    // 10 + 32 + 4 + 11 + 7 = 64px. See the note on bar one before changing it.
                    'group relative flex min-w-[84px] flex-col items-center gap-1 px-3.5 pb-[7px] pt-2.5',
                    'transition-colors duration-200',
                    isActive ? 'text-chalk' : 'text-chalk-muted hover:text-chalk',
                  ].join(' ')
                }
              >
                {({ isActive }) => (
                  <>
                    {/*
                      The glyph in a tinted tile. It fills with brand red on the active
                      route, which is the one place on this bar colour is spent — so
                      "where am I" is answerable without reading.
                    */}
                    <span
                      aria-hidden="true"
                      className={[
                        'grid h-8 w-8 place-items-center rounded-edge transition-[background-color,color,box-shadow,transform]',
                        'duration-300 ease-out-expo group-hover:-translate-y-0.5',
                        /*
                         * Every tile carries a border, not just the active one.
                         *
                         * The ring used to be the active marker on its own, which meant
                         * five of the six tiles were an untinted wash with no edge — they
                         * read as smudges behind their glyphs rather than as objects. Now
                         * the edge is what makes a tile a tile, and *weight* carries the
                         * state: the active ring is thicker and twice the opacity, so the
                         * difference is still visible without the border being the thing
                         * that appears and disappears.
                         */
                        isActive
                          ? 'bg-brand-500/[0.16] text-brand-500 ring-[1.5px] ring-brand-500/70'
                          : 'bg-brand-500/[0.10] text-brand-400 ring-1 ring-brand-500/35 group-hover:bg-brand-500/[0.16] group-hover:text-brand-500 group-hover:ring-brand-500/55',
                      ].join(' ')}
                    >
                      {/*
                        2.1, against 1.7 everywhere else. At 19px inside a 32px tile the
                        site's standard hairline reads thin next to the bold label beneath
                        it; this is the weight that makes the pair look like one object.
                      */}
                      <NavIcon name={item.icon} strokeWidth={2.1} />
                    </span>

                    <span className="text-[11px] font-bold leading-none">{item.label}</span>

                    {/*
                      No underline for the active route, deliberately.

                      The obvious choice is a rule on the bar's bottom edge — which is
                      exactly where the reading-progress indicator already lives, in the
                      same brand red. Two red lines on one edge, one of them moving, is
                      a puzzle rather than a signal. The tile carries the state instead:
                      it fills with brand red and gains a ring, so the active item
                      differs in colour *and* in shape, which is also what keeps it
                      legible for anyone who cannot separate the two reds.
                    */}
                  </>
                )}
              </NavLink>
            ))}
          </nav>
        </div>

        {/*
          Reading progress, drawn on the header's own bottom edge.

          Scaled from a full-width bar rather than animated by width, so it never
          triggers layout — and it is driven by a CSS custom property the scroll
          handler writes directly, so a page-long scroll costs zero React renders.
        */}
        <div
          ref={progressRef}
          aria-hidden="true"
          className={[
            'absolute inset-x-0 bottom-0 h-px origin-left bg-brand-500',
            'transition-opacity duration-500',
            scrolled ? 'opacity-100' : 'opacity-0',
          ].join(' ')}
          style={{ transform: 'scaleX(var(--progress, 0))' }}
        />
      </header>

      {searchOpen && (
        <Suspense fallback={null}>
          <SearchDialog onClose={closeSearch} />
        </Suspense>
      )}

      <MobileNav
        open={open}
        onClose={() => setOpen(false)}
        nav={NAV}
        account={account}
        onSignOut={() => void logout()}
      />
    </>
  )
}

/* ------------------------------------------------------------- mobile nav --- */

/**
 * The phone navigation, designed as its own thing.
 *
 * <p>The previous version dropped the desktop link list into a panel under the bar
 * at desktop type sizes — six 14px links with 10px of padding, which on a phone is a
 * row of targets you have to aim at. This is a full-height sheet with the links set
 * at display size, each one a 56px row you can hit without looking.
 *
 * <p>Three things it does that a dropdown does not, and all three are the difference
 * between a menu that works on a phone and one that merely appears on it:
 *
 * <ul>
 *   <li>Locks the page behind it, so the sheet does not scroll the article underneath
 *       when it reaches its own end.</li>
 *   <li>Closes on Escape and traps nothing — focus moves to the close button on open
 *       and returns to the trigger on close, which is what a screen-reader user
 *       expects and what a keyboard user needs to not get stranded.</li>
 *   <li>Staggers the rows in at 40ms apart. Small, but it makes the sheet feel like
 *       it is assembling rather than being pasted over the page.</li>
 * </ul>
 */
function MobileNav({
  open, onClose, nav, account, onSignOut,
}: {
  open: boolean
  onClose: () => void
  nav: { to: string; label: string }[]
  account: { role: string } | null
  onSignOut: () => void
}) {
  const t = useT()
  const closeRef = useRef<HTMLButtonElement | null>(null)
  // Remembering the trigger means focus goes back where it came from rather than to
  // the top of the document, which is where a naive close sends it.
  const returnRef = useRef<Element | null>(null)

  useEffect(() => {
    if (!open) return
    returnRef.current = document.activeElement

    const { overflow } = document.body.style
    document.body.style.overflow = 'hidden'

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)

    // Deferred a frame: focusing an element that is still transitioning in makes
    // some browsers scroll the sheet to reach it.
    const focusTimer = window.setTimeout(() => closeRef.current?.focus(), 60)

    return () => {
      document.body.style.overflow = overflow
      document.removeEventListener('keydown', onKey)
      window.clearTimeout(focusTimer)
      if (returnRef.current instanceof HTMLElement) returnRef.current.focus()
    }
  }, [open, onClose])

  return (
    <div
      id="mobile-nav"
      className={[
        'fixed inset-0 z-50 lg:hidden',
        open ? 'pointer-events-auto' : 'pointer-events-none',
      ].join(' ')}
      aria-hidden={!open}
    >
      {/* Scrim. Blurred rather than merely darkened — it puts the page visibly behind
          glass, which reads as a layer rather than as a dimmer switch. */}
      <div
        onClick={onClose}
        className={[
          /*
           * A scrim has to be darker than the page it covers. `bg-ink/80` was the
           * near-black page at 80% — correct on a dark theme and useless here, where
           * it would lay light grey over light grey and dim nothing at all. The sheet
           * would have floated on an undimmed page.
           */
          'absolute inset-0 bg-[rgba(17,17,20,0.42)] backdrop-blur-md transition-opacity duration-300',
          open ? 'opacity-100' : 'opacity-0',
        ].join(' ')}
      />

      <div
        role="dialog"
        aria-modal="true"
        aria-label={t.nav.openMenu}
        className={[
          'absolute inset-x-0 top-0 flex max-h-dvh flex-col overflow-y-auto',
          'border-b border-ink-400 bg-ink-800 shadow-e3',
          'transition-transform duration-500 ease-out-expo',
          open ? 'translate-y-0' : '-translate-y-full',
        ].join(' ')}
      >
        <div className="flex h-[56px] shrink-0 items-center justify-between px-5">
          <Logo />
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            aria-label={t.nav.closeMenu}
            className="grid h-11 w-11 place-items-center rounded-edge hairline bg-ink-700 text-chalk"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 strokeWidth="1.8" strokeLinecap="round" aria-hidden="true">
              <path d="M18 6 6 18" />
              <path d="m6 6 12 12" />
            </svg>
          </button>
        </div>

        <nav className="px-5 pb-2" aria-label="Mobile">
          {nav.map((item, index) => (
            <NavLink
              key={item.to}
              to={item.to}
              style={{ transitionDelay: open ? `${90 + index * 40}ms` : '0ms' }}
              className={({ isActive }) =>
                [
                  'flex items-center justify-between border-b border-ink-400/70 py-4',
                  'display text-[22px] transition-all duration-500 ease-out-expo',
                  open ? 'translate-y-0 opacity-100' : 'translate-y-3 opacity-0',
                  isActive ? 'text-chalk' : 'text-chalk-muted',
                ].join(' ')
              }
            >
              {({ isActive }) => (
                <>
                  <span>{item.label}</span>
                  {isActive ? (
                    <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-brand-500" />
                  ) : (
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
                         stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"
                         className="text-ink-300" aria-hidden="true">
                      <path d="M9 6l6 6-6 6" />
                    </svg>
                  )}
                </>
              )}
            </NavLink>
          ))}
        </nav>

        <div className="mt-auto space-y-4 px-5 pb-8 pt-6">
          <div className="flex items-center gap-2">
            <LanguageSwitcher className="flex-1 [&>select]:w-full" />
            <CurrencySwitcher className="flex-1 [&>select]:w-full" />
          </div>

          <div className="flex items-center gap-2">
            <ButtonLink
              to={account ? (account.role === 'CUSTOMER' ? '/account' : '/admin') : '/login'}
              variant="secondary"
              size="md"
              className="flex-1"
            >
              {account ? t.nav.myAccount : t.nav.signIn}
            </ButtonLink>
            {account && (
              <Button variant="ghost" size="md" onClick={onSignOut}>
                {t.nav.signOut}
              </Button>
            )}
          </div>

          <ButtonLink to="/order" full size="lg">
            {t.nav.buyCoins}
          </ButtonLink>
        </div>
      </div>
    </div>
  )
}
