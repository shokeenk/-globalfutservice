import { describe, expect, it } from 'vitest'
import dockerfile from '../Dockerfile?raw'

/*
 * A VITE_* variable reaches the bundle only if the storefront's Dockerfile declares it:
 * a Docker build sees no other variable, and an undeclared one comes out empty without a
 * word -- which is how the live chat never appeared on the live site (2026-10-23). So every
 * VITE_* the source reads must be an ARG there.
 */

const sources = import.meta.glob(['./**/*.{ts,tsx}', '!./**/*.test.{ts,tsx}'],
  { query: '?raw', import: 'default', eager: true }) as Record<string, string>

describe('build variables', () => {
  it('every VITE_* variable the storefront reads is declared in its Dockerfile', () => {
    const read = new Set<string>()
    for (const text of Object.values(sources)) {
      for (const m of text.matchAll(/\b(?:env|import\.meta\.env)\.(VITE_[A-Z0-9_]+)/g)) read.add(m[1]!)
    }
    expect([...read].sort()).toEqual(expect.arrayContaining(['VITE_API_BASE_URL', 'VITE_TAWK_FLOATING_WIDGET_ID']))
    const missing = [...read].filter((name) => !new RegExp(`^ARG ${name}\\b`, 'm').test(dockerfile))
    expect(missing).toEqual([])
  })
})
