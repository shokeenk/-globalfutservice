# MMOExp — cart and checkout options

Extracted 5 September 2026 from `mmoexp.com`, as competitor research for the GFS checkout.

**A note on completeness up front.** `Cart.html` is empty without items in it, and adding an
item is gated behind sign-in — so the priced cart, the payment method list and the delivery
form were not reachable without creating an account on someone else's commercial site. What
follows is what an anonymous visitor can actually see, plus the site's own configuration
object, which turned out to carry more than the rendered page does. The gaps are marked
**`[not reachable]`** rather than guessed at.

Everything here is observation. Nothing in it is a recommendation, and the prices are
theirs.

---

## 1. Cart page — empty state

`/Cart.html`, no items:

| element | text |
|---|---|
| Heading | Shopping Cart |
| Trust label | SECURE CHECKOUT |
| Empty heading | Your cart is empty |
| Empty body | You don't have items in your shopping cart. |
| Primary action | Continue shopping |
| Breadcrumb | Home › Cart |

The page also ships the whole auth modal inline (see §6) — the cart and the login flow are
one document.

---

## 2. Product configurator

This is the part worth studying. It is a three-step numbered panel, not a cart.

**Step 1 — Select Server**

- PlayStation 5 (`ps`)
- Xbox Series X|S (`xbox`)
- PC Platform (`pc`)

**Step 2 — Select Quantity** — 18 preset chips plus free entry.

| | | | | | |
|---|---|---|---|---|---|
| 10K | 20K | 30K | 40K | 50K | 80K |
| 100K | 200K | 300K | 500K | 600K | 800K |
| 1000K | 1500K | 1800K | 2000K | 3000K | 5000K |

Below the chips: **`Enter Custom Amount (K)`** with `−` / `+` steppers.

**Step 3 — Order Summary**

```
Total          10 K        $6.99
You'll Receive 10K Coins
[ Buy Now ]
```

Under the button: `Fast Delivery` · `Secure Payment` · `24/7 Support`.

### Pricing

Flat linear rate — **$0.699 per 1K**, no volume discount at any tier. 5000K is exactly 500×
the 10K price.

| K | USD | | K | USD |
|---|---|---|---|---|
| 10 | 6.99 | | 600 | 419.40 |
| 20 | 13.98 | | 800 | 559.20 |
| 30 | 20.97 | | 1000 | 699.00 |
| 40 | 27.96 | | 1500 | 1,048.50 |
| 50 | 34.95 | | 1800 | 1,258.20 |
| 80 | 55.92 | | 2000 | 1,398.00 |
| 100 | 69.90 | | 3000 | 2,097.00 |
| 200 | 139.80 | | 5000 | 3,495.00 |
| 300 | 209.70 | | | |
| 500 | 349.50 | | | |

Minimum order 10K. Range $6.99 – $3,495.00.

### Other elements on the panel

- `Stock Status: Pre-Order (Claim the early-bird offer→)`
- A **Coupon Code** block showing `8%` with a **Copy** button — the code is handed to the
  visitor rather than typed in
- Sticky mobile cart bar appearing on scroll past the buy panel

---

## 3. Product categories

Per game title, as tabs across the configurator:

`Coins` · `Packs` · `Players` · `SBC` · `Boosting` · `Top Up`

Ten storefronts share the same chrome: FC 27, NBA 2K27, MLB, MUT, Diablo 4, POE2, POE, CFB,
Roblox, RS.

---

## 4. Currency and locale

**16 currencies**, converted client-side from a rate table shipped in the page:

| | | | |
|---|---|---|---|
| USD $ (base) | EUR € 0.8611 | GBP ￡ 0.7396 | AUD A$ 1.3882 |
| CAD C$ 1.3829 | RUB 91.4096 | SGD S$ 1.2668 | TWD NT$ 32.389 |
| HKD HK$ 7.8307 | CNY ¥ 6.7289 | JPY 円 156.1566 | IDR Rp 17674.2188 |
| BRL R$ 5.3575 | MXN Mex$ 18.3115 | PLN 3.6857 | PHP 62.6966 |

**7 languages:** English, Deutsch, Français, Español, Português, Nederlands, Italiano.

The page geolocates the visitor (it reported `Ukraine` for this session) and carries that
alongside the language choice.

---

## 5. Trust and social proof

- `10,956 PROs online` in the header
- Trustpilot widget: **4.8**, *"Based on 33,479 reviews"*, with ~7 named reviews carrying
  dates, country codes, reviewer review-counts and `Verified` badges
- Reviews name individual staff ("Theodore", "Maxine", "andy")
- Footer claim: *"Trusted by 1M+ players"*
- `Sign up to get Coupon with a maximum discount of $120` — a persistent header banner

---

## 6. Account flow

Rendered inline on the cart page, not a separate route.

**Log in:** Google · Facebook · Discord, then *"Or continue with email"* → Email\*,
Password\*, `Remember me`, `Forget Password?`

**Sign up:** Email\*, `Verification code*` (a captcha/emailed code with its own send
button), Password\*, `Confirm password*`, and a single required consent:

> I accept Terms and conditions and Privacy Policy and Refund Policy

---

## 7. Site structure

| group | links |
|---|---|
| About | About Us · Mascot · News · Reviews · Site Map |
| Support | Help Center · Contact us · Refund Policy |
| Promotions | Giveaways · Member discount |
| Partner | Sell to us · Affiliate · Cooperation |
| Legal | Privacy Policy · Terms & Conditions · DMCA |

---

## 8. Not reachable without an account

- Priced cart line items, quantity editing, item removal
- Payment method list and any processing fee
- Delivery / account-credential form
- Coupon redemption at checkout (only the copy-a-code block is public)
- Order confirmation and post-purchase screens

The only payment-adjacent asset served publicly is a Google sign-in logo. **No payment
provider branding is exposed on the anonymous cart page.**

---

## 9. Differences worth noting against GFS

Observations, not proposals.

| | MMOExp | GFS |
|---|---|---|
| Quantity | 18 preset chips + custom entry, min 10K | slider + typed field, 10K steps, min 500K |
| Pricing | flat $0.699/K, no volume break | per-million rate card by platform |
| Coupon | code displayed with a Copy button | code typed, applied against the engine |
| Currencies | 16, client-side rate table | INR live; USD/EUR/GBP configured, not enabled |
| Languages | 7 | 3 |
| Consent | one checkbox, three documents | one checkbox, three documents |
| Reviews | Trustpilot, 33,479, named staff | testimonials, no aggregate rating |
| Fees | `[not reachable]` | 5% EA tax absorbed, 2.5% processing, both itemised |

Two things GFS does that this page does not: it names the EA market tax and states who
pays it, and it itemises the processing fee. Neither is visible here before sign-in.

One thing to be careful about copying: the **flat per-K price with no volume discount**
means their headline "from $6.99" is the same unit rate as their $3,495 tier. That is a
pricing decision, not a UI one.
