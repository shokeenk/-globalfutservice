import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { SupportThread } from '../lib/types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const ApiErrorClass = vi.hoisted(() => class ApiError extends Error {
  constructor(readonly status: number, body: { message: string }) { super(body.message) }
})
vi.mock('../lib/api', () => ({ api, ApiError: ApiErrorClass }))

const account = vi.hoisted(() => ({ current: null as null | { email: string; role: string } }))
vi.mock('../state/AuthContext', () => ({ useAuth: () => ({ account: account.current, loading: false }) }))

// jsdom has no matchMedia; the storefront's reveal animations ask it about reduced motion.
window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: SupportTicket } = await import('./SupportTicket')
const { default: Support } = await import('./Support')

const THREAD: SupportThread = {
  ref: 'TKT-AB12CD34', subject: 'Coins not received', category: 'COINS', status: 'ANSWERED', orderRef: 'GFS-26-CN43SP05',
  createdAt: '2026-09-26T10:12:00Z',
  messages: [
    { from: 'CUSTOMER', body: 'I paid but have no coins.', at: '2026-09-26T10:12:00Z' },
    { from: 'SUPPORT', body: 'They are on the way.', at: '2026-09-26T10:30:00Z' },
  ],
}

const openTicket = (path: string) => render(
  <MemoryRouter initialEntries={[path]}>
    <Routes><Route path="/support/tickets/:ref" element={<SupportTicket />} /></Routes>
  </MemoryRouter>,
)

describe('a support request, as its customer sees it', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.post.mockReset()
    account.current = null
  })

  it('reads the ticket with the key from the email, and shows us as the business', async () => {
    api.get.mockResolvedValue(THREAD)
    openTicket('/support/tickets/TKT-AB12CD34?key=k3y%2Bz')
    expect(await screen.findByText('They are on the way.')).toBeInTheDocument()
    expect(api.get).toHaveBeenCalledWith('/api/v1/support/tickets/TKT-AB12CD34?key=k3y%2Bz')
    expect(screen.getByText('Global FUT Services')).toBeInTheDocument()
    expect(screen.getByText('You')).toBeInTheDocument()
    expect(screen.getByText('Waiting for your reply')).toBeInTheDocument()
  })

  it('answers with the same key, and warns against sending a password', async () => {
    api.get.mockResolvedValue(THREAD)
    api.post.mockResolvedValue({ ...THREAD, status: 'OPEN',
      messages: [...THREAD.messages, { from: 'CUSTOMER', body: 'Got them, thanks!', at: '2026-09-26T11:00:00Z' }] })
    openTicket('/support/tickets/TKT-AB12CD34?key=k3y')
    const box = await screen.findByLabelText('Your reply')
    expect(screen.getByText(/Never include your password or backup codes/)).toBeInTheDocument()
    fireEvent.change(box, { target: { value: 'Got them, thanks!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send reply' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/support/tickets/TKT-AB12CD34/messages?key=k3y',
      { message: 'Got them, thanks!' }))
    expect(await screen.findByText('Got them, thanks!')).toBeInTheDocument()
    expect(screen.getByText('With our team')).toBeInTheDocument()
  })

  it('says there is no such request without a working key, and offers a new one', async () => {
    api.get.mockRejectedValue(new ApiErrorClass(404, { message: 'No such ticket.' }))
    openTicket('/support/tickets/TKT-AB12CD34')
    expect(await screen.findByText('We could not find that request')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Send a new request' })).toHaveAttribute('href', '/support')
  })

  it('tells the customer writing again reopens a resolved request', async () => {
    api.get.mockResolvedValue({ ...THREAD, status: 'CLOSED' })
    openTicket('/support/tickets/TKT-AB12CD34?key=k3y')
    expect(await screen.findByText('This request is resolved. Writing again reopens it.')).toBeInTheDocument()
  })
})

describe('the contact form', () => {
  beforeEach(() => {
    api.post.mockReset()
    account.current = null
  })

  it('sends the topic chosen, and none when none is chosen', async () => {
    api.post.mockResolvedValue({ ref: 'TKT-NEW00001', message: 'Thanks — we have your message.' })
    render(<MemoryRouter><Support /></MemoryRouter>)
    fireEvent.change(screen.getByLabelText(/Your email/), { target: { value: 'buyer@example.test' } })
    fireEvent.change(screen.getByLabelText('What is it about?'), { target: { value: 'PAYMENT' } })
    fireEvent.change(screen.getByLabelText(/Subject/), { target: { value: 'Paid twice' } })
    fireEvent.change(screen.getByLabelText(/What is going on/), { target: { value: 'Please check.' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/support/tickets',
      expect.objectContaining({ category: 'PAYMENT', subject: 'Paid twice' })))

    fireEvent.change(screen.getByLabelText(/Subject/), { target: { value: 'Another' } })
    fireEvent.change(screen.getByLabelText(/What is going on/), { target: { value: 'Hi.' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(api.post).toHaveBeenLastCalledWith('/api/v1/support/tickets',
      expect.objectContaining({ category: null, subject: 'Another' })))
  })
})
