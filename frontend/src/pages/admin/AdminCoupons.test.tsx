import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Coupon } from '../../lib/types'

/*
 * Deleting a coupon from Admin -> Coupons: an admin's, behind a confirmation that says what
 * it does. The server decides whether the coupon goes or is kept for its orders; the page
 * says which. Deleted coupons can be looked at, read-only.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), del: vi.fn() }))
vi.mock('../../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
const auth = vi.hoisted(() => ({ role: 'ADMIN' }))
vi.mock('../../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'owner@example.test', role: auth.role } }),
}))

const { default: AdminCoupons } = await import('./AdminCoupons')

function coupon(id: number, code: string): Coupon {
  return { id, code, discountPercent: 10, discountBps: 1000, description: null, maxRedemptions: null,
    redeemedCount: 3, remaining: null, maxPerAccount: 1, minOrderMinor: 0, expiresAt: null, active: true,
    exhausted: false, createdAt: '2026-10-01T10:00:00Z' }
}

let live: Coupon[]

beforeEach(() => {
  auth.role = 'ADMIN'
  live = [coupon(1, 'SAVE10'), coupon(2, 'FRESH20')]
  api.get.mockReset()
  api.del.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/admin/coupons') return live
    if (path === '/api/v1/admin/coupons/deleted') {
      return [{ couponId: 7, code: 'OLD5', discountPercent: 5, redeemedCount: 12, outcome: 'HIDDEN',
        deletedBy: 'owner@example.test', deletedAt: '2026-10-09T10:00:00Z' }]
    }
    throw new Error(`unexpected GET ${path}`)
  })
})

function page() {
  render(<MemoryRouter><AdminCoupons /></MemoryRouter>)
}

describe('deleting a coupon', () => {
  it('asks first, in plain words; Cancel deletes nothing', async () => {
    page()
    await userEvent.click(await screen.findByRole('button', { name: 'Delete coupon SAVE10' }))

    const dialog = screen.getByRole('dialog', { name: 'Delete coupon SAVE10?' })
    expect(dialog).toHaveTextContent(
      'Customers will no longer be able to use it. Orders that already used it are not affected.')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.del).not.toHaveBeenCalled()
  })

  it('Delete: the server deletes it, the page says which way, and it leaves the list', async () => {
    api.del.mockImplementation(async () => {
      live = live.filter((c) => c.id !== 1)
      return { id: 1, code: 'SAVE10', outcome: 'HIDDEN' }
    })
    page()
    await userEvent.click(await screen.findByRole('button', { name: 'Delete coupon SAVE10' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))

    expect(api.del).toHaveBeenCalledWith('/api/v1/admin/coupons/1')
    expect(await screen.findByText('SAVE10 deleted. Orders that used it keep their discount.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Delete coupon SAVE10' })).toBeNull())
    expect(screen.getByRole('button', { name: 'Delete coupon FRESH20' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('one no order used: removed, and said so', async () => {
    api.del.mockResolvedValue({ id: 2, code: 'FRESH20', outcome: 'REMOVED' })
    page()
    await userEvent.click(await screen.findByRole('button', { name: 'Delete coupon FRESH20' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('FRESH20 deleted. No order had used it.')).toBeInTheDocument()
  })

  it('operators see the list but no way to delete', async () => {
    auth.role = 'OPERATOR'
    page()
    expect(await screen.findByText('SAVE10')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Delete coupon/ })).toBeNull()
  })

  it('"Show deleted coupons": the deleted ones, read-only, with who deleted them and what was kept', async () => {
    page()
    await screen.findByText('SAVE10')
    await userEvent.click(screen.getByRole('checkbox', { name: 'Show deleted coupons' }))

    const table = await screen.findByTestId('deleted-coupons')
    expect(api.get).toHaveBeenCalledWith('/api/v1/admin/coupons/deleted')
    expect(table).toHaveTextContent('OLD5')
    expect(table).toHaveTextContent('owner@example.test')
    expect(table).toHaveTextContent('Yes, for the orders that used it')
    expect(within(table).queryByRole('button')).toBeNull()
  })
})
