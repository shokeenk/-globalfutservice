/**
 * A tiny mock of the API, for working on the UI without running Postgres.
 *
 *   node tools/mock-api.mjs          # serves the mock API on :8080
 *   node tools/mock-api.mjs --static # also serves ./dist on :5173
 *
 * The shapes here are copied from the real DTOs. When they drift, the UI breaks
 * against the real backend, which is exactly the signal you want — a mock that
 * quietly diverges is worse than no mock at all.
 */
import { createServer } from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import { extname, join, normalize } from 'node:path'

const CATALOG = {
  season: 'FC26',
  currency: 'INR',
  // Matches the four currencies the real catalogue now serves, so the header's
  // currency picker is exercisable with no backend. The mock keeps INR prices
  // whichever is selected -- it exists to drive the UI, not to price anything.
  availableCurrencies: ['INR', 'USD', 'EUR', 'GBP'],
  services: [
    {
      sku: 'TRADING_SERVICE',
      displayName: 'Safe Trading Service',
      sellable: true,
      priceUnit: 'PER_MILLION',
      marketTaxApplies: true,
      mayRequireCredentials: true,
      options: [
        { platform: 'PC', variant: null, label: 'PC', unitPriceMinor: 70000,
          unitPriceFormatted: '₹700.00', minQuantity: '0.50', maxQuantity: '5.00', stepQuantity: '0.01' },
        { platform: 'PLAYSTATION', variant: null, label: 'PlayStation', unitPriceMinor: 60000,
          unitPriceFormatted: '₹600.00', minQuantity: '0.50', maxQuantity: '5.00', stepQuantity: '0.01' },
        { platform: 'XBOX', variant: null, label: 'Xbox', unitPriceMinor: 60000,
          unitPriceFormatted: '₹600.00', minQuantity: '0.50', maxQuantity: '5.00', stepQuantity: '0.01' },
      ],
    },
    {
      sku: 'BOOST_CHAMPS',
      displayName: 'Champs Boosting',
      sellable: true,
      priceUnit: 'FLAT',
      marketTaxApplies: true,
      mayRequireCredentials: true,
      options: [
        ['WINS_9', '9 wins', 120000], ['WINS_10', '10 wins', 155000],
        ['WINS_11', '11 wins', 190000], ['WINS_12', '12 wins', 230000],
        ['WINS_13', '13 wins', 270000], ['WINS_14', '14 wins', 310000],
        ['WINS_15', '15 wins', 355000], ['WINS_EXTRA_8', '+8 extra wins', 170000],
      ].map(([variant, label, minor]) => ({
        platform: null, variant, label, unitPriceMinor: minor,
        unitPriceFormatted: inr(minor), minQuantity: null, maxQuantity: null, stepQuantity: null,
      })),
    },
    {
      sku: 'BOOST_RIVALS',
      displayName: 'Rivals Boosting',
      sellable: true,
      priceUnit: 'FLAT',
      marketTaxApplies: true,
      mayRequireCredentials: true,
      options: [
        ['DIV_8_TO_7', 'Division 8 to 7', 75000], ['DIV_7_TO_6', 'Division 7 to 6', 95000],
        ['DIV_6_TO_5', 'Division 6 to 5', 120000], ['DIV_5_TO_4', 'Division 5 to 4', 150000],
        ['DIV_4_TO_3', 'Division 4 to 3', 185000], ['DIV_3_TO_2', 'Division 3 to 2', 225000],
        ['DIV_2_TO_1', 'Division 2 to 1', 290000],
      ].map(([variant, label, minor]) => ({
        platform: null, variant, label, unitPriceMinor: minor,
        unitPriceFormatted: inr(minor), minQuantity: null, maxQuantity: null, stepQuantity: null,
      })),
    },
    { sku: 'COACHING', displayName: 'FUT Classes', sellable: true, priceUnit: 'FLAT',
      marketTaxApplies: false, mayRequireCredentials: false, options: [
        { platform: null, variant: 'SINGLE_SESSION', label: 'Single session × 40 min',
          unitPriceMinor: 90000, unitPriceFormatted: inr(90000),
          minQuantity: null, maxQuantity: null, stepQuantity: null },
        { platform: null, variant: 'MONTHLY_6_SESSIONS', label: '6 sessions × 40 min',
          unitPriceMinor: 405000, unitPriceFormatted: inr(405000),
          minQuantity: null, maxQuantity: null, stepQuantity: null },
      ] },
    { sku: 'CARDS', displayName: 'Player Cards', sellable: false, priceUnit: 'FLAT',
      marketTaxApplies: false, mayRequireCredentials: false, options: [] },
  ],
}

const POLICY = {
  marketTaxBps: 500, gatewayFeeBps: 250, gatewayFeeMode: 'PASS_THROUGH',
  pointValueMinor: 100, earnSpendUnitMinor: 200000, earnPointsPerUnit: 20,
  maxWalletRedemptionBps: 2000, quoteTtlSeconds: 600, guaranteeDays: 7,
  deliverySlaHours: 48, refundFeeBps: 500, guaranteeCashBps: 5000,
  guaranteeCreditBps: 10000, defaultDeliveryMethod: 'PLAYER_AUCTION',
  // Mirrors LoyaltyTier. Kept in step by hand here on purpose — the mock exists so the
  // UI can be worked on with no backend, and reaching for the real ladder would defeat
  // that. The rewards page reads it from this response either way, so a drift shows up
  // as wrong numbers on screen rather than as a silent difference in behaviour.
  loyaltyTiers: [
    { name: 'BRONZE', displayName: 'Bronze', thresholdPoints: 0, discountBps: 0 },
    { name: 'SILVER', displayName: 'Silver', thresholdPoints: 500, discountBps: 100 },
    { name: 'GOLD', displayName: 'Gold', thresholdPoints: 2000, discountBps: 200 },
    { name: 'ELITE', displayName: 'Elite', thresholdPoints: 5000, discountBps: 300 },
    { name: 'PLATINUM', displayName: 'Platinum', thresholdPoints: 8000, discountBps: 400 },
    { name: 'DIAMOND', displayName: 'Diamond', thresholdPoints: 10000, discountBps: 500 },
  ],
  tierDiscountEnabled: true,
  dailyBonusPoints: 3,
}

const COACHES = [
  {
    id: 'mock-coach-1', displayName: 'Vinu', headline: 'Elite Division · WL veteran',
    bio: 'Trading, squad building and the habits that actually move your rank.',
    avatarUrl: null, languages: 'English, Hindi', credentials: 'Div 1 · 15 wins',
    timezone: 'Asia/Kolkata',
  },
]

/**
 * Slots for the next fortnight: weekday evenings, 18:00–22:00 IST, on the half hour.
 *
 * Generated rather than hard-coded so the calendar always has something in it whatever
 * day the mock is started on — a fixture with fixed dates goes stale overnight and the
 * booking UI becomes untestable.
 */
function mockSlots() {
  const out = []
  const now = Date.now()
  for (let day = 0; day < 14; day++) {
    for (const [hour, minute] of [[18, 0], [18, 30], [19, 0], [19, 30], [20, 0], [21, 0]]) {
      // 18:00 IST is 12:30 UTC.
      const d = new Date(now + day * 86_400_000)
      d.setUTCHours(hour - 5, minute - 30, 0, 0)
      if (d.getTime() > now + 2 * 3600_000) out.push(d.toISOString())
    }
  }
  return out.sort()
}

/** Mirrors the server's rounding: exact arithmetic, one rounding of the total. */
function quote(body) {
  const rate = body.platform === 'PC' ? 70000 : 60000
  const qty = Number(body.quantity ?? 1)
  const base = rate * qty
  /*
   * Zero, because `market-tax-mode` is INCLUDED in every deployed configuration:
   * EA's 5% is inside the per-million rate, so the line exists to say so and costs
   * nothing. The mock used to add 5% on top, which made it the one place in the
   * project where the storefront's "EA's 5% tax is on us" banner sat directly above
   * a bill charging for it -- and it meant the `MARKET_TAX === 0` rendering path,
   * the one every real customer sees, was never exercised here.
   */
  const tax = 0
  const subtotal = base + tax
  const fee = subtotal * 0.025
  const total = Math.round(subtotal + fee)

  const lines = [
    { code: 'BASE', label: `Safe Trading Service — ${qty}M (${body.platform})`,
      amountMinor: Math.round(base), amountFormatted: inr(Math.round(base)) },
    { code: 'MARKET_TAX', label: 'EA transfer market tax (5%)',
      amountMinor: Math.round(tax), amountFormatted: inr(Math.round(tax)) },
    { code: 'GATEWAY_FEE', label: 'Payment processing (2.5%)',
      amountMinor: Math.round(fee), amountFormatted: inr(Math.round(fee)) },
  ]
  const residual = total - lines.reduce((sum, l) => sum + l.amountMinor, 0)
  lines[0].amountMinor += residual
  lines[0].amountFormatted = inr(lines[0].amountMinor)

  const now = Date.now()
  return {
    quoteId: 'q_mock', season: 'FC26', sku: 'TRADING_SERVICE',
    platform: body.platform, variant: null, quantity: String(qty), currency: 'INR',
    lines, subtotalMinor: Math.round(subtotal), totalMinor: total,
    totalFormatted: inr(total), pointsRedeemed: 0,
    pointsEarned: Math.floor(total / 200000) * 20, referralCode: null,
    issuedAt: new Date(now).toISOString(),
    expiresAt: new Date(now + 600_000).toISOString(),
    signature: 'mock',
  }
}

function inr(minor) {
  const major = (minor / 100).toFixed(2)
  const [whole, fraction] = major.split('.')
  const last3 = whole.slice(-3)
  const rest = whole.slice(0, -3)
  const grouped = rest ? `${rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',')},${last3}` : last3
  return `₹${grouped}.${fraction}`
}

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css',
  '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.json': 'application/json',
  '.png': 'image/png', '.ico': 'image/x-icon', '.txt': 'text/plain', '.xml': 'application/xml',
}

/**
 * Echoes the caller's origin rather than sending a wildcard.
 *
 * A wildcard cannot be combined with `credentials: 'include'` — the browser
 * rejects the response outright. The real API allows an explicit list of origins
 * for the same reason, so the mock behaves the same way rather than being more
 * permissive and hiding a class of bug until deployment.
 */
function json(res, status, payload, origin = 'http://localhost:5173') {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Credentials': 'true',
    'Vary': 'Origin',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Content-Length': Buffer.byteLength(body),
  })
  res.end(body)
}

createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const origin = req.headers.origin ?? 'http://localhost:5173'

  if (req.method === 'OPTIONS') return json(res, 204, {}, origin)
  if (url.pathname === '/api/v1/catalog') return json(res, 200, CATALOG, origin)
  if (url.pathname === '/api/v1/catalog/policy') return json(res, 200, POLICY, origin)

  // ---- coaching -------------------------------------------------------------------
  if (url.pathname === '/api/v1/coaching/coaches') return json(res, 200, COACHES, origin)

  if (/^\/api\/v1\/coaching\/coaches\/[^/]+\/slots$/.test(url.pathname)) {
    return json(res, 200, {
      coachId: COACHES[0].id, coachTimezone: 'Asia/Kolkata',
      sessionMinutes: 40, slots: mockSlots(),
    }, origin)
  }

  if (url.pathname === '/api/v1/coaching/policy') {
    return json(res, 200, {
      sessionMinutes: 40, changeCutoffHours: 12, maxReschedules: 2,
      minLeadTimeHours: 2, creditValidityDays: 90,
    }, origin)
  }

  if (url.pathname === '/api/v1/quotes' && req.method === 'POST') {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    return json(res, 200, quote(JSON.parse(Buffer.concat(chunks).toString() || '{}')), origin)
  }

  if (url.pathname.startsWith('/api/')) {
    return json(res, 401, { error: 'unauthenticated', message: 'Mock API' }, origin)
  }

  // Static, when asked for. Unknown paths fall back to index.html so client-side
  // routing works on a hard refresh.
  if (process.argv.includes('--static')) {
    const root = join(process.cwd(), 'dist')
    const candidate = normalize(join(root, url.pathname))
    if (!candidate.startsWith(root)) {
      res.writeHead(403).end('forbidden')
      return
    }
    try {
      const info = await stat(candidate)
      if (info.isFile()) {
        res.writeHead(200, { 'Content-Type': MIME[extname(candidate)] ?? 'application/octet-stream' })
        res.end(await readFile(candidate))
        return
      }
    } catch { /* fall through to index.html */ }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    res.end(await readFile(join(root, 'index.html')))
    return
  }

  res.writeHead(404).end('not found')
}).listen(process.argv.includes('--static') ? 5173 : 8080, () => {
  console.log(`Mock API on ${process.argv.includes('--static') ? 5173 : 8080}`)
})
