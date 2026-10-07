import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogOption } from '../lib/types'

/*
 * Client testing: the coin order asks for three backup codes, a customer entered one and
 * pressed Pay. The order was stopped, but the red errors rendered out of sight above, with
 * nothing scrolled to and nothing moving -- so the customer could not see what was wrong.
 *
 * Every box is now checked at once and each missing one says so by position, the page
 * scrolls to the first, every error nudges on every press, and the number of boxes is the
 * server's setting rather than three array literals.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))

const AUTH = { account: { publicId: 'acc_player', email: 'player@example.test', displayName: 'Player', role: 'CUSTOMER',
  pointsBalance: 0, pointsValueMinor: 0, pointsValueFormatted: '$0.00', firstOrder: true, referredByCode: null },
loading: false }
vi.mock('../state/AuthContext', () => ({ useAuth: () => AUTH }))

const coins = (platform: string): CatalogOption => ({ platform, variant: null, label: platform, unitPriceMinor: 900,
  unitPriceFormatted: '$9.00', minQuantity: '0.01', maxQuantity: '1', stepQuantity: '0.01' })
// One object, as the real context gives; the policy's count is switched per test.
const CATALOG = {
  catalog: { season: 'FC26', currency: 'USD', availableCurrencies: ['USD'], services: [
    { sku: 'TRADING_SERVICE', displayName: 'Coins', sellable: true, priceUnit: 'PER_MILLION', marketTaxApplies: true,
      mayRequireCredentials: true, options: [coins('PC'), coins('PLAYSTATION')] }] },
  policy: { onlinePaymentsEnabled: false, maxWalletRedemptionBps: 0, pointValueMinor: 100,
    backupCodesRequired: 3 as number | undefined },
  loading: false, error: null, currency: 'USD', setCurrency: () => {},
}
vi.mock('../state/CatalogContext', () => ({ useCatalog: () => CATALOG }))
vi.mock('../lib/razorpay', () => ({ isStubGateway: () => true, openCheckout: vi.fn() }))

const motion = { reduce: false }
window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce') && motion.reduce, media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: Order } = await import('./Order')
const { CredentialForm } = await import('../components/CredentialForm')

const scrolledTo: string[] = []
const nudged: string[] = []
const fill = (field: HTMLElement, value: string) => fireEvent.change(field, { target: { value } })
const orders = () => api.post.mock.calls.filter(([path]) => path === '/api/v1/orders')

beforeEach(() => {
  motion.reduce = false
  CATALOG.policy.backupCodesRequired = 3
  scrolledTo.length = 0
  nudged.length = 0
  Element.prototype.scrollIntoView = function scroll(this: Element) { scrolledTo.push(this.textContent ?? '') }
  Element.prototype.animate = function animate(this: Element) {
    nudged.push(this.textContent ?? '')
    return {} as Animation
  }
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  api.get.mockReset()
  api.post.mockReset()
  api.get.mockResolvedValue([])
  api.post.mockImplementation(async (path: string, body: { platform?: string }) => {
    if (path === '/api/v1/quotes') {
      return { quoteId: 'q1', season: 'FC26', sku: 'TRADING_SERVICE', platform: body.platform, variant: null,
        quantity: '0.1', currency: 'USD', lines: [], subtotalMinor: 90, totalMinor: 90, totalFormatted: '$0.90',
        pointsRedeemed: 0, pointsEarned: 0, referralCode: null, couponCode: null, couponMessage: null,
        issuedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 600_000).toISOString(), signature: 'x' }
    }
    if (path === '/api/v1/orders') {
      return { publicRef: 'GFS-26-CODES001', status: 'AWAITING_PAYMENT', totalMinor: 90, totalFormatted: '$0.90',
        currency: 'USD', payment: { provider: 'STUB' } }
    }
    return new Promise(() => {})
  })
})
afterEach(() => { CATALOG.policy.backupCodesRequired = 3 })

/** The coin checkout's details step, with everything but the backup codes answered. */
async function toSignIn() {
  render(<MemoryRouter initialEntries={['/order']}><Routes><Route path="/order" element={<Order />} /></Routes></MemoryRouter>)
  await userEvent.click(await screen.findByRole('button', { name: /PLAYSTATION/ }))
  await userEvent.click(await screen.findByRole('button', { name: 'Continue' }))
  const pay = await screen.findByRole('button', { name: /^Pay \$0\.90/ })
  fill(screen.getByLabelText(/EA account email/), 'ea@example.test')
  fill(screen.getByLabelText(/EA password/), 'correct-horse')
  for (const box of screen.getAllByRole('checkbox')) await userEvent.click(box)
  return pay
}

const codeBoxes = () => screen.getAllByLabelText(/^Backup code \d/)

function expectFieldError(text: string) {
  const error = screen.getByText(text)
  expect(error).toHaveAttribute('role', 'alert')
  expect(error).toHaveAttribute('data-field-error')
  expect(error).toHaveClass('text-brand-400')
  return error
}

describe('the coin checkout, with 1 of 3 backup codes', () => {
  it('blocks the order, says which codes are missing in red, scrolls to the first, and nudges each error', async () => {
    const pay = await toSignIn()
    fill(codeBoxes()[0]!, '12345678')

    await userEvent.click(pay)

    expectFieldError('Backup code 2 is required.')
    expectFieldError('Backup code 3 is required.')
    expect(codeBoxes()[1]).toHaveAttribute('aria-invalid', 'true')
    expect(codeBoxes()[2]).toHaveAttribute('aria-invalid', 'true')
    expect(orders()).toHaveLength(0)
    await waitFor(() => expect(scrolledTo).toEqual(['Backup code 2 is required.']))
    expect(nudged).toEqual(['Backup code 2 is required.', 'Backup code 3 is required.'])
  })

  it('pressing Pay again with the same gap nudges again: the press always visibly does something', async () => {
    const pay = await toSignIn()
    fill(codeBoxes()[0]!, '12345678')
    await userEvent.click(pay)
    await waitFor(() => expect(nudged).toHaveLength(2))

    await userEvent.click(pay)

    await waitFor(() => expect(nudged).toHaveLength(4))
    expect(orders()).toHaveLength(0)
  })

  it('a malformed code is named by position too', async () => {
    const pay = await toSignIn()
    fill(codeBoxes()[0]!, '12345678')
    fill(codeBoxes()[1]!, '1234')
    fill(codeBoxes()[2]!, '87654321')
    await userEvent.click(pay)
    expectFieldError('Backup code 2 must be exactly 8 digits.')
    expect(orders()).toHaveLength(0)
  })

  it('with all three, the order is placed and the codes are sent', async () => {
    const pay = await toSignIn()
    codeBoxes().forEach((box, index) => fill(box, `1234567${index}`))
    await userEvent.click(pay)
    await waitFor(() => expect(orders()).toHaveLength(1))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/orders/GFS-26-CODES001/credentials',
      expect.objectContaining({ backupCodes: ['12345670', '12345671', '12345672'] })))
  })

  it('reduced motion: still scrolled to, never shaken', async () => {
    motion.reduce = true
    const pay = await toSignIn()
    await userEvent.click(pay)
    await waitFor(() => expect(scrolledTo).toEqual(['Backup code 1 is required.']))
    expect(nudged).toEqual([])
  })
})

describe('how many codes', () => {
  it.each([1, 2, 4])('follows the server\'s setting: %i boxes, every one required', async (count) => {
    CATALOG.policy.backupCodesRequired = count
    const pay = await toSignIn()
    expect(codeBoxes()).toHaveLength(count)

    await userEvent.click(pay)

    for (let n = 1; n <= count; n++) expectFieldError(`Backup code ${n} is required.`)
    expect(screen.queryByText(`Backup code ${count + 1} is required.`)).toBeNull()
    expect(orders()).toHaveLength(0)
  })

  it('three when the policy has not said', async () => {
    CATALOG.policy.backupCodesRequired = undefined
    await toSignIn()
    expect(codeBoxes()).toHaveLength(3)
  })

  it('the sign-in form on the order page follows the same setting', async () => {
    CATALOG.policy.backupCodesRequired = 2
    render(<MemoryRouter><CredentialForm publicRef="GFS-26-CODES001" onSubmitted={vi.fn()} /></MemoryRouter>)
    expect(screen.getAllByLabelText(/^Backup code \d$/)).toHaveLength(2)
  })
})
