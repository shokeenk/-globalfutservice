import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * The payment panel's International tab, for every kind of order: Payop's local methods
 * for an order not in INR, the placeholder for an INR order, and -- when Payop cannot be
 * offered for an order that would have it -- said, never hidden.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), upload: vi.fn() }))
vi.mock('../lib/api', async (original) => ({ ...(await original<typeof import('../lib/api')>()), api }))

const { ApiError } = await import('../lib/api')
const { ManualPayment } = await import('./ManualPayment')

const METHODS = [
  { method: 'UPI', destination: 'gfs@upi', accountName: 'GFS', link: null, referenceName: 'UTR' },
  { method: 'PAYPAL', destination: 'pay@gfs.test', accountName: null, link: null, referenceName: 'transaction ID' },
  { method: 'CRYPTO', destination: 'TWALLET', accountName: null, link: null, referenceName: 'TXID' },
]

function show(currency: string, sku = 'COACHING', initialMethod?: 'INTERNATIONAL' | 'UPI') {
  return render(<ManualPayment publicRef="GFS-26-C1" email="p@example.test" sku={sku} totalFormatted="$10.24"
                               currency={currency} initialMethod={initialMethod} />)
}

beforeEach(() => {
  api.get.mockReset()
  api.post.mockReset()
  api.get.mockImplementation(async (url: string) => {
    if (url.startsWith('/api/v1/payments/methods')) return METHODS
    if (url === '/api/v1/payments/payop/country') return { country: 'DE' }
    return new Promise(() => {})
  })
  api.post.mockImplementation(() => new Promise(() => {}))
})

describe('the International tab', () => {
  it.each(['TRADING_SERVICE', 'BOOST_CHAMPS', 'COACHING'])('%s: the same four tabs, International last', async (sku) => {
    show('USD', sku)
    const tabs = (await screen.findAllByRole('tab')).map((t) => t.textContent)
    expect(tabs).toEqual(['UPI', 'PayPal', 'Crypto', 'International'])
  })

  it('an order not in INR: Payop\'s "Pay with a local method", priced by the server', async () => {
    show('USD')
    fireEvent.click(await screen.findByRole('tab', { name: 'International' }))
    expect(await screen.findByText('Pay with a local method')).toBeInTheDocument()
    expect(api.post).toHaveBeenCalledWith('/api/v1/payments/payop/options',
      { order: 'GFS-26-C1', email: 'p@example.test', country: 'DE' })
  })

  it('an INR order: the placeholder, and Payop is never asked', async () => {
    show('INR')
    fireEvent.click(await screen.findByRole('tab', { name: 'International' }))
    expect(await screen.findByText('International payment options are coming soon')).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()
  })

  it('Payop cannot be offered for an order that would have it: "Temporarily unavailable. Please use PayPal or crypto."',
    async () => {
      api.post.mockRejectedValue(new ApiError(404, { error: 'not_found', message: 'This payment option is not available.' }))
      show('EUR')
      fireEvent.click(await screen.findByRole('tab', { name: 'International' }))
      const panel = await screen.findByTestId('local-methods-unavailable')
      expect(panel).toHaveTextContent('Temporarily unavailable.')
      expect(panel).toHaveTextContent('Please use PayPal or crypto.')
      // The tab stays, so the customer can read why.
      expect(screen.getByRole('tab', { name: 'International' })).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Pay with PayPal' }))
      expect(screen.getByRole('tab', { name: 'PayPal' })).toHaveAttribute('aria-selected', 'true')
    })

  it('opens on International when the checkout chose it, and follows a later choice', async () => {
    const view = show('USD', 'COACHING', 'INTERNATIONAL')
    expect(await screen.findByRole('tab', { name: 'International' })).toHaveAttribute('aria-selected', 'true')
    view.rerender(<ManualPayment publicRef="GFS-26-C1" email="p@example.test" sku="COACHING" totalFormatted="$10.24"
                                 currency="USD" initialMethod="UPI" />)
    expect(screen.getByRole('tab', { name: 'UPI' })).toHaveAttribute('aria-selected', 'true')
  })
})
