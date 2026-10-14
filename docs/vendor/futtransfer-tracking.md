# FUT Transfer: customer tracking links and order status

FUT Transfer sent these notes directly. They are not in `fut-transfer.postman_collection.json`, the published collection in this folder. Every credential below is a placeholder: never put a real API user, key or order id in this file.

## 1. Tracking link

Once an order is created, save the `orderID` that comes back. Then call:

```
POST https://futtransfer.top/generateOrderStatusLink
{ "apiUser": "your_email@example.com", "apiKey": "MD5_HASH_OF_API_KEY", "orderID": "ORDER_UUID" }
```

The response:

```json
{ "success": true, "shortCode": "abc12345", "shop": "", "specialParams": "",
  "fullUrl": "https://transferstatus.top/abc12345", "orderID": "ORDER_UUID" }
```

- **Save `fullUrl` exactly as returned,** including any branding or language parameters.
- **Where it goes:** include it in the confirmation email and behind the site's "Track order" button.
- **When to generate it:** straight after the order is created. Non-admin users cannot generate links for orders already marked finished.
- **Who may see it:** only the customer whose order it is. The link also lets the customer update their account credentials.

## 2. Status

To read one order, send the same auth fields plus `orderID` to `POST /orderStatusAPI`. The fields:

| Field | Meaning |
| --- | --- |
| `status` | The order's lifecycle stage. |
| `simplifiedStatus` | `pending`, `completed` or `error`. |
| `amountOrdered` | Coins ordered, in thousands. |
| `amount` | Coins delivered, in thousands. `250` means about 250,000 coins delivered. |
| `accountCheckLong` | Human-readable detail. |
| `economyStateLong` | Human-readable detail. |

`accountCheck` "finished" means the account check has finished. It does **not** mean delivery is complete.

To read several orders at once: `POST /orderStatusBulkAPI` with `{ "apiUser": "...", "apiKey": "...", "orderIDs": ["ORDER_UUID", "..."] }`.

## How this compares with the published collection

- **`/generateOrderStatusLink`:** not in the collection. Its refusal for a finished order has no documented status or body.
- **`simplifiedStatus`:** not in the collection.
- **`accountCheckLong` and `economyStateLong`:** appear only in the collection's example response, not in its field table.
- **Bulk limit:** the collection says `/orderStatusBulkAPI` takes **up to 20** order IDs. That limit is used.
- **Status values:** the collection documents `status` as one of `ready`, `entered`, `partlyDelivered`, `finished`, `waitingForAssignment` and `interrupted`.

## How we use it

- **Generating the link.** `PartnerTrackingLinks` asks for the link once the order's id is committed. This is a separate step from creating the order, and it uses the domain the order was created on (the primary).
  - If the call fails, the order is untouched. Each poll asks again, 1, 2, 4, 8… minutes apart (at most hourly), until it succeeds or the order is finished.
  - A refusal that mentions the order being finished is recorded as "link unavailable", and nothing is retried. A 403 on this call never pauses calls to the partner.
  - Orders sent before this existed are picked up by the same poll (the backfill).
  - Admins can ask for it now with **Generate link** on the order page.
- **Storing it.** `fullUrl` is kept verbatim on `vendor_order`, and only an `https://` address is accepted. We never build or edit the link.
- **Keeping it secret.**
  - Only the signed-in owner of the order sees it, through their order page and one email.
  - It is never written to the call log or the application log, and never posted to Discord.
- **The emails.** Each is claimed in the database before it is sent, so each goes at most once per order, however often or from however many places the sweeps run.
  - Link stored within 5 minutes of the transfer starting: only "Your coins are on the way". "Your coin transfer has started" is settled as not needed.
  - Otherwise: "Your coin transfer has started" at 5 minutes, as before, then "Your coins are on the way" when the link arrives. "On the way" never goes before "started" has been settled.
- **Status reads.** The poller reads open orders with `/orderStatusBulkAPI`, 20 at a time. If a bulk read fails (other than a rate limit or refused credentials), it reads the same orders one by one with `/orderStatusAPI` by `orderID`.
- **Amounts.** They stay in thousands from the partner to the order page. The page turns them into coins in one place.
- **Delivery.** An order is DELIVERED only when the partner reports it finished (`status` "finished" or `simplifiedStatus` "completed") with at least the ordered amount delivered. More than ordered is delivered too, with an admin alert. Finished with less delivered goes to Needs review, with the alert "Partial delivery: X of Y K".
