-- =============================================================================
--  The screenshot a customer attaches to prove they paid.
--
--  A UTR is twelve digits a customer types, and a typo in it looks exactly like a
--  payment that has not arrived yet. The screenshot is what lets an operator tell
--  those apart without a support thread: it carries the amount, the timestamp and
--  the destination as the customer's own bank or wallet rendered them.
--
--  Optional by design. It is evidence, not authorisation -- an operator still has
--  to find the money in the account, and a claim with no screenshot is verified
--  exactly the same way. A required upload would block somebody whose phone will
--  not share an image on the screen where they have already sent money.
--
--  ---------------------------------------------------------------------------
--  Why its own table rather than columns on manual_payment_claim.
--
--  The bytes. The claim row is read on every operator queue refresh, and Postgres
--  will happily keep a small bytea inline in the row it belongs to, so putting the
--  image there would drag a few hundred kilobytes through a query that wants
--  twelve fields. Splitting it means the queue reads the claim and the image is
--  fetched only when somebody opens it.
--
--  ---------------------------------------------------------------------------
--  Why the database rather than object storage.
--
--  Deliberate, and worth revisiting later rather than pretending it scales. There
--  is no bucket configured and no credentials for one; adding both would be more
--  moving parts than this feature is worth today. At a few hundred images capped
--  at 5 MB the database handles it comfortably and they are covered by the same
--  backups as everything else. The day this becomes a burden the migration path is
--  ordinary: write the bytes to a bucket, replace `bytes` with a key column.
--
--  ---------------------------------------------------------------------------
--  What is in these files.
--
--  Payment screenshots routinely show a bank balance, a full name, a phone number
--  and a transaction history. They are personal financial data about the customer,
--  which is why serving one is operator-only and why the retention note below
--  matters: there is currently nothing that deletes them, and that is a decision
--  somebody has to make rather than a gap to leave open forever.
-- =============================================================================

CREATE TABLE manual_payment_proof (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- One live proof per claim. Replacing an unreadable screenshot has to be
    -- possible -- customers upload the wrong image constantly -- so the upload
    -- endpoint overwrites this row rather than adding to it.
    claim_id        BIGINT      NOT NULL UNIQUE
                    REFERENCES manual_payment_claim (id) ON DELETE CASCADE,

    -- The type the SERVER decided, from the file's own magic bytes. Never the
    -- Content-Type the browser announced: that is caller-controlled, and it is what
    -- is echoed back on download, so trusting it would let an upload choose the
    -- type it is later served as.
    content_type    TEXT        NOT NULL,

    size_bytes      INT         NOT NULL,

    -- The image itself. TOAST puts anything this size out of line and compresses
    -- it, so the claim row stays small whatever is stored here.
    bytes           BYTEA       NOT NULL,

    -- Kept for the operator, not for display: "the screenshot arrived four hours
    -- after the claim" is the shape of a customer who paid late or paid twice.
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Three image types, and no SVG.
    --
    -- SVG is a document, not a picture: it executes script when a browser renders
    -- it. Serving one back from our own origin to an operator who is signed in
    -- would be stored cross-site scripting against the admin console, uploaded by
    -- the customer. The three raster formats below cannot carry script, which is
    -- the entire reason this is an explicit list rather than a wildcard on the
    -- image media type.
    --
    -- Line comments, not a block, and that is not a style preference. This comment
    -- previously spelled out that wildcard literally, and the slash-star inside it
    -- opened a NESTED block comment -- Postgres nests them, unlike C -- so the
    -- closing delimiter shut the inner one and left the outer comment unterminated.
    -- The whole migration failed to parse, the application could not boot, and the
    -- platform kept serving the previous build. Prose is safe in a line comment.
    CONSTRAINT manual_payment_proof_type_ck
        CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),

    -- Floor as well as ceiling. Zero bytes is a failed upload that would otherwise
    -- render as a broken image and read as a customer who sent nothing useful.
    CONSTRAINT manual_payment_proof_size_ck
        CHECK (size_bytes BETWEEN 1 AND 5242880),

    -- The declared length has to be the real one, or the operator queue reports a
    -- size that is not the file's.
    CONSTRAINT manual_payment_proof_length_ck
        CHECK (size_bytes = length(bytes))
);

-- The queue asks "does this claim have a screenshot" for every visible row, and
-- answers it without touching the bytes.
CREATE INDEX manual_payment_proof_claim_ix ON manual_payment_proof (claim_id);
