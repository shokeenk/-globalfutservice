// @ts-expect-error -- this project carries no Node types; vitest runs its tests in Node all the same.
import { existsSync, readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import indexHtml from '../../index.html?raw'
import nginxConf from '../../nginx.conf.template?raw'
import vercelJson from '../../vercel.json?raw'
import robots from '../../public/robots.txt?raw'
import manifestText from '../../public/site.webmanifest?raw'

/*
 * The site's icons: the red GLOBAL FUT SERVICES ring on white, made by scripts/make-icons.py.
 * Google shows one beside every search result, so these read the files themselves -- size,
 * no transparency -- and what points at them.
 */

declare const process: { cwd(): string }

/** frontend/public/, from the test root (vitest runs with --root at frontend/). */
const PUBLIC = process.cwd().replace(/\\/g, '/') + '/public/'

function file(name: string): Uint8Array {
  return readFileSync(PUBLIC + name) as Uint8Array
}

function view(b: Uint8Array): DataView {
  return new DataView(b.buffer, b.byteOffset, b.byteLength)
}

/** A PNG's width, height and colour type, from its IHDR chunk. */
function png(name: string): { width: number; height: number; colourType: number } {
  const b = file(name)
  expect(String.fromCharCode(...b.subarray(1, 4)), name).toBe('PNG')
  return { width: view(b).getUint32(16), height: view(b).getUint32(20), colourType: view(b).getUint8(25) }
}

const ICONS: Record<string, number> = {
  'favicon-48.png': 48,
  'favicon-96.png': 96,
  'favicon-192.png': 192,
  'apple-touch-icon.png': 180,
  'icon-512.png': 512,
}

describe('site icons', () => {
  it('index.html points at the white icons and the manifest, and at nothing older', () => {
    expect(indexHtml).toContain('<link rel="icon" href="/favicon.ico" sizes="48x48" />')
    expect(indexHtml).toContain('<link rel="icon" type="image/png" sizes="96x96" href="/favicon-96.png" />')
    expect(indexHtml).toContain('<link rel="icon" type="image/png" sizes="192x192" href="/favicon-192.png" />')
    expect(indexHtml).toContain('<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png" />')
    expect(indexHtml).toContain('<link rel="manifest" href="/site.webmanifest" />')
    expect(indexHtml).not.toMatch(/favicon\.svg|brand\/favicon-32|brand\/apple-touch-icon/)
  })

  it('every icon is there, square, at its size, and opaque: white behind the mark, never transparent', () => {
    for (const [name, size] of Object.entries(ICONS)) {
      const p = png(name)
      expect([p.width, p.height], name).toEqual([size, size])
      // 2 is RGB, no alpha channel: whatever shows it, the background is the white it was made with.
      expect(p.colourType, name).toBe(2)
    }
  })

  it('favicon.ico holds 16, 32 and 48 px', () => {
    const ico = view(file('favicon.ico'))
    expect(ico.getUint16(0, true)).toBe(0)
    expect(ico.getUint16(2, true)).toBe(1)
    const count = ico.getUint16(4, true)
    const sizes = Array.from({ length: count }, (_, i) => ico.getUint8(6 + i * 16) || 256)
    expect(sizes.sort((a, b) => a - b)).toEqual([16, 32, 48])
  })

  it('the old dark icons are gone', () => {
    for (const old of ['favicon.svg', 'brand/favicon-32.png', 'brand/apple-touch-icon.png']) {
      expect(existsSync(PUBLIC + old), old).toBe(false)
    }
  })

  it('the manifest names the site and points at the white 192 and 512 icons', () => {
    const m = JSON.parse(manifestText)
    expect(m.name).toBe('Global FUT Services')
    expect(m.short_name).toBe('GFS')
    expect(m.background_color).toBe('#FFFFFF')
    expect(m.icons).toEqual([
      { src: '/favicon-192.png', sizes: '192x192', type: 'image/png' },
      { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
    ])
    for (const icon of m.icons) expect(existsSync(PUBLIC + icon.src.slice(1)), icon.src).toBe(true)
  })

  it('the home page says who runs it, with the logo, as JSON-LD', () => {
    const block = indexHtml.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/)
    expect(block).not.toBeNull()
    expect(JSON.parse(block![1]!)).toEqual({
      '@context': 'https://schema.org',
      '@type': 'Organization',
      name: 'Global FUT Services',
      url: 'https://globalfutservices.com',
      logo: 'https://globalfutservices.com/icon-512.png',
    })
    expect(existsSync(PUBLIC + 'icon-512.png')).toBe(true)
  })

  it('the Content-Security-Policy is not loosened for it: a data block is never run, so script-src does not apply', () => {
    const vercelCsp = (JSON.parse(vercelJson).headers as { headers: { key: string; value: string }[] }[])
      .flatMap((h) => h.headers).find((h) => h.key === 'Content-Security-Policy')!.value
    for (const csp of [nginxConf.match(/Content-Security-Policy "([^"]+)"/)![1]!, vercelCsp]) {
      const scriptSrc = csp.split(';').map((d) => d.trim()).find((d) => d.startsWith('script-src'))!
      expect(scriptSrc).not.toContain("'unsafe-inline'")
      expect(scriptSrc).not.toContain('sha256-')
    }
    // The only inline script in the shell is that data block; the app itself is an external module.
    const inline = [...indexHtml.matchAll(/<script(?![^>]*\ssrc=)([^>]*)>/g)].map((m) => (m[1] ?? '').trim())
    expect(inline).toEqual(['type="application/ld+json"'])
  })

  it('nginx serves the icons and the manifest as themselves, never the app shell', () => {
    // Icons fall in the static-file location, which has no index.html fallback.
    const statics = nginxConf.match(/location ~\* \\\.\(([^)]+)\)\$\$/)
    expect(statics).not.toBeNull()
    expect(statics![1]!.split('|')).toEqual(expect.arrayContaining(['png', 'ico']))
    expect(nginxConf).toMatch(/location = \/site\.webmanifest \{\s*default_type application\/manifest\+json;\s*types \{ \}/)
  })

  it('robots.txt leaves the icons and the manifest open to crawlers', () => {
    const disallowed = robots.split('\n').map((l) => l.trim())
      .filter((l) => /^disallow:/i.test(l)).map((l) => l.slice('disallow:'.length).trim())
    for (const path of ['/favicon.ico', ...Object.keys(ICONS).map((n) => `/${n}`), '/site.webmanifest']) {
      for (const rule of disallowed) {
        expect(rule === '/' || (rule !== '' && path.startsWith(rule)), `${rule} blocks ${path}`).toBe(false)
      }
    }
  })
})
