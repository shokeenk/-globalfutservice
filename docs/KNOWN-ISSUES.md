# Known issues

Defects found while investigating something else, and **not** the cause of
whatever was being chased at the time. They are recorded here rather than fixed
on the spot, because a fix bundled into an unrelated change is a fix nobody
reviews.

Each entry says what is wrong, when it bites, what it would take to close, and
how it was established -- observed, reproduced, or only read in the code. A
defect read in the code is a defect that *would* behave this way; it has not
been seen to.

Delete an entry when it is fixed; do not delete one because it has gone quiet.

---

## 1. A campaign interrupted mid-send stays SENDING forever

**Where:** `CampaignService.dispatch`, `CampaignRepository.claimForSending`

A send claims its campaign by moving it to `SENDING` in one short transaction,
and only moves it on to `SENT` or `FAILED` when the whole list has been worked
through. If the process stops in between -- a deploy, a crash, Render restarting
the service -- nothing ever moves it again. The claim only takes `DRAFT` or
`SCHEDULED`; the schedule job only looks for `SCHEDULED`; cancel and retry both
refuse `SENDING`, deliberately, because they cannot tell a send that died from
one that is still running.

The recipients already sent to stay `SENT`, and the rest stay `PENDING`, so
nothing is lost -- but nobody else gets the campaign, and there is no way to
resume it without SQL.

**When it bites:** a deploy or restart that lands while a campaign is going out.
The window is as long as the send, which is one SMTP round trip per recipient.

**What it would take:** a way to tell a dead send from a live one. The simplest
is a sweep at startup that moves `SENDING` campaigns to `FAILED`, after which
"Resume send" on Campaign History picks up the `PENDING` rows. That is only safe
while the backend runs as a single instance, and a message in flight at the
moment of the crash may have been delivered while its row still says `PENDING`,
so a resume can give that one person a second copy.

**Evidence:** read in the code, not reproduced.

---

## 2. A campaign in which every recipient failed is marked SENT

**Where:** `CampaignService.dispatch`

`dispatch` marks a campaign `SENT` whenever it gets to the end of its list,
however many messages the relay actually accepted. `FAILED` is reserved for the
send itself breaking. A campaign whose every message was refused therefore
appears under "Sent" with a green badge.

The analytics beneath it show the failures, and Campaign History now offers
"Retry N failed" on it, so the problem is visible to someone who looks -- but the
status says the opposite of what happened.

**When it bites:** whenever the relay refuses everything: bad credentials, a
provider's daily limit already spent, a relay that is down.

**What it would take:** decide what the status should say for a campaign that
reached nobody, or only some people. Marking a campaign that reached nobody as
`FAILED` is the smallest change; a separate state for a partial send is the more
honest one, and needs a migration for the status check constraint.

**Evidence:** reproduced locally. A campaign sent to two people with nothing
listening on the relay's port finished `SENT` with both rows `FAILED` and
`finished: 0 sent` in the log.

---

## 3. A campaign that goes out late can go out after its offer ended

**Where:** `CampaignService.dispatch`, `CampaignScheduleJob`

Scheduling refuses a send time after the offer's last day, and "Send now" and
retry refuse an offer that has already ended. `dispatch` checks neither: it sends
whatever the job hands it. A campaign that is due but does not go out on time is
sent whenever it finally does, whether or not its offer is still running.

Two things delay a due campaign. The service being down is one. The other is new
and deliberate: while `GFS_EMAIL_ENABLED` is false, the job leaves due campaigns
`SCHEDULED` so they go out once email is switched back on -- which may be after
the offer they announce has closed.

**When it bites:** a campaign scheduled close to its offer's last day, held back
past that day by downtime or by email being switched off.

**What it would take:** check the offer's end in `dispatch` before claiming, and
decide what happens to a campaign that fails the check -- `CANCELLED` with a
logged reason is the obvious candidate, since it can no longer be sent as
written.

**Evidence:** read in the code, not reproduced.

---

## 4. The campaign builder's "Send now" trusts the admin's clock

**Where:** `frontend/src/pages/admin/campaigns/SendCampaign.tsx`

The builder's final step implements "Send now" as a schedule for the browser's
current time plus one minute. The server checks that time only against its own
clock. An admin's computer whose clock is more than a minute slow is refused
with "Pick a time in the future"; one that is fast schedules the campaign that
far ahead, while the screen promises it goes out within about a minute.

**When it bites:** an admin on a machine whose clock has drifted. Rare, but the
failure is silent in the fast direction.

**What it would take:** have the builder call `POST /campaigns/{id}/send`, which
now queues the campaign for the server's own "now" and returns straight away.

**Evidence:** read in the code, not reproduced.
