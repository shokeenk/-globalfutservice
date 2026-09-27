import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminCustomer, AdminCustomerDetail, AdminCustomerOverview } from '../../lib/types'

const api = vi.hoisted(() => ({ get: vi.fn(), patch: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, body: { message: string }) { super(body.message) }
  },
}))

const auth = vi.hoisted(() => ({ role: 'ADMIN' as 'ADMIN' | 'OPERATOR' }))
vi.mock('../../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'staff@example.test', role: auth.role } }),
}))

const { default: Customers } = await import('./AdminCustomers')
const { initials, spentText } = await import('./customers/shared')

const RAHUL: AdminCustomer = {
  key: 'a-acc_rahul', kind: 'ACCOUNT', name: 'Rahul Sharma', email: 'rahul07@example.test', eaHandle: 'rahul_07',
  platform: 'PLAYSTATION', orders: 6, spent: [{ currency: 'INR', minor: 2450000, formatted: '₹24,500.00' }],
  lastOrderAt: '2026-09-26T10:04:00Z', joinedAt: '2026-08-12T09:44:00Z', status: 'ACTIVE', discordConnected: true,
}
const GUEST: AdminCustomer = {
  key: 'g-GFS-26-BWG6NGG3', kind: 'GUEST', name: 'Guest', email: 'buyer@example.test', orders: 1, spent: [],
  lastOrderAt: '2026-09-12T08:00:00Z', joinedAt: '2026-09-12T08:00:00Z', status: 'GUEST', discordConnected: false,
}
const OVERVIEW: AdminCustomerOverview = {
  total: 1284, newThisMonth: 86, newLastMonthSoFar: 69, withOrders: 742, withOrdersLastMonth: 629,
}
const DETAIL: AdminCustomerDetail = {
  customer: RAHUL,
  recentOrders: [{ publicRef: 'GFS-26-10291', sku: 'TRADING_SERVICE', serviceLabel: 'Buy Coins — 500K (PlayStation)', status: 'IN_PROGRESS', createdAt: '2026-09-26T10:04:00Z' }],
}

/** An operator's responses carry no money, as the server's do. */
function withoutMoney(c: AdminCustomer): AdminCustomer {
  const { spent: _spent, ...rest } = c
  return rest
}

function stub() {
  api.get.mockImplementation(async (path: string) => {
    const operator = auth.role !== 'ADMIN'
    if (path === '/api/v1/admin/customers/overview') return OVERVIEW
    if (path.startsWith('/api/v1/admin/customers?')) {
      const items = [RAHUL, GUEST].map((c) => (operator ? withoutMoney(c) : c))
      return { items, total: 2, page: 0, size: 25 }
    }
    if (path === '/api/v1/admin/customers/a-acc_rahul') {
      return operator ? { ...DETAIL, customer: withoutMoney(RAHUL) } : DETAIL
    }
    if (path === '/api/v1/admin/customers/g-GFS-26-BWG6NGG3') return { customer: GUEST, recentOrders: [] }
    throw new Error(`unexpected GET ${path}`)
  })
}

const renderAt = (path = '/admin/customers') => render(<MemoryRouter initialEntries={[path]}><Customers /></MemoryRouter>)
const listCalls = () => api.get.mock.calls.map(([p]) => p as string).filter((p) => p.startsWith('/api/v1/admin/customers?'))

describe('Customers page', () => {
  beforeEach(() => {
    auth.role = 'ADMIN'
    api.get.mockReset()
    api.patch.mockReset()
    stub()
  })

  it('counts customers, with growth against last month', async () => {
    renderAt()
    expect(await screen.findByText('1,284')).toBeInTheDocument()
    expect(screen.getByText('25%')).toBeInTheDocument() // 86 vs 69
    expect(screen.getByText('18%')).toBeInTheDocument() // 742 vs 629
  })

  it('lists accounts and guests; a guest with no name is "Guest"', async () => {
    renderAt()
    const rahul = (await screen.findByText('Rahul Sharma')).closest('tr')!
    expect(within(rahul).getByText('EA ID: rahul_07')).toBeInTheDocument()
    expect(within(rahul).getByText('₹24,500.00')).toBeInTheDocument()
    const guest = screen.getByText('Guest').closest('tr')!
    expect(within(guest).getByText('Guest checkout')).toBeInTheDocument()
    expect(screen.getByText('Showing 1–2 of 2 customers')).toBeInTheDocument()
  })

  it('shows an operator no money: no column, no figure', async () => {
    auth.role = 'OPERATOR'
    renderAt()
    await screen.findByText('Rahul Sharma')
    expect(screen.queryByText('Total Spent')).toBeNull()
    expect(screen.queryByText('₹24,500.00')).toBeNull()
  })

  it('filters by orders and searches on the server', async () => {
    renderAt()
    await screen.findByText('Rahul Sharma')

    fireEvent.click(screen.getByRole('button', { name: 'No Orders' }))
    await waitFor(() => expect(listCalls()[listCalls().length - 1]).toContain('filter=without'))

    fireEvent.change(screen.getByLabelText(/Search customers/), { target: { value: 'rahul_07' } })
    await waitFor(() => expect(listCalls()[listCalls().length - 1]).toContain('search=rahul_07'))
  })

  it('opens a customer with their details, orders and a way to all of them', async () => {
    renderAt()
    fireEvent.click(await screen.findByRole('button', { name: 'View Rahul Sharma' }))

    const panel = await screen.findByRole('complementary', { name: 'Customer details' })
    expect(await within(panel).findByText('#GFS-26-10291')).toBeInTheDocument()
    expect(within(panel).getByText('Connected')).toBeInTheDocument()
    expect(within(panel).getByText('Total Spent')).toBeInTheDocument()
    expect(within(panel).getByText('Active')).toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: /View All Orders/ }))
      .toHaveAttribute('href', '/admin/orders?search=rahul07%40example.test')
    // Send Email arrives with the Support page, where a reply has somewhere to go.
    expect(within(panel).queryByText('Send Email')).toBeNull()

    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('complementary', { name: 'Customer details' })).toBeNull())
  })

  it('lets an admin rename an account', async () => {
    api.patch.mockResolvedValue(DETAIL)
    renderAt('/admin/customers?customer=a-acc_rahul')
    const panel = await screen.findByRole('complementary', { name: 'Customer details' })
    fireEvent.click(await within(panel).findByRole('button', { name: 'Edit' }))
    fireEvent.change(within(panel).getByLabelText('Name'), { target: { value: 'Rahul S.' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(api.patch).toHaveBeenCalledWith('/api/v1/admin/customers/a-acc_rahul', { name: 'Rahul S.' }))
  })

  it('offers no rename to an operator, or for a guest', async () => {
    auth.role = 'OPERATOR'
    renderAt('/admin/customers?customer=a-acc_rahul')
    const panel = await screen.findByRole('complementary', { name: 'Customer details' })
    await within(panel).findByText('#GFS-26-10291')
    expect(within(panel).queryByRole('button', { name: 'Edit' })).toBeNull()
    expect(within(panel).queryByText('Total Spent')).toBeNull()
  })

  it('says the list could not load rather than showing it empty', async () => {
    api.get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/admin/customers/overview') return OVERVIEW
      throw new Error('offline')
    })
    renderAt()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load customers')
    expect(screen.queryByText('No customers yet')).toBeNull()
  })
})

describe('customer helpers', () => {
  it('makes initials from a name, an email-ish handle, or "Guest"', () => {
    expect(initials('Rahul Sharma')).toBe('RS')
    expect(initials('rahul07')).toBe('RA')
    expect(initials('Guest')).toBe('GU')
  })

  it('never adds amounts in different currencies together', () => {
    expect(spentText([])).toBe('—')
    expect(spentText([
      { currency: 'INR', minor: 100, formatted: '₹1.00' },
      { currency: 'GBP', minor: 2399, formatted: '£23.99' },
    ])).toBe('₹1.00 + £23.99')
  })
})
