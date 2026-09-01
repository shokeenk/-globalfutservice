# Handover notes

For whoever runs this after you — most likely the client, occasionally you at 2am.

## The five things that will confuse someone new

**1. Prices are never updated in place.**
Changing a rate closes the current row (`valid_to = now()`) and inserts a new one. If
you `UPDATE rate_card SET unit_price_minor = ...` by hand you will break the partial
unique index and lose the price history that makes old orders explainable. Use the
admin screen, or insert properly.

**2. Nothing calls `order.setStatus(...)`.**
Every lifecycle change goes through `OrderService.transition`, which validates against
`OrderStateMachine` and writes an `order_event` row. If you need a new status, add it
to the enum *and* to the transition map, or nothing will be able to reach it.

**3. Orders become paid from the webhook, never the browser.**
If a customer says they paid and the order says `AWAITING_PAYMENT`, the question is
"did the webhook arrive?", not "did the redirect work?". Check `webhook_event` for the
delivery. If it is absent, the webhook is misconfigured; if it is present with a
`process_error`, there is a bug and the delivery can be replayed.

**4. Points and commission settle an hour after the guarantee window, not at payment.**
An order that looks "complete" to a customer sits in `DELIVERED` for seven days first.
That is not a bug, and it is why there is no clawback code.

**5. The credential vault is genuinely unrecoverable.**
Lose `GFS_CREDENTIAL_MASTER_KEY` and every sealed sign-in becomes noise. That is the
intended property. Back the key up somewhere that is not the same place as the database.

## Common support situations

| Situation | Where to look |
| --- | --- |
| "I paid but nothing happened" | `webhook_event` for the delivery; `payment.status` |
| "The price changed while I was paying" | Expected: quotes expire in ten minutes and re-price. The order's `price_breakdown` shows exactly what was agreed. |
| "My order is on hold" | `order_event.reason` — almost always account online, market locked, or unassigned items |
| "I was banned" | `orders.guarantee_expires_at`. Inside the window, move to `DISPUTED`, then `REFUNDED` or `CREDITED`. |
| "Where are my points?" | They settle at `COMPLETED`. Check `points_ledger` and `orders.points_settled_at`. |
| Duplicate charge | `payment` rows for the order. The amount check in `PaymentApplier` flags mismatches as `amount_mismatch`. |

## Changing the season for FC 27

1. `GFS_SEASON=FC27`, `GFS_SEASON_YEAR=27`.
2. Insert new `rate_card` rows with `season = 'FC27'`.
3. That is all. Headings, meta tags, order references and rate lookup all read the
   config value. There is no hard-coded season anywhere — which is exactly the mistake
   the reference site made, and it still has "FC 25" in its meta description.

## Turning on a service that is currently "coming soon"

Set `sellable = true` on the SKU in `com.globalfutservice.domain.catalog.Sku`. The
prices are already in the rate card and the storefront renders from the catalogue, so
the card stops saying "Coming soon" and becomes orderable. Coaching additionally needs
the booking system, which is not built.

## Whose accounts everything runs on

Two phases, and they have different answers. Getting this wrong is cheap to fix during
testing and expensive to fix afterwards.

### While it is a demo — the developer's accounts are fine

A throwaway test deploy on the developer's own Vercel, Render and Neon accounts is the
sensible way to get a link in front of the client quickly. Nothing about it is permanent,
and the whole stack can be recreated in under an hour from `render.yaml` and
`frontend/vercel.json`.

Two conditions, though, and both matter:

- **Razorpay stays in test mode.** Test keys move no money and need no KYC.
- **No real customer data goes in.** Seed it yourself, or use addresses you control. The
  moment a real person places a real order, the section below applies.

### In production — the client's accounts, and one of them is not negotiable

**Razorpay must be the client's merchant account.** This is the hard constraint, not a
preference. A payment gateway account is KYC'd against a real business entity, and
settlement goes to that entity's bank account. Taking a business's customer payments
through a developer's personal merchant account means the money lands in the wrong place,
the tax position is wrong, and under the RBI's payment-aggregator rules it is a licensing
question rather than a paperwork one. It will also, eventually, get the account frozen.

The rest follow from that, and each has a concrete reason:

| Account | Why it must be the client's |
|---|---|
| Razorpay | KYC and settlement. See above. |
| Domain registrar | Whoever holds the domain controls the business's identity and email. |
| Database host | It holds customer emails, order history and encrypted EA sign-ins. |
| API host | Holds `GFS_CREDENTIAL_MASTER_KEY` — see below. |
| Email sender | Sending as the business from a developer's mailbox breaks at handover. |
| Google OAuth | The consent screen shows the app owner's name — see below. |
| Discord OAuth | Same, and the app is what customers authorise. |

**Social sign-in is the client's app, and the reason is on the customer's screen.**
When somebody taps "Continue with Discord" they are shown a consent dialog naming the
application and linking to its privacy policy. If the app was created in a developer's
personal account, that screen asks the customer to authorise a stranger — which is a
strange thing to see on a page about buying coins, and a fair reason to abandon.

Three more consequences follow. Google will not lift the "unverified app" warning without
domain-ownership verification, and the domain is the client's, so only they can complete
it. Rotating a leaked secret, changing a redirect URI after a domain move, or answering a
customer's data-deletion request all require console access — through the developer, every
time, if the app is theirs. And the relationship the OAuth app creates is between Google or
Discord and the *business*, not the person who wrote the code.

The client creates both apps and adds the developer as a collaborator: Google Cloud has
IAM roles, Discord has app teams. The four secrets go into the API host's environment,
never into source control, and the developer holds them only while actively operating the
system — same rule as the master key below.

For local development, a throwaway app in the developer's own account pointed at
`http://localhost:8080/login/oauth2/code/{provider}` is fine and expected. It never sees a
real customer, and it gets deleted rather than promoted.

#### Where the four values go

Both consoles need the **callback URL registered exactly**, character for character —
a trailing slash or a missing `s` in `https` is the single most common reason a
correctly-credentialed sign-in fails, and the error surfaces at the provider rather
than in the application logs:

| Provider | Console | Redirect / callback URI |
| --- | --- | --- |
| Google | Cloud Console → Credentials → OAuth client → Authorised redirect URIs | `https://<api-host>/login/oauth2/code/google` |
| Discord | Developer Portal → OAuth2 → Redirects | `https://<api-host>/login/oauth2/code/discord` |

`<api-host>` is the API service, not the storefront domain. Add the `http://localhost:8080`
form of each as a second entry for local work — both consoles allow several.

The values themselves are environment variables on the API host, set in the hosting
dashboard rather than in any file:

```
GFS_GOOGLE_CLIENT_ID       GFS_GOOGLE_CLIENT_SECRET
GFS_DISCORD_CLIENT_ID      GFS_DISCORD_CLIENT_SECRET
```

Leave all four unset and social sign-in switches itself off cleanly: `/api/v1/auth/providers`
reports nothing, the register page renders no social buttons, and customers use the password
form. Setting a client id without its secret is the one broken state — the button appears
and the flow fails at the provider.

The two client IDs are public by design; they travel in the redirect URL and can be pasted
anywhere. **The two secrets are not.** They should never go over chat, email or WhatsApp,
and never into the repository. If one ever does, rotate it in the console — both providers
let you generate a replacement without recreating the app, and the old value stops working
the moment you do.

**The master key is the one people miss.** `GFS_CREDENTIAL_MASTER_KEY` wraps the per-order
keys that encrypt customers' EA sign-ins. Whoever can read that environment variable can,
with database access, decrypt those credentials. It should live in the client's hosting
account, and the developer should hold it only for as long as they are actively operating
the system.

There is a data-protection reading of the same point: the client is the data controller
for their customers' personal data. A developer holding all of it under personal accounts
is a liability for both parties, and one neither of them has agreed to in writing.

### The practical arrangement

The client creates the accounts and adds the developer as a collaborator. Vercel, Render,
Neon and Razorpay all support team members with scoped access. The client keeps ownership
and billing; the developer gets the access they need to work, and loses it cleanly when
the engagement ends. Nothing has to be migrated, because nothing was ever in the wrong
place.

If the client is not technical, the shortest path is a screen-share where they create each
account and immediately invite the developer. It takes about twenty minutes and removes
the entire migration problem.

## If you have to hand this to another developer

Point them at, in order: `README.md`, then `backend/verify-domain.sh` (run it — the
output tells them the money logic is intact), then
`domain/pricing/PricingEngine.java` and `domain/orders/OrderStateMachine.java`. Those
two files plus the README are about ninety percent of what anyone needs to be useful
here.
