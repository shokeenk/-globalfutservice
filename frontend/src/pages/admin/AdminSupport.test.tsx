import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminSupportDetail, AdminSupportTicket } from '../../lib/types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, body: { message: string }) { super(body.message) }
  },
}))

const { default: Support } = await import('./AdminSupport')

function ticket(patch: Partial<AdminSupportTicket>): AdminSupportTicket {
  return {
    ref: 'TKT-AB12CD34', customerName: 'Rahul Sharma', email: 'rahul07@example.test', category: 'COINS',
    subject: 'Coins not received', orderRef: 'GFS-26-CN43SP05', status: 'OPEN', messages: 2,
    lastFrom: 'CUSTOMER', createdAt: '2026-09-26T10:12:00Z', lastActivityAt: new Date(Date.now() - 5 * 60_000).toISOString(),
    ...patch,
  }
}

const ROWS: AdminSupportTicket[] = [
  ticket({}),
  ticket({ ref: 'TKT-WAIT0001', customerName: null, email: 'guest@example.test', category: 'PAYMENT',
    subject: 'Payment pending', orderRef: null, status: 'ANSWERED' }),
  ticket({ ref: 'TKT-DONE0001', category: null, subject: 'Old question', status: 'CLOSED' }),
]

const DETAIL: AdminSupportDetail = {
  ref: 'TKT-AB12CD34', status: 'OPEN', category: 'COINS', subject: 'Coins not received', orderRef: 'GFS-26-CN43SP05',
  createdAt: '2026-09-26T10:12:00Z', customerName: 'Rahul Sharma', email: 'rahul07@example.test',
  hasAccount: false, customerLink: 'https://example.test/support/tickets/TKT-AB12CD34?key=k3y',
  messages: [
    { id: 1, author: 'CUSTOMER', kind: 'MESSAGE', body: 'I paid but have no coins.', at: '2026-09-26T10:12:00Z' },
    { id: 2, author: 'STAFF', kind: 'NOTE', body: 'Partner shows 0 delivered.', authorLabel: 'vinay@example.test', at: '2026-09-26T10:20:00Z' },
  ],
}

function stub() {
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/admin/support/overview') return { open: 12, waiting: 4, resolved: 18 }
    if (path.startsWith('/api/v1/admin/support/tickets?')) return { items: ROWS, total: ROWS.length, page: 0, size: 25 }
    if (path === '/api/v1/admin/support/tickets/TKT-AB12CD34') return DETAIL
    throw new Error(`unexpected GET ${path}`)
  })
}

const renderAt = (path = '/admin/support') => render(<MemoryRouter initialEntries={[path]}><Support /></MemoryRouter>)
const listCalls = () => api.get.mock.calls.map(([p]) => p as string).filter((p) => p.startsWith('/api/v1/admin/support/tickets?'))

describe('Support page', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.post.mockReset()
    api.patch.mockReset()
    stub()
  })
  afterEach(() => vi.restoreAllMocks())

  it('counts tickets by where they stand, with the tabs agreeing', async () => {
    renderAt()
    const tabs = await screen.findByRole('group', { name: 'Status' })
    await waitFor(() => expect(within(tabs).getByRole('button', { name: /^All/ })).toHaveTextContent('34'))
    expect(within(tabs).getByRole('button', { name: /^Open/ })).toHaveTextContent('12')
    expect(within(tabs).getByRole('button', { name: /^Waiting/ })).toHaveTextContent('4')
    expect(within(tabs).getByRole('button', { name: /^Resolved/ })).toHaveTextContent('18')
    expect(screen.getByText('Waiting for Customer')).toBeInTheDocument()
  })

  it('lists each ticket with its customer, topic, order and status in the reference’s words', async () => {
    renderAt()
    const row = (await screen.findByRole('button', { name: /Open ticket TKT-WAIT0001/ })).closest('tr')!
    expect(within(row).getByText('guest@example.test')).toBeInTheDocument()
    expect(within(row).getByText('Payment')).toBeInTheDocument()
    expect(within(row).getByText('Waiting')).toBeInTheDocument()
    const first = screen.getByRole('button', { name: /Open ticket TKT-AB12CD34/ }).closest('tr')!
    expect(within(first).getByRole('link', { name: '#GFS-26-CN43SP05' })).toHaveAttribute('href', '/admin/orders/GFS-26-CN43SP05')
    expect(within(first).getByText('5 mins ago')).toBeInTheDocument()
  })

  it('asks the server for the tab, the category and the search, "#" and all', async () => {
    renderAt('/admin/support?search=%23TKT-AB12CD34&category=COINS')
    await screen.findByRole('button', { name: /Open ticket TKT-AB12CD34/ })
    expect(listCalls()[0]).toContain('search=TKT-AB12CD34')
    expect(listCalls()[0]).toContain('category=COINS')
    fireEvent.click(within(screen.getByRole('group', { name: 'Status' })).getByRole('button', { name: /^Waiting/ }))
    await waitFor(() => expect(listCalls()[listCalls().length - 1]).toContain('status=waiting'))
  })

  it('opens a ticket with its notes marked as staff-only', async () => {
    renderAt()
    fireEvent.click(await screen.findByRole('button', { name: /Open ticket TKT-AB12CD34/ }))
    const panel = await screen.findByRole('complementary', { name: 'Ticket TKT-AB12CD34' })
    expect(await within(panel).findByText('I paid but have no coins.')).toBeInTheDocument()
    expect(within(panel).getByText('Internal note')).toBeInTheDocument()
    expect(within(panel).getByText(/Only staff see this · by vinay@example.test/)).toBeInTheDocument()
    expect(within(panel).getByText('Guest: answers through the emailed link')).toBeInTheDocument()
  })

  it('sends a reply to the customer, and a note only to staff', async () => {
    api.post.mockResolvedValue({ ...DETAIL, status: 'ANSWERED' })
    renderAt('/admin/support?ticket=TKT-AB12CD34')
    const panel = await screen.findByRole('complementary', { name: 'Ticket TKT-AB12CD34' })
    const box = await within(panel).findByLabelText('Reply to Rahul Sharma')
    expect(within(panel).getByText('Emails rahul07@example.test.')).toBeInTheDocument()
    fireEvent.change(box, { target: { value: 'Your coins are on the way.' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Send Reply' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/admin/support/tickets/TKT-AB12CD34/messages',
      { message: 'Your coins are on the way.', note: false }))
    expect(await within(panel).findByText('Reply sent to rahul07@example.test.')).toBeInTheDocument()

    fireEvent.click(within(panel).getByRole('button', { name: 'Internal Note' }))
    const note = within(panel).getByLabelText('Internal note, only staff will see it')
    expect(within(panel).getByText('Only staff see notes. Nobody is told.')).toBeInTheDocument()
    fireEvent.change(note, { target: { value: 'Asked the partner.' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Add Note' }))
    await waitFor(() => expect(api.post).toHaveBeenLastCalledWith('/api/v1/admin/support/tickets/TKT-AB12CD34/messages',
      { message: 'Asked the partner.', note: true }))
  })

  it('keeps a half-written reply when switching to a note and back', async () => {
    renderAt('/admin/support?ticket=TKT-AB12CD34')
    const panel = await screen.findByRole('complementary', { name: 'Ticket TKT-AB12CD34' })
    fireEvent.change(await within(panel).findByLabelText('Reply to Rahul Sharma'), { target: { value: 'Half a reply' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Internal Note' }))
    expect(within(panel).getByLabelText('Internal note, only staff will see it')).toHaveValue('')
    fireEvent.click(within(panel).getByRole('button', { name: 'Reply to Customer' }))
    expect(within(panel).getByLabelText('Reply to Rahul Sharma')).toHaveValue('Half a reply')
  })

  it('resolves a ticket, and offers to reopen it', async () => {
    api.post.mockResolvedValue({ ...DETAIL, status: 'CLOSED', resolvedAt: '2026-09-26T11:00:00Z' })
    renderAt('/admin/support?ticket=TKT-AB12CD34')
    const panel = await screen.findByRole('complementary', { name: 'Ticket TKT-AB12CD34' })
    fireEvent.click(await within(panel).findByRole('button', { name: 'Close Ticket' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/admin/support/tickets/TKT-AB12CD34/close'))
    expect(await within(panel).findByRole('button', { name: 'Reopen Ticket' })).toBeInTheDocument()
    expect(within(panel).getByText(/Replying reopens the ticket/)).toBeInTheDocument()
  })

  it('says so when the list cannot be read, rather than showing no tickets', async () => {
    api.get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/admin/support/overview') throw new Error('down')
      throw new Error('down')
    })
    renderAt()
    expect(await screen.findByText('Could not load tickets')).toBeInTheDocument()
    expect(screen.queryByText('No tickets yet')).not.toBeInTheDocument()
  })
})
