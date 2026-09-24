# Known issues

Defects found while investigating something else, confirmed by reading the code
but **not** the cause of whatever was being chased at the time. They are recorded
here rather than fixed on the spot, because a fix bundled into an unrelated
change is a fix nobody reviews.

Each entry says what is wrong, when it bites, and what it would take to close.
Delete an entry when it is fixed; do not delete one because it has gone quiet.

---

## 1. `CampaignSender.buildRecipients` cannot recover the way it intends

**Where:** `backend/src/main/java/com/globalfutservice/marketing/CampaignSender.java`

The method inserts one recipient row per person, and wraps the bulk insert in a
`try` so that a clash with the `(campaign_id, account_id)` unique constraint can
fall back to inserting row by row. The intent is right — a bulk insert fails
whole, so a resumed send would otherwise be unable to add the people it had not
reached yet.

The recovery cannot work where it is written. The whole method is one
`@Transactional`, and JPA defers the INSERTs to flush rather than issuing them
inside `saveAll`. The constraint violation therefore surfaces at the first thing
that forces a flush — the `countByCampaignId` call at the end of the method, or
the commit — and both are outside the `try`. The `catch` is likely never entered
at all. Worse, once a persistence exception has marked the transaction
rollback-only, continuing to use that session cannot succeed, so even entering
the `catch` would not help: the per-row `save` calls and the count would fail,
and the commit would throw `UnexpectedRollbackException`.

**When it bites:** only when a campaign is re-sent after a partial run, which is
the exact situation the fallback exists to handle. A first send has nothing to
clash with, so the code looks fine until the day it is needed.

**What it would take:** move the per-row insert to its own bean so each row is
its own transaction, in the same way `sendOne` already is, and let the constraint
reject duplicates one at a time. `CampaignSender`'s class comment already
explains why the committed steps live behind a real bean boundary; this method is
the one that did not follow it.

---

## 2. `CampaignService.readForSend` is `@Transactional` but the annotation does nothing

**Where:** `backend/src/main/java/com/globalfutservice/marketing/CampaignService.java`

`readForSend` is annotated `@Transactional(readOnly = true)` and is neither.

It fails twice over. It is `protected`, and Spring's transaction attribute source
ignores non-public methods, so no advice is ever attached. And it is called from
`sendNow` on `this`, which bypasses the proxy regardless of visibility — the same
trap `CampaignSender`'s class comment was written to warn about.

The method works today only because every repository call it makes opens a
transaction of its own. What it does not get is a single consistent read: the
status check, the sendable check and the audience count each see the database at
a different moment, so a campaign can be cancelled between the check that says it
is a draft and the dispatch that acts on it.

**When it bites:** a narrow race, and an unlikely one at current volume. It is
recorded because the annotation reads as a guarantee that is not there, which is
worse than no annotation — the next person to touch this will trust it.

**What it would take:** make the method public and move it behind a bean
boundary, or drop the annotation and say plainly that each read stands alone.

---

## 3. A campaign recipient that fails once is never retried

**Where:** `backend/src/main/java/com/globalfutservice/marketing/CampaignSender.java`

`sendOne` catches everything the send can throw and calls `row.markFailed(...)`.
The send loop in `CampaignService.dispatch` then pulls the next batch of rows
`where status = 'PENDING'` — so a row marked `FAILED` is not picked up again, by
this run or any later one. There is no retry, and no endpoint that moves a row
back to `PENDING`.

That is the right shape for a permanent failure, such as an address that does not
exist. It is the wrong shape for a transient one, and the transient case is the
likely one: a provider rate limit. Resend's free tier allows 100 messages a day
and 3,000 a month. An audience larger than the daily allowance does not queue —
the overflow is refused, marked `FAILED`, and silently excluded from every
subsequent attempt.

**When it bites:** the first campaign sent to an audience larger than the
provider's allowance, which is the first campaign that matters. Nothing warns
beforehand; the audience count is shown next to the Send button but is not
compared against anything.

**What it would take:** distinguish a refusal from a deferral, and leave the
deferred rows `PENDING` so the next pass picks them up. Failing that, an operator
action that resets `FAILED` rows for one campaign would at least make it
recoverable without SQL.

*Resolved while investigating this: whether `GFS_SMTP_PASSWORD` holds a genuine
Resend API key is no longer open. `CampaignSender` and `EmailNotifier` are given
the same `JavaMailSender` singleton with the same credentials, and transactional
mail is being delivered, so the credentials authenticate. Worth recording because
`EmailSenderCheck` deliberately stays silent when the SMTP username is not an
email address — a provider proves its right to send in DNS, not by its login — so
a bad key would never have been caught at startup.*

---

## 4. `CampaignSender` never checks whether email is enabled

**Where:** `backend/src/main/java/com/globalfutservice/marketing/CampaignSender.java`

`GFS_EMAIL_ENABLED` gates `EmailNotifier` and `OperatorEmailNotifier`. It does not
gate campaigns. A campaign sent while the flag is off still hands every message to
`JavaMailSender`, and each one fails against whatever relay is configured and is
recorded as `FAILED` against its recipient row.

**When it bites:** any campaign sent while the flag is off burns its recipient
rows, by way of the defect in 3 above — they are marked `FAILED`, not left
`PENDING`, so no later re-send retries them.

**What it would take:** decide what the flag means. Either it is a master switch
for all outbound mail, in which case campaigns must respect it and refuse to
start; or it governs transactional mail only, in which case say so where it is
defined, because the current name does not suggest it.
