# FUT Transfer: observed behaviour

This file records what FUT Transfer actually sent, where it differs from `fut-transfer.postman_collection.json` or `futtransfer-tracking.md`. Each entry has a date. Ids and references below are placeholders: never put a real order id, API user, key or tracking link here.

## 2026-10-14: public pool orders put economyState words in `status`

**What we saw.** A public pool order (`/buyCoinsAPI`, `targetedSnipe`) was read with `/orderStatusBulkAPI` about 40 seconds after FUT Transfer accepted it. The reply for that order:

```json
{ "status": "transfersInProgress", "simplifiedStatus": "pending",
  "accountCheck": "started", "accountCheckLong": "Awaiting account-check result",
  "economyState": "transfersInProgress", "economyStateLong": "Transfers In Progress",
  "amountOrdered": 50, "amount": 0, "coinsUsed": 0, "toPay": 0 }
```

The collection documents `status` as one of `ready`, `entered`, `partlyDelivered`, `finished`, `waitingForAssignment` and `interrupted`. `transfersInProgress` is one of the economyState values, not a status value.

**What it cost.** Our status map sent any status word it didn't know to Needs review, and an order in review was never read again. The order froze at "0K delivered" on our pages, while FUT Transfer's own page showed it delivering (11K / 50K about 40 minutes later).

**What we do now.**
- **Progress words in `status`** are read as their economyState meaning:
  - `transfersInProgress` and `transferCycleComplete`: delivering, the same as `partlyDelivered`;
  - `started` and `entered`: not started yet.
- **A failure word in `status`** is still a status we don't know, and goes to review.
- **A word we don't know never freezes an order.** An order in review is still read every 5 minutes while FUT Transfer has it open, and its delivered amount and status fields stay current. When a later report is fully understood, the order moves back where that report puts it, and its history notes the move. An admin is alerted once per unknown word per order.
- **DELIVERED** still comes only from the finished rules: `status` "finished" or `simplifiedStatus` "completed", with at least the ordered amount delivered.

## 2026-10-15: transferstatus.top short links answer 404

**What we saw.** Every link `/generateOrderStatusLink` returned was in its documented form, `https://transferstatus.top/<shortCode>`. Each one answered "404 - File or directory not found", an IIS error page served behind Cloudflare. The site's root page answered with an empty 200. The same code worked on FUT Transfer's own page, `https://futtransfer.top/orderStatus?code=<shortCode>`. FUT Transfer has been asked about it.

**What we did then** (superseded the same day, see below). `GFS_FUTTRANSFER_SHOW_PARTNER_LINK` defaulted to `false`. While it is off:
- customers get no partner Track order button;
- the "Your coins are on the way" email's TRACK ORDER button opens our own `/track?ref=` page;
- the link is still requested, stored exactly as returned and shown to admins, marked "hidden from customers".

Turning it on needs no backfill.

## 2026-10-15: FUT Transfer's answers

FUT Transfer answered our questions in writing. Their answers override what we had assumed from the collection.

### Tracking link

- Their short-link fix (`transferstatus.top/<shortCode>`) isn't live yet. Until it is, use `https://futtransfer.top/orderStatus?code=<shortCode>`, keeping parameters such as `shop`.
- We store `shortCode`, `shop` and `specialParams` from `/generateOrderStatusLink` beside the link, which we still keep exactly as returned. For links stored earlier, the code is taken from the link's path.
- `GFS_FUTTRANSFER_PARTNER_LINK_STYLE` chooses what customers get:
  - **`STATUS_PAGE`** (default): `https://futtransfer.top/orderStatus?code=<shortCode>`, plus `&shop=<shop>` when the shop isn't empty.
  - **`SHORT_LINK`**: the link as returned.
- An order whose link came with a non-empty `specialParams` gets the short link instead. Nothing says how to add `specialParams` to the status page, and the admin page says so for that order.
- `GFS_FUTTRANSFER_SHOW_PARTNER_LINK` is back to `true` by default.

### Status values

The overall `status` values are: `entered`, `ready`, `waitingForAssignment`, `transfersInProgress`, `partlyDelivered`, `interrupted`, `finished`, `paused`, `cancelled` / `canceled` and `archived`. **The list is not closed.**

`simplifiedStatus` is one of `pending`, `completed`, `error`, `interrupted` or `cancelled`.

What we do with each:

| Reported | Our state |
| --- | --- |
| `entered`, `ready`, `waitingForAssignment` (and `started`, observed earlier) | onboarding: SUBMITTED, or IN_DELIVERY once coins arrive |
| `transfersInProgress`, `partlyDelivered` (and `transferCycleComplete`) | delivering: IN_DELIVERY |
| `finished`, or `simplifiedStatus` `completed` | the existing finished rules (below) |
| `paused` | NEEDS_REVIEW (`PAUSED`). Still read every 5 minutes, and recovers by itself once a later report says it is moving again. |
| `cancelled` / `canceled`, or `simplifiedStatus` `cancelled` | NEEDS_REVIEW: `CANCELLED_PARTIAL` when some coins were delivered, `CANCELLED_NOTHING_DELIVERED` when none. No longer read. |
| `archived` | NEEDS_REVIEW (`ARCHIVED`). No longer read. |
| `interrupted`, or `simplifiedStatus` `interrupted` | NEEDS_REVIEW (`INTERRUPTED`) |
| `simplifiedStatus` `error` | NEEDS_REVIEW (`PARTNER_ERROR`) |
| a sign-in refusal in `status`, `accountCheck` or `economyState` | AWAITING_CUSTOMER, and the customer is asked to fix it |
| any word we don't know | NEEDS_REVIEW (`UNKNOWN_*`), read every 5 minutes, recovers once understood. Never treated as completion. |

DELIVERED comes only from the finished rules: `status` `finished` or `simplifiedStatus` `completed`, with at least the ordered amount delivered. Finished with less goes to review.

### Ordering (applies when sending public-pool orders)

- **`buyNowThreshold`**: a price **ceiling in EUR per 100K** for public-pool `targetedSnipe`. A positive value is required; 0 doesn't mean "unlimited". It's a maximum, not a fixed execution price.
- **`maxPrice`**: a guard for the standard supplier-buying flow only (0 = off). `targetedSnipe` doesn't use it.
- **Rate**: `/buyConditionAPI` with the platform (`PS`, which covers Xbox, or `PC`), then the supplier's `priceConsole` or `pricePC`. Re-check it just before sending. Never send the slider quantity or the order total as `buyNowThreshold`, and count tool and transfer fees only once.
- **`minTransferAmount`**: the API default is 0 (off); 50 means 50K per transfer. To turn it off, send `"minTransferAmount": 0` explicitly. `targetedSnipe` needs at least 6K in total.

### What we send (PR #124, 2026-10-15)

- **`buyNowThreshold`**, worked out on every send, first send or retry:
  1. Read `/buyConditionAPI` for the order's platform (`PS` for PlayStation and Xbox, `PC` for PC). This is the same call that reads our balance.
  2. Take the public `suppliers` with a price and stock on that side (`priceConsole`/`stockConsole`, or `pricePC`/`stockPC`, stock in K). Private suppliers are ignored.
  3. Sort them cheapest first. The **clearing rate** is the price of the supplier at which their combined stock first covers the order.
  4. Add `GFS_FUTTRANSFER_THRESHOLD_MARGIN_PCT` (default 10%) and round to 2 decimals.
  - With `GFS_FUTTRANSFER_MAX_EUR_PER_100K_CONSOLE` / `_PC` set: if the clearing rate is above the limit, nothing is sent and the order waits for Approve ("FUT Transfer price €X/100K is above your limit €Y"). If only the margin goes above it, the limit is sent.
  - A failed read, or not enough priced stock, sends nothing. Automatic sending tries again shortly.
  - The result is never 0, never negative, never missing, never the order's quantity and never our rupee price.
  - `GFS_FUTTRANSFER_BUY_NOW_THRESHOLD` is an emergency fixed value only. It must be positive and within the limit.
- **`minTransferAmount`**: 0 by default, always sent.
- **`maxPrice`**: not sent (`GFS_FUTTRANSFER_SEND_MAX_PRICE=false`).
- **Recorded per order** (V54): the supplier rate, the margin, the threshold sent and `transferFee`. Admin shows these with an estimated cost and what the customer paid.
  - Estimated cost = amount / 100K × (rate + `transferFee`), with the fee counted once.
  - **Unconfirmed assumption**: that `transferFee` is per 100K. `toolFee` is documented as applying to private suppliers only, so it is not added.
- **Still exactly one `/buyCoinsAPI` call per order.**

### Orders sent before the change: minTransferAmount at the partner (2026-10-15)

Orders sent before #124 went with `minTransferAmount` 50 (50K per transfer), and at least one was held up by it. `/editOrderAPI` is a partial update, so it can change that one field on an order FUT Transfer already has.

- **On deploy:** `V55__partner_settings_update.sql` marks every open public-pool order that FUT Transfer has. That means state `SUBMITTED`, `IN_DELIVERY`, `AWAITING_CUSTOMER` or `NEEDS_REVIEW`, with a partner id, and not reported `finished`, `cancelled`/`canceled` or `archived`.
  - About a minute after startup, each marked order is sent `/editOrderAPI` with `{"orderID": ..., "minTransferAmount": 0}`. Nothing else is sent.
  - Each order is claimed before its call, so it gets at most one call.
  - Each call is recorded in the order's call log and on its admin timeline as "Update settings at partner", by "automatic: settings update on deploy".
  - The orders it touched are logged on one line starting `FUT TRANSFER SETTINGS UPDATE:` and listed to staff.
  - If FUT Transfer asks us to wait (429), that order goes back in the queue and the run stops. It is tried on the next run, 10 minutes later. While calls are paused or FUT Transfer is off, nothing is claimed.
  - Orders sent from then on are never marked.
- **Admin button "Update settings at partner":** sends the same call for one order while the partner has it. Pressing it again is safe.
- **What FUT Transfer answers:** `{"outcome":"success","updatedFields":[...]}`. If `minTransferAmount` isn't among the `updatedFields`, the partner didn't apply it. Their docs say it only applies to `cycle` and `targetedSnipe` orders. We record that as `IGNORED`.

## 2026-10-15: GFS-26-ZTE3A759, a stop that didn't hold

**What happened.** The order was a 50K PlayStation order, sent before #124 with `buyNowThreshold` 50 (the order size) and `minTransferAmount` 50.
- FUT Transfer reported `partlyDelivered` / `finished` / `transferInserted`. `transferInserted` wasn't in our map, so the order went to review (`UNKNOWN_ECONOMY_STATE`).
- The next understood report brought it back to delivering, and the unknown one sent it to review again. It flipped every poll, and the admin buttons came and went.
- An admin pressed **Stop at partner**, and FUT Transfer confirmed it. The next report moved the order back to IN_DELIVERY, so **Resume** was refused ("while it is IN_DELIVERY").
- Meanwhile the review text said "20K of 50K" while the customer page said 28K, because the text wasn't refreshed while the order sat in review.

**What we do now** (codes in `futtransfer-codes.md`):
- **`transferInserted` is progress.** An `economyState` we don't know, while coins are moving and the delivered amount isn't going down, stays delivering, with one alert per code per order.
- **No flip-flopping.** Once an order has recovered from a review over an unknown code, the same code doesn't send it back.
- **A confirmed stop puts the order in STOPPED, recording who stopped it and when.** Reports still record what was delivered, but never move it back to delivering. Only the partner finishing it in full (delivered) does.
- **Resume at partner** is offered for stopped and held orders, and for any order FUT Transfer reports `paused`. A refusal says why, for example "Resume is only for stopped or held orders; this order is delivering."
- **The customer page:** a stopped order shows "Paused, our team is handling your order." with the progress so far.
- **The admin review box and "Held up by"** show the latest delivered/ordered and when it was reported. The review text follows each report while the reason stays the same, and the box goes once the order recovers.
- **Stopped orders are never resumed automatically.** An admin presses Resume.

**`/editOrderAPI` `cyclePrice`: not touched.**
- The collection describes it as "New price per 100K for cycle/targetedSnipe orders", and nowhere as the `buyNowThreshold` ceiling of `/buyCoinsAPI`.
- Because that is unclear, we don't send it. FUT Transfer still needs to be asked whether it is the same thing.
- `/editOrderAPI` is used only for `minTransferAmount` (#126).
