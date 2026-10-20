import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * The live chat for any order, from an email or a guest order page. Signed in, it hands over
 * to the order's own support page; signed out, the order's email is the proof, as for the
 * guest lookup, and the chat opens with the reference filled in. Never Discord.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, body: { message: string }) {
      super(body.message)
    }
  },
}))
const auth = vi.hoisted(() => ({ account: null as null | { email: string } }))
vi.mock('../state/AuthContext', () => ({ useAuth: () => ({ account: auth.account, loading: false }) }))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { ApiError } = await import('../lib/api')
const { default: GuestOrderChat } = await import('./GuestOrderChat')

const CONTEXT = {
  mode: 'BOOSTING',
  summary: { reference: 'GFS-26-GUEST001', service: 'Champs Boosting', platform: 'PlayStation', status: 'In progress',
    coins: null, session: null, sessionStartsAt: null, sessionTimezone: null, coach: null, transferStarted: false },
  chat: { name: null, email: 'guest@example.test', hash: null, attributes: { 'order-id': 'GFS-26-GUEST001' } },
}

function open(path: string, state?: unknown) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/support/chat', search: path, state }]}>
      <Routes>
        <Route path="/support/chat" element={<GuestOrderChat />} />
        <Route path="/orders/:ref/support" element={<p data-testid="owner-page">owner support page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  auth.account = null
  api.post.mockReset()
})

describe('the live chat for any order', () => {
  it('a guest: the order reference filled in, the email asked for, then the chat', async () => {
    api.post.mockResolvedValue(CONTEXT)
    open('?ref=gfs-26-guest001')

    expect(screen.getByLabelText('Order reference')).toHaveValue('GFS-26-GUEST001')
    await userEvent.type(screen.getByLabelText('Email'), 'guest@example.test')
    await userEvent.click(screen.getByRole('button', { name: 'Open the chat' }))

    expect(api.post).toHaveBeenCalledWith('/api/v1/orders/track/support-context',
      { publicRef: 'GFS-26-GUEST001', email: 'guest@example.test' })
    expect(await screen.findByTestId('never-in-chat'))
      .toHaveTextContent('Never share your EA password or backup codes in chat. Use the secure form above.')
    expect(document.body.innerHTML).not.toMatch(/discord/i)
  })

  it('from the order page, with the email handed over: straight to the chat, not asked twice', async () => {
    api.post.mockResolvedValue(CONTEXT)
    open('?ref=GFS-26-GUEST001', { email: 'guest@example.test' })

    expect(await screen.findByTestId('never-in-chat')).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(screen.queryByTestId('guest-chat-form')).toBeNull()
  })

  it('a reference and email that do not match: said, and nothing opened', async () => {
    api.post.mockRejectedValue(new ApiError(404, { message: 'not found' } as never))
    open('?ref=GFS-26-GUEST001')

    await userEvent.type(screen.getByLabelText('Email'), 'someone@example.test')
    await userEvent.click(screen.getByRole('button', { name: 'Open the chat' }))

    expect(await screen.findByText('We couldn’t find an order with that reference and email.')).toBeInTheDocument()
    expect(screen.queryByTestId('never-in-chat')).toBeNull()
  })

  it('signed in: on to the order’s own support page, which the server opens for its owner only', async () => {
    auth.account = { email: 'owner@example.test' }
    open('?ref=GFS-26-OWNER01')
    await waitFor(() => expect(screen.getByTestId('owner-page')).toBeInTheDocument())
    expect(api.post).not.toHaveBeenCalled()
  })
})
