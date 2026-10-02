import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Account, SupportContext } from '../lib/types'

/*
 * The support pages, through the app's own routes: who may open them, what they show,
 * and that the chat lives on them and nowhere else.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const ApiErrorClass = vi.hoisted(() => class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
})
vi.mock('../lib/api', () => ({ api, ApiError: ApiErrorClass }))

const auth = vi.hoisted(() => ({ account: null as Account | null }))
vi.mock('../state/AuthContext', () => ({ useAuth: () => ({ account: auth.account, loading: false }) }))

// The site's chrome, as stand-ins: this is about the routes, not the header.
vi.mock('../components/Header', () => ({ Header: () => null }))
vi.mock('../components/Footer', () => ({ Footer: () => null }))
vi.mock('../components/CookieNotice', () => ({ CookieNotice: () => null }))
vi.mock('../components/NotificationToasts', () => ({ NotificationToasts: () => null }))
vi.mock('../components/CursorLight', () => ({ CursorLight: () => null }))
vi.mock('../components/AskWidget', () => ({ AskWidget: () => <div data-testid="ask-widget" /> }))

/** Where sign-in was asked to send the customer back to. */
function LoginStandIn() {
  const location = useLocation()
  return <p>login page, then back to {(location.state as { from?: string } | null)?.from}</p>
}
vi.mock('./Login', () => ({ default: LoginStandIn }))
vi.mock('./Home', () => ({ default: () => <p>home page</p> }))
vi.mock('./Order', () => ({ default: () => <p>order page</p> }))
vi.mock('./BoostingCheckout', () => ({ default: () => <p>checkout page</p> }))
vi.mock('./Help', () => ({ default: () => <p>help page</p> }))
vi.mock('./admin/shell/AdminShell', async () => {
  const { Outlet } = await import('react-router-dom')
  return { default: () => <Outlet /> }
})
vi.mock('./admin/Admin', () => ({ default: () => <p>admin orders</p> }))

// jsdom has no matchMedia; the storefront's reveal animations ask it about reduced motion.
window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: App } = await import('../App')
const { resetTawkForTests } = await import('../lib/tawk')

const OWNER: Account = {
  publicId: 'acc_owner', email: 'rahul@example.test', displayName: 'Rahul', role: 'CUSTOMER',
  pointsBalance: 0, pointsValueMinor: 0, pointsValueFormatted: '₹0.00', firstOrder: false, referredByCode: null,
}

function context(mode: SupportContext['mode'], patch: Partial<SupportContext['summary']> = {}): SupportContext {
  return {
    mode,
    summary: { reference: 'GFS-26-70C4DPWH', service: 'Champs Boosting — 14 wins', platform: 'PlayStation',
      status: 'Queued', ...patch },
    chat: { name: 'Rahul', email: 'rahul@example.test', hash: null,
      attributes: { 'order-id': 'GFS-26-70C4DPWH', service: 'Champs Boosting — 14 wins', platform: 'PlayStation',
        'order-status': 'Queued', 'coin-amount': 'n/a', 'session-id': 'n/a', coach: 'n/a' } },
  }
}

function WhereAmI() {
  return <p data-testid="path">{useLocation().pathname}</p>
}

function renderAt(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><App /><WhereAmI /></MemoryRouter>)
}

const tawkLoaded = () => [...document.querySelectorAll('script')].some((s) => s.src.includes('tawk.to'))
  || window.Tawk_API !== undefined

beforeEach(() => {
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  api.get.mockReset()
  auth.account = OWNER
})
afterEach(() => {
  resetTawkForTests()
  vi.unstubAllEnvs()
})

describe('who may open a support page', () => {
  it('signed out: sign in first, then straight back to the same support page', async () => {
    auth.account = null
    renderAt('/orders/GFS-26-70C4DPWH/support')
    expect(await screen.findByText('login page, then back to /orders/GFS-26-70C4DPWH/support')).toBeInTheDocument()
    expect(api.get).not.toHaveBeenCalled()
  })

  it('somebody else\'s order, or one that does not exist: the ordinary not-found page, alike', async () => {
    api.get.mockRejectedValue(new ApiErrorClass(404, 'No such order.'))
    renderAt('/orders/GFS-26-NOTYOURS/support')
    expect(await screen.findByText('That page does not exist')).toBeInTheDocument()
    expect(screen.queryByText(/No such order/)).toBeNull()
  })

  it('the coaching address works only for coaching orders, and the other way round, by what the order is', async () => {
    api.get.mockResolvedValue(context('COACHING', { session: 'CS-1', coach: 'Vinay' }))
    renderAt('/orders/GFS-26-70C4DPWH/support')
    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/coaching/GFS-26-70C4DPWH/support'))

    api.get.mockResolvedValue(context('COINS', { coins: '500K' }))
    renderAt('/coaching/GFS-26-70C4DPWH/support')
    await waitFor(() => {
      const paths = screen.getAllByTestId('path')
      expect(paths[paths.length - 1]).toHaveTextContent('/orders/GFS-26-70C4DPWH/support')
    })
  })
})

describe('the page', () => {
  it.each([
    ['BOOSTING' as const, {}, 'Connect with GFS', []],
    ['COINS' as const, { coins: '500K' }, 'Connect with GFS', ['500K']],
    ['COACHING' as const, { session: 'CS-1', coach: 'Vinay' }, 'Connect with Your Coach', ['Vinay']],
  ])('%s: the order at the top, the chat below, in the page', async (mode, patch, title, extra) => {
    api.get.mockResolvedValue(context(mode, patch))
    renderAt(mode === 'COACHING' ? '/coaching/GFS-26-70C4DPWH/support' : '/orders/GFS-26-70C4DPWH/support')

    expect(await screen.findByRole('heading', { name: title })).toBeInTheDocument()
    const summary = screen.getByTestId('support-summary')
    expect(summary).toHaveTextContent('#GFS-26-70C4DPWH')
    expect(summary).toHaveTextContent('Queued')
    for (const text of extra) expect(summary).toHaveTextContent(text)
    expect(api.get).toHaveBeenCalledWith('/api/v1/orders/GFS-26-70C4DPWH/support-context')
    // The way back to the order stays on this site.
    expect(screen.getByRole('link', { name: /Back to your order/ })).toHaveAttribute('href', '/track?ref=GFS-26-70C4DPWH')
  })

  it('the help assistant\'s bubble is not shown here, where the chat is; it is everywhere else', async () => {
    api.get.mockResolvedValue(context('BOOSTING'))
    renderAt('/orders/GFS-26-70C4DPWH/support')
    await screen.findByTestId('support-summary')
    expect(screen.queryByTestId('ask-widget')).toBeNull()

    renderAt('/help')
    expect(await screen.findByText('help page')).toBeInTheDocument()
    expect(screen.getByTestId('ask-widget')).toBeInTheDocument()
  })
})

describe('tawk.to stays on support pages', () => {
  it.each([['/'], ['/order'], ['/boosting/checkout'], ['/admin/orders']])(
    '%s loads nothing of it, with the chat configured', async (path) => {
      vi.stubEnv('VITE_TAWK_PROPERTY_ID', 'prop123')
      vi.stubEnv('VITE_TAWK_EMBED_WIDGET_ID', 'widget1')
      auth.account = { ...OWNER, role: 'ADMIN' }
      renderAt(path)
      await screen.findByText(/home page|order page|checkout page|admin orders/)
      expect(tawkLoaded()).toBe(false)
    })

  it('and even on a support page, nothing until Start chat', async () => {
    vi.stubEnv('VITE_TAWK_PROPERTY_ID', 'prop123')
    vi.stubEnv('VITE_TAWK_EMBED_WIDGET_ID', 'widget1')
    api.get.mockResolvedValue(context('BOOSTING'))
    renderAt('/orders/GFS-26-70C4DPWH/support')
    expect(await screen.findByRole('button', { name: 'Start chat' })).toBeInTheDocument()
    expect(tawkLoaded()).toBe(false)
  })
})
