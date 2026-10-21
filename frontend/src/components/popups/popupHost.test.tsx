import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PublicPopup } from '../../lib/popups'

/*
 * The popup host as a visitor meets it: one at a time, the modal first, how often, when,
 * never on the payment step, and never in the way of a checkout control.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), baseUrl: '' }))
vi.mock('../../lib/api', async (original) => ({ ...(await original<typeof import('../../lib/api')>()), api }))
const consent = vi.hoisted(() => ({ ok: true }))
vi.mock('../../lib/consent', async (original) => ({
  ...(await original<typeof import('../../lib/consent')>()),
  mayStorePreferences: () => consent.ok,
}))

const { PopupHost, resetPopupHostForTests, cardPlacement, nextPopup } = await import('./PopupHost')
const { firePopupStep, alreadySeen, rememberSeen, resetStepsForTests } = await import('../../lib/popups')
const { useNoPopups, resetPopupContextForTests } = await import('../../state/popupContext')

function popup(over: Partial<PublicPopup> & { id: string }): PublicPopup {
  return {
    version: 1, type: 'CENTER_MODAL', desktopImage: { url: '/api/v1/popups/images/1/abc.png', width: 800, height: 400 },
    mobileImage: null, copy: { en: { headline: `Offer ${over.id}`, body: 'Body', referral: null, trust: 'Trusted' } },
    buttons: [{ label: { en: 'Buy coins' }, destination: '/order' }], targetPages: ['HOME'], targetPaths: [],
    triggerType: 'PAGE_LOAD', triggerValue: null, triggerEvent: null, frequency: 'SESSION', frequencyDays: null,
    priority: 0, ...over,
  }
}

function serve(list: PublicPopup[]) {
  api.get.mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/popups')) return list
    throw new Error(path)
  })
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function PaymentStep() {
  useNoPopups()
  return <p>Pay here</p>
}

function at(path: string, page: React.ReactNode = null) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="*" element={<>{page}<Where /><PopupHost /></>} />
      </Routes>
    </MemoryRouter>,
  )
}

const modal = () => screen.queryByTestId('popup-modal')
const card = () => screen.queryByTestId('popup-card')

beforeEach(() => {
  resetPopupHostForTests()
  resetStepsForTests()
  window.history.pushState({}, '', '/')
  resetPopupContextForTests()
  window.localStorage.clear()
  window.sessionStorage.clear()
  consent.ok = true
  api.post.mockResolvedValue(undefined)
})

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

describe('one popup at a time', () => {
  it('a modal and a card both ready: the modal first, the card only once the modal is closed', async () => {
    serve([popup({ id: 'pop_card', type: 'BOTTOM_RIGHT', priority: 900 }), popup({ id: 'pop_modal', priority: 1 })])
    at('/')
    expect(await screen.findByTestId('popup-modal')).toBeInTheDocument()
    expect(card()).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(await screen.findByTestId('popup-card')).toBeInTheDocument()
    expect(modal()).toBeNull()
  })

  it('never two on screen, and no second one on a page view while the first is open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    serve([popup({ id: 'pop_a', type: 'BOTTOM_RIGHT' }), popup({ id: 'pop_b', triggerType: 'DELAY', triggerValue: 3 })])
    at('/')
    await screen.findByTestId('popup-card')
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect(document.querySelectorAll('[data-testid^="popup-"][role="dialog"], [data-testid="popup-modal"]')).toHaveLength(1)
    expect(modal()).toBeNull()
    // Closed by the visitor: the next may follow.
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(await screen.findByTestId('popup-modal')).toBeInTheDocument()
    expect(card()).toBeNull()
  })

  it('higher priority first among the same kind', () => {
    expect(nextPopup([popup({ id: 'low', priority: 1 }), popup({ id: 'high', priority: 50 })])?.id).toBe('high')
    expect(nextPopup([popup({ id: 'card', type: 'BOTTOM_RIGHT', priority: 999 }), popup({ id: 'm', priority: 0 })])?.id).toBe('m')
  })

  it('counts a view when shown, a close when closed, and a click when a button is used', async () => {
    serve([popup({ id: 'pop_count' })])
    at('/')
    await screen.findByTestId('popup-modal')
    expect(api.post).toHaveBeenCalledWith('/api/v1/popups/pop_count/events', { event: 'VIEW' })
    fireEvent.click(screen.getByRole('button', { name: 'Buy coins' }))
    expect(api.post).toHaveBeenCalledWith('/api/v1/popups/pop_count/events', { event: 'CLICK' })
    expect(screen.getByTestId('where')).toHaveTextContent('/order')
  })
})

describe('frequency', () => {
  it('once per session: shown, then not again in this session; an edited version shows again', async () => {
    serve([popup({ id: 'pop_s' })])
    const first = at('/')
    await screen.findByTestId('popup-modal')
    first.unmount()
    resetPopupHostForTests()
    at('/')
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2))
    expect(modal()).toBeNull()

    expect(alreadySeen(popup({ id: 'pop_s', version: 2 }))).toBe(false)
    expect(window.localStorage.length).toBe(0)
  })

  it('every N days: remembered in localStorage until the days have passed', () => {
    const p = popup({ id: 'pop_d', frequency: 'DAYS', frequencyDays: 3 })
    const now = Date.parse('2026-11-03T12:00:00Z')
    rememberSeen(p, now)
    expect(window.localStorage.getItem('gfs.popup.pop_d.v1')).toBe(String(now))
    expect(alreadySeen(p, now + 2 * 86_400_000)).toBe(true)
    expect(alreadySeen(p, now + 3 * 86_400_000 + 1)).toBe(false)
  })

  it('every N days without consent to storing preferences: only for the session, nothing in localStorage', () => {
    consent.ok = false
    const p = popup({ id: 'pop_nc', frequency: 'DAYS', frequencyDays: 30 })
    rememberSeen(p)
    expect(window.localStorage.length).toBe(0)
    expect(alreadySeen(p)).toBe(true)
  })

  it('every visit: always shown, nothing stored', async () => {
    const p = popup({ id: 'pop_e', frequency: 'EVERY_VISIT' })
    rememberSeen(p)
    expect(alreadySeen(p)).toBe(false)
    expect(window.localStorage.length + window.sessionStorage.length).toBe(0)
    expect(document.cookie).toBe('')
  })
})

describe('triggers', () => {
  it('after N seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    serve([popup({ id: 'pop_t', triggerType: 'DELAY', triggerValue: 4 })])
    at('/')
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    await act(async () => { vi.advanceTimersByTime(3000) })
    expect(modal()).toBeNull()
    await act(async () => { vi.advanceTimersByTime(1500) })
    expect(modal()).not.toBeNull()
  })

  it('at a page step, even one fired just before the list arrived', async () => {
    let answer: (v: PublicPopup[]) => void = () => {}
    api.get.mockImplementation(() => new Promise((r) => { answer = r }))
    // The real router keeps window.location in step; the in-memory one used here does not.
    window.history.pushState({}, '', '/order')
    at('/order')
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    act(() => firePopupStep('coins:continue'))
    await act(async () => answer([popup({ id: 'pop_step', targetPages: ['COINS'], triggerType: 'STEP', triggerEvent: 'coins:continue' })]))
    expect(await screen.findByTestId('popup-modal')).toBeInTheDocument()
  })

  it('after scrolling part of the page', async () => {
    serve([popup({ id: 'pop_sc', triggerType: 'SCROLL', triggerValue: 50 })])
    Object.defineProperty(document.documentElement, 'scrollHeight', { configurable: true, value: 3000 })
    at('/')
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    act(() => { window.scrollY = 500; window.dispatchEvent(new Event('scroll')) })
    expect(modal()).toBeNull()
    act(() => { window.scrollY = 1200; window.dispatchEvent(new Event('scroll')) })
    expect(await screen.findByTestId('popup-modal')).toBeInTheDocument()
    window.scrollY = 0
  })

  it('asks for the page it is on', async () => {
    serve([])
    at('/boosting/checkout')
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/v1/popups?path=%2Fboosting%2Fcheckout&page=CHAMPS_CHECKOUT'))
  })

  it('asks nothing at all on an order support page or sign-in', async () => {
    serve([popup({ id: 'pop_x', targetPaths: ['/login'] })])
    at('/orders/GFS-26-ABC/support')
    at('/login')
    await new Promise((r) => setTimeout(r, 20))
    expect(api.get).not.toHaveBeenCalled()
  })
})

describe('never on the payment step', () => {
  it('nothing shows while a payment step is on the page', async () => {
    serve([popup({ id: 'pop_pay', targetPages: ['COINS'] })])
    at('/order', <PaymentStep />)
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    expect(modal()).toBeNull()
  })
})

describe('the centred modal', () => {
  it('is a dialog: X closes, Esc closes, focus starts inside and stays inside', async () => {
    const user = userEvent.setup()
    serve([popup({ id: 'pop_k', buttons: [{ label: { en: 'One' }, destination: '/order' }, { label: { en: 'Two' }, destination: '/rewards' }] })])
    at('/')
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: 'One' })).toHaveFocus()
    await user.tab()
    await user.tab()
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(modal()).toBeNull()
    expect(api.post).toHaveBeenCalledWith('/api/v1/popups/pop_k/events', { event: 'CLOSE' })
  })

  it('uses the mobile image on a phone-width screen, and never wider than the screen', async () => {
    serve([popup({ id: 'pop_m', mobileImage: { url: '/api/v1/popups/images/2/def.webp', width: 390, height: 600 } })])
    at('/')
    await screen.findByTestId('popup-modal')
    expect(document.querySelector('source')).toHaveAttribute('media', '(max-width: 640px)')
    expect(document.querySelector('source')).toHaveAttribute('srcset', '/api/v1/popups/images/2/def.webp')
    expect(screen.getByTestId('popup-modal').className).toMatch(/w-full/)
    expect(screen.getByTestId('popup-modal').className).toMatch(/overflow-x-hidden/)
  })

  it('in the visitor\'s language, falling back to English line by line', async () => {
    window.localStorage.setItem('gfs.language', 'es')
    serve([popup({ id: 'pop_es', copy: { en: { headline: 'Hello', body: 'English body' }, es: { headline: 'Hola' } } })])
    const { I18nProvider } = await import('../../i18n')
    render(<I18nProvider><MemoryRouter initialEntries={['/']}><PopupHost /></MemoryRouter></I18nProvider>)
    expect(await screen.findByText('Hola')).toBeInTheDocument()
    expect(screen.getByText('English body')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cerrar' })).toBeInTheDocument()
  })
})

describe('the bottom-right card and checkout controls', () => {
  function rect(top: number, left: number, width: number, height: number): DOMRect {
    return { top, left, width, height, bottom: top + height, right: left + width, x: left, y: top, toJSON: () => ({}) } as DOMRect
  }

  function Checkout({ onPay }: { onPay: () => void }) {
    return (
      <div>
        <button type="button" data-popup-avoid="" data-testid="pay" onClick={onPay}>Pay</button>
        <div data-popup-above="" data-testid="chat" />
      </div>
    )
  }

  it('sits above the chat button', () => {
    Object.assign(window, { innerWidth: 1280, innerHeight: 800 })
    const chat = document.createElement('div')
    chat.setAttribute('data-popup-above', '')
    document.body.appendChild(chat)
    vi.spyOn(chat, 'getBoundingClientRect').mockReturnValue(rect(724, 1200, 60, 56))
    expect(cardPlacement(340, 160)).toBe(800 - 724 + 12)
    chat.remove()
  })

  it('is hidden while it would cover the Pay button, which stays clickable; shown once clear', async () => {
    Object.assign(window, { innerWidth: 1280, innerHeight: 800 })
    const onPay = vi.fn()
    serve([popup({ id: 'pop_c', type: 'BOTTOM_RIGHT', targetPages: ['COINS'] })])
    const real = Element.prototype.getBoundingClientRect
    let payRect = rect(640, 960, 300, 52)
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
      if (this.getAttribute('data-testid') === 'pay') return payRect
      return real.call(this)
    })
    at('/order', <Checkout onPay={onPay} />)
    const shown = await screen.findByTestId('popup-card')
    await waitFor(() => expect(shown).toHaveAttribute('aria-hidden', 'true'))
    expect(shown.className).toMatch(/invisible/)
    expect(shown.className).toMatch(/pointer-events-none/)
    await userEvent.setup().click(screen.getByTestId('pay'))
    expect(onPay).toHaveBeenCalledTimes(1)

    // The Pay button scrolled up out of the corner: the card shows, and Pay still works.
    payRect = rect(200, 960, 300, 52)
    act(() => { window.dispatchEvent(new Event('resize')) })
    await waitFor(() => expect(screen.getByTestId('popup-card')).not.toHaveAttribute('aria-hidden'))
    await userEvent.setup().click(screen.getByTestId('pay'))
    expect(onPay).toHaveBeenCalledTimes(2)
  })

  it('on a phone with a sticky checkout bar: above the bar, or not shown when there is no room', () => {
    Object.assign(window, { innerWidth: 390, innerHeight: 700 })
    const bar = document.createElement('div')
    bar.setAttribute('data-popup-sticky-bar', '')
    document.body.appendChild(bar)
    const spy = vi.spyOn(bar, 'getBoundingClientRect').mockReturnValue(rect(620, 0, 390, 80))
    expect(cardPlacement(358, 140)).toBe(700 - 620 + 12)
    spy.mockReturnValue(rect(200, 0, 390, 500))
    expect(cardPlacement(358, 140)).toBeNull()
    bar.remove()
  })
})
