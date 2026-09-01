import { Suspense, lazy, useEffect } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { RouteErrorBoundary } from './components/ErrorBoundary'
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
const ComingSoon = lazy(() => import('./pages/ComingSoon'))
const Coaching = lazy(() => import('./pages/Coaching'))
const Login = lazy(() => import('./pages/Login'))
const AuthCallback = lazy(() => import('./pages/AuthCallback'))
const Account = lazy(() => import('./pages/Account'))
const Admin = lazy(() => import('./pages/admin/Admin'))
const AdminOrder = lazy(() => import('./pages/admin/AdminOrder'))
const AdminCoupons = lazy(() => import('./pages/admin/AdminCoupons'))
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
            <Route path="/help" element={<Help />} />
            <Route path="/support" element={<Support />} />

            <Route path="/terms" element={<Legal doc="terms" />} />
            <Route path="/privacy" element={<Legal doc="privacy" />} />
            <Route path="/aml-kyc" element={<Legal doc="aml" />} />

            {/*
              Cards remains priced but not sellable. Coaching shipped: the reference
              site still has both behind a placeholder, so this is the one place the
              product is now ahead of it rather than level with it.
            */}
            <Route path="/cards" element={<ComingSoon service="Player Cards" />} />
            <Route path="/coaching" element={<Coaching />} />

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

            <Route path="*" element={<NotFound />} />
          </Routes>
        </Suspense>
        </RouteErrorBoundary>
      </main>
      <Footer />
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
  if (!account) return <Navigate to="/login" state={{ from: location.pathname }} replace />
  return <>{children}</>
}

function RequireStaff({ children }: { children: React.ReactNode }) {
  const { account, loading } = useAuth()
  if (loading) return <RouteFallback />
  if (!account) return <Navigate to="/login" replace />
  if (account.role === 'CUSTOMER') return <Navigate to="/account" replace />
  return <>{children}</>
}
