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

**What we do now.** `GFS_FUTTRANSFER_SHOW_PARTNER_LINK` defaults to `false`. While it is off:
- customers get no partner Track order button;
- the "Your coins are on the way" email's TRACK ORDER button opens our own `/track?ref=` page;
- the link is still requested, stored exactly as returned and shown to admins, marked "hidden from customers".

Turning it on needs no backfill.

