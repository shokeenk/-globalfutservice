import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  CoinPricingOverview, CoinPricingPreview, CoinPreviewRow, CoinRateView, CoinStructure,
} from '../../lib/types'

// ---- the API, mocked: these tests are about the page, not the server ---------------
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  // The real constructor's shape: (status, body), with the server's message on the body.
  ApiError: class ApiError extends Error {
    constructor(readonly status: number, body: { message: string }) {
      super(body.message)
    }
  },
}))

vi.mock('../../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'admin@example.test', role: 'ADMIN', displayName: 'Administrator' },
    logout: vi.fn() }),
}))

const { ApiError } = await import('../../lib/api')
const { default: AdminShell } = await import('./shell/AdminShell')
const { default: AdminRates } = await import('./AdminRates')
const { navFor } = await import('./shell/nav')
const { formatMinor, per100kText } = await import('./rates/coinPricing')

/*
 * The Coin rates page: two structures, PC and PlayStation + Xbox, each with its slider and
 * its prices per currency. What the page sends and shows is checked here; that the server's
 * preview equals a customer's quote for the same inputs is CoinPreviewParityTest's job, on
 * the backend, where the engine is.
 */

const LIMITS = {
  smallestStepK: 10, maxCapK: 10_000, vendorMinTransferK: 50, defaultQuickPicksK: [50, 100, 250, 500, 1000],
  maxQuickPicks: 8, maxBrackets: 10,
}
const CURRENCIES = ['INR', 'USD', 'EUR', 'GBP']

function rate(currency: string, fromK: number, perMillionMinor: number): CoinRateView {
  return {
    fromK, per100k: String(perMillionMinor / 1000), per100kFormatted: per100kText(perMillionMinor, currency),
    perMillionMinor, perMillionFormatted: formatMinor(perMillionMinor, currency),
    per10k: `${perMillionMinor / 10_000}`, stepIsWholeMinorUnit: perMillionMinor % 100 === 0,
  }
}

function structure(market: 'PC' | 'CONSOLE', patch: Partial<CoinStructure> = {}): CoinStructure {
  const prices: Record<string, number> = { INR: 1_300_000, USD: 14_300, EUR: 12_500, GBP: 10_800 }
  return {
    market, label: market === 'PC' ? 'PC' : 'PlayStation + Xbox',
    platforms: market === 'PC' ? ['PC'] : ['PLAYSTATION', 'XBOX'],
    version: market === 'PC' ? 3 : 4, validFrom: '2026-10-08T06:30:00Z', validTo: null,
    setBy: market === 'PC' ? 'owner@example.test' : null,
    minK: 50, maxK: 1000, stepK: 10, quickPicksK: [50, 100, 250, 500, 1000],
    rates: CURRENCIES.map((c) => ({ currency: c, symbol: c, base: rate(c, 0, prices[c]!), brackets: [] })),
    warnings: [],
    ...patch,
  }
}

const OVERVIEW: CoinPricingOverview = {
  season: 'FC26', currencies: CURRENCIES, limits: LIMITS, structures: [structure('PC'), structure('CONSOLE')],
}

function previewRow(patch: Partial<CoinPreviewRow> = {}): CoinPreviewRow {
  return {
    amountK: 100, currency: 'INR', per100k: '1300', per100kFormatted: '₹1,300.00', perMillionFormatted: '₹13,000.00',
    coinPriceMinor: 130_000, coinPriceFormatted: '₹1,300.00', marketTaxLabel: 'EA transfer market tax (5%)',
    marketTaxIncluded: true, marketTaxMinor: 0, marketTaxFormatted: '₹0.00', afterTaxMinor: 130_000,
    afterTaxFormatted: '₹1,300.00', guestTotalMinor: 133_250, guestTotalFormatted: '₹1,332.50',
    ...patch,
  }
}

let preview: CoinPricingPreview
function serve(overview: () => Promise<unknown> = () => Promise.resolve(OVERVIEW)) {
  api.get.mockImplementation((path: string) => {
    if (path === '/api/v1/admin/coin-pricing') return overview()
    if (path.endsWith('/history')) {
      return Promise.resolve([
        structure('PC'),
        structure('PC', { version: 2, validFrom: '2026-10-07T12:00:00Z', validTo: '2026-10-08T06:30:00Z', setBy: null }),
      ])
    }
    return Promise.resolve([])
  })
  api.post.mockImplementation(() => Promise.resolve(preview))
}

/** The page mounted as the console mounts it: inside the shell's layout route. */
function renderInShell() {
  return render(
    <MemoryRouter initialEntries={['/admin/services/rates']}>
      <Routes>
        <Route path="/admin" element={<AdminShell />}>
          <Route path="services/rates" element={<AdminRates />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

const card = (name: string) => within(screen.getByRole('region', { name }))
const fill = (input: HTMLElement, value: string) => fireEvent.change(input, { target: { value } })

beforeEach(() => {
  api.get.mockReset()
  api.post.mockReset()
  api.put.mockReset()
  preview = { errors: [], warnings: [], rows: [previewRow()] }
  serve()
})

describe('the page', () => {
  it('two structures side by side, the shared-price note, and who changed each last', async () => {
    renderInShell()
    const pc = card((await screen.findByRole('heading', { name: 'PC' })).textContent!)
    const console_ = card('PlayStation + Xbox (shared market)')
    expect(screen.getByText('PlayStation and Xbox always use the same prices.')).toBeInTheDocument()
    expect(pc.getByTestId('last-changed')).toHaveTextContent('Last changed by owner@example.test at')
    expect(console_.getByTestId('last-changed')).toHaveTextContent('Set up at')
    expect(pc.getByLabelText('Minimum (K)')).toHaveValue('50')
    expect(console_.getByLabelText('Base price per 100K (INR)')).toHaveValue('1300')
  })

  it('admins only: operators are not offered it', () => {
    const links = (role: string) => navFor(role).flatMap((i) => ('children' in i ? i.children : [i]))
      .map((i) => i.label)
    expect(links('ADMIN')).toContain('Coin rates')
    expect(links('OPERATOR')).not.toContain('Coin rates')
  })

  it('a failed load says so, with a retry, rather than showing a price', async () => {
    serve(() => Promise.reject(new ApiError(500, { error: 'internal', message: 'Server error.' })))
    renderInShell()
    expect(await screen.findByText('The coin prices did not load')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})

describe('checks, under the field, in the server’s words', () => {
  it('the slider: minimum and step in whole 10Ks, maximum at most 10M and on a step, quick picks on the slider', async () => {
    renderInShell()
    await screen.findByRole('region', { name: 'PC' })
    const pc = card('PC')

    fill(pc.getByLabelText('Minimum (K)'), '55')
    expect(pc.getByText('The minimum must be a whole multiple of 10K, at least 10K.')).toBeInTheDocument()
    // Off the step, it is shown in red and kept as typed -- never moved onto a step -- and
    // Save refuses it without asking the server anything.
    fireEvent.click(pc.getByRole('button', { name: 'Save PC prices' }))
    expect(pc.getByText('Fix the errors above before saving.')).toBeInTheDocument()
    expect(pc.getByLabelText('Minimum (K)')).toHaveValue('55')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.put).not.toHaveBeenCalled()
    fill(pc.getByLabelText('Minimum (K)'), '50')
    fill(pc.getByLabelText('Maximum (K)'), '20000')
    expect(pc.getByText('The maximum can be at most 10M.')).toBeInTheDocument()
    fill(pc.getByLabelText('Maximum (K)'), '995')
    expect(pc.getByText('The maximum must be the minimum plus whole steps of 10K: 990K or 1M, not 995K.'))
      .toBeInTheDocument()
    fill(pc.getByLabelText('Maximum (K)'), '1000')
    fill(pc.getByLabelText('Step (K)'), '5')
    expect(pc.getByText('The step must be a whole multiple of 10K, at least 10K.')).toBeInTheDocument()
    fill(pc.getByLabelText('Step (K)'), '10')
    fill(pc.getByLabelText('Quick-pick chips (K)'), '50, 55')
    expect(pc.getByText('Quick pick 55K is not on a step: the slider goes 50K, 60K…')).toBeInTheDocument()
    fill(pc.getByLabelText('Quick-pick chips (K)'), '50, 2000')
    expect(pc.getByText('Quick pick 2M is above the maximum (1M).')).toBeInTheDocument()
  })

  it('prices: a base price in every currency, brackets above the minimum and on a step; save refused until fixed', async () => {
    renderInShell()
    await screen.findByRole('region', { name: 'PC' })
    const pc = card('PC')

    fill(pc.getByLabelText('Base price per 100K (INR)'), '')
    expect(pc.getByText('Set a base price for INR.')).toBeInTheDocument()
    fill(pc.getByLabelText('Base price per 100K (INR)'), 'abc')
    expect(pc.getByText('Enter a price per 100K, e.g. 1300 or 14.30 (at most three decimals).')).toBeInTheDocument()
    fill(pc.getByLabelText('Base price per 100K (INR)'), '1300')

    fireEvent.click(pc.getByRole('button', { name: /Add INR bracket/ }))
    fill(pc.getByLabelText('INR bracket 1 from (K)'), '50')
    fill(pc.getByLabelText('INR bracket 1 price per 100K'), '1200')
    expect(pc.getByText('A bracket must start above the minimum (50K); the base price covers the minimum.'))
      .toBeInTheDocument()
    fill(pc.getByLabelText('INR bracket 1 from (K)'), '505')
    expect(pc.getByText('The bracket from 505K is not on a step: the slider goes 500K, 510K…')).toBeInTheDocument()

    fireEvent.click(pc.getByRole('button', { name: 'Save PC prices' }))
    expect(await pc.findByText('Fix the errors above before saving.')).toBeInTheDocument()
    expect(api.put).not.toHaveBeenCalled()
  })

  it('every price shows its per-1M figure beside it', async () => {
    renderInShell()
    await screen.findByRole('region', { name: 'PC' })
    const pc = card('PC')
    expect(pc.getByText('= ₹13,000.00 per 1M')).toBeInTheDocument()
    fill(pc.getByLabelText('Base price per 100K (INR)'), '1250')
    expect(pc.getByText('= ₹12,500.00 per 1M')).toBeInTheDocument()

    fireEvent.click(pc.getByRole('button', { name: 'USD' }))
    expect(pc.getByText('= $143.00 per 1M')).toBeInTheDocument()

    fireEvent.click(pc.getByRole('button', { name: /Add USD bracket/ }))
    fill(pc.getByLabelText('USD bracket 1 price per 100K'), '13.9')
    expect(pc.getByLabelText('USD bracket 1 price per 100K').closest('tr')).toHaveTextContent('$139.00')
  })
})

describe('the preview calculator', () => {
  it('asks the server with the draft and the amount, and shows its price before and after the market tax', async () => {
    renderInShell()
    await screen.findByRole('region', { name: 'PC' })
    const pc = card('PC')
    const shown = await pc.findByTestId('coin-preview')

    // Each card asks about its own structure; this is PC's question.
    const [, body] = api.post.mock.calls.find(([path]) => path === '/api/v1/admin/coin-pricing/PC/preview')!
    expect(body).toMatchObject({ minK: 50, maxK: 1000, stepK: 10, previewK: [100] })
    expect(body.rates).toContainEqual({ currency: 'INR', per100k: '1300', brackets: [] })

    expect(shown).toHaveTextContent('Before market tax₹1,300.00')
    expect(shown).toHaveTextContent('EA transfer market tax (5%)Included in the price')
    expect(shown).toHaveTextContent('After market tax₹1,300.00')
    expect(shown).toHaveTextContent('A guest pays, card fee included₹1,332.50')
  })

  it('with the tax added on top, shows it, and the price after it', async () => {
    preview = { errors: [], warnings: [], rows: [previewRow({ amountK: 600, currency: 'USD',
      marketTaxIncluded: false, coinPriceFormatted: '$85.80', marketTaxFormatted: '$4.29', afterTaxFormatted: '$90.09',
      guestTotalFormatted: '$92.34' })] }
    renderInShell()
    await screen.findByRole('region', { name: 'PC' })
    const pc = card('PC')
    fill(pc.getByLabelText('Amount (K)'), '600')
    fireEvent.change(pc.getByLabelText('Currency'), { target: { value: 'USD' } })

    await waitFor(() => expect(pc.getByTestId('coin-preview')).toHaveTextContent('+$4.29'))
    expect(pc.getByTestId('coin-preview')).toHaveTextContent('Before market tax$85.80')
    expect(pc.getByTestId('coin-preview')).toHaveTextContent('After market tax$90.09')
    const asked = api.post.mock.calls.filter(([path]) => path === '/api/v1/admin/coin-pricing/PC/preview')
    expect(asked[asked.length - 1]![1]).toMatchObject({ previewK: [600] })
  })

  it('an amount the slider does not stop on is said, not priced', async () => {
    renderInShell()
    await screen.findByRole('region', { name: 'PC' })
    const pc = card('PC')
    fill(pc.getByLabelText('Amount (K)'), '55')
    expect(pc.getByText('Pick an amount the slider stops on: 50K to 1M in steps of 10K.')).toBeInTheDocument()
  })

  it('the server’s warnings, a price typed in the wrong unit among them, are shown before saving', async () => {
    const wrongUnit = 'INR: the base price, ₹13.00 per 100K (₹130.00 per 1M), is less than a fifth of '
      + 'PlayStation + Xbox\'s ₹1,300.00 per 100K. It may have been typed in the wrong unit: prices here are per '
      + '100,000 coins.'
    preview = { errors: [], warnings: [wrongUnit], rows: [previewRow()] }
    renderInShell()
    await screen.findByRole('region', { name: 'PC' })
    const pc = card('PC')
    fill(pc.getByLabelText('Base price per 100K (INR)'), '13')

    const box = await pc.findByTestId('coin-warnings')
    expect(box).toHaveTextContent('Check before saving')
    expect(box).toHaveTextContent(wrongUnit)
  })
})

describe('saving', () => {
  it('confirms with exactly what changes and the warnings, then saves the draft and says who changed it last', async () => {
    const warning = 'INR: 500K costs ₹6,000.00, less than 490K at ₹6,370.00.'
    preview = { errors: [], warnings: [warning], rows: [previewRow()] }
    api.put.mockResolvedValue(structure('PC', { version: 5, setBy: 'second@example.test',
      rates: CURRENCIES.map((c) => ({ currency: c, symbol: c,
        base: rate(c, 0, c === 'INR' ? 1_250_000 : ({ USD: 14_300, EUR: 12_500, GBP: 10_800 } as Record<string, number>)[c]!),
        brackets: c === 'INR' ? [rate(c, 500, 1_200_000)] : [] })) }))
    renderInShell()
    await screen.findByRole('region', { name: 'PC' })
    const pc = card('PC')
    expect(pc.getByRole('button', { name: 'Save PC prices' })).toBeDisabled()

    fill(pc.getByLabelText('Base price per 100K (INR)'), '1250')
    fireEvent.click(pc.getByRole('button', { name: /Add INR bracket/ }))
    fill(pc.getByLabelText('INR bracket 1 from (K)'), '500')
    fill(pc.getByLabelText('INR bracket 1 price per 100K'), '1200')
    fireEvent.click(pc.getByRole('button', { name: 'Save PC prices' }))

    const dialog = await screen.findByRole('dialog', { name: 'Save PC prices?' })
    expect(within(dialog).getByTestId('confirm-changes')).toHaveTextContent(
      'INR base price: ₹1,300.00 → ₹1,250.00 per 100K (₹13,000.00 → ₹12,500.00 per 1M)')
    expect(within(dialog).getByTestId('confirm-changes')).toHaveTextContent(
      'INR: new bracket from 500K at ₹1,200.00 per 100K (₹12,000.00 per 1M)')
    expect(dialog).toHaveTextContent(warning)
    expect(api.put).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Save prices' }))
    await waitFor(() => expect(api.put).toHaveBeenCalledTimes(1))
    const [path, body] = api.put.mock.calls[0]!
    expect(path).toBe('/api/v1/admin/coin-pricing/PC')
    expect(body).toEqual({
      minK: 50, maxK: 1000, stepK: 10, quickPicksK: [50, 100, 250, 500, 1000],
      rates: [
        { currency: 'INR', per100k: '1250', brackets: [{ fromK: 500, per100k: '1200' }] },
        { currency: 'USD', per100k: '14.3', brackets: [] },
        { currency: 'EUR', per100k: '12.5', brackets: [] },
        { currency: 'GBP', per100k: '10.8', brackets: [] },
      ],
    })
    expect(await pc.findByText('Saved. PC prices apply to the next quote.')).toBeInTheDocument()
    expect(pc.getByTestId('last-changed')).toHaveTextContent('Last changed by second@example.test')
    expect(pc.getByRole('button', { name: 'Save PC prices' })).toBeDisabled()
    // The other structure is untouched: nothing here writes PC's prices into PlayStation + Xbox.
    expect(card('PlayStation + Xbox (shared market)').getByLabelText('Base price per 100K (INR)')).toHaveValue('1300')
  })

  it('a save the server refuses shows every reason, and nothing changes', async () => {
    api.put.mockRejectedValue(new ApiError(400, { error: 'invalid_coin_pricing',
      message: 'The maximum can be at most 10M.\nSet a base price for USD.' }))
    renderInShell()
    await screen.findByRole('region', { name: 'PC' })
    const pc = card('PC')
    fill(pc.getByLabelText('Base price per 100K (INR)'), '1250')
    fireEvent.click(pc.getByRole('button', { name: 'Save PC prices' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Save prices' }))

    expect(await pc.findByText('The maximum can be at most 10M.')).toBeInTheDocument()
    expect(pc.getByText('Set a base price for USD.')).toBeInTheDocument()
    expect(pc.getByTestId('last-changed')).toHaveTextContent('owner@example.test')
  })
})

describe('change history', () => {
  it('the link opens every version, newest first, who set it and what it said', async () => {
    renderInShell()
    await screen.findByRole('region', { name: 'PC' })
    fireEvent.click(card('PC').getByRole('button', { name: 'Change history' }))

    const dialog = await screen.findByRole('dialog', { name: 'PC: change history' })
    const versions = await within(dialog).findAllByRole('listitem')
    expect(api.get).toHaveBeenCalledWith('/api/v1/admin/coin-pricing/PC/history')
    const entries = within(within(dialog).getByTestId('coin-history')).getAllByRole('listitem')
      .filter((li) => li.parentElement?.getAttribute('data-testid') === 'coin-history')
    expect(entries).toHaveLength(2)
    expect(entries[0]).toHaveTextContent('owner@example.test')
    expect(entries[0]).toHaveTextContent('Live')
    expect(entries[0]).toHaveTextContent('INR: ₹1,300.00 per 100K (₹13,000.00 per 1M)')
    expect(entries[1]).toHaveTextContent('set up when coin pricing moved to structures')
    expect(entries[1]).not.toHaveTextContent('Live')
    expect(versions.length).toBeGreaterThan(2)
  })
})
