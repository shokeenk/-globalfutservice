import { useMemo, useState } from 'react'
import { TESTIMONIALS } from '../data/testimonials'
import type { Testimonial, TestimonialService } from '../data/testimonials'
import { useI18n, useT } from '../i18n'
import { Reveal } from '../motion/Reveal'
import { SERVICE_SKINS, SKIN_FOR_SERVICE } from '../content/serviceSkins'
import { Section } from './ui'

type Filter = TestimonialService | 'all'

/**
 * Three quotes to a page.
 *
 * <p>Twenty-four cards in one scroll is a wall nobody reads to the bottom of; three
 * is a set you can actually finish, and finishing one is what makes somebody press
 * for the next. Fixed at three across every breakpoint on purpose — a page size that
 * changes with the viewport means the same "next" press lands somewhere different on
 * a phone than on a laptop.
 */
const PAGE_SIZE = 3

/**
 * What customers said, filtered by what the reader is considering buying.
 *
 * <p>The filter is the design. Fourteen quotes in one undifferentiated wall is a
 * wall — somebody weighing up a coaching block does not want to read three
 * paragraphs about a coin delivery to find the one testimony that speaks to them.
 * Split by service, and the section answers the question the reader actually has.
 *
 * <p>Two things this section deliberately does not have. There are no star
 * ratings, because nobody gave a rating and inventing one to fill a row of
 * asterisks is fabricating evidence. There are no avatars, because there are no
 * photographs — a generated face beside a real quote turns a true statement into
 * a false impression.
 *
 * <p>Quotes render exactly as written, in the language they were written in, and
 * are not passed through the dictionary. See {@code data/testimonials.ts}.
 */
export function Testimonials({
  only,
  className = '',
}: {
  /** Restrict to one service and hide the filter — for a product page. */
  only?: TestimonialService
  className?: string
}) {
  const t = useT()
  const [filter, setFilter] = useState<Filter>(only ?? 'all')
  const [page, setPage] = useState(0)

  const shown = useMemo(
    () => (filter === 'all' ? TESTIMONIALS : TESTIMONIALS.filter((x) => x.service === filter)),
    [filter],
  )

  const pageCount = Math.max(1, Math.ceil(shown.length / PAGE_SIZE))
  /*
   * Clamped on read rather than reset in an effect.
   *
   * Switching from "Everything" (24 quotes, 8 pages) to "Coaching" (far fewer) while
   * on page 6 would otherwise slice past the end of the array and render an empty
   * section — the filter would look broken. Deriving the safe page during render
   * means there is never a frame showing nothing.
   */
  const safePage = Math.min(page, pageCount - 1)
  const start = safePage * PAGE_SIZE
  const visible = shown.slice(start, start + PAGE_SIZE)

  const go = (next: number) => setPage(Math.min(Math.max(next, 0), pageCount - 1))

  const pick = (key: Filter) => {
    setFilter(key)
    // Back to the first page: staying on page 4 of a new, shorter list is disorienting
    // even when it is in range, because the reader did not ask to move.
    setPage(0)
  }

  const tabs: { key: Filter; label: string }[] = [
    { key: 'all', label: t.proof.all },
    { key: 'trading', label: t.proof.trading },
    { key: 'boosting', label: t.proof.boosting },
    { key: 'coaching', label: t.proof.coaching },
  ]

  return (
    <Section
      // The target of the hero link. Named for what a visitor calls it, not for
      // what the file is called, because it ends up in the URL bar.
      id="reviews"
      className={`rhythm-page ${className}`}
      eyebrow={t.proof.eyebrow}
      title={t.proof.title}
      lead={only ? undefined : t.proof.lead}
      action={
        only ? undefined : (
          <p className="tnum text-[12px] text-chalk-faint">
            {t.proof.range(start + 1, start + visible.length, shown.length)}
          </p>
        )
      }
    >
      {!only && (
        /*
         * A real tablist, not a row of buttons that happen to filter.
         *
         * `role="tab"` plus `aria-selected` is what tells a screen reader this is
         * one control with four states rather than four unrelated actions, and it
         * is the difference between "Coaching, button" and "Coaching, tab, 4 of 4,
         * selected".
         */
        <div
          role="tablist"
          aria-label={t.proof.title}
          className="mb-8 flex flex-wrap gap-2"
        >
          {tabs.map((tab) => {
            const active = filter === tab.key
            return (
              <button
                key={tab.key}
                role="tab"
                type="button"
                aria-selected={active}
                onClick={() => pick(tab.key)}
                className={[
                  // 44px minimum: this is a filter people use with a thumb.
                  'inline-flex min-h-[44px] items-center rounded-edge border px-4',
                  'text-[13px] font-semibold',
                  'transition-[background-color,border-color,color,transform] duration-200',
                  'ease-out-expo active:scale-95',
                  active
                    ? 'border-brand-500 bg-brand-500 text-paper shadow-glow'
                    : 'border-ink-400 bg-paper text-chalk-muted hover:border-ink-300 hover:text-chalk',
                ].join(' ')}
              >
                {tab.label}
              </button>
            )
          })}
        </div>
      )}

      {/*
        Columns rather than a row of equal cards.
        These quotes are different lengths — two sentences to five — and forcing
        them into a uniform grid either truncates the long ones or leaves the short
        ones floating in dead space. A masonry column flow lets each one be its own
        height, which is what a wall of quotes should look like.
      */}
      {/*
        A grid now, not a masonry column flow.

        Columns were right for a wall of twenty-four: they let quotes of wildly
        different lengths each take their natural height. But column flow fills
        top-to-bottom, so with three items on a page the reading order became
        1-below-2 rather than left-to-right, which is wrong for a set the reader is
        stepping through. `items-start` keeps the thing masonry was protecting —
        each card stays its own height instead of stretching to match the tallest.
      */}
      <div className="grid items-start gap-4 md:grid-cols-2 xl:grid-cols-3">
        {visible.map((item, index) => (
          /*
            Keyed by page as well as identity. Without the page in the key React
            reuses the same three DOM nodes across a press, so the Reveal animation
            never re-runs and the new quotes appear with no transition at all —
            the change reads as a glitch rather than a move.
          */
          <Quote key={`${safePage}-${item.name}-${item.service}`} item={item} index={index} />
        ))}
      </div>

      {pageCount > 1 && (
        <nav
          aria-label={t.proof.title}
          className="mt-8 flex items-center justify-between gap-4"
        >
          <PagerButton
            onClick={() => go(safePage - 1)}
            disabled={safePage === 0}
            label={t.proof.prev}
            direction="prev"
          />

          {/*
            Announced, because pressing Next changes content further up the page
            that a screen-reader user is not looking at. `aria-live` on the position
            is the smallest thing that reports the move without reading all three
            quotes aloud again.
          */}
          <p aria-live="polite" className="tnum text-[12px] text-chalk-faint">
            {t.proof.pageOf(safePage + 1, pageCount)}
          </p>

          <PagerButton
            onClick={() => go(safePage + 1)}
            disabled={safePage === pageCount - 1}
            label={t.proof.next}
            direction="next"
          />
        </nav>
      )}

      {/*
        The disclosure is not boilerplate here.

        Several coaching quotes describe an outcome — a division reached, a win
        count hit. Under the FTC's endorsement guides, the UK DMCC Act and India's
        CCPA rules, publishing outcome testimonials without saying they are the
        individual's own experience is the part that gets a business in trouble.
        It also happens to be true, which is the better reason.
      */}
      <p className="measure mt-10 border-t border-ink-400 pt-5 text-[12px] leading-relaxed text-chalk-faint">
        {t.proof.disclosure}
      </p>
    </Section>
  )
}

/**
 * One card.
 *
 * <p>Where a reviewed translation exists for the current language it is shown, and
 * it is <em>labelled</em> — with the customer's own sentence one click away. The
 * label is not decoration. A testimonial is a claim about what a named person said,
 * so silently substituting our wording for theirs misrepresents them, and in the
 * markets this site sells into that is a regulatory question rather than a matter of
 * taste. Saying "translated" costs a line and settles it.
 *
 * <p>`lang` moves with the text. A screen reader that hits a Spanish sentence inside
 * a French page needs to be told, or it pronounces it as French.
 */
function Quote({ item, index }: { item: Testimonial; index: number }) {
  const t = useT()
  const { lang } = useI18n()
  const [original, setOriginal] = useState(false)

  /*
   * The card wears the colour of the service it is about.
   *
   * Same three skins as the services row and the picker dialog, taken from the same
   * file rather than restated — a reader who chose "Champs & Rivals" in blue should
   * meet blue again when they read what a customer said about it. Each skin carries its
   * own foreground, which is what makes the text legible on a saturated ground: chalk
   * on the yellow, paper on the blue and the red.
   */
  const skin = SERVICE_SKINS[SKIN_FOR_SERVICE[item.service] ?? 'deep']

  const translation = lang === 'en' ? undefined : item.translated?.[lang]
  const showingTranslation = Boolean(translation) && !original
  const body = showingTranslation ? translation! : item.quote

  return (
    <Reveal
      delay={Math.min(index, 5) * 60}
      distance={12}
      // `break-inside` is what stops a column flow slicing a card across the gap.
      className="mb-4 break-inside-avoid"
    >
      <figure className={`h-full rounded-panel border p-6 shadow-e1 ${skin.panel}`}>
        {/*
          An open quote mark set large and dim, bled behind the text. It marks the
          block as testimony without spending a line of copy saying so, and it is
          the one decorative flourish in the section.
        */}
        <span
          aria-hidden="true"
          className={`display block text-[40px] leading-[0.6] ${skin.mark}`}
        >
          &ldquo;
        </span>

        <blockquote
          lang={showingTranslation ? lang : 'en'}
          className={`mt-3 text-body-sm leading-relaxed ${skin.body}`}
        >
          {body}
        </blockquote>

        {translation && (
          <p className={`mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11.5px] ${skin.body}`}>
            <span>{showingTranslation ? t.proof.translated : t.proof.originalLabel}</span>
            <span aria-hidden="true" className="text-ink-300">&middot;</span>
            <button
              type="button"
              onClick={() => setOriginal((v) => !v)}
              className={`rounded-edge font-semibold underline-offset-2 transition-opacity
                          hover:underline hover:opacity-80 ${skin.cta}`}
            >
              {showingTranslation ? t.proof.showOriginal : t.proof.showTranslation}
            </button>
          </p>
        )}

        {/*
          The divider, in the card's own colour rather than the page's.

          It was `border-ink-400`, a lilac hairline chosen against the light surface
          these cards used to have. On a saturated ground that line is either invisible
          or a stripe of the wrong hue, so it takes the skin's rule like everything else
          on the card.
        */}
        <span aria-hidden="true" className={`mt-5 block h-px w-full ${skin.rule}`} />

        <figcaption className="flex items-center gap-2.5 pt-4">
          <span aria-hidden="true" className={`h-3 w-0.5 ${skin.rule}`} />
          <span className={`text-body-sm font-semibold ${skin.title}`}>{item.name}</span>
          {/*
            The country as a letter code, not a flag emoji.

            Flag emoji are regional-indicator pairs, and Windows has never shipped
            glyphs for them — every Windows visitor would see "IN" and "GB" in a
            slightly wrong font instead of a flag. A deliberate letter code renders
            identically everywhere and matches the small-caps language the rest of
            the site already uses for metadata.
          */}
          <span className={`ml-auto font-display text-[10.5px] uppercase tracking-[0.16em] ${skin.body}`}>
            {item.country}
          </span>
        </figcaption>
      </figure>
    </Reveal>
  )
}

/**
 * One end of the pager.
 *
 * <p>A disabled button rather than a hidden one. Hiding the control at the ends
 * shifts the row and moves the other button under the reader's cursor mid-press;
 * keeping it in place and dimmed keeps the layout still and makes the end of the
 * list legible instead of mysterious.
 */
function PagerButton({
  onClick,
  disabled,
  label,
  direction,
}: {
  onClick: () => void
  disabled: boolean
  label: string
  direction: 'prev' | 'next'
}) {
  const arrow = direction === 'next' ? 'M5 12h14M13 6l6 6-6 6' : 'M19 12H5M11 18l-6-6 6-6'
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={[
        // 44px target, same as the filter tabs above it.
        'inline-flex min-h-[44px] items-center gap-2 rounded-edge border px-4',
        'text-[13px] font-semibold',
        'transition-[background-color,border-color,color,transform,opacity] duration-200',
        'ease-out-expo',
        disabled
          ? 'cursor-not-allowed border-ink-400 bg-paper text-chalk-faint opacity-60'
          : 'border-brand-500 bg-brand-500 text-paper shadow-glow active:scale-95 hover:bg-brand-600',
      ].join(' ')}
    >
      {direction === 'prev' && <Arrow d={arrow} />}
      {label}
      {direction === 'next' && <Arrow d={arrow} />}
    </button>
  )
}

function Arrow({ d }: { d: string }) {
  return (
    <svg
      width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"
      className="shrink-0"
    >
      <path d={d} />
    </svg>
  )
}
