import { Component, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'

/**
 * The last line before a white screen.
 *
 * <p>React unmounts the entire tree when a render throws. Without a boundary the
 * result is not a broken page — it is a blank document, with the header, the footer
 * and every escape route gone too. A visitor who hits that has no way back other
 * than the browser's own controls, and no idea whether the fault was theirs.
 *
 * <p>This was not hypothetical here. The rewards page read
 * `policy.loyaltyTiers[policy.loyaltyTiers.length - 1]` against a policy payload that
 * did not carry the field, and the resulting TypeError took down the whole site —
 * every subsequent route returned an empty document until a hard reload. The field
 * is derived from an enum server-side and is always present in practice, which is
 * exactly why nobody noticed: the crash needed a malformed response to appear, and
 * when it appeared it cost everything rather than one page.
 *
 * <p><b>Reset on navigation.</b> A boundary that latches is only marginally better
 * than the white screen, because the fallback then follows you around the site. The
 * `resetKey` is the pathname; changing route clears the error and lets the next page
 * render normally. That makes "go somewhere else" a real recovery, which is what the
 * fallback tells the visitor to do.
 */
export class ErrorBoundary extends Component<
  { children: ReactNode; resetKey?: string; fallback?: (reset: () => void) => ReactNode },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidUpdate(prev: { resetKey?: string }) {
    if (this.state.error && prev.resetKey !== this.props.resetKey) {
      this.setState({ error: null })
    }
  }

  componentDidCatch(error: Error) {
    /*
     * Console rather than a reporting service, because there is no reporting service
     * wired up and a silent catch is worse than a noisy one: it turns a crash into a
     * mystery. When error reporting ships, this is the single place it attaches.
     */
    console.error('Render error caught by boundary:', error)
  }

  private reset = () => this.setState({ error: null })

  render() {
    if (!this.state.error) return this.props.children
    if (this.props.fallback) return this.props.fallback(this.reset)

    return (
      <section className="mx-auto w-full max-w-[1320px] px-5 py-24 sm:px-8 lg:px-10">
        <div className="plate mx-auto max-w-xl px-8 py-10 text-center">
          <p className="text-[10.5px] font-semibold uppercase tracking-[0.16em] text-brand-400">
            Something broke
          </p>
          <h1 className="display mt-3 text-display-sm text-chalk">
            This page didn’t load
          </h1>
          <p className="measure mx-auto mt-3 text-body-sm text-chalk-muted">
            The rest of the site is fine — it’s this page specifically. Try again, or head
            back and take another route.
          </p>
          <div className="mt-7 flex flex-wrap justify-center gap-3">
            <button
              type="button"
              onClick={this.reset}
              className="inline-flex min-h-[44px] items-center rounded-edge bg-brand-500 px-5
                         text-[13.5px] font-semibold text-paper transition-colors duration-200
                         hover:bg-brand-600"
            >
              Try again
            </button>
            <a
              href="/"
              className="inline-flex min-h-[44px] items-center rounded-edge border border-ink-400
                         px-5 text-[13.5px] font-semibold text-chalk transition-colors
                         duration-200 hover:bg-ink-500"
            >
              Go to the homepage
            </a>
          </div>
        </div>
      </section>
    )
  }
}

/**
 * The boundary, wired to the router.
 *
 * <p>Exists so `App` does not have to call a hook purely to feed the reset key. If it
 * did, every navigation would re-render the whole shell — the cursor light, the
 * atmosphere canvas — to change one string. Here only this wrapper re-renders, and
 * `App` stays a plain function.
 */
export function RouteErrorBoundary({ children }: { children: ReactNode }) {
  const { pathname } = useLocation()
  return <ErrorBoundary resetKey={pathname}>{children}</ErrorBoundary>
}
