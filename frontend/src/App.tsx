import { Suspense, lazy, useEffect } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { RouteErrorBoundary } from './components/ErrorBoundary'
import { CookieNotice } from './components/CookieNotice'
import { NotificationToasts } from './components/NotificationToasts'
import { Footer } from './components/Footer'
import { AskWidget } from './components/AskWidget'
import { CursorLight } from './components/CursorLight'
import { Header } from './components/Header'
import { Spinner } from './components/ui'
import { useAuth } from './state/AuthContext'

/*
 * Routes are lazily loaded, so a visitor landing on the homepage does not download
 * the admin console, the checkout flow and three legal documents before anything
 * paints. On the connections this audience actually browses on, that is the
 * difference between a fast site and a slow one.
 */
const Home = lazy(() => import('./pages/Home'))
const Order = lazy(() => import('./pages/Order'))
const Boosting = lazy(() => import('./pages/Boosting'))
const Rewards = lazy(() => import('./pages/Rewards'))
const Track = lazy(() => import('./pages/Track'))
const Help = lazy(() => import('./pages/Help'))
const Support = lazy(() => import('./pages/Support'))
const Legal = lazy(() => import('./pages/Legal'))
const About = lazy(() => import('./pages/About'))
const ComingSoon = lazy(() => import('./pages/ComingSoon'))
const Coaching = lazy(() => import('./pages/Coaching'))
const BoostingCheckout = lazy(() => import('./pages/BoostingCheckout'))
const CoachingBook = lazy(() => import('./pages/CoachingBook'))
const Login = lazy(() => import('./pages/Login'))
const AuthCallback = lazy(() => import('./pages/AuthCallback'))
const Account = lazy(() => import('./pages/Account'))
const Admin = lazy(() => import('./pages/admin/Admin'))
const AdminOrder = lazy(() => import('./pages/admin/AdminOrder'))
const AdminCoupons = lazy(() => import('./pages/admin/AdminCoupons'))
const AdminRates = lazy(() => import('./pages/admin/AdminRates'))
const AdminCampaigns = lazy(() => import('./pages/admin/AdminCampaigns'))
const AdminCoaching = lazy(() => import('./pages/admin/AdminCoaching'))
const Unsubscribe = lazy(() => import('./pages/Unsubscribe'))
const NotFound = lazy(() => import('./pages/NotFound'))

export default function App() {
  return (
    /*
      `isolate` matters here.

      The cursor light is a fixed, positioned element; the page content below it is
      mostly static flow, and static content paints *under* positioned siblings. Without
      a stacking context of its own and an explicit layer for the content, the light
      would sit on top of the whole site instead of behind it. So: the root isolates,
      the light takes layer 0, and the content takes layer 1.
    */
    <div className="relative isolate flex min-h-screen flex-col">
      <CursorLight />
      <ScrollToTop />
      <Header />
      <main id="main" className="relative z-10 flex-1">
        <RouteErrorBoundary>
        <Suspense fallback={<RouteFallback />}>
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/order" element={<Order />} />
            <Route path="/boosting" element={<Boosting />} />
            <Route path="/rewards" element={<Rewards />} />
            <Route path="/track" element={<Track />} />
            {/* Public and unguarded on purpose: this is opened from an email by
                somebody who is not signed in, and making them sign in to leave a
                mailing list is how people report mail as spam instead. */}
            <Route path="/unsubscribe" element={<Unsubscribe />} />
            <Route path="/help" element={<Help />} />
            <Route path="/support" element={<Support />} />

            <Route path="/terms" element={<Legal doc="terms" />} />
            <Route path="/privacy" element={<Legal doc="privacy" />} />
            <Route path="/aml-kyc" element={<Legal doc="aml" />} />
            {/*
              The policy pages a payment gateway checks for by name. The paths use the
              wording a reviewer searches for rather than something shorter — /shipping
              on a site that ships nothing is deliberate, because that is the name on
              the checklist.
            */}
            <Route path="/refund-policy" element={<Legal doc="refund" />} />
            <Route path="/cancellation-policy" element={<Legal doc="cancellation" />} />
            <Route path="/shipping-policy" element={<Legal doc="shipping" />} />
            <Route path="/about" element={<About />} />
            {/*
              /contact is the name people and reviewers look for; /support is the name
              this site has always used and the one existing links point at. Both render
              the same page rather than one redirecting, so neither link can rot.
            */}
            <Route path="/contact" element={<Support />} />

            {/*
              Cards remains priced but not sellable. Coaching shipped: the reference
              site still has both behind a placeholder, so this is the one place the
              product is now ahead of it rather than level with it.
            */}
            <Route path="/cards" element={<ComingSoon service="Player Cards" />} />
            <Route path="/coaching" element={<Coaching />} />
            <Route path="/coaching/book" element={<CoachingBook />} />
            <Route path="/boosting/checkout" element={<BoostingCheckout />} />

            <Route path="/login" element={<Login mode="login" />} />
            <Route path="/register" element={<Login mode="register" />} />
            {/* Where Google and Discord send the browser back. */}
            <Route path="/auth/callback" element={<AuthCallback />} />

            <Route path="/account" element={<RequireAuth><Account /></RequireAuth>} />
            <Route path="/admin" element={<RequireStaff><Admin /></RequireStaff>} />
            <Route path="/admin/orders/:publicRef"
                   element={<RequireStaff><AdminOrder /></RequireStaff>} />
            <Route path="/admin/coupons"
                   element={<RequireStaff><AdminCoupons /></RequireStaff>} />
            {/* RequireAdmin, not RequireStaff: this screen sets what customers are
                charged, and the endpoint behind it is hasRole('ADMIN'). An operator
                reaching it would see a form that 403s on save. */}
            <Route path="/admin/rates"
                   element={<RequireAdmin><AdminRates /></RequireAdmin>} />
            {/* Same reasoning as the rate card: a campaign reaches every opted-in
                customer at once and cannot be recalled, so ADMIN rather than staff. */}
            <Route path="/admin/campaigns"
                   element={<RequireAdmin><AdminCampaigns /></RequireAdmin>} />
            {/* Operator, not admin: marking who turned up is fulfilment work. Setting
                the weekly hours on the same screen is the exception the endpoint itself
                guards — that call is ADMIN and refuses an operator. */}
            <Route path="/admin/coaching"
                   element={<RequireStaff><AdminCoaching /></RequireStaff>} />

            <Route path="*" element={<NotFound />} />
          </Routes>
        </Suspense>
        </RouteErrorBoundary>
      </main>
      <Footer />

      {/* Shown until this visitor answers, and never again after they do. */}
      <CookieNotice />

      {/* Real-time only: anything older than this page load is read in the bell. */}
      <NotificationToasts />
      {/* Outside <main> and fixed-positioned: it follows the reader across every
          route rather than being a thing you have to navigate to. */}
      <AskWidget />
    </div>
  )
}

function RouteFallback() {
  return (
    <div className="grid min-h-[55vh] place-items-center text-chalk-faint">
      <Spinner size={28} />
    </div>
  )
}

/**
 * New route, top of the page. Browsers do not do this for client-side navigation.
 *
 * <p>Skipped when the URL carries a hash. Search results deep-link into individual
 * help answers, and scrolling to the top of a page someone asked to enter partway
 * down undoes the only thing the link was for.
 */
function ScrollToTop() {
  const { pathname, hash } = useLocation()
  useEffect(() => {
    if (hash) return
    window.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior })
  }, [pathname, hash])
  return null
}

/**
 * Route guards.
 *
 * These are a convenience, not a security control. Everything they protect is also
 * enforced server-side, because a guard in a JavaScript bundle is a suggestion —
 * anyone can edit it out in the console. Their job is to avoid showing a signed-out
 * visitor an empty console and a stack of failed requests.
 */
function RequireAuth({ children }: { children: React.ReactNode }) {
  const { account, loading } = useAuth()
  const location = useLocation()
  if (loading) return <RouteFallback />
  // The whole address, not just the path: a query string is where an order keeps its
  // service and package, and dropping it lands a boosting customer on coins.
  if (!account) return <Navigate to="/login" state={{ from: fullPath(location) }} replace />
  return <>{children}</>
}

function RequireStaff({ children }: { children: React.ReactNode }) {
  const { account, loading } = useAuth()
  const location = useLocation()
  if (loading) return <RouteFallback />
  if (!account) return <Navigate to="/login" state={{ from: fullPath(location) }} replace />
  if (account.role === 'CUSTOMER') return <Navigate to="/account" replace />
  return <>{children}</>
}

/**
 * ADMIN only — stricter than {@link RequireStaff}, which admits operators too.
 *
 * <p>An operator fulfils orders; changing what the business charges is a different kind
 * of authority, and the server draws the line in the same place. An operator who reaches
 * an admin-only route is sent to the console they do have, not to the login page: they
 * are signed in, and asking them to sign in again would suggest otherwise.
 */
function RequireAdmin({ children }: { children: React.ReactNode }) {
  const { account, loading } = useAuth()
  const location = useLocation()
  if (loading) return <RouteFallback />
  if (!account) return <Navigate to="/login" state={{ from: fullPath(location) }} replace />
  if (account.role !== 'ADMIN') {
    return <Navigate to={account.role === 'CUSTOMER' ? '/account' : '/admin'} replace />
  }
  return <>{children}</>
}

function fullPath(location: { pathname: string; search: string; hash: string }) {
  return `${location.pathname}${location.search}${location.hash}`
}
