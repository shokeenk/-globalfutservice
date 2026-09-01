# Pre-launch checklist

Work through this before the client's first real customer. Items are ordered by what
goes wrong if you skip them.

## Blocking — do not take a real payment without these

- [ ] **Three distinct secrets generated per environment.** `GFS_JWT_SECRET`,
      `GFS_QUOTE_SECRET`, `GFS_CREDENTIAL_MASTER_KEY`. Never the same value twice, never
      reused between staging and production.
- [ ] **`.env` is not in git.** `git log --all -p -- .env` should return nothing. If it
      ever was committed, rotate every secret — deleting the file does not delete the
      history.
- [ ] **`GFS_REQUIRE_HTTPS=true`** and TLS terminated in front. Without it the session
      cookie is not marked `Secure` and will travel in clear over any plain-HTTP hop.
- [ ] **Razorpay webhook registered** at `/api/v1/payments/webhook/razorpay`, with its
      secret in `GFS_RAZORPAY_WEBHOOK_SECRET`. This is a *different* value from the API
      key secret. Without it, customers pay and orders stay unpaid — silently. The
      application warns about this at startup; read the startup log.
- [ ] **One real end-to-end payment in Razorpay test mode**, watched all the way to
      `PAID` in the admin console. Then one in live mode, for a small amount, refunded.
- [ ] **`GFS_SWAGGER_ENABLED=false`** in production.
- [ ] **Bootstrap admin password changed**, and the two bootstrap environment variables
      cleared afterwards.
- [ ] **Postgres backups running and a restore tested.** An untested backup is a belief,
      not a backup.

## Commercial — settle these with the client before launch, not after

- [ ] **Comfort trade or player auction?** If he only ever does player auction, the
      credential vault never holds anything and most of the risk in this system
      evaporates. Confirm it rather than assuming.
- [ ] **Is the 5% the EA market tax, or margin?** It changes the invoice line label —
      "EA transfer market tax" is a pass-through, "service fee" is not — and whether it
      should apply to boosting at all.
- [ ] **Refund fee.** Currently 5% (`GFS_REFUND_FEE_BPS`). It has to be in the Terms
      before the first order, not after the first dispute.
- [ ] **Guarantee remedy.** Currently 50% cash or 100% store credit. The asymmetry is
      deliberate — credit costs capacity rather than cash and keeps the customer — but
      it is his money, so he decides.
- [ ] **Who staffs "24/7"?** The homepage says traders are on shift around the clock. If
      that is one person in IST, soften the copy. A delivery promise on a homepage is a
      contractual representation.
- [ ] **Razorpay account in the client's business name**, KYC'd to him. You should not be
      the merchant of record for someone else's revenue.
- [ ] **Merchant category and business description** match the Terms of Service word for
      word: trading, consultation and account services. A mismatch found during a risk
      review is how these accounts get terminated.

## Coaching

- [x] **Vinay is seeded, with availability** — `V9__seed_coach_vinay.sql`, 2026-08-24.
      `Asia/Kolkata`; weekdays 18:00–23:00, weekends 11:00–14:00 and 17:00–23:00. That
      is 9 bookable starts on a weekday and 16 at the weekend, on the 30-minute grid.
      Verified against the real `SlotPlanner` before it shipped.

      Seeded in a migration rather than through the API because there is **no admin UI
      for coaches** — `AdminCoachingController` exposes the endpoints and nothing in the
      console calls them. Changing his hours today means an API call or SQL, not a
      screen. That gap is worth closing before a second coach exists.

- [ ] **Write Vinay's headline, bio and credentials.** Deliberately left NULL. They
      render on the coach card as claims about a real person's rank and experience and
      were not ours to invent — the card degrades to name, languages and timezone until
      somebody who knows fills them in.
      `POST /api/v1/admin/coaching/coaches/{id}`

- [ ] **Confirm his languages.** Seeded as "English, Hindi" — an inference, not a fact
      anyone supplied.

- [ ] **Adding a second coach** — `POST /api/v1/admin/coaching/coaches` (ADMIN), then
      `POST /api/v1/admin/coaching/coaches/{id}/availability`. Windows are in the
      **coach's own local time**, and a window crossing midnight is two windows on two
      days.
- [ ] **Confirm the single-session price.** Seeded at ₹900 against the ₹4,050 six-pack
      (₹675 each) — a 33% premium that makes the block a 25% saving. It was chosen, not
      derived; the client should agree it.

## Pricing — signed off

- [x] **USD, EUR and GBP price lists approved, 2026-08-24.** The header of
      `V7__multi_currency_price_list.sql` still calls them drafts; that text is now
      historical and is left alone on purpose — Flyway runs with
      `validate-on-migrate: true`, so editing an applied migration changes its checksum
      and fails startup on every database that already ran it. This file is the record.

      They are live: the currency switcher is in the header, and a visitor choosing USD
      is charged the seeded USD amounts.

      To change one later, **close the row and insert a new one** — never `UPDATE`. The
      rate cards are temporal (see `V2`), and editing a price in place rewrites the price
      of orders already taken against it.
- [ ] **Decide where sessions actually happen.** Discord, Meet, something else. The
      `meeting_url` on a session is currently set by an operator per session — if it is
      always the same link, that wants automating before it becomes a nightly chore.
- [ ] **Coaching in the Terms.** The cancellation cutoff (12h), reschedule limit (2) and
      credit validity (30 days) are enforced in code and shown on the page, but they are
      consumer-facing promises and belong in the written Terms too.
- [ ] **Who coaches, and are they staff or contractors?** Different tax and liability
      position from a trader, and the answer affects the AML/KYC page.

## Loyalty tiers — confirm the margin before switching on

- [ ] **The tier discount stacks with the points rebate.** A Diamond customer redeeming
      their balance costs roughly **6% of gross** — 5% tier plus ~1% effective rebate.
      That is real margin, and the ladder was matched to a competitor rather than derived
      from this business's numbers. Confirm the client is happy before launch, or set
      `GFS_PRICING_TIER_DISCOUNT_ENABLED=false` and ship points alone.
- [ ] **Daily check-in points.** 3/day is 1,095 a year, or ₹1,095 of redeemable credit
      per active account for no purchase at all. Cheap for engagement, not free.

## Legal

- [ ] **A solicitor reads `/terms`, `/privacy` and `/aml-kyc`.** They accurately describe
      what the software does and are modelled on what comparable operators publish, which
      makes them a sound draft. The jurisdiction clause, the consumer-law position under
      Indian law and the AML thresholds are not drafting choices — they are regulatory
      ones.
- [ ] **DPDP Act position confirmed** for the personal data held: email, phone, and the
      EA sign-ins held transiently.

## Before scaling past one instance

- [ ] **Rate-limit buckets moved to Redis** (`bucket4j-redis`). In-memory buckets give
      each replica the full budget.
- [ ] **Scheduled jobs locked** (ShedLock over the same Postgres) or pinned to one node.
- [ ] **Structured JSON logging** with the trace id from `ApiError` as a field, so a
      customer quoting a reference can be found in one query.

## Worth doing early, not urgent

- [ ] Uptime check on `/actuator/health` with an alert that reaches a phone.
- [ ] An alert on orders sitting in `ON_HOLD` for more than a few hours — it is the
      state that silently accumulates unhappy customers.
- [ ] An alert on the un-purged credential count climbing, which means orders are
      getting stuck before delivery.
- [ ] A dead-letter review of `webhook_event` rows with a non-null `process_error`.
