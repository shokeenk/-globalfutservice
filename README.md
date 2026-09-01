# Global FUT Services

A trading-service storefront and fulfilment system for EA FC, built for
**Global FUT Services** (FC Vinu Hunter).

Spring Boot 3 / JDK 21 API, React + Vite storefront, PostgreSQL, Razorpay.
One repository, two deployables, `docker compose up` to run the lot.

---

## Contents

- [What this is](#what-this-is)
- [Quick start](#quick-start)
- [Architecture](#architecture)
- [The five decisions that shaped it](#the-five-decisions-that-shaped-it)
- [Security](#security)
- [Coaching](#coaching)
- [Loyalty](#loyalty)
- [Configuration](#configuration)
- [Testing](#testing)
- [Deploying](#deploying)
- [Operating it](#operating-it)
- [What is deliberately not built yet](#what-is-deliberately-not-built-yet)

---

## What this is

Customers order a **trading service**: our traders work the EA FC transfer market on
their account, and the resulting coins stay with them. The system prices that work,
takes payment, routes the order to an operator, tracks it to delivery, and settles
loyalty points and creator commission once the guarantee window closes.

**It never sells in-game currency.** That is not a wording preference — "sale of
virtual currency" is on the prohibited-business list of most payment processors,
Razorpay included, and the merchant category, the Terms of Service, the invoice line
and the enum name all have to say the same thing. If a risk reviewer finds a
mismatch between what the site says and what the merchant account declares, the
account gets terminated, and a coin business without payment rails is not a
business. The SKU is called `TRADING_SERVICE` everywhere for this reason.

---

## Quick start

**Prerequisites:** Docker, or JDK 21 + Node 22 + PostgreSQL 16.

```bash
git clone <your-repo> globalfutservice && cd globalfutservice
cp .env.example .env

# Generate the three secrets. The application refuses to start without them,
# which is deliberate — see "Security" below.
echo "GFS_JWT_SECRET=$(openssl rand -base64 48)"           >> .env
echo "GFS_QUOTE_SECRET=$(openssl rand -base64 48)"         >> .env
echo "GFS_CREDENTIAL_MASTER_KEY=$(openssl rand -base64 32)" >> .env

# The first operator account, created once on boot.
echo "GFS_BOOTSTRAP_ADMIN_EMAIL=you@example.com" >> .env
echo "GFS_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 18)" >> .env

docker compose up --build
```

| Service | Where | Notes |
| --- | --- | --- |
| Storefront | http://localhost:5173 | |
| API | http://localhost:8080 | |
| API docs | http://localhost:8080/swagger-ui.html | development only |
| Mail catcher | http://localhost:8025 | every outbound email lands here |
| Admin console | http://localhost:5173/admin | sign in with the bootstrap account |

Razorpay is **off** by default, so the entire checkout flow can be walked end to end
without live credentials — the gateway hands back a stub order and the storefront
says so plainly rather than opening a payment window that does nothing.

### Running the halves separately

```bash
# API
cd backend && mvn spring-boot:run

# Storefront
cd frontend && npm install && npm run dev

# Storefront with no backend at all — useful for UI work
cd frontend && node tools/mock-api.mjs &      # mock API on :8080
npm run dev
```

---

## Architecture

```
                        ┌─────────────────────────────────────────┐
  Browser  ────────────▶│  React + Vite (nginx)                   │
                        │  route-split bundles, self-hosted fonts │
                        └───────────────────┬─────────────────────┘
                                            │  REST + bearer token
                                            │  refresh in an HttpOnly cookie
                        ┌───────────────────▼─────────────────────┐
                        │  Spring Boot 3  ·  JDK 21               │
                        │                                         │
                        │  ┌───────── domain/ ─────────────────┐  │
                        │  │  NO Spring. NO JPA. NO Jackson.   │  │
                        │  │  Money · PricingEngine · Quote    │  │
                        │  │  OrderStateMachine · EnvelopeCipher│ │
                        │  │  RazorpaySignature · PointsWallet │  │
                        │  │  LoyaltyTier · SlotPlanner        │  │
                        │  │  SessionStateMachine              │  │
                        │  └───────────────────────────────────┘  │
                        │                                         │
                        │  catalog · pricing · orders · payments  │
                        │  credentials · loyalty · affiliate      │
                        │  coaching · identity · notify · admin   │
                        │  support                                │
                        └──────┬───────────────────────┬──────────┘
                               │                       │
                     ┌─────────▼────────┐   ┌──────────▼──────────┐
                     │   PostgreSQL     │   │ Razorpay · WhatsApp │
                     │   (Flyway)       │   │ Cloud API · SMTP    │
                     └──────────────────┘   └─────────────────────┘
```

**A modular monolith.** Each package under `com.globalfutservice` is a bounded
context and they talk through service interfaces rather than reaching into each
other's entities. That buys the seams of a service architecture at none of its
operational cost, and leaves `credentials` ready to be lifted out behind its own
boundary if it ever needs to be.

**The domain core is framework-free on purpose.** Everything that decides what a
customer is charged — money arithmetic, the pricing engine, quote signing, the order
state machine, envelope encryption, gateway signature verification — lives in
`domain/` with no annotations and no container. It can be exhaustively unit-tested in
milliseconds, and it can be verified with nothing but a JDK:

```bash
cd backend && ./verify-domain.sh
```

```
  PASS  190 assertions, 0 failures
```

That script compiles the domain with `javac` and runs an assertion suite covering the
pricing arithmetic (including a 6,400-quote rounding sweep that walks every rung of the
loyalty ladder), quote signing and tampering, the credential cipher, every legal and
illegal state transition for both orders and coaching sessions, the Razorpay signature
checks, and slot planning across a daylight-saving transition. No Maven, no network, no
database — so "does the money still add up?" is never blocked on dependency resolution.

---

## The five decisions that shaped it

### 1. The client sends intent. The server sends the price.

The common failure in storefronts of this shape is a React configurator that computes
a total and posts it to a backend that trusts it. One `curl` later, ten million coins
cost one rupee.

Here the client posts `{sku, platform, quantity}` and receives a **signed quote**:
a full price breakdown, an expiry, and an HMAC over every field that moves money —
including a binding to the customer it was issued to, so a quote minted for an account
with a large points balance cannot be lifted and replayed by a guest.

Creating an order posts that quote back unchanged. The server re-verifies the
signature, re-checks the expiry, and re-reads the customer's balance from the
database. A unique index on `orders.quote_id` means one quote can become at most one
order, so replay is stopped by the schema rather than by hopeful application logic.

### 2. Rounding happens once, and the printed breakdown always adds up.

Money is `BIGINT` minor units — paise — everywhere. Never `double`, never
"rupees as a decimal". Razorpay's API takes paise as an integer, so this alignment is
free at the gateway boundary too.

Every component is computed in exact decimal at high precision and accumulated without
rounding. The **total** is rounded once. Each displayed line is then rounded
independently, and the sub-paisa residual is folded back into the base line — so the
column on the customer's invoice sums exactly to what their card was charged. There is
a test that asserts this across four thousand quotes.

### 3. `DELIVERED` is a contractual event, not a status flag.

Under the Terms, receipt of the "Order Delivered" email closes the refund window. So
that transition is operator-only, irreversible, timestamped, starts the seven-day
guarantee clock and sends the email — all in one transaction. Nothing transitions back
out of it.

Every status change in the application goes through one method,
`OrderService.transition`, which validates against `OrderStateMachine` and writes an
`order_event` row. Nothing anywhere calls `setStatus` directly. That is what makes it
possible to say with confidence that every change is legal and every change is
audited — and the audit trail doubles as the evidence pack for a chargeback
representment.

### 4. Points and commission settle at `COMPLETED`, never at `PAID`.

Granting loyalty points at payment means writing clawback logic for every refund and
every upheld ban claim. Granting them after the guarantee window elapses means that
logic exists only for genuine reversals and is almost never exercised. Creator
commission accrues on the same event for the same reason — nobody wants to invoice a
streamer for money already paid out.

Both are idempotent per order at the **database** level (`UNIQUE (order_id,
entry_type)`), so a retried job or a duplicated webhook cannot mint points twice even
if the application logic is wrong.

### 5. The credential vault is designed as though it will one day be read by the wrong person.

Some orders require the customer's EA sign-in. That is the highest-severity data in
the system, and it is handled accordingly — see below.

---

## Security

### The credential vault

| Control | Why |
| --- | --- |
| Collected **only after payment** | An endpoint accepting EA sign-ins on unpaid orders is a credential-harvesting funnel with a company's name on it |
| **AES-256-GCM envelope encryption**, fresh data key per order, master key from the environment | One compromised record cannot decrypt any other; a stolen database dump is inert |
| Random 96-bit IV per operation | GCM with a repeated IV under the same key is catastrophic, not merely weak |
| **Never logged** — request DTO overrides `toString`, entity overrides `toString`, `include-message: never` on errors | Spring's request logging, any APM agent, and one careless `log.debug` would each print the password in clear |
| **Hard TTL**, enforced by a scheduled job independent of the order state | The state machine purges on the happy path; the job purges regardless, so a stuck order cannot mean an indefinitely retained password |
| Every read **counted and attributed** to an operator | Staff are in the threat model |
| Purge is irreversible, enforced by a database `CHECK` | Even a bad `UPDATE` cannot leave key material behind |
| Delivery email tells the customer to rotate | Defence in depth, and it is what a careful operator would say |

Player-auction delivery needs no credentials at all, so it is the **default**.

### Payments

- The webhook signature is verified over the **raw request body**, before any parsing —
  which is why the controller takes a `String` and not a DTO. Comparison is
  `MessageDigest.isEqual`; a byte-by-byte `equals` leaks how much of a forged prefix
  was correct.
- **Only the webhook can mark an order paid.** The browser redirect decides what the
  customer sees; it never touches the ledger. It can be closed, replayed, or
  hand-crafted.
- The amount on the event is re-checked against the order total. A mismatch is
  recorded and flagged, never silently accepted.
- Deliveries are idempotent via `UNIQUE (provider, provider_event_id)` — gateways
  retry, and a duplicate must not re-run the post-payment path.
- A missing webhook secret **fails closed** and is warned about loudly at startup,
  because the failure mode is otherwise invisible: orders quietly never get paid.

### Sessions

- Access token: HS256, 15 minutes, held **in memory** by the frontend — never
  `localStorage`, where one XSS bug yields a token usable from anywhere.
- Refresh token: opaque, **stored only as a SHA-256 hash**, delivered in an
  `HttpOnly; SameSite=Strict; Secure` cookie scoped to `/api/v1/auth`.
- **Rotation with reuse detection.** Each refresh issues a new token in the same
  family. Presenting an already-used token means it was captured, so the whole family
  is revoked and everyone signs in again. The frontend coalesces concurrent refreshes
  so a page firing four requests at once does not trip this on itself.
- BCrypt strength 12. Registration cannot set a role. Sign-in verifies a dummy hash
  when no account exists, so response time does not distinguish "no such user" from
  "wrong password". Lockout after repeated failures reports identically to a wrong
  password.

### Everything else

- **Object-level authorisation is in the query**, not in an `if` after the fetch:
  `findByPublicRefAndAccountId(...)`. Guest order lookup requires reference **and**
  email, because a reference alone turns up in screenshots and support chats.
- CSRF tokens are unnecessary and absent **because** nothing authenticates from a
  cookie the browser will attach cross-site — `SameSite=Strict` on the only cookie
  that exists. Reintroduce them the moment that changes.
- CORS: exact origins, never a wildcard, never origin reflection.
- Rate limits with separate budgets for quotes (price scraping), auth (credential
  stuffing) and order creation (card testing). The webhook is exempt — dropping it
  means an order sits unpaid while the money has moved — and is protected by its
  signature instead.
- Security headers on both tiers: CSP, HSTS, `X-Content-Type-Options`,
  `frame-ancestors 'none'`, `Referrer-Policy`, `Permissions-Policy`. The storefront
  CSP allows scripts only from itself and Razorpay Checkout, and `connect-src` is
  narrow enough that an injected script has nowhere to send what it steals.
- Error responses carry a stable code, a customer-safe message and a trace id.
  Stack traces, SQL and constraint names go to the log, never the wire.
- Actuator exposes `health` and `info` only. `/env` and `/heapdump` between them
  contain every secret the process holds.
- No secret has a default. A missing key stops startup rather than falling back to a
  development constant that then ships.

### Known gaps, stated plainly

- **Rate-limit buckets are in-memory.** Correct for one instance, wrong for two —
  each replica would grant the full budget. Swap for `bucket4j-redis` behind a load
  balancer; the call sites do not change.
- **Scheduled jobs run on every instance.** Add ShedLock over the same Postgres, or
  pin them to one node, before scaling out. The idempotency guards would catch a
  double-settle, but relying on them as the primary control is a hope, not a design.
- **The legal pages are working drafts.** They accurately describe what the software
  does and are modelled on what comparable operators publish, which makes them a sound
  starting point — but the jurisdiction clause, the consumer-law position under Indian
  law and the AML thresholds need a solicitor before the business trades on them.
- **OAuth sign-in is not built.** The `account` table has the columns; see below.

---

## Coaching

Sold two ways — a single 40-minute session (₹900) or a six-session block (₹4,050, ₹675
each) — and fulfilled by a calendar rather than by the operator queue. Buying grants
**session credits**; booking spends one. That split is what makes the product work: the
customer pays once and books at their own pace, and a reschedule never has to touch
money.

**Coaching never touches the credential vault.** `Sku.COACHING` is excluded from
`mayRequireCredentials()` and its delivery method is its own constant,
`SCHEDULED_SESSION`. Coaching is teaching — the customer plays their own account while a
coach watches — so the entire highest-severity subsystem is bypassed rather than merely
unused.

### The three things that make booking hard, and where each is handled

**Timezones.** A coach's availability is stored as a weekday and two *local* times
(`AvailabilityRule`), never as instants, together with the coach's IANA zone. Freezing
today's offset into a UTC instant means the whole schedule slides by an hour the next
time the region changes clocks — the coach keeps their stated hours on paper while the
calendar quietly starts selling 17:00 slots. `SlotPlanner` resolves local times against
the zone at generation time and is verified against a real spring-forward transition.

**Double-booking.** Sessions are 40 minutes on a 30-minute grid, so 18:00 and 18:30 are
different start times that overlap by ten minutes. A unique index on
`(coach_id, starts_at)` looks sufficient and is not. The guard is a Postgres exclusion
constraint over `tstzrange`, so two customers confirming adjacent slots in the same
instant is settled by the database rather than by a check the application cannot win.

**Credit accounting.** `session_credit` is an append-only ledger, like the points wallet.
Credits are held at **booking**, not at attendance — otherwise one credit could hold six
slots at once. Every terminal session state settles exactly once, enforced by unique
indexes on `(session_id, entry_type)`, and whether the credit came back is *stored* on
the session rather than re-derived, so a later policy change cannot rewrite what a
customer was told when they cancelled.

Credits are granted at `PAID`, unlike loyalty points, which wait for the guarantee
window. Somebody who has bought sessions needs to book them now; making them wait a week
would make the product unusable.

## Loyalty

Two schemes on one ledger:

**Points**, as before — 20 per ₹2,000 spent, worth ₹1 each, capped at 20% of an order.

**Six tiers**, on lifetime points: Bronze → Silver (500) → Gold (2,000) → Elite (5,000)
→ Platinum (8,000) → Diamond (10,000), discounting 0–5% automatically at checkout. Plus
a daily check-in worth 3 points, idempotent per account per day at the database level.

Three rules stop the ladder being farmable, and they are encoded on
`PointsEntryType.countsTowardLifetime()` rather than in a query somewhere:

- **Spending never demotes.** `REDEEMED` does not touch lifetime. Withdrawing status for
  using the reward we granted is a support ticket waiting to happen.
- **Refunds do not re-earn.** `REFUND_REVERSAL` does not count either — the original
  spend never reduced lifetime, so crediting it back would let redeem-refund-repeat climb
  to Diamond for free.
- **Clawback reverses status.** It counts, negatively. Otherwise: place a large order,
  let it complete, bank the tier, reverse it, keep the discount forever.

The tier discount **stacks** with the points rebate — roughly 6% of gross at Diamond for
a customer redeeming their balance. That is a margin decision rather than an
architectural one, so `GFS_PRICING_TIER_DISCOUNT_ENABLED=false` switches the discount and
its quote line off without a redeploy, while tiers and statuses carry on accruing.

The ladder is served from `GET /api/v1/catalog/policy` and rendered by the rewards page,
for the same reason all the other copy is: a tier table written out in JSX is a second
copy of the ladder, and the second copy is the one that stops matching.

## Configuration

Everything is bound and validated at startup into `AppProperties`. `.env.example`
documents every variable; the ones that matter most:

| Variable | Purpose |
| --- | --- |
| `GFS_JWT_SECRET` | Access-token signing. ≥32 bytes. |
| `GFS_QUOTE_SECRET` | Quote signing. **Different value** from the JWT key. |
| `GFS_CREDENTIAL_MASTER_KEY` | Base64 of exactly 32 bytes. Wraps every per-order data key. |
| `GFS_RAZORPAY_WEBHOOK_SECRET` | **Not** the API key secret. Set separately in the Razorpay dashboard. |
| `GFS_REQUIRE_HTTPS` | `true` in every deployed environment. Marks cookies `Secure`, emits HSTS. |
| `GFS_MARKET_TAX_BPS` | EA transfer market tax. `500` = 5%. |
| `GFS_GATEWAY_FEE_BPS` | Processing fee. `250` = 2.5%. |
| `GFS_GATEWAY_FEE_MODE` | `ABSORBED`, `PASS_THROUGH` or `GROSS_UP`. |
| `GFS_PRICING_TIER_DISCOUNT_ENABLED` | Loyalty-tier discounts. Stacks with the points rebate — see [Loyalty](#loyalty). |
| `GFS_LOYALTY_BONUS_ZONE` | Whose midnight ends a loyalty day. **Not** UTC. |
| `GFS_COACHING_CHANGE_CUTOFF` | Notice needed to move or cancel a session and keep the credit. |
| `GFS_COACHING_CREDITS_PER_VARIANT` | Sessions each rate-card variant grants. A new pack is a row plus a line here. |
| `GFS_SEASON` | `FC26`. Drives rate-card lookup, page copy and meta tags. |

### Prices are data, not code

Rate cards are **temporal**. Changing a price closes the current row
(`valid_to = now()`) and inserts a new one — never an `UPDATE`. You get a free
price-history audit trail, an order placed last month stays explainable, and a
mistaken change is undone by making another one rather than restoring a backup. A
partial unique index guarantees at most one live row per sellable combination.

Seeded for FC 26 from the client's own numbers: ₹700/M on PC, ₹600/M on console,
Champs win tiers ₹1,200–₹3,550, Rivals division pushes ₹750–₹2,900.

**The season is a config value.** The reference site this business is modelled on
still has "FC 25" in its meta description while every heading says FC 26 — that is
what hard-coding a season costs, every September.

### Marketing copy is rendered from the pricing engine's configuration

`GET /api/v1/catalog/policy` returns the guarantee window, earn rate, point value,
redemption cap and refund fee, and the storefront renders its rewards, guarantee and
fee copy from it. That same reference site advertises "5% cashback on every order" on
its homepage while its rewards page tops out at a 5% *discount tier* — the copy
drifted from the rules engine and nobody noticed. Here it cannot.

---

## Testing

```bash
cd backend
./verify-domain.sh     # 107 assertions, JDK only, no network — run this first
mvn test               # the same ground as JUnit, plus more
```

```bash
cd frontend
npm run typecheck      # strict TS, noUnusedLocals, noUncheckedIndexedAccess
npm run build
```

The test suite concentrates on the parts where a bug costs money rather than chasing
a coverage number: pricing arithmetic across a 4,000-quote sweep, discount and wallet
interaction, all three fee modes, rounding reconciliation, quote signing and every way
of tampering with one, envelope encryption including tamper and wrong-key cases, every
legal and illegal state transition, and both Razorpay signature checks including their
fail-closed behaviour.

---

## Deploying

1. **Set every secret.** Different values per environment. Startup fails otherwise.
2. **`GFS_REQUIRE_HTTPS=true`** and terminate TLS in front.
3. **`GFS_SWAGGER_ENABLED=false`** in production. An endpoint catalogue is a gift to
   anyone probing the API.
4. **Register the webhook** at `https://your-api/api/v1/payments/webhook/razorpay`
   and put its secret in `GFS_RAZORPAY_WEBHOOK_SECRET`. Without it, paid orders are
   never marked paid.
5. **Set the API origin in `frontend/nginx.conf`'s `connect-src`** and build the
   storefront with `VITE_API_BASE_URL` pointing at it.
6. **Razorpay account in the client's name**, KYC'd to his business. You should not
   be the merchant of record for someone else's revenue.
7. **Back up Postgres.** The credential vault is deliberately unrecoverable without
   the master key — losing the key loses only in-flight sign-ins, but losing the
   database loses the order history that defends a chargeback.

Both images run as non-root, carry health checks, and ship without their build
toolchains.

---

## Operating it

The admin console at `/admin` is the screen the business lives in:

- **Queue** filtered by state, with counts across the top — the first question every
  morning is "what needs doing", not "how are we trending".
- **Order detail** with the frozen price breakdown, the full event timeline and the
  transitions the state machine permits from here. `PAID`, `COMPLETED` and
  `ABANDONED` are deliberately absent: an operator must not be able to mark an unpaid
  order paid "just to unblock the customer".
- **The vault**, opened per order, with an audit entry naming who opened it and when.
- **Rate cards** with full price history (ADMIN only — an operator fulfils orders;
  changing what the business charges is a different authority).

Five scheduled jobs run the clock. Two for orders: hourly guarantee settlement (which
grants points, accrues commission and purges the vault) and an abandoned-checkout sweep
with a deliberately generous cutoff, because people go and find their card and come back
twenty minutes later. Three for coaching:

- **Session settlement**, hourly. Sessions nobody recorded an outcome for are closed as
  `COMPLETED` after 48 hours, **not** as `NO_SHOW`. A session still sitting in `SCHEDULED`
  a day later usually means the coach forgot to click a button, not that the customer
  failed to appear — and guessing "no-show" would quietly charge customers a session for
  the coach's admin. The sweep errs in the customer's favour; an operator who knows better
  records the truth first.
- **Credit expiry**, nightly. Writes an `EXPIRED` entry rather than deleting the grant, so
  the statement still explains where the credits went.
- **Reminders**, hourly, for sessions starting in roughly a day — rendered in the zone the
  customer booked in, with the zone named in the message.

---

## What is deliberately not built yet

`/cards` ships as a **"Coming soon"** page. It is priced in the rate card and the pricing
engine refuses to quote it — one enum flag turns it on.

**Coaching is live.** It is the one place this product is ahead of the reference site
rather than level with it: that site has a Trustpilot presence, an AML programme, a
six-tier loyalty engine and a 30% affiliate dashboard, and its `/coaching` page is still
a placeholder. See [Coaching](#coaching) below.

Also deferred, and worth quoting separately:

| Deferred | Why |
| --- | --- |
| Google / Discord / X sign-in | The `account` table has `oauth_provider` and `oauth_subject` with a partial unique index; the flows are a day of redirect-URI archaeology for near-zero conversion lift over guest checkout |
| Multi-currency at checkout | USD rate cards are seeded and the engine is currency-agnostic. Needs Razorpay international activation first. |
| Localised languages | Backend messages would move to `MessageSource`; frontend to `react-i18next`. Ship English + INR, add a locale when a real customer asks. |
| LLM support chat | Should be retrieval-grounded over the FAQ with exactly two tools (`lookupOrder`, `getRate`), hard escalation on refunds and guarantee claims, and a filter that drops anything resembling a password before it reaches a log. |
| Sell-coins buyback | A separate risk profile, KYC burden and payout rail. The reference site runs it as a **separate application** for exactly that reason. |

---

## Repository layout

```
backend/
  src/main/java/com/globalfutservice/
    domain/          framework-free core — money, pricing, state machines, crypto,
                     the loyalty ladder, slot planning
    catalog/         temporal rate cards
    pricing/         quote minting and verification
    orders/          lifecycle, audit trail, scheduled jobs
    payments/        gateway client, webhook, idempotency
    credentials/     the vault and its purge job
    loyalty/         points ledger and the tier ladder
    coaching/        coaches, availability, bookings, session credits
    affiliate/       creator codes and commission
    identity/        accounts, sessions, token rotation
    notify/          WhatsApp Cloud API, email
    admin/           operations console API
    security/        JWT, rate limiting, principal resolution
    config/          properties, security config, bootstrap
  src/main/resources/db/migration/   Flyway
  tools/ + verify-domain.sh          offline domain verification

frontend/
  src/brand/         logo and design tokens
  src/lib/           API client, formatting, SEO, Razorpay loader
  src/state/         auth and catalogue providers
  src/components/    UI primitives, header, footer
  src/pages/         storefront, checkout, account, admin console
  tools/mock-api.mjs UI development without a backend
  nginx.conf         caching and security headers
```

---

*Built for Global FUT Services. In-game currency, items and player cards are the
property of Electronic Arts Inc.; this software provides trading and account services
and is not affiliated with EA.*
