import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminCustomerDetail } from '../../../lib/types'

const api = vi.hoisted(() => ({ post: vi.fn() }))
vi.mock('../../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, body: { message: string }) { super(body.message) }
  },
}))

const { SendEmail } = await import('./SendEmail')

const DETAIL: AdminCustomerDetail = {
  customer: {
    key: 'g-GFS-26-CN43SP05', kind: 'GUEST', name: 'Guest', email: 'buyer@example.test', orders: 1,
    joinedAt: '2026-09-01T10:00:00Z', status: 'GUEST', discordConnected: false,
  },
  recentOrders: [{ publicRef: 'GFS-26-CN43SP05', sku: 'TRADING_SERVICE', serviceLabel: 'Coins · 500K', status: 'PAID',
    createdAt: '2026-09-01T10:00:00Z' }],
}

describe('Send Email', () => {
  beforeEach(() => api.post.mockReset())

  it('opens a support ticket to this one customer, and says what it is not for', async () => {
    const onSent = vi.fn()
    api.post.mockResolvedValue({ ref: 'TKT-STAFF001', email: 'buyer@example.test' })
    render(<SendEmail detail={DETAIL} onClose={() => {}} onSent={onSent} />)

    expect(screen.getByText(/only reaches people who opted in/)).toBeInTheDocument()
    expect(screen.getByText(/Never ask for their password/)).toBeInTheDocument()
    // No order is assumed: staff pick one if the message is about one.
    expect(screen.getByLabelText('About order')).toHaveValue('')

    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'ACCOUNT' } })
    fireEvent.change(screen.getByLabelText('About order'), { target: { value: 'GFS-26-CN43SP05' } })
    fireEvent.change(screen.getByLabelText('Subject'), { target: { value: 'Your platform' } })
    fireEvent.change(screen.getByLabelText('Message'), { target: { value: 'Could you confirm your platform?' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send Email' }))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/admin/support/tickets', {
      customerKey: 'g-GFS-26-CN43SP05', orderRef: 'GFS-26-CN43SP05', category: 'ACCOUNT',
      subject: 'Your platform', message: 'Could you confirm your platform?',
    }))
    expect(onSent).toHaveBeenCalledWith(expect.objectContaining({ ref: 'TKT-STAFF001' }))
  })
})
