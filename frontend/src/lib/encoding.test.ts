import { describe, expect, it } from 'vitest'

/*
 * Text saved as UTF-8, read back as Windows-1252 and saved again: "á" becomes "Ã¡" and
 * "’" becomes "â€™". Two such words reached the live Spanish site; this keeps the next one
 * out of every page and dictionary.
 */

/** Every source file, as text, keyed by its path. Tests are left out. */
const SOURCES: Record<string, string> = import.meta.glob<string>(
  ['/src/**/*.{ts,tsx}', '!/src/**/*.test.{ts,tsx}'],
  { query: '?raw', import: 'default', eager: true },
)

/** "Ã" or "Â" before a Latin-1 symbol, or "â€" before one: the marks of double encoding. */
const DOUBLE_ENCODED = /Ã[\u0080-¿]|Â[\u0080-¿]|â€[\u0080-¿œ‘-„†-™]/

describe('text is encoded once', () => {
  it('no source file carries double-encoded UTF-8', () => {
    expect(Object.keys(SOURCES).length).toBeGreaterThan(50)
    const hits = Object.entries(SOURCES).flatMap(([path, text]) =>
      text.split('\n').flatMap((line, i) =>
        DOUBLE_ENCODED.test(line) ? [`${path}:${i + 1}: ${line.trim().slice(0, 80)}`] : []))
    expect(hits).toEqual([])
  })

  it('catches the two words that went live, and leaves real accents alone', () => {
    expect(DOUBLE_ENCODED.test('aceptÃ¡is')).toBe(true)
    expect(DOUBLE_ENCODED.test('CogÃ­')).toBe(true)
    expect(DOUBLE_ENCODED.test('itâ€™s')).toBe(true)
    expect(DOUBLE_ENCODED.test('aceptáis, Cogí, « Contacter le coach », d’aide, Âge, São, —, …')).toBe(false)
  })
})
