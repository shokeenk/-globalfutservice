import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// ---- the API, mocked: these tests are about the page, not the server ---------------
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {},
}))

vi.mock('../../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'admin@example.test', role: 'ADMIN' } }),
}))

// Imported after the mocks are declared, so the page picks up the mocked modules.
const { default: Admin } = await import('./Admin')

const STATS = {
  awaitingPayment: 2, paid: 1, credentialsPending: 4, readyForDelivery: 5, inProgress: 3,
  onHold: 1, deliveredAwaitingGuarantee: 0, disputed: 0, credentialsHeld: 6,
}

describe('Orders page', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/admin/orders/stats') {
        // A revenue figure in the response, as an older server would still send, so the
        // test shows the page does not draw it rather than simply not being given it.
        return { ...STATS, revenueLast30dMinor: 999900, revenueLast30dFormatted: '₹9,999.00' }
      }
      return []
    })
  })

  it('no longer shows the revenue card', async () => {
    render(<MemoryRouter><Admin /></MemoryRouter>)

    expect(await screen.findByText('Ready to work')).toBeInTheDocument()
    expect(screen.queryByText('Revenue, last 30 days')).toBeNull()
    expect(screen.queryByText('₹9,999.00')).toBeNull()
    expect(screen.queryByText(/guarantee window are/)).toBeNull()
  })

  it('keeps the six queue counters, the filters and the queue', async () => {
    render(<MemoryRouter><Admin /></MemoryRouter>)

    expect(await screen.findByText('Ready to work')).toBeInTheDocument()
    for (const label of ['Waiting for sign-in', 'In progress', 'On hold', 'Disputed', 'Sign-ins held']) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0)
    }
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument()
    expect(await screen.findByText('No orders yet')).toBeInTheDocument()
    expect(api.get).toHaveBeenCalledWith('/api/v1/admin/orders/stats')
    // The Orders page does not ask for revenue: that is the Analytics page's call.
    expect(api.get).not.toHaveBeenCalledWith('/api/v1/admin/analytics/revenue')
  })
})
