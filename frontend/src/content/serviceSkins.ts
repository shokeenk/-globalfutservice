/**
 * The colour each service wears.
 *
 * <p>Trading is yellow, boosting blue, coaching red. The three appear in three places —
 * the homepage services row, the picker dialog behind "Start an order", and the quote
 * cards — and a reader who picked a colour in one should meet the same colour in the
 * others. Written once here because the first version was written twice, and two copies
 * of a colour decision is one copy too many.
 *
 * <p><b>These are grounds, not actions.</b> Red is still the only colour on this site
 * that means "press this": every button is red, and none of these three is ever a
 * button. That is what keeps a yellow card from competing with the call to action
 * inside it.
 *
 * <p><b>Each skin carries its own foreground.</b> On a saturated ground the page's three
 * levels of type do not survive: yellow is light enough to keep the near-black ink and a
 * softened step below it, while blue and red invert to white. The chip, the rule, the
 * arrow and the bled numeral all take the card's foreground too, so nothing on a card is
 * coloured for a ground it is not sitting on.
 *
 * <p>Red here is {@code #AE2418}, one stop down from the brand red on buttons, and that
 * stop is load-bearing: the quiet body step measures 4.22:1 against {@code #C1281B} —
 * under AA — and 4.86:1 against this one.
 */
export type ServiceSkinName = 'sun' | 'deep' | 'red'

export type ServiceSkin = {
  /** Ground, border and hover ground for the card itself. */
  panel: string
  /** The small status chip at the top. */
  chip: string
  title: string
  body: string
  /** The short rule under the title, where a card has one. */
  rule: string
  /** The call to action, and the arrow that travels with it. */
  cta: string
  /** A glyph in the card's corner, meant to be seen. */
  mark: string
  /**
   * The plate the card's artwork sits on.
   *
   * <p><b>Matched to the artwork, not to the ground.</b> That is the opposite of every
   * other field here and it is not an oversight: the grounds vary but so does what sits
   * on them, and the two do not vary together. The FUT coin is struck in {@code #FFC93C}
   * on a card that is also {@code #FFC93C} — measured at 1.30:1 against a lightly tinted
   * plate, which is to say invisible — so it needs a dark disc. The Rivals badge is a
   * black triangle and needs a pale one. A single plate colour cannot serve both.
   *
   * <p>The coaching photo carries its own white ground and covers the plate entirely, so
   * it takes the pale disc for consistency with the badge rather than for any effect.
   */
  medallion: string

  /**
   * The giant figure bled off the card's edge.
   *
   * <p>Far fainter than {@code mark} because it is texture rather than a mark — it is
   * `aria-hidden` and exists to stop the row reading as a numbered list. At the corner
   * glyph's strength it would compete with the title.
   */
  numeral: string
}

export const SERVICE_SKINS: Record<ServiceSkinName, ServiceSkin> = {
  sun: {
    /*
     * The grounds are written as literals rather than the `sun` and `deep` tokens they
     * also exist as. Tailwind rescans source files on save but does not re-read
     * `tailwind.config.js`, so a newly added colour key needs a dev-server restart before
     * it compiles — and until then the class silently produces nothing and the card falls
     * through to the page behind it. An arbitrary value comes from the same source scan
     * as everything else here and cannot be missed that way.
     */
    panel: 'border-[#FFC93C] bg-[#FFC93C] hover:bg-[#FFD466]',
    chip: 'bg-chalk/[0.10] text-chalk',
    title: 'text-chalk',
    body: 'text-chalk/80',
    rule: 'bg-chalk/35',
    cta: 'text-chalk',
    mark: 'text-chalk/[0.55]',
    medallion: 'bg-chalk/90 ring-1 ring-chalk/20',
    numeral: 'text-chalk/[0.09] group-hover:text-chalk/[0.14]',
  },
  deep: {
    panel: 'border-[#3A32A3] bg-[#3A32A3] hover:bg-[#453CB8]',
    chip: 'bg-white/[0.16] text-white',
    title: 'text-white',
    body: 'text-white/85',
    rule: 'bg-white/45',
    cta: 'text-white',
    mark: 'text-white/[0.55]',
    medallion: 'bg-white/[0.92] ring-1 ring-white/30',
    numeral: 'text-white/[0.10] group-hover:text-white/[0.16]',
  },
  red: {
    panel: 'border-[#AE2418] bg-[#AE2418] hover:bg-[#C1281B]',
    chip: 'bg-white/[0.16] text-white',
    title: 'text-white',
    body: 'text-white/85',
    rule: 'bg-white/45',
    cta: 'text-white',
    /*
     * 0.62, not the 0.55 the other two use. Red is the darkest of the three grounds, so
     * the same opacity lands differently on each: white at 55% clears 3:1 on the yellow
     * and the blue, and does not here.
     */
    mark: 'text-white/[0.62]',
    medallion: 'bg-white/[0.92] ring-1 ring-white/30',
    numeral: 'text-white/[0.10] group-hover:text-white/[0.16]',
  },
}

/** Which skin a service key wears. Keyed on the same names the picker already uses. */
export const SKIN_FOR_SERVICE: Record<string, ServiceSkinName> = {
  trading: 'sun',
  boosting: 'deep',
  coaching: 'red',
}
