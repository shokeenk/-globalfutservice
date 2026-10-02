import { describe, expect, it } from 'vitest'
import indexHtml from '../../index.html?raw'
import nginxConf from '../../nginx.conf.template?raw'
import vercelJson from '../../vercel.json?raw'

/*
 * The chat stays where it was put. These read the source itself, so a later change that
 * reaches for tawk.to from the layout, the shell or another page fails here, whatever
 * that page renders in a test.
 */

/** Every source file, as text, keyed by its path under src/. Tests are left out. */
const SOURCES: Record<string, string> = Object.fromEntries(
  Object.entries(import.meta.glob<string>(['/src/**/*.{ts,tsx}', '!/src/**/*.test.{ts,tsx}'],
    { query: '?raw', import: 'default', eager: true }))
    .map(([path, text]) => [path.replace(/^\/src\//, ''), text]),
)

describe('tawk.to is reachable from the support page only', () => {
  it('only lib/tawk.ts knows tawk.to\'s script or its API', () => {
    expect(Object.keys(SOURCES).length).toBeGreaterThan(50)
    const mentions = Object.entries(SOURCES).filter(([, text]) => /embed\.tawk\.to|Tawk_API/.test(text))
    expect(mentions.map(([path]) => path)).toEqual(['lib/tawk.ts'])
  })

  it('only the support chat imports it', () => {
    const importers = Object.entries(SOURCES).filter(([, text]) => /from ['"](\.\.?\/)+lib\/tawk['"]/.test(text))
    expect(importers.map(([path]) => path)).toEqual(['components/support/SupportChat.tsx'])
  })

  it('nothing global loads it: not index.html, not the app shell, not the layout', () => {
    expect(indexHtml).not.toMatch(/tawk/i)
    const app = SOURCES['App.tsx'] ?? ''
    expect(app).not.toMatch(/lib\/tawk|SupportChat/)
    // The support page itself is loaded on demand, like every other page.
    expect(app).toMatch(/const OrderSupport = lazy\(\(\) => import\('\.\/pages\/OrderSupport'\)\)/)
  })
})

/** The policy before tawk.to, directive by directive, as both configs had it. */
const BEFORE: Record<string, string[]> = {
  'default-src': ["'self'"],
  'script-src': ["'self'", 'https://checkout.razorpay.com'],
  'style-src': ["'self'", "'unsafe-inline'", 'https://fonts.googleapis.com'],
  'img-src': ["'self'", 'data:', 'https:'],
  'font-src': ["'self'", 'data:', 'https://fonts.gstatic.com'],
  'connect-src': ["'self'", 'https://api.razorpay.com'],
  'frame-src': ['https://api.razorpay.com', 'https://checkout.razorpay.com'],
  'frame-ancestors': ["'none'"],
  'base-uri': ["'self'"],
  'form-action': ["'self'"],
  'object-src': ["'none'"],
}

/** tawk.to's own list (help.tawk.to, "Resolving CSP issues"); img-src's `https:` already covers its images. */
const TAWK: Record<string, string[]> = {
  'script-src': ['https://*.tawk.to', 'https://cdn.jsdelivr.net'],
  'style-src': ['https://*.tawk.to', 'https://cdn.jsdelivr.net'],
  'font-src': ['https://*.tawk.to'],
  'connect-src': ['https://*.tawk.to', 'wss://*.tawk.to'],
  'frame-src': ['https://*.tawk.to'],
  'form-action': ['https://*.tawk.to'],
}

function parse(csp: string): Record<string, string[]> {
  const out: Record<string, string[]> = {}
  for (const part of csp.split(';').map((p) => p.trim()).filter(Boolean)) {
    const [name, ...values] = part.split(/\s+/)
    out[name as string] = values
  }
  return out
}

function expected(extra: Record<string, string[]> = {}): Record<string, string[]> {
  const out: Record<string, string[]> = {}
  for (const [name, values] of Object.entries(BEFORE)) {
    out[name] = [...values, ...(TAWK[name] ?? []), ...(extra[name] ?? [])]
  }
  return out
}

describe('the Content-Security-Policy lets the chat in, and nothing else changed', () => {
  it('vercel.json', () => {
    const config = JSON.parse(vercelJson) as {
      headers: { headers: { key: string; value: string }[] }[]
    }
    const csp = config.headers.flatMap((h) => h.headers).find((h) => h.key === 'Content-Security-Policy')?.value
    expect(parse(csp ?? '')).toEqual(expected())
  })

  it('nginx.conf.template', () => {
    const csp = /add_header Content-Security-Policy "([^"]+)"/.exec(nginxConf)?.[1]
    // The API's own origin, filled in at start-up, stays the last connect-src entry.
    expect(parse(csp ?? '')).toEqual(expected({ 'connect-src': ['${API_CONNECT_SRC}'] }))
  })
})
