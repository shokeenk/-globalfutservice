import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Account } from '../lib/types'

/*
 * Where the link in the order emails lands: /track?ref=<reference>. Signed in, it opens
 * that order with nothing to type. As a guest, the lookup has the reference filled in and
 * asks for the order's email, which is never in the link.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))
const auth = vi.hoisted(() => ({ account: null as Account | null }))
vi.mock('../state/AuthContext', () => ({ useAuth: () => ({ account: auth.account, loading: false }) }))
vi.mock('../state/CatalogContext', () => ({
  useCatalog: () => ({ catalog: null, policy: null, loading: false, error: null, currency: 'INR', setCurrency: () => {} }),
}))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: Track } = await import('./Track')

const OWNER: Account = {
  publicId: 'acc_owner', email: 'rahul@example.test', displayName: 'Rahul', role: 'CUSTOMER',
  pointsBalance: 0, pointsValueMinor: 0, pointsValueFormatted: '₹0.00', firstOrder: false, referredByCode: null,
}

function openEmailLink(ref: string) {
  return render(<MemoryRouter initialEntries={[`/track?ref=${ref}`]}><Track /></MemoryRouter>)
}

beforeEach(() => {
  api.get.mockReset()
  api.post.mockReset()
  // The order itself never answers here: this is about which order is asked for, and how.
  api.get.mockImplementation(async (url: string) => (url === '/api/v1/orders' ? [] : new Promise(() => {})))
  api.post.mockImplementation(() => new Promise(() => {}))
})

describe('the tracking link in an order email', () => {
  it('signed in: opens that order straight away, with nothing to type', async () => {
    auth.account = OWNER
    openEmailLink('GFS-26-COIN0001')
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/v1/orders/GFS-26-COIN0001'))
    expect(api.post).not.toHaveBeenCalled()
  })

  it('a guest: the reference is filled in, and the order is looked up only with the email they give', async () => {
    auth.account = null
    openEmailLink('GFS-26-COIN0001')

    expect(await screen.findByLabelText(/Order reference/)).toHaveValue('GFS-26-COIN0001')
    const email = screen.getByLabelText(/^Email/)
    expect(email).toHaveValue('')
    expect(api.post).not.toHaveBeenCalled()

    fireEvent.change(email, { target: { value: 'player@example.test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Find my order' }))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/orders/track',
      { publicRef: 'GFS-26-COIN0001', email: 'player@example.test' }))
  })
})
