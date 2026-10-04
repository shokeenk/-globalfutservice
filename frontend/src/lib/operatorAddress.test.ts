import { describe, expect, it } from 'vitest'
import indexHtml from '../../index.html?raw'
import { BUSINESS } from '../content/business'

/*
 * The operator's street address is not published, at the owner's instruction (October
 * 2026). This fails if any part of it comes back in a page, a component, a translation or
 * the site's HTML. The backend's OperatorAddressTest covers the emails, the messages and
 * the public files.
 *
 * The parts are held as fingerprints, not text: a test that spelled the address out would
 * publish it again in a public repository. A fingerprint keeps it out of reading and
 * searching; it does not stop someone who already knows what to guess.
 */

/** Every source file, as text, keyed by its path. Tests are left out, this one included. */
const SOURCES: Record<string, string> = import.meta.glob<string>(
  ['/src/**/*.{ts,tsx}', '!/src/**/*.test.{ts,tsx}'],
  { query: '?raw', import: 'default', eager: true },
)

/** The removed address, part by part: house number, locality, city, state and postcode. */
const ADDRESS = new Set([8335211447407610, 426394199867781, 4085892071911414, 4595916220753159, 3313117660318515])

/**
 * cyrb53 (public domain): a small 53-bit string hash, over {@code text[from, to)}. The
 * backend test carries the same function, and both check it against the same value.
 */
function cyrb53(text: string, from = 0, to = text.length): number {
  let h1 = 0xdeadbeef
  let h2 = 0x41c6ce57
  for (let i = from; i < to; i++) {
    const ch = text.charCodeAt(i)
    h1 = Math.imul(h1 ^ ch, 2654435761)
    h2 = Math.imul(h2 ^ ch, 1597334677)
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507)
  h1 ^= Math.imul(h2 ^ (h2 >>> 13), 3266489909)
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507)
  h2 ^= Math.imul(h1 ^ (h1 >>> 13), 3266489909)
  return 4294967296 * (2097151 & h2) + (h1 >>> 0)
}

/**
 * Whether a line carries one of the fingerprinted parts, in any of the ways it could be
 * written: three to ten letters anywhere inside a word, initials spelled out with dots or
 * spaces, a six-digit number with or without a space or hyphen in the middle, or a
 * number/number pair, in any case and spacing.
 */
function carries(line: string, parts: Set<number>): boolean {
  const lower = line.toLowerCase()
  for (const word of lower.matchAll(/[a-z]+/g)) {
    const start = word.index ?? 0
    const end = start + word[0].length
    for (let length = 3; length <= 10; length++) {
      for (let i = start; i + length <= end; i++) {
        if (parts.has(cyrb53(lower, i, i + length))) return true
      }
    }
  }
  const normalised = [
    ...[...lower.matchAll(/(?<![a-z])[a-z](?:[\s.]+[a-z]){3,}(?![a-z])/g)].map((m) => m[0].replace(/[^a-z]/g, '')),
    ...[...lower.matchAll(/(?<!\d)\d{3}[\s-]?\d{3}(?!\d)/g)].map((m) => m[0].replace(/\D/g, '')),
    ...[...lower.matchAll(/(?<!\d)\d{1,4}\s*\/\s*\d{1,4}(?!\d)/g)].map((m) => m[0].replace(/\s/g, '')),
  ]
  return normalised.some((text) => parts.has(cyrb53(text)))
}

describe('the operator\'s address stays off the site', () => {
  it('no page, component, translation or the HTML shell carries any part of it', () => {
    const files: Record<string, string> = { ...SOURCES, 'index.html': indexHtml }
    expect(Object.keys(files).length).toBeGreaterThan(50)
    const hits = Object.entries(files).flatMap(([path, text]) =>
      text.split('\n').flatMap((line, i) => (carries(line, ADDRESS) ? [`${path}:${i + 1}`] : [])))
    expect(hits).toEqual([])
  })

  it('the operator\'s details have no location field, and keep the name, Discord and email', () => {
    expect(Object.keys(BUSINESS).filter((key) => /address|street|city|postcode|location/i.test(key))).toEqual([])
    expect(BUSINESS.legalName).toBe('Vinay Kumar Sharma')
    expect(BUSINESS.discordName).toBe('globalfutservices')
    expect(BUSINESS.email).toBe('globalfutservices@gmail.com')
    for (const kept of [BUSINESS.legalName, BUSINESS.tradingName, BUSINESS.discordName, BUSINESS.email]) {
      expect(carries(kept, ADDRESS), kept).toBe(false)
    }
  })

  it('the matching works, shown on made-up parts rather than the real ones', () => {
    expect(cyrb53('abcd')).toBe(3842662445558733)
    const example = new Set(['abcd', 'london', '560001', '7/12'].map((part) => cyrb53(part)))
    for (const written of ['ABCD', 'A.B.C.D.', 'a b c d', 'LONDON', 'Londoner', 'greaterlondon', '560001', '560 001',
      '560-001', '7/12', '7 / 12']) {
      expect(carries(written, example), written).toBe(true)
    }
    for (const other of ['lond on', '17/120', '7/123', '15600012', '5600011']) {
      expect(carries(other, example), other).toBe(false)
    }
  })
})
