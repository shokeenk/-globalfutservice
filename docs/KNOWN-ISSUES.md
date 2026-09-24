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

## 3. Confirm the Resend SMTP password is the API key

**Where:** deployment configuration, not code.

Nothing in this codebase speaks to Resend's HTTP API; the only mail path is SMTP
through `JavaMailSender`. For Resend's SMTP endpoint the username is the literal
string `resend` and **the password is the API key**. There is no separate API-key
setting to configure and none is read.

So if `GFS_SMTP_PASSWORD` holds anything other than a Resend API key,
authentication fails and no mail is sent. This is worth confirming directly
rather than inferring from a message that happened to arrive.

Note also that `EmailSenderCheck` deliberately stays silent when the SMTP
username is not an email address, because a provider proves its right to send in
DNS rather than by the login. That silence is correct, and it means this
particular mistake will not be caught at startup.

---

## 4. `CampaignSender` never checks whether email is enabled

**Where:** `backend/src/main/java/com/globalfutservice/marketing/CampaignSender.java`

`GFS_EMAIL_ENABLED` gates `EmailNotifier` and `OperatorEmailNotifier`. It does not
gate campaigns. A campaign sent while the flag is off still hands every message to
`JavaMailSender`, and each one fails against whatever relay is configured and is
recorded as `FAILED` against its recipient row.

**When it bites:** any campaign sent while the flag is off burns its recipient
rows — they are marked `FAILED`, not left `PENDING`, so a later re-send does not
retry them.

**What it would take:** decide what the flag means. Either it is a master switch
for all outbound mail, in which case campaigns must respect it and refuse to
start; or it governs transactional mail only, in which case say so where it is
defined, because the current name does not suggest it.
