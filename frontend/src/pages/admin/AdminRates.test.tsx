import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// ---- the API, mocked: these tests are about the page, not the server ---------------
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {},
}))

vi.mock('../../state/AuthContext', () => ({
  useAuth: () => ({ account: { email: 'admin@example.test', role: 'ADMIN', displayName: 'Administrator' },
    logout: vi.fn() }),
}))

// Imported after the mocks are declared, so the pages pick up the mocked modules.
const { default: AdminShell } = await import('./shell/AdminShell')
const { default: AdminRates, toRows } = await import('./AdminRates')

/**
 * The body GET /api/v1/admin/rates/coin-rates actually returned from the local backend,
 * byte for byte: the prices are JSON numbers in exponent form, not strings. Parsed here
 * the way the browser parses it, so the page sees exactly what production sends.
 */
const REAL_BODY = '[{"currency":"INR","symbol":"₹","perMillionMinor":1600000,"per100k":1.6E+3,'
  + '"per10k":1.6E+2,"stepIsWholeMinorUnit":true,"validFrom":"2026-09-19T16:45:14.876544Z"}]'

/** The Coin rates page mounted as the console mounts it: inside the shell's layout route. */
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

/** The load-failure alert, found by its title: the page and the shell have other alerts. */
async function loadFailure() {
  const title = await screen.findByText('The coin rates did not load')
  return title.closest('[role="alert"]') as HTMLElement
}

function serve(coinRates: () => Promise<unknown>) {
  api.get.mockImplementation((path: string) =>
    path === '/api/v1/admin/rates/coin-rates' ? coinRates() : Promise.resolve([]))
}

describe('Coin rates, in the console shell', () => {
  // Braces: a function returned from beforeEach is run as teardown.
  beforeEach(() => { api.get.mockReset(); api.post.mockReset() })

  it('renders the real response instead of crashing into the error boundary', async () => {
    serve(async () => JSON.parse(REAL_BODY))
    renderInShell()

    const field = await screen.findByLabelText('INR — price per 100,000 coins')
    expect(field).toHaveValue('1600')
    expect(screen.getByText('₹160 per 10,000 · ₹16000.00 per 1,000,000')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Coin rates' })).toBeInTheDocument()
    // The shell is around it: the sidebar's own entry for this page is there.
    expect(screen.getByRole('link', { name: 'Coin rates' })).toBeInTheDocument()
    expect(screen.queryByText(/This page didn.t load/)).toBeNull()
  })

  it('saves only the changed currency, as the text typed', async () => {
    serve(async () => JSON.parse(REAL_BODY))
    api.post.mockResolvedValue(JSON.parse(REAL_BODY))
    renderInShell()

    const field = await screen.findByLabelText('INR — price per 100,000 coins')
    await userEvent.clear(field)
    await userEvent.type(field, '1650')
    await userEvent.click(screen.getByRole('button', { name: 'Save rates' }))

    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/rates/coin-rates',
      { rates: [{ currency: 'INR', per100k: '1650' }] })
  })

  it('says so when there are no live rates', async () => {
    serve(async () => [])
    renderInShell()

    expect(await screen.findByText(/No live coin rates were found/)).toBeInTheDocument()
  })

  it('shows a failed request as a message with a retry, not a crashed page', async () => {
    let calls = 0
    serve(async () => {
      calls += 1
      if (calls === 1) throw new Error('network')
      return JSON.parse(REAL_BODY)
    })
    renderInShell()

    const alert = await loadFailure()
    expect(alert).toHaveTextContent('Could not load the coin rates.')
    expect(screen.queryByText(/This page didn.t load/)).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByLabelText('INR — price per 100,000 coins')).toHaveValue('1600')
  })

  it('refuses a body it does not recognise, with a message, rather than showing a price', async () => {
    serve(async () => ({ rates: JSON.parse(REAL_BODY) }))
    renderInShell()

    const alert = await loadFailure()
    expect(within(alert).getByText(/in a form this page does not recognise/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/price per 100,000 coins/)).toBeNull()
  })
})

describe('toRows', () => {
  it('turns number prices, exponent form included, into the text the inputs hold', () => {
    expect(toRows(JSON.parse(REAL_BODY))).toEqual([{
      currency: 'INR', symbol: '₹', perMillionMinor: 1600000, per100k: '1600', per10k: '160',
      stepIsWholeMinorUnit: true, validFrom: '2026-09-19T16:45:14.876544Z',
    }])
  })

  it('accepts prices sent as strings, should the server ever send them that way', () => {
    const [row] = toRows([{ currency: 'EUR', symbol: '€', perMillionMinor: 145500,
      per100k: '14.55', per10k: '1.455', stepIsWholeMinorUnit: false, validFrom: null }])!
    expect(row).toMatchObject({ per100k: '14.55', per10k: '1.455', stepIsWholeMinorUnit: false })
  })

  it('refuses anything that is not a list of rows with a currency and a price', () => {
    expect(toRows({ rates: [] })).toBeNull()
    expect(toRows(null)).toBeNull()
    expect(toRows([{ symbol: '₹', per100k: 1600 }])).toBeNull()
    expect(toRows([{ currency: 'INR', per100k: 'abc' }])).toBeNull()
    expect(toRows([])).toEqual([])
  })
})
