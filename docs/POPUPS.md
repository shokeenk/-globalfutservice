# Popups: the guide's seven drafts

Created by migration V60 as **drafts, switched off, with no images**. Nothing shows on the
site until, for each one, an admin opens **Admin → Popups**, uploads the desktop image (and
optionally a mobile one), checks the preview, presses **Publish**, and then **Switch on**.

| # | Popup | Type | Where | When | How often | Buttons |
|---|---|---|---|---|---|---|
| 1 | Homepage entry | Centred | Home | On page load | Once per session | Explore Boosting → /boosting · Buy Coins → /order |
| 2 | Coins offer | Bottom-right | Coins | After 8 s or 40% scroll | Once a day | none (refused) |
| 3 | Coins checkout | Bottom-right | Coins checkout | Step `coins:continue` | Once per session | none (refused) |
| 4 | Champs listing | Bottom-right | Champs listing | After 8 s or 40% scroll | Once per session | Explore Champs Boosting → /boosting |
| 5 | Champs checkout | Bottom-right | Champs checkout | Step `champs:checkout` | Once per session | none (refused) |
| 6 | Membership explainer | Centred | Rewards (add pages as wanted) | On page load | Once per session | none set |
| 7 | Rewards reminder | Bottom-right | Home (choose pages) | After 8 s or 40% scroll | Once per session | Explore Rewards → /rewards; never during checkout |

Everything in the table can be changed in the editor except the "no button" rule on 2, 3
and 5. A duplicate of one of those keeps the rule.

## Before publishing

- **#1 headline and body are placeholders.** The guide leaves them to you:
  "WELCOME TO GLOBAL FUT SERVICES" / "EA FC coins, Champs boosting and 1-to-1 coaching, all in
  one place.", with Spanish and French.
- **"5% OFF" in the referral line (#2, #4, #5) is the guide's wording, not a live number.**
  A referral (creator) code's first-order discount is set per code in Admin and defaults to
  **13%**. Either make sure the codes you promote give 5%, or edit the line.
- **#6's tiers are not in the copy.** The popup lists the tiers and their discounts from the
  live settings under the body. With tier discounts switched off it lists the tier names and
  no percentages. "{pointValue}" in its last line is filled in with the live point value
  (₹1 today).
- Spanish (tú) and French (tu) are filled in for every line and button; English is used for
  any line left empty.

## Tiers: the guide's image vs the site

The guide's artwork shows five tiers. The site has six, and the popup shows the site's.
Nothing was changed.

| Tier | Guide image | Site (live) |
|---|---|---|
| Bronze | 1% | 0% |
| Silver | 2% | 1% |
| Gold | 3% | 2% |
| Elite | — (not in the image) | 3% |
| Platinum | 4% | 4% |
| Diamond | 5% | 5% |

Points thresholds on the site: Bronze 0, Silver 500, Gold 2,000, Elite 5,000, Platinum 8,000,
Diamond 10,000 lifetime points. If an image is made from the guide's artwork, leave the tier
percentages off it: the popup already shows the real ones, and an image cannot follow a change.

## Page steps

- `coins:continue`: Continue on the coin page, moving to the customer details step.
- `champs:checkout`: the Champs order and payment page opening (Champs only, not Rivals).
