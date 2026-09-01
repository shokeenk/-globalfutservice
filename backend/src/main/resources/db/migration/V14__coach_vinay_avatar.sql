-- =============================================================================
--  Vinay gets his photograph.
-- =============================================================================
--
--  The coach card already renders `avatar_url` when it is set and falls back to
--  initials in a brand ring when it is not. V9 seeded the coach without one, so
--  every visitor to the coaching page saw "VI" in a circle — the fallback doing
--  exactly its job, for a coach who does in fact have a photograph.
--
--  An UPDATE rather than a new row: unlike `rate_card`, `coach` is not a temporal
--  record. There is one Vinay, orders reference him by id, and his portrait is a
--  property of the person rather than a version of a price. Closing and re-inserting
--  here would orphan every booking pointing at the old row.
--
--  The path is relative and served by the storefront, not the API. The frontend
--  renders it straight into `<img src>`, so a relative path resolves against
--  whichever origin the customer is on — which is the same reason /api is proxied
--  rather than pointed at the API host. An absolute URL here would hardcode one
--  environment into the database.
--
--  Cropped to a head-and-shoulders square before being committed. The source is a
--  1080x1080 three-quarter body shot, and dropped whole into a 48px circle the face
--  would have been a handful of pixels — the crop is what makes it a portrait rather
--  than a thumbnail of a person standing some distance away.
-- =============================================================================

UPDATE coach
SET avatar_url = '/brand/coaches/vinay-256.jpg'
WHERE public_id = 'vinay';
