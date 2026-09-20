-- =============================================================================
--  Marketing consent, and the token that lets somebody withdraw it.
--
--  The promotional mailing system cannot safely exist without this. Nothing in
--  the account table recorded whether a customer agreed to hear from marketing,
--  so "send to all eligible registered customers" had no way to mean anything:
--  every address in the table looked identical to a campaign audience query.
--
--  ---------------------------------------------------------------------------
--  EVERY EXISTING CUSTOMER DEFAULTS TO FALSE, AND THAT IS THE POINT.
--
--  Backfilling TRUE would be the easy thing to do -- it makes the first campaign
--  reach the whole customer list -- and it would be recording a consent nobody
--  gave. These accounts were created to buy coins; none of them were shown a
--  marketing checkbox, because there wasn't one. Opting them in retrospectively
--  is the kind of decision that is indistinguishable from a bug until a
--  regulator or an angry customer asks where the consent came from.
--
--  So the audience starts empty and fills as customers opt in. Somebody will
--  need to add the checkbox at registration and, if the business wants the
--  existing list, run a re-permission campaign -- which is itself a marketing
--  send and therefore a decision for the owner, not a migration.
--  ---------------------------------------------------------------------------
--
--  Transactional order mail is NOT governed by this column and must never read
--  it. A customer who opts out of marketing still gets told their payment was
--  verified; those emails are operationally required and are sent because they
--  ordered something, not because they agreed to be marketed to. The unsubscribe
--  link in a campaign flips `marketing_opt_in` alone and touches nothing else.
--
--  `marketing_token` is what an unsubscribe link carries. A random per-account
--  value rather than the account id or a hash of the email: an unsubscribe URL
--  travels in plain text through mail servers and sits in inboxes forever, so it
--  must not be guessable from an address and must not identify the account to
--  anyone who intercepts it. It is generated for every row now so a link can be
--  built for any account without a null check at send time.
-- =============================================================================

ALTER TABLE account
    ADD COLUMN marketing_opt_in      boolean     NOT NULL DEFAULT false,
    ADD COLUMN marketing_opt_in_at   timestamptz,
    ADD COLUMN marketing_opt_out_at  timestamptz,
    ADD COLUMN marketing_token       uuid        NOT NULL DEFAULT gen_random_uuid();

-- One token per account, because the unsubscribe endpoint looks an account up by
-- it and a collision would unsubscribe the wrong person.
ALTER TABLE account
    ADD CONSTRAINT account_marketing_token_key UNIQUE (marketing_token);

-- The campaign audience query is "opted in, with a usable address". Partial, so
-- the index stays small: the opted-in set is expected to be a minority of rows
-- for a long time, and this is the only query that reads the column.
CREATE INDEX idx_account_marketing_opt_in
    ON account (marketing_opt_in)
    WHERE marketing_opt_in = true;

COMMENT ON COLUMN account.marketing_opt_in IS
    'Promotional email consent. Never consulted for transactional order email.';
COMMENT ON COLUMN account.marketing_token IS
    'Opaque per-account token carried by unsubscribe links. Not derived from the email.';
