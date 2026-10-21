import { describe, expect, it } from 'vitest'
import { setConsent } from './consent'

describe('declining preference storage', () => {
  it('forgets which popups were seen, with the language and currency', () => {
    window.localStorage.setItem('gfs.popup.pop_a.v2', '1')
    window.localStorage.setItem('gfs.language', 'es')
    window.localStorage.setItem('unrelated', 'x')
    setConsent('declined')
    expect(window.localStorage.getItem('gfs.popup.pop_a.v2')).toBeNull()
    expect(window.localStorage.getItem('gfs.language')).toBeNull()
    expect(window.localStorage.getItem('unrelated')).toBe('x')
  })
})
