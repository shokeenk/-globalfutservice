import { describe, expect, it } from 'vitest'
import indexHtml from '../../index.html?raw'
import en from '../i18n/en'
import { SEASON } from './seo'

/*
 * The site names the current game FC 27. This fails if the previous one, FC 26 in any
 * spelling, comes back in text a visitor or an admin can see: a page, a component, a
 * translation, a page title or a meta tag.
 *
 * Comments are not text anyone sees, so they are left out; so is GFS_SEASON, which stays
 * FC26 as the key prices are looked up by, and never reaches the page.
 */

/** Every source file, as text, keyed by its path. Tests are left out, this one included. */
const SOURCES: Record<string, string> = import.meta.glob<string>(
  ['/src/**/*.{ts,tsx}', '!/src/**/*.test.{ts,tsx}'],
  { query: '?raw', import: 'default', eager: true },
)

/**
 * FC26, FC 26, FC-26, FC™ 26 -- but not FC 260 or FC 2026. Capital FC, as every visible
 * mention writes it; asset paths such as /brand/hero-fc26.webp name an image, not the game.
 */
const PREVIOUS_SEASON = /FC\s*(?:™\s*)?-?\s*26(?!\d)/

/** Source without its comments: block and JSX comments, line comments, HTML comments. */
function withoutComments(text: string): string {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(^|[^:\\])\/\/.*$/gm, '$1')
}

describe('the site says FC 27, not FC 26', () => {
  it('no page, component, translation, title or meta tag names FC 26', () => {
    const files: Record<string, string> = { ...SOURCES, 'index.html': indexHtml }
    expect(Object.keys(files).length).toBeGreaterThan(50)
    const hits = Object.entries(files).flatMap(([path, text]) =>
      withoutComments(text).split('\n').flatMap((line, i) =>
        PREVIOUS_SEASON.test(line) ? [`${path}:${i + 1}: ${line.trim().slice(0, 100)}`] : []))
    expect(hits).toEqual([])
  })

  it('the season every title and heading is built from is FC 27', () => {
    expect(SEASON).toBe('FC 27')
    expect(en.home.seoTitle(SEASON)).toBe('Buy Coins for EA FC 27')
    expect(indexHtml).toContain('<title>Global FUT Services — Buy FC 27 Coins, Boosting &amp; Coaching</title>')
  })

  it('catches every spelling of FC 26, and leaves comments and other numbers alone', () => {
    for (const written of ['FC26', 'FC 26', 'FC-26', 'EA SPORTS FC 26', 'FC™ 26', 'Buy FC26 coins']) {
      expect(PREVIOUS_SEASON.test(written), written).toBe(true)
    }
    for (const other of ['FC 27', 'FC 260', 'FC 2026', 'GFS-26-70C4DPWH', '26 wins', '/brand/hero-fc26.webp']) {
      expect(PREVIOUS_SEASON.test(other), other).toBe(false)
    }
    expect(withoutComments("const a = 'x' // FC 26 key art\n/* FC 26 */ const b = 'https://x.test'"))
      .not.toMatch(PREVIOUS_SEASON)
  })
})
