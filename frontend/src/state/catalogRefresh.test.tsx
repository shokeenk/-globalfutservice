import { act, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * The catalogue is served with a minute's public cache. When a page learns its prices are
 * stale -- a quote priced from a newer version -- it asks for them again: past the cache,
 * without a loading state, and without touching the policy.
 */

const api = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../lib/api', () => ({ api }))

const { CatalogProvider, useCatalog } = await import('./CatalogContext')

const catalog = (price: string) => ({ season: 'FC26', currency: 'INR', availableCurrencies: ['INR'],
  services: [{ sku: 'TRADING_SERVICE', options: [{ unitPriceFormatted: price }] }] })

let latest: ReturnType<typeof useCatalog> | null = null
function Probe() {
  latest = useCatalog()
  const price = latest.catalog?.services[0]?.options[0]?.unitPriceFormatted ?? 'none'
  return <p data-testid="probe">{`${price} loading=${latest.loading}`}</p>
}

beforeEach(() => {
  api.get.mockReset()
  latest = null
})

describe('refreshing the catalogue', () => {
  it('fetches past the cache, replaces the prices, and never shows a loading state', async () => {
    api.get.mockImplementation(async (path: string) => (path.startsWith('/api/v1/catalog/policy') ? { p: 1 }
      : path.includes('fresh=') ? catalog('₹15,000.00') : catalog('₹16,000.00')))
    render(<CatalogProvider><Probe /></CatalogProvider>)
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('₹16,000.00 loading=false'))
    const policy = latest!.policy

    act(() => latest!.refresh())

    expect(screen.getByTestId('probe')).toHaveTextContent('loading=false')
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('₹15,000.00 loading=false'))
    const fresh = api.get.mock.calls.map(([path]) => path).filter((p: string) => p.includes('fresh='))
    expect(fresh).toHaveLength(1)
    expect(fresh[0]).toMatch(/^\/api\/v1\/catalog\?currency=INR&fresh=\d+$/)
    expect(latest!.policy).toBe(policy)
  })

  it('a refresh that fails leaves the prices on screen as they were', async () => {
    api.get.mockImplementation(async (path: string) => {
      if (path.includes('fresh=')) throw new Error('offline')
      return path.startsWith('/api/v1/catalog/policy') ? { p: 1 } : catalog('₹16,000.00')
    })
    render(<CatalogProvider><Probe /></CatalogProvider>)
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent('₹16,000.00'))
    act(() => latest!.refresh())
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.getByTestId('probe')).toHaveTextContent('₹16,000.00 loading=false')
    expect(latest!.error).toBeNull()
  })
})
