# Deployment

Everything on Render, except the database.

| # | piece | where | why |
|---|-------|-------|-----|
| 1 | Postgres | Neon (free tier) | Render's own free Postgres is deleted after 30 days |
| 2 | API — `gfs-api` | Render | private; the storefront reaches it over Render's internal network |
| 3 | Storefront — `gfs-web` | Render | the only service the public touches |

`render.yaml` declares services 2 and 3 together, so one Blueprint deploys both.
Only the database is set up by hand first, because the API cannot boot without its
URL.

**The storefront is a reverse proxy, not just a bundle.** Its nginx serves the
built site and forwards `/api`, `/oauth2` and `/login/oauth2` to the API. That is
what makes every request the browser sends same-origin, and it is not a
preference: the refresh cookie is `SameSite=Strict` and set by the API. If the
browser talked to the API on its own hostname, the cookie would belong to that
hostname and the storefront could never read it — a customer would complete the
entire Google sign-in and land back signed out, with nothing in any log saying
why. One origin, one cookie.

Render injects the API's private address into the storefront as `API_UPSTREAM`
via `fromService`, so it cannot drift if the API is renamed, and that traffic
never leaves Render's network.

---

## 0 · Before the first push

**Rotate the OAuth secrets.** The Google and Discord client secrets currently in
`.env` were shared in plaintext during development. The client *IDs* are public by
design — they ship inside the redirect URL — but the two secrets are not, and a
secret that has been pasted anywhere should be treated as burned.

- Google Cloud Console → Credentials → your OAuth client → Reset secret
- Discord Developer Portal → your app → OAuth2 → Reset Secret

Paste the new values over `.env` lines 115 and 117. They never enter the repository;
`.gitignore` excludes `.env` as its first entry.

**Check what a commit would actually contain.** `.gitignore` already covers `.env`,
`target/`, `node_modules/` and `dist/`. The thing it does not cover is roughly 19 MB
of source artwork sitting loose at the repository root — `FC26 background.png` alone
is 14 MB. None of it is used by the build; the app serves its own optimised copies
from `frontend/public/brand/` (1.3 MB total).

That is a decision to make *before* the first commit, because git history is
immutable and 19 MB stays in every clone forever. Either is defensible:

- **Commit them.** A private repo is a reasonable home for original artwork, and
  19 MB is not a problem at this scale. Move them into `assets-source/` first so the
  root stays readable.
- **Leave them out.** Add `assets-source/` to `.gitignore` and keep them backed up
  elsewhere. Only do this if they genuinely exist somewhere else — these are
  originals, not derivatives.

```bash
mkdir -p assets-source && mv *.png assets-source/ 2>/dev/null; ls assets-source | head
```

---

## 1 · Database — Neon

Render's own free Postgres is deleted after 30 days, which is shorter than most
client review cycles. Neon's free tier is not.

1. Create a project at neon.tech. Pick the region closest to Singapore.
2. Copy the connection string. It arrives in libpq form:
   `postgresql://user:pass@ep-xxx.region.aws.neon.tech/dbname?sslmode=require`
3. **Split it up.** Spring wants the credentials separate and the scheme prefixed:

   | Render env var | value |
   |---|---|
   | `GFS_DB_URL` | `jdbc:postgresql://ep-xxx.region.aws.neon.tech/dbname?sslmode=require` |
   | `GFS_DB_USER` | the user from the string |
   | `GFS_DB_PASSWORD` | the password from the string |

   Pasting the libpq URL straight into `GFS_DB_URL` is the single most common way
   this fails, and the error it produces (`No suitable driver`) does not say so.

4. Confirm the database allows `CREATE EXTENSION btree_gist`. Migration V3 uses a
   gist exclusion constraint to make double-booking a coach structurally impossible.
   Neon allows it; some managed providers do not, and Flyway will fail on V3 if not.

---

## 2 · Both services — one Render Blueprint

`render.yaml` declares `gfs-api` and `gfs-web` together. Everything marked
`sync: false` is prompted for in the dashboard and never enters git.

1. Render → New → **Blueprint** → pick the repo. It reads `render.yaml` and
   creates both services.
2. Fill in the prompted values on **`gfs-api`**. The ones without which nothing
   works:

   - `GFS_DB_URL`, `GFS_DB_USER`, `GFS_DB_PASSWORD` — from step 1
   - `GFS_BOOTSTRAP_ADMIN_EMAIL`, `GFS_BOOTSTRAP_ADMIN_PASSWORD` — without these
     there is no way into the admin console at all. Change the password from
     inside the app after first login.
   - `GFS_PUBLIC_URL`, `GFS_CORS_ORIGINS` — both are the **`gfs-web`** origin, not
     the API's. You do not have it until the first deploy finishes, so put a
     placeholder and come back in step 3.

3. `gfs-web` needs nothing typed. Its one variable, `API_UPSTREAM`, is injected by
   Render from `gfs-api` via `fromService`.

4. Three secrets are generated by Render rather than typed, so they never exist on
   your laptop: `GFS_JWT_SECRET`, `GFS_QUOTE_SECRET`, `GFS_CREDENTIAL_MASTER_KEY`.

   > **Copy `GFS_CREDENTIAL_MASTER_KEY` into a password manager after the first
   > deploy.** It wraps the per-order keys that encrypt customer EA sign-ins. If it
   > is lost, every stored credential becomes permanently unreadable — there is no
   > recovery path, by design.

5. Deploy. Watch the `gfs-api` log for Flyway applying `V1`–`V13`, then check both:

   ```bash
   curl -s https://gfs-web-xxxx.onrender.com/api/v1/catalog/policy
   ```

   Answering through `gfs-web` proves the whole chain: nginx received it, proxied
   it privately to `gfs-api`, and the API reached the database. If this works, the
   API's own public URL is not something you ever need again.

**Both services must be in the same region.** Render's private networking is
per-region, and `render.yaml` pins both to `singapore`. Change one and the proxy
cannot reach the API.

**On the free plan services sleep after 15 minutes idle** and take ~50 s to wake.
With two services that is two cold starts. Survivable for a demo, not for a
launch — upgrade before taking real payments.

---

## 3 · Close the loop

1. **`gfs-api`** → environment → set `GFS_PUBLIC_URL` and `GFS_CORS_ORIGINS` to the
   **`gfs-web`** origin (`https://gfs-web-xxxx.onrender.com`, no trailing slash).
   Save, then redeploy — editing a variable does not redeploy by itself.

2. **OAuth callbacks.** Because `gfs-web` proxies the OAuth legs, the URL each
   provider must have registered is on the **storefront** domain, not the API's:

   ```
   https://gfs-web-xxxx.onrender.com/login/oauth2/code/google
   https://gfs-web-xxxx.onrender.com/login/oauth2/code/discord
   ```

   Add these in Google Cloud Console (Authorized redirect URIs) and the Discord
   Developer Portal (OAuth2 → Redirects), alongside the localhost ones for dev.

   If Google returns `redirect_uri_mismatch`, its error page prints the URI it
   actually received — register that verbatim rather than guessing.

3. **Check the Google consent screen.** If Publishing status is *Testing*, only
   accounts listed as test users can sign in; everyone else gets
   `Error 403: access_denied` with no useful hint. Either add the test users or
   click **Publish app** — the scopes here (`openid profile email`) are
   non-sensitive, so publishing needs no Google review.

4. Verify:

   ```bash
   curl -s https://gfs-web-xxxx.onrender.com/api/v1/auth/providers
   ```

   `{"providers":["discord","google"]}` means the sign-in buttons will appear on
   `/login` and `/register` by themselves — they are server-driven and render
   nothing when the list is empty.

---

## A note on `frontend/vercel.json`

Unused in this arrangement. It is kept because it is the equivalent configuration
for a Vercel front end and describes the same three rewrites, so it remains a
working alternative — but its `REPLACE-WITH-YOUR-API-HOST` placeholders are dead
text while the storefront is on Render. Nothing reads it.

---

## Updating after deploy

Both services deploy on push to the default branch. The normal loop is:

```bash
git add -A && git commit -m "..." && git push
```

Both build Docker images, so expect roughly four minutes each; they build in
parallel. Neither needs
reconfiguring. Environment variable changes take effect on the next deploy, and on
Render you must trigger one manually — editing a variable does not redeploy by
itself.

Database schema changes go through Flyway. **Never edit an applied migration** —
`validate-on-migrate` is on, and changing a file that has already run breaks its
checksum and refuses to start. Add `V13__*.sql` instead.

---

## Before real money moves

`docs/PRE-LAUNCH-CHECKLIST.md` is the authority; its "Blocking" section is the part
that matters. The deployment-specific items:

- Razorpay in **test** mode until the client's own account is connected, and the
  webhook secret set — without it, payment confirmation cannot be verified
- Render's free plan upgraded, so the API is not asleep when a customer arrives
- `GFS_CREDENTIAL_MASTER_KEY` backed up somewhere that is not Render
- The privacy policy naming futtransfer as a sub-processor, since customer EA
  credentials are forwarded to them
