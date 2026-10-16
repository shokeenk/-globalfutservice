# FUT Transfer transfer codes: what each one means to us

**Date:** 2026-10-15 (GFS-26-ZTE3A759).

**Source:**
- the collection in this folder (dated 1 October);
- FUT Transfer's written answers of 2026-10-15 (see `futtransfer-observed.md`);
- what live orders reported.

**Missing:** FUT Transfer's own full list ("Transfer codes reference" / `/getTransferCodes`) isn't in this folder yet. When it arrives, compare it with this page and add any missing codes to `VendorStatusMap` (backend, `fulfilment/VendorStatusMap.java`). That file is the source of truth; this page mirrors it.

Codes are matched without regard to case.

**Meanings:**
- **progress:** the order is moving.
- **customer can fix:** the order waits for the customer, who is told what to do.
- **failure:** an admin decides.

## Overall `status`

| Code | Meaning | Our state |
| --- | --- | --- |
| `entered`, `ready`, `waitingForAssignment`, `started` | progress (onboarding) | SUBMITTED |
| `transfersInProgress`, `partlyDelivered`, `transferCycleComplete` | progress (delivering) | IN_DELIVERY |
| `finished` | finished | DELIVERED if all arrived, otherwise review |
| `paused` | paused at FUT Transfer | NEEDS_REVIEW (`PAUSED`). Still read, and Resume is offered. |
| `interrupted` | failure | NEEDS_REVIEW (`INTERRUPTED`) |
| `cancelled` / `canceled` | failure | NEEDS_REVIEW (`CANCELLED_PARTIAL` / `CANCELLED_NOTHING_DELIVERED`) |
| `archived` | failure | NEEDS_REVIEW (`ARCHIVED`) |
| `wrongUserPass`, `FailedWrongCredentialsTO` | customer can fix: sign-in | AWAITING_CUSTOMER |
| `wrongBA`, `FailedWrongBACodeTO` | customer can fix: backup codes | AWAITING_CUSTOMER |

`simplifiedStatus`:
- `pending`: no effect.
- `completed`: finished.
- `error`: NEEDS_REVIEW (`PARTNER_ERROR`).
- `interrupted`: NEEDS_REVIEW (`INTERRUPTED`).
- `cancelled`: as `cancelled` above.

## `accountCheck`

| Code | Meaning |
| --- | --- |
| `entered`, `started`, `finished`, `userPassVerified`, `correctBA`, `noUnassignedItemsPresent` | progress |
| `wrongUserPass` | customer can fix: sign-in |
| `wrongBA` | customer can fix: backup codes |
| `console` | customer can fix: sign out of the console |
| `unassignedItemsPresent` | customer can fix: clear unassigned items |
| `tlFull` | customer can fix: free transfer slots |
| `notEnoughCoins` | customer can fix: add coins |
| `captcha` | customer can fix: solve the captcha |
| `wrongPersona` | customer can fix: persona |
| `noTM`, `noClub`, `wrongConsole` | failure: account unusable |
| `loginFailedDeviceBan` | failure: banned |
| `loginFailed`, `FailedProxyConnectionError`, `FailedProxyPoolExhausted` | failure: retry (an admin resumes) |

## `economyState`

| Code | Meaning |
| --- | --- |
| `entered`, `started`, `transfersInProgress`, `transferCycleComplete`, `customerHasPlayer`, `customerListedPlayer`, `finished` | progress |
| **`transferInserted`** (new, observed 2026-10-15) | progress |
| `FailedWrongCredentialsTO` | customer can fix: sign-in |
| `FailedWrongBACodeTO` | customer can fix: backup codes |
| `FailLoggedInConsoleTO`, `FailedSessionExpiredCustomerLoggedIn?` | customer can fix: sign out of the console |
| `FailedTLFullReceiver` | customer can fix: free transfer slots |
| `FailNoClubToCanBeEAErrorTryAgain`, `FailWebappCustomerLocked`, `FailWebappNotYetUnlocked` | failure: account unusable |
| `FailedReceiverDeviceBan`, `FailPlayerLostToTempBan`, `FailListTempBanCustomer` | failure: banned |
| `insufficientFunds`, `calcErrorMaintenance`, `noPlayer`, `belowMinTransfer` | failure: FUT Transfer's side |
| `noSuitableSender` | failure: no seller or sender left (`NO_SUITABLE_SENDER`) |

## A code we don't know

- **`economyState`, while coins are moving:** this is when `status` is `transfersInProgress` or `partlyDelivered` and the delivered amount isn't going down. The order stays IN_DELIVERY, not review, and admins are alerted once per code per order. The code is shown on the admin order page under "Partner codes we don't know yet".
- **Anything else:** NEEDS_REVIEW, with one alert per code per order. The order is read again every 5 minutes and recovers once a report is understood.
  - Once an order has recovered, the same code never sends it back to review. Without this rule the order flipped between review and delivering on every poll.
