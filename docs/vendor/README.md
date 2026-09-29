# FUT Transfer API collection

`fut-transfer.postman_collection.json` is FUT Transfer's own Postman collection. It is the source of truth for the fulfilment integration in `backend/src/main/java/com/globalfutservice/fulfilment`. The integration uses only what this collection documents.

- **Source:** the public collection published by FUT Transfer on the Postman API Network (collection `37015796-17c57997-73c7-415f-86c7-bf7fc1cc394c`).
- **Retrieved:** 29 September 2026.
- **Format:** Postman Collection v2.1, converted from the published collection with nothing added or removed. It has 10 folders, 71 requests, 113 example responses and 31 variables. Every request body and URL is identical to the published one.

## No credentials

Checked before committing:

- **Auth:** there are no auth blocks at collection, folder or request level. Every request sends `apiUser` and `apiKey` as the placeholders `{{apiUser}}` and `{{apiKey}}`.
- **Variables:** only placeholders or empty values, for example `your_email@example.com` and `ENTER_MD5_HASH_OF_API_KEY_HERE`.
- **Sample values:** every email address is on `example.com` or `test.com`. The passwords and backup codes in the example bodies are obvious samples, such as `hellogello123` and `12345678`.
- **Other values:**
  - The one 32-character hex value is the vendor's example of a buyer-email MD5.
  - The proxy IP is their example.
  - The three request scripts are test assertions and variable setters.

Never commit a copy with real values filled in. Set `apiUser` and `apiKey` locally in Postman instead.

## Base URLs

The vendor offers `https://futtransfer.top/` and `https://eatransfer.top/`, and recommends using one as primary and the other as backup. Only status reads may fall back to the backup. Placing an order (`/orderAPI`) never does, because re-sending it anywhere after a timeout could create a second order.
