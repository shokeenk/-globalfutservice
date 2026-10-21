# Acceptance check: "GFS Website Popups, Membership & Login" (21 Oct 2026)

Checked against the code and a local copy of the site (desktop 1280px, phones 390px and 360px)
in Chromium. The live site could not be reached from the checking environment, so every
"pass" below is for this code. Anything that depends on settings only the live site has is
marked **needs the client**.

**Fixed in this round** means the check failed and the fix ships with this change.

## Popups

| Item | Result |
|---|---|
| Centred modal has a dim overlay and an X | **Fixed in this round.** The overlay sat under the header and the help button, and on phones the modal was off-centre. Popups are now drawn on top of the whole page. Esc, the X and keeping focus inside already worked. |
| Bottom-right cards can be closed | Pass (X, and now Esc when focus is inside the card). |
| Cards never cover form controls, totals or payment buttons | **Fixed in this round.** On the coin order page the card could cover the platform buttons, the amount presets and box, and an unmarked Continue. On order and checkout pages every control now counts. Elsewhere, fields count. |
| No button on the Coins and Champs checkout popups | Pass. The editor offers none and the server refuses one, also on a copy. |
| "Explore Rewards" on the rewards reminder | Pass. |
| Never two popups at once | Pass. |
| Frequency holds after navigation and refresh | Pass. |

## Checkout

| Item | Result |
|---|---|
| Prices, fees, coupons, points and checkout state unchanged | Pass. Popups only read the page. No price, coupon, points or checkout code changed in Parts 1–6. Popups never show on the payment step. |
| Tracking / live chat popup promises help after the order, not before | Pass. "After placing your order, you will get live order tracking and live chat support…" |

## Admin

| Item | Result |
|---|---|
| All popup content, button links and timings editable without a deploy | Pass. Admin → Popups. The one exception is the "no button" rule on popups 2, 3 and 5. |

## Accounts

| Item | Result |
|---|---|
| Email/password sign-in | Pass. |
| Google / Discord sign-in | Pass in code. **Needs the client:** each button shows only when that provider's keys are set on Render. |
| Forgot, reset and change password | Pass. |
| Email verification | Pass. **Needs the client:** emails need the mail settings on Render. |
| Human check | Pass in code. It now uses the compact widget on narrow phones. The normal one pushed a 360px screen sideways. **Needs the client:** `GFS_TURNSTILE_SITE_KEY` and `GFS_TURNSTILE_SECRET_KEY`. Without them the check is skipped and a warning is logged. |
| Marketing checkbox saved with when and how | Pass. |
| Friendly error messages | **Fixed in this round.** Sign-up showed the server's English for a refused sign-up and for some field errors. These are now in EN / ES / FR. |

## Support

| Item | Result |
|---|---|
| Form rules and states | Pass. |
| Floating chat on Support, tracking and checkout pages | Pass in code. **Needs the client:** `VITE_TAWK_FLOATING_WIDGET_ID` on Render. Until it is set, those pages show the help button instead. |
| Order reference shared, never passwords or backup codes | Pass. |

## Phone, keyboard and focus

| Item | Result |
|---|---|
| No sideways scroll | **Fixed in this round.** The home page's background glow made it 565px wide on a 390px phone. That is what pushed the popup off-centre. Login, Support, Order and Rewards were already fine. |
| Keyboard, focus and close controls | Pass. Every popup closes with its X and with Esc. The modal keeps focus inside and gives it back when closed. Form errors are in each language. |

## Still for the client

- Before publishing popups 2, 4 and 5, check the "5% OFF" referral wording against the
  codes you promote. Codes default to 13%. See `docs/POPUPS.md`.
- Popup #1's headline and body are placeholders.
- Set the Render variables named above.
