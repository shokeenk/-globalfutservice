-- =============================================================================
--  Two more things the checkout asks for: a name, and a Discord username.
--
--  The checkout collects both. Before this migration the API took neither, so a
--  customer typing their name into the form was typing into nothing -- which is
--  worse than not asking, because it looks like it was recorded.
--
--  Both nullable, and deliberately so. The name is on the receipt and the Discord
--  handle is optional by design (the field is labelled "optional" on the form),
--  and every order placed before this column existed has neither. A NOT NULL with
--  a backfilled empty string would say those orders had a blank name rather than
--  no name recorded, which is a different and less true statement.
--
--  Discord is a contact channel here, not a social link -- the terms name it as
--  the route for scheduling, support and safety-policy claims -- which is why it
--  sits on the order beside the phone number rather than in a profile somewhere.
-- =============================================================================

ALTER TABLE orders ADD COLUMN guest_name       TEXT;
ALTER TABLE orders ADD COLUMN discord_username TEXT;
