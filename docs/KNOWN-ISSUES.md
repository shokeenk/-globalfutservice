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

---

## 4. Weekly hours are read back shifted when the server's clock is not on UTC

**Where:** `spring.jpa.properties.hibernate.jdbc.time_zone: UTC` in `application.yml`,
read through `CoachAvailabilityEntity`

`coach_availability.start_time` and `end_time` are `TIME` columns. With Hibernate's JDBC
time zone set to UTC, it converts a `LocalTime` through the JVM's own time zone on the way
in and out. On a JVM running in India the coach's 18:00–23:00 comes back as 23:30–04:30:
the end wraps past midnight, the slot planner refuses the window, and
`GET /coaching/coaches/{id}/slots` answers 500. The admin's weekly-hours editor shows the
shifted times, and hours saved from such a server are stored shifted the other way.

**When it bites:** any server whose JVM default time zone is not UTC. Production should not
be one: the backend image (`eclipse-temurin:21-jre-jammy`) sets no time zone, so its JVM
runs in UTC. That is inferred from the image, not checked on Render. It does bite a
developer's machine in India, which is where it was found.

**What it would take:** pin the JVM to UTC (`-Duser.timezone=UTC` in `JAVA_OPTS`, and the
same for local runs), or map the two columns so Hibernate does not convert them.

**Evidence:** observed locally. The rows hold 18:00–23:00; the admin coaches endpoint
returned 23:30–04:30 from a JVM in `Asia/Calcutta`, and 18:00–23:00 from the same code
started with `-Duser.timezone=UTC`, which is how the coaching checkout was then checked.

---

## 5. A Discord announcement being retried is lost if the server restarts

**Where:** `DiscordNotifier.postQuietly`

A coaching announcement that Discord rate-limits, fails on its side or never answers is
retried twice, after 5 and 30 seconds, on the notification thread. The retries live in
memory. A deploy or restart during those pauses drops the message; the failure is in the
log, with Discord's HTTP status, but nothing sends it again.

**When it bites:** a Discord outage or rate limit that coincides with a deploy. The booking
itself is never affected.

**What it would take:** an outbox table the notifier writes to and a job that sends from,
so an unsent announcement survives a restart.

**Evidence:** read in the code.

---

## 6. Booking a package's later sessions offers hour-long slots, not forty-minute ones

**Where:** the slot picker on `/coaching` (`SlotPicker` in `Coaching.tsx`), calling
`GET /coaching/coaches/{id}/slots` without `variant`

The checkout asks for slots at the product's own length, so a six-pack's first session is
offered on the forty-minute grid. The picker on `/coaching`, where a customer books the
package's other five, does not say what it is booking for, and the endpoint then lays the
calendar out for a one-hour session. A forty-minute session that would fit -- the last one
before a booked session or the end of the coach's hours -- is not offered. Booking checks
the right length, so nothing wrong can be booked; some right slots are just missing.

**When it bites:** a package customer booking sessions two to six near the edges of the
coach's day.

**What it would take:** have the endpoint use the signed-in customer's own session length
when no variant is given -- the length of the credit the booking will spend -- or have the
picker pass it.

**Evidence:** read in the code.

---

## 7. Approve's timeline entry does not say which admin released the order

**Where:** `AdminOrderController.approveFulfilment` (`main` and the FUT Transfer branch)

Releasing a coin order to the fulfilment partner moves it to In progress with
`operator.email()` as the timeline's actor label. The principal built from an access
token never carries an email -- `JwtService.verify` passes `null` for it, because the
token holds only the account id, public id and role -- so the label is always empty. The
admin order page shows the row as "OPERATOR" with no name. The account id is still stored
on the row, so who did it can be recovered from the database.

Every other transition labels the row with the account's public id instead
(`operator.publicId()`), which is also the right fix here: the customer's own order API
returns timeline labels as written, so a staff email must never be one.

**When it bites:** every approval, from the order page or the Orders table.

**What it would take:** pass `operator.publicId()` instead of `operator.email()`.

**Evidence:** observed locally. An order approved during the FUT Transfer end-to-end
run has `actor_label` null on its IN_PROGRESS row, alongside `actor_id` set.

---

## 8. One Service Listings test fails now and then under a full run

**Where:** `frontend/src/pages/admin/AdminListings.test.tsx`, "edits a tier: title
read-only, prices per currency, and asks before changing a price"

It failed once during a full run of the admin tests: `waitFor(() => expect(api.put)
.toHaveBeenCalledWith(...))` gave up with "expected spy to be called with arguments"
after the test had run for 1.3 s. Run on its own it passed three times in a row, and the
next full run of every frontend test passed. `waitFor` stops retrying after one second by
default, so a save that has not happened within a second of the click on a loaded machine
fails the test -- the likeliest reading, not an established one.

**When it bites:** a full `vitest run` on a loaded machine; it would show as a flaky CI
failure.

**What it would take:** confirm by reproducing under load, then give that `waitFor` a
longer `timeout`, or wait on what the save renders (`findByRole('status')`, which the
test already checks afterwards) before asserting on the call.

**Evidence:** observed once; the cause is read, not confirmed.
