import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const reloadPage = vi.hoisted(() => vi.fn())
vi.mock('../../lib/tawk', async (original) => ({
  ...(await original<typeof import('../../lib/tawk')>()),
  reloadPage,
}))

const { GuestSupportCard, SupportCard, isSupportPage, supportModeFor, supportPath } = await import('./SupportCard')
const { SupportChat } = await import('./SupportChat')
const { resetTawkForTests } = await import('../../lib/tawk')

const CHAT = {
  name: 'Rahul', email: 'rahul@example.test', hash: null,
  attributes: { 'order-id': 'GFS-26-70C4DPWH', service: 'Champs Boosting', platform: 'PC', 'order-status': 'Queued',
    'coin-amount': 'n/a', 'session-id': 'n/a', coach: 'n/a' },
}

const tawkScripts = () => [...document.querySelectorAll('script')].filter((s) => s.src.includes('tawk.to'))

function inRouter(node: React.ReactNode) {
  return render(<MemoryRouter>{node}</MemoryRouter>)
}

beforeEach(() => {
  reloadPage.mockReset()
})
afterEach(() => {
  resetTawkForTests()
  vi.unstubAllEnvs()
})

describe('the card on an order', () => {
  it.each([
    ['BOOST_CHAMPS', 'Connect with GFS',
      'Need help with your order? Create a ticket and our GFS Support team will connect with you shortly and guide '
      + 'you through the next steps.', 'Create Order Ticket', '/orders/GFS-26-70C4DPWH/support'],
    ['TRADING_SERVICE', 'Connect with GFS',
      'Having an issue with your coin order? Create a support request and our GFS Support team will check the order '
      + 'and help you resolve it.', 'Create Order Support', '/orders/GFS-26-70C4DPWH/support'],
    ['COACHING', 'Connect with Your Coach',
      'Need help with your coaching order or session? Connect with your coach and continue the conversation directly '
      + 'from your GFS account.', 'Connect with Coach', '/coaching/GFS-26-70C4DPWH/support'],
  ])('%s: the client\'s title, text and button, to a page on this site', (sku, title, body, cta, path) => {
    inRouter(<SupportCard reference="GFS-26-70C4DPWH" sku={sku} />)
    expect(screen.getByText(title)).toBeInTheDocument()
    expect(screen.getByText(body)).toBeInTheDocument()
    const link = screen.getByRole('link', { name: cta })
    expect(link).toHaveAttribute('href', path)
    expect(link.getAttribute('target')).toBeNull()
  })

  it('a boosting tier other than Champs is a service order too', () => {
    expect(supportModeFor('BOOST_RIVALS')).toBe('BOOSTING')
    expect(supportPath('COINS', 'GFS 26/X')).toBe('/orders/GFS%2026%2FX/support')
  })

  it('a guest order is pointed to a support ticket, not the chat', () => {
    inRouter(<GuestSupportCard reference="GFS-26-GUEST001" />)
    expect(screen.getByText(/mention order GFS-26-GUEST001/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open a support ticket' })).toHaveAttribute('href', '/support')
  })

  it('knows the two support addresses, and nothing else, as support pages', () => {
    expect(isSupportPage('/orders/GFS-26-70C4DPWH/support')).toBe(true)
    expect(isSupportPage('/coaching/GFS-26-70C4DPWH/support/')).toBe(true)
    expect(isSupportPage('/orders')).toBe(false)
    expect(isSupportPage('/support')).toBe(false)
    expect(isSupportPage('/coaching/book')).toBe(false)
    expect(isSupportPage('/orders/a/b/support')).toBe(false)
  })
})

describe('the chat block', () => {
  it('without the ids: says live chat is unavailable and points to the Support page, never a broken box', () => {
    vi.stubEnv('VITE_TAWK_PROPERTY_ID', '')
    vi.stubEnv('VITE_TAWK_EMBED_WIDGET_ID', '')
    inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)
    expect(screen.getByText('Live chat is unavailable right now.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to the Support page' })).toHaveAttribute('href', '/support')
    expect(screen.queryByRole('button', { name: 'Start chat' })).toBeNull()
    expect(tawkScripts()).toHaveLength(0)
  })

  it('loads nothing from tawk.to until the customer, told who provides it, presses Start chat', () => {
    vi.stubEnv('VITE_TAWK_PROPERTY_ID', 'prop123')
    vi.stubEnv('VITE_TAWK_EMBED_WIDGET_ID', 'widget1')
    inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)

    expect(screen.getByText(/Live chat is provided by tawk.to/)).toBeInTheDocument()
    expect(tawkScripts()).toHaveLength(0)
    expect(window.Tawk_API).toBeUndefined()

    fireEvent.click(screen.getByRole('button', { name: 'Start chat' }))

    expect(tawkScripts()).toHaveLength(1)
    expect(screen.getByTestId('chat-host').querySelector('#tawk_prop123')).not.toBeNull()
    expect(screen.queryByTestId('chat-consent')).toBeNull()
  })

  it('leaving takes the chat off the page; coming back to the same order puts it back without a second script',
    () => {
      vi.stubEnv('VITE_TAWK_PROPERTY_ID', 'prop123')
      vi.stubEnv('VITE_TAWK_EMBED_WIDGET_ID', 'widget1')
      const first = inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)
      fireEvent.click(screen.getByRole('button', { name: 'Start chat' }))
      first.unmount()
      expect(document.getElementById('tawk_prop123')).toBeNull()

      inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)
      expect(screen.getByTestId('chat-host').querySelector('#tawk_prop123')).not.toBeNull()
      expect(tawkScripts()).toHaveLength(1)
      expect(reloadPage).not.toHaveBeenCalled()
    })

  it('another order\'s support page reloads the page rather than carry the first order\'s details', () => {
    vi.stubEnv('VITE_TAWK_PROPERTY_ID', 'prop123')
    vi.stubEnv('VITE_TAWK_EMBED_WIDGET_ID', 'widget1')
    const first = inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)
    fireEvent.click(screen.getByRole('button', { name: 'Start chat' }))
    first.unmount()

    inRouter(<SupportChat reference="GFS-26-OTHER001" chat={{ ...CHAT, email: 'x@example.test' }} />)

    expect(reloadPage).toHaveBeenCalledTimes(1)
    expect(tawkScripts()).toHaveLength(1)
    expect(window.Tawk_API?.visitor?.email).toBe('rahul@example.test')
  })

  it('a chat that cannot load says so, with the way to the Support page', () => {
    vi.stubEnv('VITE_TAWK_PROPERTY_ID', 'prop123')
    vi.stubEnv('VITE_TAWK_EMBED_WIDGET_ID', 'widget1')
    inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)
    fireEvent.click(screen.getByRole('button', { name: 'Start chat' }))
    fireEvent(tawkScripts()[0]!, new Event('error'))
    expect(screen.getByText('The chat could not be loaded.')).toBeInTheDocument()
  })
})
