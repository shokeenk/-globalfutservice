import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CharCount, Switch, TypeTiles } from './controls'
import type { CampaignType } from './wizard'

// ---- the API, mocked: these tests are about the page, not the server ---------------
const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  html: vi.fn(),
  upload: vi.fn(),
}))
vi.mock('../../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {
    status: number
    fieldErrors: Record<string, string> = {}
    constructor(status: number, body: { message: string }) {
      super(body.message)
      this.status = status
    }
  },
}))

// Imported after the mock is declared, so the page picks up the mocked module.
const { default: SendCampaign } = await import('./SendCampaign')

function HistoryProbe() {
  const location = useLocation()
  return <p data-testid="history">{(location.state as { notice?: string } | null)?.notice}</p>
}

function renderPage(path = '/admin/email/send') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/email/send" element={<SendCampaign />} />
        <Route path="/admin/email/history" element={<HistoryProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('controls', () => {
  it('counts against the limit and says when it is over', () => {
    const { rerender } = render(<CharCount id="c" value={'s'.repeat(32)} limit={100} />)
    expect(screen.getByText('32/100')).toBeInTheDocument()
    rerender(<CharCount id="c" value={'s'.repeat(101)} limit={100} />)
    expect(screen.getByText(/101\/100/)).toHaveTextContent('over the limit')
  })

  it('announces a switch as on or off, and the whole row toggles it', async () => {
    function Harness() {
      const [on, setOn] = useState(true)
      return <Switch checked={on} onChange={setOn}>Track email opens and clicks</Switch>
    }
    render(<Harness />)
    const toggle = screen.getByRole('switch', { name: 'Track email opens and clicks' })
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    await userEvent.click(screen.getByText('Track email opens and clicks'))
    expect(toggle).toHaveAttribute('aria-checked', 'false')
  })

  it('moves the campaign type with the arrow keys, like a native radio group', async () => {
    function Harness() {
      const [type, setType] = useState<CampaignType>('COINS')
      return (<><span id="l">Campaign Type</span><TypeTiles value={type} onChange={setType} labelledBy="l" /></>)
    }
    render(<Harness />)
    const group = screen.getByRole('radiogroup', { name: 'Campaign Type' })
    const coins = within(group).getByRole('radio', { name: /Coins/ })
    expect(coins).toHaveAttribute('aria-checked', 'true')
    // Only the chosen tile is in the Tab order.
    expect(within(group).getAllByRole('radio').filter((r) => r.tabIndex === 0)).toHaveLength(1)

    coins.focus()
    await userEvent.keyboard('{ArrowRight}')
    const champs = within(group).getByRole('radio', { name: /FUT Champs/ })
    expect(champs).toHaveAttribute('aria-checked', 'true')
    expect(champs).toHaveFocus()

    await userEvent.keyboard('{ArrowLeft}{ArrowLeft}')
    expect(within(group).getByRole('radio', { name: /General/ })).toHaveAttribute('aria-checked', 'true')
  })
})

describe('Send Campaign', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    Object.values(api).forEach((fn) => fn.mockReset())
    api.html.mockResolvedValue('<html><head></head><body>email</body></html>')
  })
  afterEach(() => vi.useRealTimers())

  const user = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

  it('labels every input, so each is reachable by its name', () => {
    renderPage()
    for (const name of ['Campaign Name', 'Email Subject', 'Promo Title', 'Discount / Offer',
      'Promo Code', 'Offer Valid Till', 'Offer Description']) {
      expect(screen.getByLabelText(new RegExp(`^${name.replace('/', '\\/')}`))).toBeInTheDocument()
    }
  })

  it('counts the subject and the description as they are typed', async () => {
    renderPage()
    await user().type(screen.getByLabelText(/^Email Subject/), 'TOTY is here')
    expect(screen.getByText('12/100')).toBeInTheDocument()
    await user().type(screen.getByLabelText(/^Offer Description/), 'Hello')
    expect(screen.getByText('5/500')).toBeInTheDocument()
  })

  it('refuses Next Step on an empty form, says why, and focuses the first problem', async () => {
    renderPage()
    await user().click(screen.getByRole('button', { name: /Next Step/ }))

    expect(screen.getByRole('status')).toHaveTextContent('4 fields need attention')
    expect(screen.getByLabelText(/^Campaign Name/)).toHaveFocus()
    for (const name of ['Campaign Name', 'Email Subject', 'Promo Title', 'Offer Description']) {
      expect(screen.getByLabelText(new RegExp(`^${name}`))).toHaveAttribute('aria-invalid', 'true')
    }
    expect(api.post).not.toHaveBeenCalled()
  })

  it('refuses a subject over 100 characters before anything is sent', async () => {
    renderPage()
    fireEvent.change(screen.getByLabelText(/^Campaign Name/), { target: { value: 'n' } })
    fireEvent.change(screen.getByLabelText(/^Email Subject/), { target: { value: 's'.repeat(101) } })
    fireEvent.change(screen.getByLabelText(/^Promo Title/), { target: { value: 'p' } })
    fireEvent.change(screen.getByLabelText(/^Offer Description/), { target: { value: 'd' } })
    await user().click(screen.getByRole('button', { name: /Next Step/ }))

    expect(screen.getByText('Keep the subject to 100 characters.')).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()
  })

  it('renders the preview once a burst of typing stops, not on every keystroke', async () => {
    renderPage()
    await act(async () => { await vi.advanceTimersByTimeAsync(400) })
    api.html.mockClear()

    await user().type(screen.getByLabelText(/^Promo Title/), 'Coins Sale')
    await act(async () => { await vi.advanceTimersByTimeAsync(400) })

    expect(api.html).toHaveBeenCalledTimes(1)
    const [path, body] = api.html.mock.calls[0]!
    expect(path).toBe('/api/v1/admin/campaigns/preview')
    expect(body).toMatchObject({ promoTitle: 'Coins Sale', type: 'COINS', showButton: true })
    expect(await screen.findByTitle('Email preview')).toBeInTheDocument()
  })

  it('saves a valid step as a draft and hands over to Campaign History', async () => {
    api.post.mockResolvedValue({ publicId: 'camp_abc', title: 'TOTY', status: 'DRAFT' })
    renderPage()
    fireEvent.change(screen.getByLabelText(/^Campaign Name/), { target: { value: 'TOTY' } })
    fireEvent.change(screen.getByLabelText(/^Email Subject/), { target: { value: 'TOTY is here' } })
    fireEvent.change(screen.getByLabelText(/^Promo Title/), { target: { value: 'Coins Sale' } })
    fireEvent.change(screen.getByLabelText(/^Discount \/ Offer/), { target: { value: '15% OFF' } })
    fireEvent.change(screen.getByLabelText(/^Offer Description/), { target: { value: 'Team of the Year.' } })

    await user().click(screen.getByRole('button', { name: /Next Step/ }))

    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/campaigns/drafts', expect.objectContaining({
      title: 'TOTY', subject: 'TOTY is here', type: 'COINS', promoTitle: 'Coins Sale',
      offerText: '15% OFF', promoCode: null, showButton: true, trackingEnabled: true,
    }))
    expect(await screen.findByTestId('history')).toHaveTextContent('Draft "TOTY" saved')
  })

  it('reopens a saved draft from the address and saves it in place', async () => {
    api.get.mockResolvedValue({
      publicId: 'camp_abc', title: 'TOTY', subject: 'S', heading: 'Coins Sale', body: 'B',
      status: 'DRAFT', type: 'BOOSTING', offerText: '15% OFF', showButton: true,
      showPromoCode: false, trackingEnabled: false, hasBanner: false,
    })
    api.put.mockResolvedValue({ publicId: 'camp_abc', title: 'TOTY', status: 'DRAFT' })
    renderPage('/admin/email/send?draft=camp_abc')

    expect(await screen.findByDisplayValue('Coins Sale')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /FUT Champs/ })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('switch', { name: /Track email/ })).toHaveAttribute('aria-checked', 'false')

    await user().click(screen.getByRole('button', { name: /Next Step/ }))
    expect(api.put).toHaveBeenCalledWith('/api/v1/admin/campaigns/camp_abc/details',
      expect.objectContaining({ type: 'BOOSTING', showPromoCode: false, trackingEnabled: false }))
    expect(api.post).not.toHaveBeenCalled()
  })

  it('refuses a banner that is not an image before uploading anything', async () => {
    renderPage()
    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(input, { target: { files: [new File(['x'], 'notes.pdf', { type: 'application/pdf' })] } })

    expect(await screen.findByText('Use a PNG, JPEG, GIF or WebP image.')).toBeInTheDocument()
    expect(api.upload).not.toHaveBeenCalled()
    expect(api.post).not.toHaveBeenCalled()
  })
})
