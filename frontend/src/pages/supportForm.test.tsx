import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * The Support page's form: what it requires, and that a message is sent once, with a
 * clear answer either way. And the page around it stays as it was, Discord included --
 * as the community link, not the support chat.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../lib/api', async (original) => ({ ...(await original<typeof import('../lib/api')>()), api }))
vi.mock('../state/AuthContext', () => ({ useAuth: () => ({ account: null, loading: false }) }))

window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { ApiError } = await import('../lib/api')
const { I18nProvider } = await import('../i18n')
const { default: Support } = await import('./Support')

function page() {
  return render(<I18nProvider><MemoryRouter><Support /></MemoryRouter></I18nProvider>)
}

async function fill(user: ReturnType<typeof userEvent.setup>, tick = true) {
  await user.type(screen.getByLabelText(/Your email/), 'ada@example.test')
  await user.type(screen.getByLabelText(/^Subject/), 'Coins not arrived')
  await user.type(screen.getByLabelText(/What is going on/), 'Ordered yesterday, nothing yet.')
  if (tick) await user.click(screen.getByRole('checkbox'))
}

const send = () => screen.getByRole('button', { name: /Send message|Sending/ })

beforeEach(() => window.localStorage.clear())
afterEach(() => vi.clearAllMocks())

describe('the Support page stays as it was', () => {
  it('has the form, the Discord community panel, the contact details and Faster answers', () => {
    page()
    for (const label of [/Your email/, /Order reference/, /What is it about/, /^Subject/, /What is going on/]) {
      expect(screen.getByLabelText(label)).toBeInTheDocument()
    }
    expect(screen.getByRole('checkbox', { name: /not included my password or backup codes/ })).not.toBeChecked()
    expect(send()).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Contact details' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Faster answers' })).toBeInTheDocument()
  })

  it('"Join our Discord" is the community link, and never says it is the support chat', () => {
    page()
    const discord = screen.getByRole('link', { name: 'Join our Discord' })
    expect(discord).toHaveClass('bg-[#5865F2]')
    expect(discord).not.toHaveClass('bg-brand-500')
    expect(document.body.textContent).toMatch(/Our community server/)
    expect(document.body.textContent).not.toMatch(/official support channel/i)
  })
})

describe('what the form requires', () => {
  it('email, subject, message and the acknowledgement, each said under its own field', async () => {
    const user = userEvent.setup()
    page()
    await user.click(send())
    expect(screen.getByText('Enter your email address.')).toBeInTheDocument()
    expect(screen.getByText('Enter a subject.')).toBeInTheDocument()
    expect(screen.getByText('Write your message.')).toBeInTheDocument()
    expect(screen.getByText('Tick this to send your message.')).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()
  })

  it('the order reference and topic are optional, but the acknowledgement is not', async () => {
    const user = userEvent.setup()
    page()
    await fill(user, false)
    await user.click(send())
    expect(screen.getByText('Tick this to send your message.')).toBeInTheDocument()
    expect(api.post).not.toHaveBeenCalled()

    api.post.mockResolvedValue({ ref: 'T-123', message: 'ignored' })
    await user.click(screen.getByRole('checkbox'))
    await user.click(send())
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1))
    expect(api.post).toHaveBeenCalledWith('/api/v1/support/tickets', expect.objectContaining({
      email: 'ada@example.test', orderRef: null, category: null, confirmedNoCredentials: true,
    }))
  })
})

describe('sending', () => {
  it('sends once however often Send is pressed, and says it is sending', async () => {
    const user = userEvent.setup()
    let answer: (value: unknown) => void = () => {}
    api.post.mockReturnValue(new Promise((resolve) => { answer = resolve }))
    page()
    await fill(user)
    const button = send()
    act(() => {
      fireEvent.submit(button.closest('form')!)
      fireEvent.submit(button.closest('form')!)
      fireEvent.click(button)
    })
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(send()).toHaveTextContent('Sending…')
    expect(send()).toBeDisabled()

    await act(async () => answer({ ref: 'T-123', message: 'ignored' }))
    expect(api.post).toHaveBeenCalledTimes(1)
  })

  it('on success: the reference, in the visitor\'s language, and a fresh form', async () => {
    window.localStorage.setItem('gfs.language', 'es')
    const user = userEvent.setup()
    api.post.mockResolvedValue({ ref: 'T-123', message: 'Thanks — we have your message.' })
    page()
    await user.type(screen.getByLabelText(/Tu correo/), 'ada@example.test')
    await user.type(screen.getByLabelText(/^Asunto/), 'Monedas')
    await user.type(screen.getByLabelText(/Qué está pasando/), 'Nada todavía.')
    await user.click(screen.getByRole('checkbox'))
    await user.click(screen.getByRole('button', { name: /Enviar/ }))
    expect(await screen.findByText(/Tu referencia es T-123/)).toBeInTheDocument()
    expect(screen.getByRole('checkbox')).not.toBeChecked()
    expect(screen.getByLabelText(/^Asunto/)).toHaveValue('')
  })

  it('on failure: says it was not sent, keeps what they wrote, and Send works again', async () => {
    const user = userEvent.setup()
    api.post.mockRejectedValueOnce(new ApiError(500, { error: 'internal', message: 'Something broke.' }))
    page()
    await fill(user)
    await user.click(send())
    expect(await screen.findByText(/Your message was not sent/)).toBeInTheDocument()
    expect(screen.getByLabelText(/^Subject/)).toHaveValue('Coins not arrived')
    expect(send()).not.toBeDisabled()

    api.post.mockResolvedValueOnce({ ref: 'T-9', message: 'x' })
    await user.click(send())
    expect(await screen.findByText(/Your reference is T-9/)).toBeInTheDocument()
  })

  it('when sending too often, says to wait', async () => {
    const user = userEvent.setup()
    api.post.mockRejectedValue(new ApiError(429, { error: 'rate_limited', message: 'Too many requests.' }))
    page()
    await fill(user)
    await user.click(send())
    expect(await screen.findByText(/Please wait a minute/)).toBeInTheDocument()
  })
})
