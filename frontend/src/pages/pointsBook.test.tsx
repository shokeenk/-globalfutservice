import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import en from '../i18n/en'
import es from '../i18n/es'
import fr from '../i18n/fr'
import type { PointsBreakdown, PointsHistoryPage, PointsLine } from '../lib/types'

/*
 * "I earned 49 but can only use 3": the account page's breakdown, which always adds up to
 * what is available, the points on the way, and the history with the balance after every
 * line. The staff view of the same, in Admin → Customers.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', async (original) => ({ ...(await original<typeof import('../lib/api')>()), api }))
vi.mock('../state/AuthContext', () => ({
  useAuth: () => ({ account: { displayName: 'Ada', role: 'CUSTOMER' }, loading: false }),
}))
vi.mock('../components/Atmosphere', () => ({ Atmosphere: () => null }))
vi.mock('../components/StoreCredit', () => ({ StoreCreditBalance: () => null }))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { I18nProvider } = await import('../i18n')
const { default: Account } = await import('./Account')
const { PointsBreakdownCard } = await import('../components/PointsBook')
const { CustomerPoints } = await import('./admin/customers/CustomerPoints')

/** The customer who asked: 49 earned, 46 spent, 3 left, and two orders on the way. */
const ASKED: PointsBreakdown = {
  available: 3, earned: 49, spent: 46, returned: 0, removed: 0, onTheWay: 37,
  pending: [
    { orderRef: 'GFS-26-WAIT01', points: 25, landsAt: '2026-10-28T09:00:00Z' },
    { orderRef: 'GFS-26-WAIT02', points: 12, landsAt: null },
  ],
}

function line(id: number, over: Partial<PointsLine>): PointsLine {
  return { id, at: '2026-10-20T10:00:00Z', orderRef: null, kind: 'EARNED', amount: 0, balanceAfter: 0, ...over }
}

const HISTORY: PointsLine[] = [
  line(4, { kind: 'SPENT', orderRef: 'GFS-26-SPEND1', amount: -46, balanceAfter: 3 }),
  line(3, { kind: 'DAILY_BONUS', amount: 5, balanceAfter: 49 }),
  line(2, { kind: 'DAILY_BONUS', amount: 4, balanceAfter: 44 }),
  line(1, { kind: 'EARNED', orderRef: 'GFS-26-EARN01', amount: 40, balanceAfter: 40 }),
]

function serve(breakdown: PointsBreakdown, lines: PointsLine[], size = 20) {
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/account/wallet') {
      return { balance: breakdown.available, lifetimeEarned: breakdown.earned, valueMinor: breakdown.available * 100,
        valueFormatted: `₹${breakdown.available}.00`, pointValueMinor: 100, earnSpendUnitMinor: 200000,
        earnPointsPerUnit: 20, maxRedemptionBps: 2000, statement: [] }
    }
    if (path === '/api/v1/account/points') return breakdown
    if (path.startsWith('/api/v1/account/points/history')) {
      const page = Number(new URLSearchParams(path.split('?')[1]).get('page') ?? 0)
      return { entries: lines.slice(page * size, page * size + size), page, size, total: lines.length } satisfies PointsHistoryPage
    }
    if (path === '/api/v1/account/marketing') return { optIn: false }
    if (path === '/api/v1/orders' || path === '/api/v1/support/tickets') return []
    throw new Error(path)
  })
}

function account(lang?: 'es' | 'fr') {
  if (lang) window.localStorage.setItem('gfs.language', lang)
  return render(<I18nProvider><MemoryRouter><Account /></MemoryRouter></I18nProvider>)
}

beforeEach(() => window.localStorage.clear())
afterEach(() => vi.clearAllMocks())

describe('the account page', () => {
  it('shows available, earned, spent and returned, and how they add up to the balance', async () => {
    serve(ASKED, HISTORY)
    account()
    const card = await screen.findByTestId('points-breakdown')
    expect(within(card).getByTestId('points-available')).toHaveTextContent('3')
    expect(within(card).getByText('Earned (all time)').nextSibling).toHaveTextContent('49')
    expect(within(card).getByText('Spent at checkout').nextSibling).toHaveTextContent('−46')
    expect(within(card).getByText('Returned from refunds').nextSibling).toHaveTextContent('0')
    expect(within(card).getByTestId('points-sum')).toHaveTextContent('49 earned − 46 spent = 3 available')
    expect(within(card).getByText('Points can cover up to 20% of an order.')).toBeInTheDocument()
    // "Earned 49 in total" with nothing else is gone.
    expect(screen.queryByText(/Earned 49 in total/)).toBeNull()
  })

  it('lists the points on the way, with the date each lands', async () => {
    serve(ASKED, HISTORY)
    account()
    expect(await screen.findByTestId('points-on-the-way')).toHaveTextContent('+37')
    const wait1 = screen.getByRole('link', { name: 'Order GFS-26-WAIT01' }).closest('li')!
    expect(wait1).toHaveTextContent(`+25 · lands ${new Date('2026-10-28T09:00:00Z').toLocaleDateString('en',
      { day: 'numeric', month: 'short', year: 'numeric' })}`)
    expect(screen.getByRole('link', { name: 'Order GFS-26-WAIT02' }).closest('li'))
      .toHaveTextContent('lands once delivered and the guarantee window closes')
  })

  it('the history: what each line was, its order, +/− and the balance after it', async () => {
    serve(ASKED, HISTORY)
    account()
    const rows = within(await screen.findByTestId('points-history')).getAllByTestId('points-line')
    expect(rows).toHaveLength(4)
    expect(rows[0]).toHaveTextContent('Spent · GFS-26-SPEND1')
    expect(rows[0]).toHaveTextContent('−46')
    expect(rows[0]).toHaveTextContent('Balance 3')
    expect(rows[1]).toHaveTextContent('Daily bonus')
    expect(rows[3]).toHaveTextContent('Earned · GFS-26-EARN01')
    expect(rows[3]).toHaveTextContent('+40')
    expect(within(rows[0]!).getByRole('link', { name: 'GFS-26-SPEND1' })).toHaveAttribute('href', '/track?ref=GFS-26-SPEND1')
  })

  it('a page at a time', async () => {
    const many = Array.from({ length: 45 }, (_, i) => line(45 - i, { amount: 1, balanceAfter: 45 - i }))
    serve({ ...ASKED, available: 45, earned: 45, spent: 0, onTheWay: 0, pending: [] }, many)
    const user = userEvent.setup()
    account()
    expect(within(await screen.findByTestId('points-history')).getAllByTestId('points-line')).toHaveLength(20)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Newer' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Older' }))
    await waitFor(() => expect(screen.getByText('Page 2 of 3')).toBeInTheDocument())
    expect(api.get).toHaveBeenCalledWith('/api/v1/account/points/history?page=1&size=20')
  })

  it('in Spanish and French', async () => {
    serve(ASKED, HISTORY)
    account('es')
    expect(await screen.findByText('Disponibles ahora')).toBeInTheDocument()
    expect(screen.getByTestId('points-sum')).toHaveTextContent('49 ganados − 46 gastados = 3 disponibles')
    expect(within(await screen.findByTestId('points-history')).getAllByTestId('points-line')[0])
      .toHaveTextContent('Gastados · GFS-26-SPEND1')
  })
})

describe('the numbers always add up', () => {
  const cases: PointsBreakdown[] = [
    ASKED,
    { available: 113, earned: 120, spent: 60, returned: 60, removed: 7, onTheWay: 0, pending: [] },
    { available: 0, earned: 0, spent: 0, returned: 0, removed: 0, onTheWay: 0, pending: [] },
  ]

  it.each(cases)('shows exactly what the server sent, and earned − spent + returned − removed = available (%#)', (b) => {
    render(<I18nProvider><MemoryRouter><PointsBreakdownCard breakdown={b} /></MemoryRouter></I18nProvider>)
    const shown = screen.getByTestId('points-sum').textContent!.replace(/,/g, '')
    const parts = [...shown.matchAll(/([−+=]?)\s*(\d+) (earned|spent|returned|removed|available)/g)]
      .map(([, , value, word]) => [word, Number(value)] as const)
    const v = Object.fromEntries(parts) as Record<string, number>
    expect(v.earned).toBe(b.earned)
    expect(v.spent).toBe(b.spent)
    expect(v.available).toBe(b.available)
    expect(v.earned! - v.spent! + (v.returned ?? 0) - (v.removed ?? 0)).toBe(v.available)
    expect(screen.getByTestId('points-available')).toHaveTextContent(String(b.available))
  })

  it('every word is in each language', () => {
    for (const d of [es, fr]) {
      expect(Object.keys(d.pointsBook).sort()).toEqual(Object.keys(en.pointsBook).sort())
      expect(Object.keys(d.pointsBook.kinds).sort()).toEqual(Object.keys(en.pointsBook.kinds).sort())
      expect(d.pointsBook.available).not.toBe(en.pointsBook.available)
    }
  })
})

describe('Admin → Customers → a customer', () => {
  it('shows staff the same breakdown, what is on the way, and the full history', async () => {
    api.get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/admin/customers/a-acc_ada/points') return ASKED
      if (path.startsWith('/api/v1/admin/customers/a-acc_ada/points/history')) {
        return { entries: HISTORY, page: 0, size: 25, total: 4 }
      }
      throw new Error(path)
    })
    render(<MemoryRouter><CustomerPoints customerKey="a-acc_ada" /></MemoryRouter>)
    expect(await screen.findByTestId('customer-points-sum'))
      .toHaveTextContent('49 earned − 46 spent + 0 returned − 0 removed = 3 available')
    expect(screen.getByRole('link', { name: '#GFS-26-WAIT01' })).toHaveAttribute('href', '/admin/orders/GFS-26-WAIT01')
    const rows = within(await screen.findByTestId('customer-points-history')).getAllByRole('row')
    expect(rows).toHaveLength(4)
    expect(rows[0]).toHaveTextContent('Spent')
    expect(rows[0]).toHaveTextContent('−46')
    expect(rows[0]).toHaveTextContent('3')
  })
})
