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

## 1. Resuming or retrying a send can give one person a second copy

**Where:** `CampaignService.resumeStalled`, `CampaignService.retryFailed`

A row is marked `SENT` only once the relay has said it accepted the message. If
the connection drops after the relay has the whole message but before its reply
arrives -- the process is killed, or the network fails -- the row stays `PENDING`
(or becomes `FAILED`) although the message may already be on its way. Resuming the
send, or retrying its failures, sends that person the campaign again.

At most one message per interruption is affected: the one in flight at the time.

**When it bites:** a deploy or crash in the middle of a send, followed by the
automatic resume; or a retry after a failure that was really a lost reply.

**What it would take:** a way of sending that the provider can de-duplicate.
SMTP has none. An HTTP sending API that accepts an idempotency key per message
would, if the provider offers one; that is a change of transport, not a fix to
this code.

**Evidence:** observed locally. The backend was killed two seconds into a send
to a local SMTP sink; the sink received the complete first message after the
backend had died, and that recipient's row was still `PENDING`. Whether a real
relay delivers a message whose acceptance was never acknowledged depends on the
relay; that part has not been tested.

---

## 2. A campaign withdrawn automatically looks like one an admin cancelled

**Where:** `CampaignService.dispatch`, `CampaignRepository.withdrawIfOfferEnded`

A scheduled campaign that comes due after its offer has ended is withdrawn rather
than sent, and is recorded as `CANCELLED`. Campaign History shows it as cancelled,
exactly as if an admin had pressed Cancel. The reason -- the offer ended before it
could go out -- is written only to the server log.

**When it bites:** an admin looking at Campaign History after a campaign was held
back past its offer's end, by downtime or with email switched off, and wondering
who cancelled it.

**What it would take:** somewhere on the campaign to record why it ended the way it
did -- a short reason column, which needs a migration -- shown beside the status.

**Evidence:** observed locally. A campaign seeded as due with an offer that ended
the day before was withdrawn on the first tick; it showed only `CANCELLED`, and the
reason appeared in the log alone.

---

## 3. The 30-day revenue figure adds amounts in different currencies together

**Where:** `OrderRepository.revenueSince`, shown by `AdminAnalyticsController.revenue`

Every order records its own currency, and the rate card can price in INR, USD, EUR,
GBP or AED. The revenue query sums `total_minor` across every delivered and
completed order regardless of currency, and the endpoint formats the total as
rupees. A $10 order adds 1,000 to the sum, which is then shown as ₹10.00 rather
than the several hundred rupees it is worth; a £10 order does the same. The figure is right only while every order is
in rupees.

**When it bites:** the first delivered or completed order priced in anything other
than INR. Whether one exists in production is not known -- it has not been queried.

**What it would take:** decide what the figure should mean -- rupees only, one total
per currency, or everything converted at some rate -- and change the query to match.
Deliberately not changed when the card moved to Analytics, where the instruction
was to keep what counts as revenue exactly as it was.

**Evidence:** read in the code. The local dev database does hold a GBP order, so
non-INR orders are not hypothetical, but that one is abandoned and so is not
counted; no mixed total has been observed.
