import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminListing, AdminListingsOverview } from '../../lib/types'

const api = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn(), post: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, body: { message: string }) { super(body.message) }
  },
}))

const { default: Listings } = await import('./AdminListings')
const { fromMajor, fromPercent, toMajor, toPercent } = await import('./listings/money')

function listing(variant: string, inr: number, patch: Partial<AdminListing> = {}): AdminListing {
  return {
    sku: 'BOOST_CHAMPS', variant, label: variant, sortOrder: 0, active: true,
    prices: { INR: { minor: inr, formatted: `₹${(inr / 100).toLocaleString('en-IN')}.00` }, USD: { minor: 2399, formatted: '$23.99' } },
    successRateBps: null, successRateSource: null, bestValue: false, ...patch,
  }
}

const DATA: AdminListingsOverview = {
  currencies: ['INR', 'USD'],
  categories: [
    { sku: 'BOOST_CHAMPS', name: 'Champs Wins', bestValueChoice: 'DEFAULT', listings: [
      listing('WINS_11', 190000),
      listing('WINS_15', 355000, { successRateBps: 9500, successRateSource: 'CONFIGURATION', bestValue: true }),
      listing('WINS_9', 120000, { active: false }),
    ] },
    { sku: 'BOOST_RIVALS', name: 'Rivals Divisions', bestValueChoice: 'DEFAULT', listings: [listing('DIV_5_TO_4', 75000, { sku: 'BOOST_RIVALS' })] },
    { sku: 'COACHING', name: 'Coaching', listings: [listing('SINGLE_SESSION', 100000, { sku: 'COACHING' })] },
  ],
}

function renderAt(path = '/admin/services/listings') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/services/listings" element={<Listings />} />
        <Route path="/admin/services/listings/new" element={<Listings />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('Service Listings', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.put.mockReset()
    api.post.mockReset()
    api.get.mockResolvedValue(DATA)
  })
  afterEach(() => vi.restoreAllMocks())

  it('shows the services, and each tier with the title customers see, its price and tags', async () => {
    renderAt()
    expect(await screen.findByRole('button', { name: /Champs Wins.*3 listings/ })).toBeInTheDocument()
    // Titles come from the site's wording, not the stored code.
    expect(screen.getByText('11 wins · Elite V · Rank 5')).toBeInTheDocument()
    expect(screen.getByText('₹1,900.00')).toBeInTheDocument()
    expect(screen.getByText('95% success rate')).toBeInTheDocument()
    expect(screen.getByText('Best Value')).toBeInTheDocument()
    expect(screen.getByText('Hidden')).toBeInTheDocument()
    expect(screen.getByText('2 Active')).toBeInTheDocument()
    // Only services the site sells.
    expect(screen.queryByText(/Tournaments|Objectives/)).toBeNull()
  })

  it('edits a tier: title read-only, prices per currency, and asks before changing a price', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    api.put.mockResolvedValue(DATA)
    renderAt('/admin/services/listings?listing=WINS_11')

    const editor = await screen.findByRole('complementary', { name: 'Edit listing' })
    expect(within(editor).getByDisplayValue('11 wins · Elite V · Rank 5')).toHaveAttribute('readonly')
    fireEvent.change(within(editor).getByLabelText('Price in INR'), { target: { value: '1,950' } })
    fireEvent.change(within(editor).getByLabelText('Success rate in percent'), { target: { value: '91.5' } })
    fireEvent.click(within(editor).getByRole('button', { name: 'Save Changes' }))

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/api/v1/admin/listings/BOOST_CHAMPS/WINS_11', {
      prices: { INR: 195000, USD: 2399 }, active: true, successRateBps: 9150, bestValue: false,
    }))
    expect(confirm.mock.calls[0]![0]).toContain('INR: ₹1,900.00 → ₹1950')
    expect(confirm.mock.calls[0]![0]).toContain('orders already placed keep theirs')
    expect(await screen.findByRole('status')).toHaveTextContent('Saved 11 wins')
  })

  it('turns a tier off with the switch, saying it leaves the site', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    api.put.mockResolvedValue(DATA)
    renderAt('/admin/services/listings?listing=WINS_11')
    const editor = await screen.findByRole('complementary', { name: 'Edit listing' })
    fireEvent.click(within(editor).getByRole('switch', { name: 'Active' }))
    fireEvent.click(within(editor).getByRole('button', { name: 'Save Changes' }))
    await waitFor(() => expect(api.put).toHaveBeenCalled())
    expect(api.put.mock.calls[0]![1]).toMatchObject({ active: false, bestValue: false })
    expect(confirm.mock.calls[0]![0]).toContain('disappears from the site')
  })

  it('refuses a price that is not a number, without sending anything', async () => {
    renderAt('/admin/services/listings?listing=WINS_11')
    const editor = await screen.findByRole('complementary', { name: 'Edit listing' })
    fireEvent.change(within(editor).getByLabelText('Price in INR'), { target: { value: '19OO' } })
    fireEvent.click(within(editor).getByRole('button', { name: 'Save Changes' }))
    expect(await within(editor).findByRole('alert')).toHaveTextContent('The INR price is not a number.')
    expect(api.put).not.toHaveBeenCalled()
  })

  it('has no success rate or Best Value for coaching', async () => {
    renderAt('/admin/services/listings?service=COACHING&listing=SINGLE_SESSION')
    const editor = await screen.findByRole('complementary', { name: 'Edit listing' })
    expect(within(editor).queryByLabelText('Success rate in percent')).toBeNull()
    expect(within(editor).queryByText('Best Value tag')).toBeNull()
  })

  it('adds a Champs or Rivals tier', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    api.post.mockResolvedValue({ sku: 'BOOST_RIVALS', variant: 'DIV_6_TO_5_FAST' })
    renderAt('/admin/services/listings/new')
    const editor = await screen.findByRole('complementary', { name: 'Add listing' })
    fireEvent.change(within(editor).getByLabelText('Category'), { target: { value: 'BOOST_RIVALS' } })
    fireEvent.change(within(editor).getByLabelText('Title'), { target: { value: 'Div 6 to 5 fast' } })
    fireEvent.change(within(editor).getByLabelText('Price in INR'), { target: { value: '999' } })
    fireEvent.click(within(editor).getByRole('button', { name: 'Add Listing' }))
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/admin/listings', {
      sku: 'BOOST_RIVALS', name: 'Div 6 to 5 fast', prices: { INR: 99900 }, successRateBps: null,
    }))
  })

  it('narrows the cards to the top bar’s search', async () => {
    renderAt('/admin/services/listings?search=15%20wins')
    expect(await screen.findByText('1 matching “15 wins”')).toBeInTheDocument()
    expect(screen.getByText('15 wins · Elite I · Rank 1')).toBeInTheDocument()
    expect(screen.queryByText('11 wins · Elite V · Rank 5')).toBeNull()
  })

  it('says the listings could not load rather than showing none', async () => {
    api.get.mockRejectedValue(new Error('offline'))
    renderAt()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the listings')
  })
})

describe('typed prices and rates', () => {
  it('reads what a person types, and refuses what is not a price', () => {
    expect(fromMajor('1,900')).toBe(190000)
    expect(fromMajor('23.99')).toBe(2399)
    expect(fromMajor('23.9')).toBe(2390)
    expect(fromMajor('19OO')).toBeNull()
    expect(fromMajor('1.234')).toBeNull()
    expect(toMajor(190000)).toBe('1900')
    expect(toMajor(2399)).toBe('23.99')
    expect(fromPercent('92')).toBe(9200)
    expect(fromPercent('91.5%')).toBe(9150)
    expect(toPercent(9150)).toBe('91.5')
  })
})
