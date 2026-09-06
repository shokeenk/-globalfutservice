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
  /*
   * Boosting and coaching are FLAT: one price for the tier, no quantity. Pricing them
   * per-million produced a plausible-looking wrong number, which is worse than an
   * obviously wrong one.
   */
  const FLAT_PRICES = {
    WINS_9: 120000, WINS_10: 155000, WINS_11: 190000, WINS_12: 230000,
    WINS_13: 270000, WINS_14: 310000, WINS_15: 355000, WINS_EXTRA_8: 170000,
    DIV_8_TO_7: 75000, DIV_7_TO_6: 95000, DIV_6_TO_5: 120000, DIV_5_TO_4: 150000,
    DIV_4_TO_3: 185000, DIV_3_TO_2: 225000, DIV_2_TO_1: 290000,
    SINGLE_SESSION: 90000, MONTHLY_6_SESSIONS: 405000,
  }
  const flat = body.variant ? FLAT_PRICES[body.variant] : undefined

  const rate = body.platform === 'PC' ? 70000 : 60000
  const qty = flat ? 1 : Number(body.quantity ?? 1)
  const base = flat ?? rate * qty

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
    { code: 'BASE', label: flat
        ? `${body.sku ?? 'Order'} — ${body.variant}`
        : `Safe Trading Service — ${qty}M (${body.platform})`,
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
    /*
     * Echoes the sku and variant it was asked about.
     *
     * It used to answer every quote as TRADING_SERVICE with a null variant, whatever was
     * requested -- so a boosting tier priced as coins and the cart line said "Safe Trading
     * Service · 1M". That looked exactly like the bug it was hiding, which is the worst
     * thing a mock can do: it made a real defect indistinguishable from its own shortcut.
     */
    quoteId: 'q_mock', season: 'FC26', sku: body.sku || 'TRADING_SERVICE',
    platform: body.platform ?? null, variant: body.variant ?? null,
    quantity: String(qty), currency: 'INR',
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

const ADMIN = process.argv.includes('--admin')

/*
 * Two rows, not one, and deliberately awkward ones.
 *
 * They differ in the two ways that matter to whoever is reviewing them: different
 * destinations, so the column that says which account to open is doing visible work,
 * and a 64-character TRON hash next to a 12-digit UTR, which is what breaks the layout
 * if the reference column is allowed to truncate or refuse to wrap.
 */
const ADMIN_CLAIMS = [
  {
    id: 1,
    publicRef: 'GFS-26-000123',
    customerEmail: 'buyer@example.com',
    sku: 'TRADING_SERVICE',
    totalMinor: 215250,
    totalFormatted: '₹2,152.50',
    currency: 'INR',
    method: 'UPI',
    destination: '9166172359@ybl',
    reference: '432198765012',
    status: 'SUBMITTED',
    submittedAt: new Date(Date.now() - 46 * 60_000).toISOString(),
    reviewedAt: null,
    reviewNote: null,
  },
  {
    id: 2,
    publicRef: 'GFS-26-000124',
    customerEmail: 'someone@example.com',
    sku: 'CHAMPS_BOOSTING',
    totalMinor: 363875,
    totalFormatted: '₹3,638.75',
    currency: 'INR',
    method: 'CRYPTO',
    destination: 'TEGes4sDu6f81jN5na5sR3BZaNXXSFLtoX',
    reference: '3f1a9c4e77bd2058e1c6a9f30b47d5a2e8c1097b46fa25d3e0b8c7419af62d35',
    status: 'SUBMITTED',
    submittedAt: new Date(Date.now() - 8 * 60_000).toISOString(),
    reviewedAt: null,
    reviewNote: null,
  },
]

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
      /*
       * These two strings have to be exactly what RazorpayGateway emits when the
       * gateway is disabled -- "order_stub_" + receipt, and "rzp_test_stub" -- because
       * isStubGateway() sniffs for those prefixes to decide whether to show the pay
       * button. The mock previously answered `stub_1` with an empty key, which matches
       * neither test, so the storefront treated a disabled gateway as a live one and
       * rendered a button that could only fail. The mock has to lie in the same shape
       * as the thing it stands in for or it hides the branch it is meant to exercise.
       */
      payment: { provider: 'STUB', providerOrderId: 'order_stub_GFS-MOCK-0001', publicKey: 'rzp_test_stub', amountMinor: q.totalMinor ?? 0, currency: q.currency ?? 'INR' },
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

  /*
   * Manual payment methods.
   *
   * Mirrors the real endpoint's shape exactly, including the rule that decides which UPI
   * destination a sku gets -- coins to one account, everything else to the other. Getting
   * that wrong here would be the same class of mistake as the mock that answered every
   * quote as TRADING_SERVICE: the branch this feature is built on would never be
   * exercised locally, and the bug would be invisible until it was live.
   *
   * The addresses are the real ones, because they are printed on the checkout page and
   * encoded in the QR images this repo serves. Fake values would make the local page
   * disagree with the pictures next to them.
   */
  if (url.pathname === '/api/v1/payments/methods') {
    const sku = url.searchParams.get('sku') ?? ''
    const coins = sku.toUpperCase() === 'TRADING_SERVICE'
    return json(res, 200, [
      {
        method: 'UPI',
        destination: coins ? '9166172359@ybl' : 'sharvinay088@okicici',
        accountName: coins ? 'Sunita Yogi' : 'Vinu Hunter',
        link: null,
        referenceName: 'UTR',
      },
      {
        // The account is the email; the managed-QR link is the scanning convenience and
        // is what the committed QR image encodes.
        method: 'PAYPAL',
        destination: 'sharvinay088@gmail.com',
        accountName: null,
        link: 'https://www.paypal.com/qrcodes/managed/2241b4bb-ae71-4c69-8337-9efa9c89169a',
        referenceName: 'transaction id',
      },
      {
        method: 'CRYPTO',
        destination: 'TEGes4sDu6f81jN5na5sR3BZaNXXSFLtoX',
        accountName: null,
        link: null,
        referenceName: 'transaction hash',
      },
    ], origin)
  }

  if (/^\/api\/v1\/payments\/claims\/[^/]+$/.test(url.pathname) && req.method === 'POST') {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString() || '{}')
    console.log('[mock] payment claim:', body.method, '| reference:', body.reference,
      '| email:', body.email)
    // 201 and SUBMITTED, never VERIFIED. The real server cannot verify a claim without a
    // person looking at a bank statement, and a mock that returns success would let a
    // "payment confirmed" state be written against a flow that can never produce one.
    return json(res, 201, {
      id: 1,
      method: body.method,
      reference: body.reference,
      status: 'SUBMITTED',
      submittedAt: new Date().toISOString(),
    }, origin)
  }

  /*
   * Admin side of manual payments.
   *
   * Gated behind --admin because the mock otherwise signs everybody in as an operator,
   * and a storefront that thinks the visitor is staff renders a different header, a
   * different account menu and an admin link on every page. That is a worse default for
   * the flow this mock is mostly used to walk.
   *
   *   node tools/mock-api.mjs --admin
   */
  if (ADMIN) {
    const OPERATOR = {
      id: 1, email: 'operator@example.com', displayName: 'Operator',
      role: 'OPERATOR', emailVerified: true, points: 0,
    }

    if (url.pathname === '/api/v1/auth/me') {
      return json(res, 200, OPERATOR, origin)
    }

    // The account has to come back on the token response itself, not only from /me:
    // AuthContext's adopt() reads response.account, so a refresh that returns a token
    // alone signs the browser in with no identity and every guarded route bounces.
    if (url.pathname === '/api/v1/auth/refresh' && req.method === 'POST') {
      return json(res, 200, {
        accessToken: 'mock-operator-token', expiresInSeconds: 900, account: OPERATOR,
      }, origin)
    }

    if (url.pathname === '/api/v1/admin/payment-claims') {
      return json(res, 200, ADMIN_CLAIMS, origin)
    }

    const review = url.pathname.match(/^\/api\/v1\/admin\/payment-claims\/(\d+)\/(verify|reject)$/)
    if (review && req.method === 'POST') {
      const [, id, outcome] = review
      const at = ADMIN_CLAIMS.findIndex((c) => String(c.id) === id)
      if (at >= 0) {
        console.log(`[mock] claim ${id} ${outcome}ed`)
        // Removed from the queue either way: verified claims are done, and rejected ones
        // are no longer SUBMITTED, which is what the real query filters on.
        const [claim] = ADMIN_CLAIMS.splice(at, 1)
        return json(res, 200, { ...claim, status: outcome === 'verify' ? 'VERIFIED' : 'REJECTED' }, origin)
      }
      return json(res, 404, { error: 'not_found', message: 'No such claim.' }, origin)
    }
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
