-- =============================================================================
--  V34 -- support tickets become conversations
--
--  Until now a ticket was one message from the contact form that nobody could
--  answer from the site. This makes it a thread: the customer's messages, staff
--  replies, and staff notes the customer never sees.
--
--  support_ticket gains:
--   * category, chosen on the form or by staff. Nullable: tickets from before
--     this have none.
--   * last_activity_at, what the Support page sorts and ages by.
--  A ticket is always opened by the customer: we never contact a customer
--  first, so staff answer tickets and never start them.
--  The status values stay as they were: OPEN (waiting for staff), ANSWERED
--  (waiting for the customer) and CLOSED.
--
--  support_message holds the thread. Every existing ticket's body becomes its
--  first message, so nothing already written is lost from view. A NOTE is
--  staff-only by construction: the check says a note can only be written by
--  staff, and the customer's endpoints select messages, never notes.
--
--  The customer's bell gains SUPPORT_REPLY for "we replied to your ticket".
-- =============================================================================

ALTER TABLE support_ticket
    ADD COLUMN category          TEXT,
    ADD COLUMN last_activity_at  TIMESTAMPTZ;

UPDATE support_ticket SET last_activity_at = coalesce(resolved_at, created_at);

ALTER TABLE support_ticket
    ALTER COLUMN last_activity_at SET NOT NULL,
    ALTER COLUMN last_activity_at SET DEFAULT now(),
    ADD CONSTRAINT support_category_ck CHECK (category IS NULL OR category IN
        ('COINS', 'BOOSTING', 'COACHING', 'PAYMENT', 'ACCOUNT', 'TECHNICAL', 'OTHER'));

CREATE INDEX support_ticket_activity_ix ON support_ticket (last_activity_at DESC);

CREATE TABLE support_message (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id          BIGINT      NOT NULL REFERENCES support_ticket (id) ON DELETE CASCADE,
    author             TEXT        NOT NULL,
    kind               TEXT        NOT NULL,
    body               TEXT        NOT NULL,
    -- Who on the staff wrote it; null for the customer.
    author_account_id  BIGINT      REFERENCES account (id),
    author_label       TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT support_message_author_ck CHECK (author IN ('CUSTOMER', 'STAFF')),
    CONSTRAINT support_message_kind_ck CHECK (kind IN ('MESSAGE', 'NOTE')),
    CONSTRAINT support_message_note_ck CHECK (kind = 'MESSAGE' OR author = 'STAFF'),
    CONSTRAINT support_message_body_ck CHECK (char_length(body) BETWEEN 1 AND 4000)
);

CREATE INDEX support_message_ticket_ix ON support_message (ticket_id, created_at);

INSERT INTO support_message (ticket_id, author, kind, body, created_at)
SELECT id, 'CUSTOMER', 'MESSAGE', left(body, 4000), created_at
  FROM support_ticket
 WHERE char_length(body) > 0;

ALTER TABLE customer_notification DROP CONSTRAINT customer_notification_kind_ck;
ALTER TABLE customer_notification ADD CONSTRAINT customer_notification_kind_ck
    CHECK (kind IN ('ORDER_PLACED', 'PAYMENT_SUBMITTED', 'PAYMENT_CONFIRMED',
                    'ACTION_NEEDED', 'STATUS_CHANGED', 'DELIVERED', 'ANNOUNCEMENT',
                    'SUPPORT_REPLY'));
