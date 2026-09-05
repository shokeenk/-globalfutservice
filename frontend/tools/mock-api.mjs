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
      /*
       * The fourth column is `successRateBps`, and it is INVENTED.
       *
       * It exists so the note 17 headline and the note 18 per-card labels can be seen,
       * reviewed and signed off before any real measurement exists. Nothing here is a
       * measurement and none of it is an estimate of one -- the values slope the way a
       * plausible dataset would purely so the "lowest, not mean" rule in
       * SuccessHeadline is visibly exercised.
       *
       * THIS FILE IS NEVER DEPLOYED. It is not referenced by frontend/Dockerfile,
       * render.yaml, docker-compose.yml or any npm script, and Render builds the Vite
       * bundle against the real API. The real CatalogService sends null for every one
       * of these, so production renders no percentage anywhere. That is the only reason
       * it is acceptable for these numbers to exist at all: they cannot reach a
       * customer, a payment provider or a regulator.
       *
       * When real rates arrive they come from the API, not from here. Delete the column
       * rather than editing it to match -- a mock that agrees with production today is
       * a mock that silently disagrees with it in six months.
       */
      options: [
        ['WINS_9', '9 wins', 120000, null], ['WINS_10', '10 wins', 155000, null],
        ['WINS_11', '11 wins', 190000, null], ['WINS_12', '12 wins', 230000, null],
        ['WINS_13', '13 wins', 270000, 9200], ['WINS_14', '14 wins', 310000, 9400],
        ['WINS_15', '15 wins', 355000, 9500], ['WINS_EXTRA_8', '+8 extra wins', 170000, null],
      ].map(([variant, label, minor, successRateBps]) => ({
        platform: null, variant, label, unitPriceMinor: minor,
        unitPriceFormatted: inr(minor), minQuantity: null, maxQuantity: null, stepQuantity: null,
        successRateBps,
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
  // Two lengths, matching CatalogService. A single session is an hour; a session out
  // of the six-pack is forty minutes, which is why the pack costs less per session.
  coachingSessionMinutes: 60,
  coachingBlockSessionMinutes: 40,
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
   * One coupon, so the discount path can actually be exercised.
   *
   * The mock used to ignore `couponCode` entirely, which meant the storefront's coupon
   * field, its applied/rejected messages and the discount line in the savings group had
   * no way to be seen without a database. SAVE10 takes 10% off the base; anything else
   * is rejected with the same shape the real engine uses, so both outcomes are
   * reachable. The percentage is the mock's own and is not a real offer.
   */
  const code = (body.couponCode ?? '').trim().toUpperCase()
  const couponOk = code === 'SAVE10'
  const couponMinor = couponOk ? -Math.round(base * 0.10) : 0
  /*
   * Zero, because `market-tax-mode` is INCLUDED in every deployed configuration:
   * EA's 5% is inside the per-million rate, so the line exists to say so and costs
   * nothing. The mock used to add 5% on top, which made it the one place in the
   * project where the storefront's "EA's 5% tax is on us" banner sat directly above
   * a bill charging for it -- and it meant the `MARKET_TAX === 0` rendering path,
   * the one every real customer sees, was never exercised here.
   */
  const tax = 0
  const subtotal = base + tax + couponMinor
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
  if (couponMinor !== 0) {
    lines.splice(2, 0, {
      code: 'COUPON_DISCOUNT', label: `Coupon ${code} (10% off)`,
      amountMinor: couponMinor, amountFormatted: inr(couponMinor),
    })
  }
  const residual = total - lines.reduce((sum, l) => sum + l.amountMinor, 0)
  lines[0].amountMinor += residual
  lines[0].amountFormatted = inr(lines[0].amountMinor)

  const now = Date.now()
  return {
    quoteId: 'q_mock', season: 'FC26', sku: 'TRADING_SERVICE',
    platform: body.platform, variant: null, quantity: String(qty), currency: 'INR',
    lines, subtotalMinor: Math.round(subtotal), totalMinor: total,
    couponCode: couponOk ? code : null,
    couponMessage: code && !couponOk ? 'That code is not valid.' : null,
    totalFormatted: inr(total), pointsRedeemed: 0,
    pointsEarned: Math.floor(total / 200000) * 20, referralCode: null,
    issuedAt: new Date(now).toISOString(),
    expiresAt: new Date(now + 600_000).toISOString(),
    signature: 'mock',
  }
}

function inr(minor) {
  /*
   * The sign is taken off before grouping and put back after.
   *
   * Lakh grouping slices the last three digits off the string, and on a negative the
   * minus sign travels with the leading digits -- so -21000 grouped as text produced
   * "-,210.00". Nothing needed a negative amount until the mock learned about coupons,
   * and a discount line is exactly where it shows.
   */
  const negative = minor < 0
  const major = (Math.abs(minor) / 100).toFixed(2)
  const [whole, fraction] = major.split('.')
  const last3 = whole.slice(-3)
  const rest = whole.slice(0, -3)
  const grouped = rest ? `${rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',')},${last3}` : last3
  return `${negative ? '-' : ''}₹${grouped}.${fraction}`
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
      // The single-session length, as the real endpoint publishes: it is shared-cached
      // and cannot vary by caller, and a start with room for an hour has room for forty
      // minutes, so every slot offered is bookable by either customer.
      sessionMinutes: 60, slots: mockSlots(),
    }, origin)
  }

  if (url.pathname === '/api/v1/coaching/policy') {
    return json(res, 200, {
      sessionMinutes: 60, blockSessionMinutes: 40, changeCutoffHours: 12, maxReschedules: 2,
      minLeadTimeHours: 2, creditValidityDays: 90,
    }, origin)
  }

  if (url.pathname === '/api/v1/quotes' && req.method === 'POST') {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    return json(res, 200, quote(JSON.parse(Buffer.concat(chunks).toString() || '{}')), origin)
  }

  /*
   * Order creation and the credential vault.
   *
   * Added so the checkout's two-call submission can actually be exercised: the order is
   * created first, then the sign-in is posted separately to the encrypted endpoint. With
   * neither of these the whole flow fell through to the 401 below and nothing past the
   * pay button could be seen without a database.
   *
   * The credentials handler deliberately echoes back only field NAMES and lengths, never
   * values. A mock that logged a password to a terminal would be the one place in this
   * project where a plaintext credential is written down.
   */
  if (url.pathname === '/api/v1/orders' && req.method === 'POST') {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString() || '{}')
    const q = body.quote ?? {}
    console.log('[mock] order created for', body.email, '- fields:', Object.keys(body).join(', '))
    return json(res, 201, {
      publicRef: 'GFS-MOCK-0001',
      status: 'AWAITING_PAYMENT',
      totalMinor: q.totalMinor ?? 0,
      totalFormatted: q.totalFormatted ?? inr(q.totalMinor ?? 0),
      currency: q.currency ?? 'INR',
      payment: { provider: 'STUB', providerOrderId: 'stub_1', publicKey: '', amountMinor: q.totalMinor ?? 0, currency: q.currency ?? 'INR' },
    }, origin)
  }

  if (/^\/api\/v1\/orders\/[^/]+\/credentials$/.test(url.pathname) && req.method === 'POST') {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString() || '{}')
    console.log('[mock] credentials received - keys:', Object.keys(body).join(', '),
      '| backupCodes:', (body.backupCodes ?? []).length,
      '| passwordLength:', (body.eaPassword ?? '').length)
    return json(res, 200, { publicRef: 'GFS-MOCK-0001', status: 'AWAITING_PAYMENT' }, origin)
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
