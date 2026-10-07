import { describe, expect, it } from 'vitest'
import { looksLikeEmail } from './validation'

describe('looksLikeEmail', () => {
  it('accepts an ordinary address, with spaces around it', () => {
    expect(looksLikeEmail('player@example.com')).toBe(true)
    expect(looksLikeEmail('  first.last+fc@mail.co.uk ')).toBe(true)
  })

  it('refuses what is not one', () => {
    for (const bad of ['', '   ', 'player', 'player@', '@example.com', 'player@gmail', 'pla yer@example.com']) {
      expect(looksLikeEmail(bad)).toBe(false)
    }
  })
})
