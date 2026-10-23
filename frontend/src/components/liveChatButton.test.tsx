import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * The floating live chat button: which pages have it, what pressing it loads, and what the
 * chat is told -- an order's reference, never more.
 */

vi.mock('./AskWidget', () => ({ AskWidget: () => <div data-testid="ask-widget" /> }))
/** The ids the API served at start; set per test. */
const chat = vi.hoisted(() => ({ ids: null as null | { propertyId: string | null; embedWidgetId: string | null;
  floatingWidgetId: string | null } }))
vi.mock('../lib/chatConfig', async (original) => ({
  ...(await original<typeof import('../lib/chatConfig')>()),
  useChatIds: () => chat.ids,
}))
const reload = vi.hoisted(() => vi.fn())
vi.mock('../lib/tawk', async (original) => ({
  ...(await original<typeof import('../lib/tawk')>()),
  reloadPage: reload,
}))

const { CornerWidget } = await import('../App')
const { LiveChatButton, isLiveChatPage, useLiveChatOrder } = await import('./LiveChatButton')
const { SupportChat } = await import('./support/SupportChat')
const { resetTawkForTests } = await import('../lib/tawk')

const tawkScripts = () => [...document.querySelectorAll('script')].filter((s) => s.src.includes('tawk.to'))

function corner(pathname: string) {
  return render(<MemoryRouter><CornerWidget pathname={pathname} /></MemoryRouter>)
}

/** A page about an order, as Track and the checkouts are. */
function OrderPage({ reference }: { reference: string | null }) {
  useLiveChatOrder(reference)
  return <LiveChatButton />
}

/** tawk.to's API once its script has run, recording what the site asked of it. */
function tawkLoads() {
  const calls: string[] = []
  const attributes: Record<string, string>[] = []
  const api = window.Tawk_API!
  Object.assign(api, {
    setAttributes: (a: Record<string, string>, cb: () => void) => { attributes.push(a); cb() },
    showWidget: () => calls.push('show'),
    hideWidget: () => calls.push('hide'),
    maximize: () => calls.push('maximize'),
    minimize: () => calls.push('minimize'),
  })
  act(() => api.onLoad?.())
  return { calls, attributes }
}

beforeEach(() => {
  resetTawkForTests()
  window.sessionStorage.clear()
  chat.ids = { propertyId: 'prop123', floatingWidgetId: 'float456', embedWidgetId: 'embed789' }
})

afterEach(() => {
  vi.clearAllMocks()
  resetTawkForTests()
})

describe('which pages have the chat button', () => {
  const WITH = ['/support', '/contact', '/track', '/order', '/boosting/checkout', '/coaching/book', '/track/']
  const WITHOUT = ['/', '/help', '/boosting', '/coaching', '/rewards', '/account', '/login', '/about', '/terms',
    '/orders/GFS-26-ABCDEFGH/support', '/coaching/GFS-26-ABCDEFGH/support', '/support/chat',
    '/support/tickets/T-1', '/admin/orders', '/order/extra']

  it.each(WITH)('%s has it', (path) => {
    expect(isLiveChatPage(path)).toBe(true)
    corner(path)
    expect(screen.getByRole('button', { name: 'Live chat' })).toBeInTheDocument()
    expect(screen.queryByTestId('ask-widget')).not.toBeInTheDocument()
  })

  it.each(WITHOUT)('%s does not', (path) => {
    expect(isLiveChatPage(path)).toBe(false)
    corner(path)
    expect(screen.queryByRole('button', { name: 'Live chat' })).not.toBeInTheDocument()
  })

  it('an order support page, which has the chat in the page, gets no second button of any kind', () => {
    corner('/orders/GFS-26-ABCDEFGH/support')
    expect(screen.queryByRole('button', { name: 'Live chat' })).not.toBeInTheDocument()
    expect(screen.queryByTestId('ask-widget')).not.toBeInTheDocument()
  })

  it('without the floating widget id, the chat pages show no chat button, only what they had before', () => {
    chat.ids = { propertyId: 'prop123', floatingWidgetId: null, embedWidgetId: 'embed789' }
    corner('/support')
    expect(screen.queryByRole('button', { name: 'Live chat' })).not.toBeInTheDocument()
    expect(screen.getByTestId('ask-widget')).toBeInTheDocument()
  })

  it('nothing loads from tawk.to until the button is pressed', () => {
    corner('/track')
    expect(tawkScripts()).toHaveLength(0)
    expect(window.Tawk_API).toBeUndefined()
  })
})

describe('pressing it', () => {
  it('loads the floating widget and opens the chat over the page', () => {
    corner('/support')
    fireEvent.click(screen.getByRole('button', { name: 'Live chat' }))
    expect(tawkScripts().map((s) => s.src)).toEqual(['https://embed.tawk.to/prop123/float456'])
    // Floating, not embedded, and nobody named to tawk.to.
    expect(window.Tawk_API?.embedded).toBeUndefined()
    expect(window.Tawk_API?.visitor).toBeUndefined()

    const { calls, attributes } = tawkLoads()
    expect(calls).toEqual(['show', 'maximize'])
    // No order on the Support page: nothing is set.
    expect(attributes).toEqual([])

    // Minimised: tawk.to's own launcher is hidden again; the red button is the way back in.
    window.Tawk_API?.onChatMinimized?.()
    expect(calls).toContain('hide')

    fireEvent.click(screen.getByRole('button', { name: 'Live chat' }))
    expect(tawkScripts()).toHaveLength(1)
    expect(calls.slice(-2)).toEqual(['show', 'maximize'])
  })

  it('pressed twice while loading, it still loads once', () => {
    corner('/support')
    fireEvent.click(screen.getByRole('button', { name: 'Live chat' }))
    fireEvent.click(screen.getByRole('button', { name: 'Live chat' }))
    expect(tawkScripts()).toHaveLength(1)
  })

  it('says so when the chat cannot load', () => {
    corner('/support')
    fireEvent.click(screen.getByRole('button', { name: 'Live chat' }))
    act(() => { tawkScripts()[0]?.dispatchEvent(new Event('error')) })
    expect(screen.getByRole('alert')).toHaveTextContent('The live chat could not load')
  })

  it('leaving for a page without the button closes the window, and does not end the chat', () => {
    const { unmount } = corner('/support')
    fireEvent.click(screen.getByRole('button', { name: 'Live chat' }))
    const { calls } = tawkLoads()
    unmount()
    expect(calls.slice(-2)).toEqual(['minimize', 'hide'])
  })
})

describe('on an order page', () => {
  it('passes the order reference, and only that, as the order-id attribute', () => {
    render(<MemoryRouter><OrderPage reference="gfs-26-abcdefgh" /></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: 'Live chat' }))
    const { attributes } = tawkLoads()
    expect(attributes).toEqual([{ 'order-id': 'GFS-26-ABCDEFGH' }])
    expect(window.Tawk_API?.visitor).toBeUndefined()
  })

  it('a reference arriving after the chat opened is passed then', () => {
    const { rerender } = render(<MemoryRouter><OrderPage reference={null} /></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: 'Live chat' }))
    const { attributes } = tawkLoads()
    expect(attributes).toEqual([])
    rerender(<MemoryRouter><OrderPage reference="GFS-26-PLACED01" /></MemoryRouter>)
    expect(attributes).toEqual([{ 'order-id': 'GFS-26-PLACED01' }])
  })

  it('anything that is not a reference is not passed', () => {
    render(<MemoryRouter><OrderPage reference="buyer@example.test" /></MemoryRouter>)
    fireEvent.click(screen.getByRole('button', { name: 'Live chat' }))
    const { attributes } = tawkLoads()
    expect(attributes).toEqual([])
  })
})

describe('one widget per page load', () => {
  it('after the embedded chat, pressing the button reloads the page and opens the chat when it is back', () => {
    const host = render(<MemoryRouter><SupportChat reference="GFS-26-ABCDEFGH" chat={{ attributes: {} }} /></MemoryRouter>)
    expect(tawkScripts().map((s) => s.src)).toEqual(['https://embed.tawk.to/prop123/embed789'])
    host.unmount()

    corner('/support')
    fireEvent.click(screen.getByRole('button', { name: 'Live chat' }))
    expect(reload).toHaveBeenCalledTimes(1)
    expect(tawkScripts()).toHaveLength(1)
    expect(window.sessionStorage.getItem('gfs.liveChat.open')).toBe('1')
  })

  it('after the floating chat, the order support page reloads rather than load a second widget', () => {
    corner('/support')
    fireEvent.click(screen.getByRole('button', { name: 'Live chat' }))
    render(<MemoryRouter><SupportChat reference="GFS-26-ABCDEFGH" chat={{ attributes: {} }} /></MemoryRouter>)
    expect(reload).toHaveBeenCalledTimes(1)
    expect(tawkScripts()).toHaveLength(1)
  })

  it('back after that reload, the button opens the chat by itself', () => {
    window.sessionStorage.setItem('gfs.liveChat.open', '1')
    corner('/support')
    expect(tawkScripts().map((s) => s.src)).toEqual(['https://embed.tawk.to/prop123/float456'])
    expect(window.sessionStorage.getItem('gfs.liveChat.open')).toBeNull()
  })
})
