import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), upload: vi.fn() }))
vi.mock('../../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))

const role = vi.hoisted(() => ({ current: 'ADMIN' }))
vi.mock('../../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'staff@example.test', role: role.current, displayName: 'Staff' }, logout: vi.fn() }),
}))

const { default: AdminPayop, reasonText } = await import('./AdminPayop')

const OVERVIEW = {
  enabled: false, configured: false, jwtExpiresAt: null,
  merchantPaysNote: 'Payop’s commission must stay "merchant pays" in Payop’s panel.',
  methods: 2, activeMethods: 2, needsAttention: 1,
  rates: [{ currency: 'USD', rate: 1.1225, source: 'ECB', date: '2026-10-02', current: true },
    { currency: 'AED', rate: null, source: null, date: null, current: false }],
}
const INVOICES = [{
  id: 5, orderRef: 'GFS-26-EUR00001', invoiceId: 'inv-1', status: 'REVIEW', reason: 'UNCONFIRMED_AMOUNT',
  methodName: 'Bank transfer', currency: 'EUR', totalFormatted: '€94.07', amountSent: '94.07', country: 'DE',
  txid: 'tx-1', createdAt: '2026-10-04T10:00:00Z', expiresAt: '2026-10-05T10:00:00Z',
}]
const FEES = [{
  methodId: 381, name: 'Bank transfer', type: 'bank_transfer', region: 'Europe', fixedEur: 0.3, percent: 2.4,
  countries: ['DE', 'AT'], currencies: ['EUR'], active: true, version: 1, updatedAt: '2026-10-04T10:00:00Z',
}]

beforeEach(() => {
  api.get.mockReset()
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/admin/payop') return OVERVIEW
    if (path.startsWith('/api/v1/admin/payop/invoices')) return INVOICES
    if (path === '/api/v1/admin/payop/fees') return FEES
    return []
  })
})

function open() {
  render(<MemoryRouter><AdminPayop /></MemoryRouter>)
}

const LIVE = [
  { methodId: 900001, title: 'Cards', type: 'cards_international', currencies: ['INR', 'USD'], countries: ['IN'],
    card: true, fee: null, offered: false },
  { methodId: 381, title: 'Bank transfer', type: 'bank_transfer', currencies: ['EUR'], countries: ['IN', 'DE'],
    card: false, fee: FEES[0], offered: true },
]

describe('pricing a method by hand, and the Payop check', () => {
  beforeEach(() => {
    api.post.mockReset()
    const base = api.get.getMockImplementation()!
    api.get.mockImplementation(async (path: string) =>
      (path.startsWith('/api/v1/admin/payop/live-methods') ? LIVE : base(path)))
    api.post.mockImplementation(async (_path: string, body: { name: string }) => ({
      ...FEES[0], methodId: 900001, name: body.name, type: 'cards_international', source: 'MANUAL', version: 1 }))
  })

  it('an admin adds the card method the sheet lacks: every field goes to the server, which checks and audits it', async () => {
    role.current = 'ADMIN'
    open()
    await userEvent.click(await screen.findByRole('button', { name: /Fee table/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Add a method' }))
    const dialog = screen.getByRole('dialog', { name: 'Price a method by hand' })
    await userEvent.type(within(dialog).getByLabelText('Payop method ID'), '900001')
    await userEvent.type(within(dialog).getByLabelText(/^Type/), 'cards_international')
    await userEvent.type(within(dialog).getByLabelText('Name customers see'), 'Visa / Mastercard')
    await userEvent.type(within(dialog).getByLabelText('Fixed part (EUR)'), '0.20')
    await userEvent.type(within(dialog).getByLabelText('Percentage'), '3.5')
    await userEvent.type(within(dialog).getByLabelText(/^Countries/), 'in')
    await userEvent.type(within(dialog).getByLabelText('Processing currencies'), 'inr, usd')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/payop/fees', {
      methodId: 900001, name: 'Visa / Mastercard', type: 'cards_international', region: null, fixedEur: 0.2,
      percent: 3.5, countries: ['IN'], currencies: ['INR', 'USD'], active: true,
    })
    expect(await screen.findByText('Visa / Mastercard: priced by hand, version 1.')).toBeInTheDocument()
  })

  it('the Payop check: what Payop lists for India now, priced or not, and a fee added from it', async () => {
    role.current = 'ADMIN'
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'Payop check' }))
    expect(screen.getByLabelText('Country to check')).toHaveValue('IN')
    await userEvent.click(screen.getByRole('button', { name: 'Check with Payop' }))

    const table = await screen.findByTestId('payop-check')
    expect(api.get).toHaveBeenCalledWith('/api/v1/admin/payop/live-methods?country=IN')
    const cards = within(table).getByText('900001').closest('tr') as HTMLElement
    expect(cards).toHaveTextContent('cards_international')
    expect(cards).toHaveTextContent('INR, USD')
    expect(cards).toHaveTextContent('No fee')
    expect(cards).toHaveTextContent('Hidden')
    const bank = within(table).getByText('381').closest('tr') as HTMLElement
    expect(bank).toHaveTextContent('0.30 EUR + 2.4%')
    expect(bank).toHaveTextContent('Offered')
    expect(within(bank).queryByRole('button', { name: 'Add fee' })).toBeNull()

    await userEvent.click(within(cards).getByRole('button', { name: 'Add fee' }))
    const dialog = screen.getByRole('dialog', { name: 'Price a method by hand' })
    expect(within(dialog).getByLabelText('Payop method ID')).toHaveValue('900001')
    expect(within(dialog).getByLabelText(/^Type/)).toHaveValue('cards_international')
    expect(within(dialog).getByLabelText(/^Countries/)).toHaveValue('IN')
  })

  it('an operator sees neither: no "Add a method", no Payop check', async () => {
    role.current = 'OPERATOR'
    open()
    await userEvent.click(await screen.findByRole('button', { name: /Fee table/ }))
    expect(await screen.findByText('Bank transfer', { selector: 'td' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add a method' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Payop check' })).toBeNull()
  })
})

describe('Payop administration', () => {
  it('always shows the merchant-pays rule, whether Payop is on, and the currencies with no rate', async () => {
    role.current = 'OPERATOR'
    open()
    expect(await screen.findByText(/must stay "merchant pays"/)).toBeInTheDocument()
    expect(screen.getByText(/Not offered to anyone/)).toBeInTheDocument()
    expect(screen.getByText(/no exchange rate for AED/)).toBeInTheDocument()
  })

  it('an operator can check a payment with Payop, but is offered no way to accept, edit or import', async () => {
    role.current = 'OPERATOR'
    open()
    const row = (await screen.findByText('GFS-26-EUR00001')).closest('tr') as HTMLElement
    expect(within(row).getByText('Payop did not state the amount: check the dashboard')).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Check with Payop' })).toBeInTheDocument()
    expect(within(row).queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Fee table/ }))
    expect(await screen.findByText('Bank transfer', { selector: 'td' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Import pricing sheet' })).not.toBeInTheDocument()
  })

  it('an admin gets accept, edit and import', async () => {
    role.current = 'ADMIN'
    open()
    const row = (await screen.findByText('GFS-26-EUR00001')).closest('tr') as HTMLElement
    expect(within(row).getByRole('button', { name: 'Accept' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Fee table/ }))
    expect(await screen.findByRole('button', { name: 'Edit' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Import pricing sheet' })).toBeInTheDocument()
  })

  it('names every stored reason in words', () => {
    expect(reasonText('DUPLICATE_PAYMENT')).toBe('Duplicate payment, refund needed')
    expect(reasonText('ORDER_ABANDONED')).toBe('Paid on an order that is abandoned')
    expect(reasonText('CREATE_WRONG_SIGNATURE')).toBe('Payop did not create the invoice')
  })
})
