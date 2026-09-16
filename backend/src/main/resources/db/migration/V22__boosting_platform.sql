-- Which platform a boosting order is played on, and for PC which launcher.
--
-- The booster cannot start without both: signing in to a PC account through Steam is a
-- different login from the same account on the EA app, and the two are not
-- interchangeable. Until now boosting orders carried no platform at all and the question
-- was asked on Discord after the money had already been taken.
--
-- Only the launcher is a new column. The platform goes in orders.platform, which has
-- existed since V1 and was simply null for every boosting row -- so a booster reading the
-- order sees it in the same field as a coin order.
--
-- Line comments only. A block comment nests badly in psql, and these files get pasted
-- into a console.
ALTER TABLE orders ADD COLUMN pc_launcher TEXT NULL;

ALTER TABLE orders ADD CONSTRAINT orders_pc_launcher_ck
    CHECK (pc_launcher IS NULL OR pc_launcher IN ('STEAM', 'EA_APP', 'EPIC'));
