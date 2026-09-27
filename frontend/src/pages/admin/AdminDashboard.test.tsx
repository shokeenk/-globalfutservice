import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminDashboard, AdminOrderRow, CurrencyRevenue } from '../../lib/types'

const api = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))

const auth = vi.hoisted(() => ({ role: 'ADMIN' as 'ADMIN' | 'OPERATOR' }))
vi.mock('../../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'staff@example.test', role: auth.role } }),
}))

const { default: Dashboard } = await import('./AdminDashboard')

function row(patch: Partial<AdminOrderRow>): AdminOrderRow {
  return {
    publicRef: 'GFS-26-CN43SP05', status: 'AWAITING_PAYMENT', sku: 'TRADING_SERVICE',
    serviceLabel: 'Buy Coins — 500K (PlayStation)', variant: null, quantity: 0.5, platform: 'PLAYSTATION',
    deliveryMethod: 'COMFORT_TRADE', credentialsHeld: false, withPartner: false, customerName: 'rahul_07',
    customerEmail: 'rahul07@example.test', paymentState: 'VERIFIED', paymentMethod: 'UPI',
    paymentReference: null, eaHandle: null, totalMinor: 825000, totalFormatted: '₹8,250.00', currency: 'INR',
    createdAt: new Date(Date.now() - 12 * 60_000).toISOString(), deliveredAt: null, availableTransitions: [],
    ...patch,
  }
}

const DATA: AdminDashboard = {
  newToday: 18, newYesterdaySoFar: 14,
  pending: 27, pendingYesterday: 24,
  deliveredToday: 42, deliveredYesterdaySoFar: 42,
  newest: [row({}), row({ publicRef: 'GFS-26-F47FHGXZ', paymentState: null, customerName: null })],
  recent: [{ order: row({ status: 'DELIVERED' }), changedAt: '2026-09-26T15:12:00Z' }],
}

const REVENUE: CurrencyRevenue[] = [
  { currency: 'INR', todayMinor: 5845000, todayFormatted: '₹58,450.00', yesterdayMinor: 4830000, yesterdayFormatted: '₹48,300.00' },
  { currency: 'GBP', todayMinor: 2399, todayFormatted: '£23.99', yesterdayMinor: 0, yesterdayFormatted: '£0.00' },
]

function stub({ dashboard = DATA as AdminDashboard | Error, revenue = REVENUE as CurrencyRevenue[] | Error } = {}) {
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/admin/dashboard') {
      if (dashboard instanceof Error) throw dashboard
      return dashboard
    }
    if (path === '/api/v1/admin/dashboard/revenue') {
      if (revenue instanceof Error) throw revenue
      return revenue
    }
    throw new Error(`unexpected GET ${path}`)
  })
}

const renderPage = () => render(<MemoryRouter><Dashboard /></MemoryRouter>)

describe('Dashboard', () => {
  beforeEach(() => {
    auth.role = 'ADMIN'
    api.get.mockReset()
    stub()
  })

  it('shows today against yesterday, with a backlog that grew drawn as bad news', async () => {
    renderPage()

    expect(await screen.findByText('18')).toBeInTheDocument()
    expect(screen.getByText('29%')).toHaveClass('text-admin-up') // new orders, 18 vs 14
    const pending = screen.getByText('13%') // 27 vs 24
    expect(pending).toHaveClass('text-admin-down')
    expect(screen.getByText(/Same as/)).toBeInTheDocument() // 42 delivered both days
  })

  it('gives an admin today’s revenue in rupees, with other currencies beneath and never added in', async () => {
    renderPage()

    expect(await screen.findByText('₹58,450.00')).toBeInTheDocument()
    expect(screen.getByText('+ £23.99 today')).toBeInTheDocument()
    expect(screen.getByText('21%')).toBeInTheDocument() // 58,450 vs 48,300, rupees only
  })

  it('does not draw revenue for an operator, or ask the server for it', async () => {
    auth.role = 'OPERATOR'
    renderPage()

    expect(await screen.findByText('18')).toBeInTheDocument()
    expect(screen.queryByText("Today's Revenue")).toBeNull()
    expect(screen.queryByText('₹58,450.00')).toBeNull()
    expect(api.get).not.toHaveBeenCalledWith('/api/v1/admin/dashboard/revenue')
  })

  it('lists the newest orders with their payment, and the latest changes with their status', async () => {
    renderPage()

    const newest = (await screen.findByRole('heading', { name: 'New Orders' })).closest('section')!
    expect(within(newest).getAllByText('#GFS-26-CN43SP05')).toHaveLength(1)
    expect(within(newest).getByText('Verified')).toBeInTheDocument()
    expect(within(newest).getByText('Unpaid')).toBeInTheDocument()
    expect(within(newest).getAllByText('12 mins ago')).toHaveLength(2)
    // No name given: the email stands in.
    expect(within(newest).getByText('rahul07@example.test')).toBeInTheDocument()
    expect(within(newest).getAllByText('PlayStation')).toHaveLength(2)

    const recent = screen.getByRole('heading', { name: 'Recent Orders' }).closest('section')!
    expect(within(recent).getByText('Delivered')).toBeInTheDocument()
    expect(within(recent).getByText('Sep 26, 08:42 PM')).toBeInTheDocument()

    for (const link of screen.getAllByRole('link', { name: /View all/ })) {
      expect(link).toHaveAttribute('href', '/admin/orders')
    }
  })

  it('says the figures could not load rather than showing zeros', async () => {
    stub({ dashboard: new Error('offline'), revenue: new Error('offline') })
    renderPage()

    expect((await screen.findAllByText('Could not load')).length).toBe(4)
    expect(screen.getAllByRole('alert').map((a) => a.textContent)).toContain('Could not load orders')
    expect(screen.queryByText('0')).toBeNull()
  })
})
