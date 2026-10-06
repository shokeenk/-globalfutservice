import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  TAWK_ATTRIBUTES, allowedAttributes, detachTawk, resetTawkForTests, startTawk, tawkConfig, tawkStartedFor,
} from './tawk'
import type { TawkChat } from './tawk'

const CONFIG = { propertyId: '64aa11bb22cc33dd44ee55ff', widgetId: '1hkembed01' }

function chat(patch: Partial<TawkChat> = {}): TawkChat {
  return {
    name: 'Rahul',
    email: 'rahul@example.test',
    hash: 'a'.repeat(64),
    attributes: {
      'order-id': 'GFS-26-70C4DPWH', service: 'Champs Boosting', platform: 'PlayStation',
      'order-status': 'Queued', 'coin-amount': 'n/a', 'session-id': 'n/a', coach: 'n/a',
    },
    ...patch,
  }
}

const scripts = () => [...document.querySelectorAll('script')].filter((s) => s.src.includes('tawk.to'))

afterEach(() => {
  resetTawkForTests()
  document.body.innerHTML = ''
})

describe('tawk.to configuration', () => {
  it('needs both ids, as plain tokens, or there is no chat at all', () => {
    expect(tawkConfig({ VITE_TAWK_PROPERTY_ID: 'abc123', VITE_TAWK_EMBED_WIDGET_ID: 'w1' }))
      .toEqual({ propertyId: 'abc123', widgetId: 'w1' })
    expect(tawkConfig({ VITE_TAWK_PROPERTY_ID: 'abc123' })).toBeNull()
    expect(tawkConfig({ VITE_TAWK_PROPERTY_ID: '', VITE_TAWK_EMBED_WIDGET_ID: 'w1' })).toBeNull()
    // Never something that could change the script's address.
    expect(tawkConfig({ VITE_TAWK_PROPERTY_ID: 'abc/../x', VITE_TAWK_EMBED_WIDGET_ID: 'w1' })).toBeNull()
    expect(tawkConfig({ VITE_TAWK_PROPERTY_ID: 'abc', VITE_TAWK_EMBED_WIDGET_ID: 'w1?x=1' })).toBeNull()
  })
})

describe('starting the chat', () => {
  it('adds the Embed Widget script once, inside the page, with the visitor set before it loads', () => {
    const host = document.createElement('div')
    document.body.appendChild(host)

    expect(startTawk(CONFIG, 'GFS-26-70C4DPWH', chat(), host, vi.fn())).toBe('started')

    expect(scripts()).toHaveLength(1)
    expect(scripts()[0]?.src).toBe(`https://embed.tawk.to/${CONFIG.propertyId}/${CONFIG.widgetId}`)
    expect(window.Tawk_API?.embedded).toBe(`tawk_${CONFIG.propertyId}`)
    expect(host.querySelector(`#tawk_${CONFIG.propertyId}`)).not.toBeNull()
    expect(window.Tawk_API?.visitor).toEqual({ name: 'Rahul', email: 'rahul@example.test', hash: 'a'.repeat(64) })
    expect(tawkStartedFor()).toBe('GFS-26-70C4DPWH')
  })

  it('tells tawk.to the allowlisted fields once it has loaded, and marks the chat with the order', () => {
    const host = document.createElement('div')
    startTawk(CONFIG, 'GFS-26-70C4DPWH', chat(), host, vi.fn())
    const setAttributes = vi.fn()
    const addEvent = vi.fn()
    Object.assign(window.Tawk_API!, { setAttributes, addEvent })

    window.Tawk_API?.onLoad?.()

    expect(setAttributes).toHaveBeenCalledTimes(1)
    expect(Object.keys(setAttributes.mock.calls[0]?.[0] ?? {})).toEqual([...TAWK_ATTRIBUTES])
    expect(addEvent).toHaveBeenCalledWith('order-support-opened', { 'order-id': 'GFS-26-70C4DPWH' }, expect.any(Function))
  })

  it('a call tawk.to refuses is reported in the console, by its error code alone', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    startTawk(CONFIG, 'GFS-26-70C4DPWH', chat(), document.createElement('div'), vi.fn())
    const setAttributes = vi.fn()
    const addEvent = vi.fn()
    Object.assign(window.Tawk_API!, { setAttributes, addEvent })
    window.Tawk_API?.onLoad?.()

    const answer = setAttributes.mock.calls[0]?.[1] as (error?: unknown) => void
    answer(undefined)
    expect(warn).not.toHaveBeenCalled()

    answer('ACCESS_ERROR')
    expect(warn).toHaveBeenCalledWith('Live chat: tawk.to did not accept the order details:', 'ACCESS_ERROR')
    // Nothing that was sent goes into the log.
    expect(JSON.stringify(warn.mock.calls)).not.toMatch(/rahul|GFS-26-70C4DPWH|Champs/i)

    ;(addEvent.mock.calls[0]?.[2] as (error?: unknown) => void)(new Error('SERVER_ERROR'))
    expect(warn).toHaveBeenLastCalledWith('Live chat: tawk.to did not accept the order event:', 'SERVER_ERROR')
    warn.mockRestore()
  })

  it('the same order again, before tawk.to has drawn anything, puts the same empty element back', () => {
    const first = document.createElement('div')
    startTawk(CONFIG, 'GFS-26-70C4DPWH', chat(), first, vi.fn())
    detachTawk()
    expect(document.getElementById(`tawk_${CONFIG.propertyId}`)).toBeNull()

    const second = document.createElement('div')
    document.body.appendChild(second)
    expect(startTawk(CONFIG, 'GFS-26-70C4DPWH', chat(), second, vi.fn())).toBe('attached')
    expect(second.querySelector(`#tawk_${CONFIG.propertyId}`)).not.toBeNull()
    expect(document.querySelectorAll(`#tawk_${CONFIG.propertyId}`)).toHaveLength(1)
    expect(scripts()).toHaveLength(1)
  })

  /*
   * tawk.to's chat window is an iframe it fills once, on its first load. Taken out of the
   * page and put back, the iframe reloads empty and is not filled again -- so a drawn chat
   * is never put back; the page reloads and tawk.to draws it afresh, conversation included.
   */
  it.each([
    ['its load callback has run', () => window.Tawk_API?.onLoad?.()],
    ['its frames are in the element', () => {
      document.getElementById(`tawk_${CONFIG.propertyId}`)?.appendChild(document.createElement('iframe'))
    }],
    ['its script failed to load', () => scripts()[0]?.dispatchEvent(new Event('error'))],
  ])('the same order again, once %s, asks for a fresh page load instead', (_, draw) => {
    const first = document.createElement('div')
    document.body.appendChild(first)
    startTawk(CONFIG, 'GFS-26-70C4DPWH', chat(), first, vi.fn())
    draw()
    detachTawk()

    const second = document.createElement('div')
    expect(startTawk(CONFIG, 'GFS-26-70C4DPWH', chat(), second, vi.fn())).toBe('reload')
    expect(second.childElementCount).toBe(0)
    expect(scripts()).toHaveLength(1)
  })

  it('never ends the conversation, disconnects or signs the visitor out -- not on leaving, not on coming back', () => {
    const enders = { endChat: vi.fn(), shutdown: vi.fn(), logout: vi.fn() }
    startTawk(CONFIG, 'GFS-26-70C4DPWH', chat(), document.createElement('div'), vi.fn())
    Object.assign(window.Tawk_API!, enders, { setAttributes: vi.fn(), addEvent: vi.fn() })
    window.Tawk_API?.onLoad?.()
    detachTawk()
    startTawk(CONFIG, 'GFS-26-70C4DPWH', chat(), document.createElement('div'), vi.fn())
    startTawk(CONFIG, 'GFS-26-OTHER001', chat(), document.createElement('div'), vi.fn())
    detachTawk()
    for (const call of Object.values(enders)) expect(call).not.toHaveBeenCalled()
  })

  it('another order in the same page load is refused, so the page reloads instead of mixing the two', () => {
    startTawk(CONFIG, 'GFS-26-70C4DPWH', chat(), document.createElement('div'), vi.fn())
    const before = { ...window.Tawk_API?.visitor }

    expect(startTawk(CONFIG, 'GFS-26-OTHER001', chat({ email: 'other@example.test' }), document.createElement('div'),
      vi.fn())).toBe('reload')

    expect(scripts()).toHaveLength(1)
    expect(window.Tawk_API?.visitor).toEqual(before)
    expect(tawkStartedFor()).toBe('GFS-26-70C4DPWH')
  })

  it('a script that cannot load is reported, not left as an empty box', () => {
    const onError = vi.fn()
    startTawk(CONFIG, 'GFS-26-70C4DPWH', chat(), document.createElement('div'), onError)
    scripts()[0]?.dispatchEvent(new Event('error'))
    expect(onError).toHaveBeenCalled()
  })
})

describe('what reaches tawk.to', () => {
  it('only the allowlisted fields, even if something else were ever sent', () => {
    const sneaky = {
      ...chat().attributes,
      password: 'Hunter2', 'backup-code': '11112222', 'ea-email': 'ea@example.test', upi: 'pay@bank',
      'card-number': '4111111111111111', paymentReference: 'pay_123',
    }
    const out = allowedAttributes(sneaky)
    expect(Object.keys(out)).toEqual([...TAWK_ATTRIBUTES])
    expect(JSON.stringify(out)).not.toMatch(/Hunter2|11112222|ea@example|pay@bank|4111|pay_123/)
  })

  it('and only name, email and hash as the visitor', () => {
    const host = document.createElement('div')
    const withExtras = { ...chat(), password: 'Hunter2', backupCodes: ['11112222'] } as unknown as TawkChat
    startTawk(CONFIG, 'GFS-26-70C4DPWH', withExtras, host, vi.fn())
    expect(Object.keys(window.Tawk_API?.visitor ?? {}).sort()).toEqual(['email', 'hash', 'name'])
    expect(JSON.stringify(window.Tawk_API)).not.toMatch(/Hunter2|11112222/)
  })

  it('never empty values, never over 255 characters', () => {
    expect(allowedAttributes({ 'order-id': '', service: 'x'.repeat(300) })).toEqual({ service: 'x'.repeat(255) })
  })
})
