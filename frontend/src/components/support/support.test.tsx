import { StrictMode } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const reloadPage = vi.hoisted(() => vi.fn())
/** The ids the API served at start; set per test. */
const chat = vi.hoisted(() => ({ ids: null as null | { propertyId: string | null; embedWidgetId: string | null;
  floatingWidgetId: string | null } }))
vi.mock('../../lib/chatConfig', async (original) => ({
  ...(await original<typeof import('../../lib/chatConfig')>()),
  useChatIds: () => chat.ids,
}))
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

function configured() {
  chat.ids = { propertyId: 'prop123', embedWidgetId: 'widget1', floatingWidgetId: null }
}

beforeEach(() => {
  reloadPage.mockReset()
})
afterEach(() => {
  resetTawkForTests()
})

describe('the card on an order', () => {
  it.each([
    ['BOOST_CHAMPS', 'Connect with GFS',
      'Need help with your order? Create a ticket and our GFS Support team will connect you with your booster '
      + 'shortly.', 'Connect to booster', '/orders/GFS-26-70C4DPWH/support'],
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

  it('a guest order goes to the same live chat, with its reference filled in -- never a Discord link', () => {
    inRouter(<GuestSupportCard reference="GFS-26-GUEST001" />)
    expect(screen.getByText(/Chat with our team about order GFS-26-GUEST001/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Chat with us' })).toHaveAttribute('href', '/support/chat?ref=GFS-26-GUEST001')
    expect(document.body.innerHTML).not.toMatch(/discord/i)
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
    chat.ids = { propertyId: null, embedWidgetId: null, floatingWidgetId: null }
    inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)
    expect(screen.getByText('Live chat is unavailable right now.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to the Support page' })).toHaveAttribute('href', '/support')
    expect(screen.queryByTestId('chat-host')).toBeNull()
    expect(tawkScripts()).toHaveLength(0)
  })

  it('loads and opens as the page opens, under the notice saying who provides it -- no Start chat step', () => {
    configured()
    inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)

    const notice = screen.getByTestId('chat-notice')
    expect(notice).toHaveTextContent('Live chat is provided by tawk.to.')
    expect(screen.queryByRole('button')).toBeNull()
    expect(tawkScripts()).toHaveLength(1)
    const host = screen.getByTestId('chat-host')
    expect(host.querySelector('#tawk_prop123')).not.toBeNull()
    expect(host).not.toHaveClass('hidden')
    // The notice is above the chat.
    expect(notice.compareDocumentPosition(host) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('React\'s development double mount still gives one script and one chat, in place', () => {
    configured()
    render(<StrictMode><MemoryRouter><SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} /></MemoryRouter></StrictMode>)
    expect(tawkScripts()).toHaveLength(1)
    expect(document.querySelectorAll('#tawk_prop123')).toHaveLength(1)
    expect(screen.getByTestId('chat-host').querySelector('#tawk_prop123')).not.toBeNull()
    expect(reloadPage).not.toHaveBeenCalled()
  })

  it('leaving takes the chat out of view and does not end the conversation', () => {
    configured()
    const first = inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)
    const enders = { endChat: vi.fn(), shutdown: vi.fn(), logout: vi.fn(), hideWidget: vi.fn() }
    Object.assign(window.Tawk_API!, enders, { setAttributes: vi.fn(), addEvent: vi.fn() })
    window.Tawk_API?.onLoad?.()

    first.unmount()

    expect(document.getElementById('tawk_prop123')).toBeNull()
    for (const call of Object.values(enders)) expect(call).not.toHaveBeenCalled()
  })

  it('coming back to the same order, once the chat is drawn, reloads the page so tawk.to brings the conversation '
    + 'back', () => {
    configured()
    const first = inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)
    window.Tawk_API?.onLoad?.()
    first.unmount()

    inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)

    expect(reloadPage).toHaveBeenCalledTimes(1)
    expect(tawkScripts()).toHaveLength(1)
  })

  it('another order\'s support page reloads the page rather than carry the first order\'s details', () => {
    configured()
    const first = inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)
    first.unmount()

    inRouter(<SupportChat reference="GFS-26-OTHER001" chat={{ ...CHAT, email: 'x@example.test' }} />)

    expect(reloadPage).toHaveBeenCalledTimes(1)
    expect(tawkScripts()).toHaveLength(1)
    expect(window.Tawk_API?.visitor?.email).toBe('rahul@example.test')
  })

  it('after that reload, the second order\'s chat has the same verified visitor and its own order details', () => {
    configured()
    const verified = { ...CHAT, hash: 'f'.repeat(64) }
    const first = inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={verified} />)
    first.unmount()
    resetTawkForTests() // the fresh page load

    const second = { ...verified, attributes: { ...verified.attributes, 'order-id': 'GFS-26-OTHER001',
      service: '500K coins', 'order-status': 'Processing', 'coin-amount': '500K' } }
    inRouter(<SupportChat reference="GFS-26-OTHER001" chat={second} />)
    const setAttributes = vi.fn()
    const addEvent = vi.fn()
    Object.assign(window.Tawk_API!, { setAttributes, addEvent })
    window.Tawk_API?.onLoad?.()

    expect(window.Tawk_API?.visitor).toEqual({ name: 'Rahul', email: 'rahul@example.test', hash: 'f'.repeat(64) })
    expect(setAttributes.mock.calls[0]?.[0]).toMatchObject({
      'order-id': 'GFS-26-OTHER001', service: '500K coins', 'order-status': 'Processing', 'coin-amount': '500K' })
    expect(addEvent).toHaveBeenCalledWith('order-support-opened', { 'order-id': 'GFS-26-OTHER001' }, expect.any(Function))
  })

  it('a chat that cannot load says so, with the way to the Support page, and leaves no empty box', () => {
    configured()
    inRouter(<SupportChat reference="GFS-26-70C4DPWH" chat={CHAT} />)
    fireEvent(tawkScripts()[0]!, new Event('error'))
    expect(screen.getByText('The chat could not be loaded.')).toBeInTheDocument()
    expect(screen.getByTestId('chat-host')).toHaveClass('hidden')
    expect(screen.queryByTestId('chat-notice')).toBeNull()
  })
})
