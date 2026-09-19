import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Calendar, endOfMonth, startOfMonth } from '../components/Calendar'
import { BUSINESS } from '../content/business'
import { CoachIcon } from '../components/CoachingIcons'
import type { CoachIconName } from '../components/CoachingIcons'
import { Reveal } from '../motion/Reveal'
import { Alert, Badge, Button, ButtonLink, Card, EmptyState, Section, Skeleton, Spinner } from '../components/ui'
import { useI18n, useT } from '../i18n'
import { useCatalogLabels } from '../content/catalogLabels'
import { ApiError, api } from '../lib/api'
import { SEASON, useSeo } from '../lib/seo'
import type { CatalogOption, Coach, CoachSlots, CoachingSession, MyCoaching } from '../lib/types'
import { TESTIMONIALS } from '../data/testimonials'
import { useAuth } from '../state/AuthContext'
import { useCatalog } from '../state/CatalogContext'

/**
 * The coaching page: what coaching is, who runs it, what it costs, and the way in.
 *
 * <p>Every "book" and "buy" control leads to the same place, the booking flow at
 * /coaching/book -- including the "Book your session" button in the coach section. This is a one-coach service and there
 * is no profile page behind that button; sending it anywhere else would be two destinations
 * for one action.
 *
 * <p>A customer who already holds session credits sees the booking calendar straight under
 * the hero, as before. That is how credits are spent, and a redesign of the marketing page is
 * not a reason to hide it from somebody who has paid.
 */
export default function Coaching() {
  const t = useT()
  const p = t.coachingPage
  useSeo({
    title: t.coaching.seoTitle(SEASON),
    description: p.seoDescription(SEASON),
  })

  const { account } = useAuth()
  const { catalog } = useCatalog()

  const [coaches, setCoaches] = useState<Coach[] | null>(null)
  const [mine, setMine] = useState<MyCoaching | null>(null)
  const [error, setError] = useState<string | null>(null)

  const coachingService = catalog?.services.find((s) => s.sku === 'COACHING')

  // Coaches feed the booking calendar only; the page itself presents the one coach.
  useEffect(() => {
    let cancelled = false
    api
      .get<Coach[]>('/api/v1/coaching/coaches')
      .then((list) => !cancelled && setCoaches(list))
      .catch((e: unknown) => {
        if (!cancelled) {
          setCoaches([])
          setError(e instanceof ApiError ? e.message : t.coaching.loadCoachesFailed)
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  const loadMine = useCallback(() => {
    if (!account) {
      setMine(null)
      return
    }
    api
      .get<MyCoaching>('/api/v1/coaching/me')
      .then(setMine)
      .catch(() => setMine(null))
  }, [account])

  useEffect(loadMine, [loadMine])

  const credits = mine?.creditBalance ?? 0

  return (
    <>
      <Hero />

      {account && credits > 0 && (
        <>
          <Section className="pt-8">
            {error && <Alert tone="warn">{error}</Alert>}
            <Alert tone="ok">
              {t.coaching.creditsAlertPrefix} <strong>{credits}</strong>{' '}
              {t.coaching.creditsLeft(credits)}{t.coaching.creditsAlertSuffix}
            </Alert>
          </Section>
          <BookingArea coaches={coaches} mine={mine} onBooked={loadMine} />
        </>
      )}

      {mine && mine.upcoming.length > 0 && (
        <UpcomingSessions sessions={mine.upcoming} onChanged={loadMine} />
      )}

      <Pricing options={coachingService?.options} currency={catalog?.currency} />
      <Process />
      <MeetCoach />
      <Areas />
      <CustomerWords />
      <FinalCta />
    </>
  )
}

/* ------------------------------------------------------------------ shared --------- */

function Eyebrow({ children }: { children: ReactNode }) {
  return (
    <p className="flex items-center gap-2.5 text-[11px] font-semibold uppercase tracking-[0.18em] text-chalk-muted">
      <span aria-hidden="true" className="h-[2px] w-6 bg-brand-500" />
      {children}
    </p>
  )
}

function scrollToCoach() {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  document.getElementById('coach')?.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' })
}

/* -------------------------------------------------------------------- hero ---------- */

/**
 * The artwork is the supplied composition, served from /brand/coaching/ at two widths so a
 * phone does not download the desktop file. If it ever fails to load, the column is simply
 * left out -- the headline takes the width -- rather than showing a broken image.
 */
function Hero() {
  const p = useT().coachingPage
  const [artwork, setArtwork] = useState(true)
  const badges: { icon: CoachIconName; label: string }[] = [
    { icon: 'chat', label: p.badgeFeedback },
    { icon: 'clipboard', label: p.badgeImprovements },
    { icon: 'gamepad', label: p.badgeConfidence },
  ]

  return (
    <section className="relative overflow-hidden bg-paper">
      <div
        className={[
          'mx-auto grid max-w-[1320px] items-center gap-10 px-5 pb-10 pt-10 sm:px-8 lg:gap-12 lg:px-10 lg:pb-14 lg:pt-14',
          artwork ? 'lg:grid-cols-[1fr_1.1fr]' : '',
        ].join(' ')}
      >
        <div>
          <Eyebrow>{p.eyebrow(SEASON)}</Eyebrow>
          <h1 className="display mt-4 max-w-[17ch] text-balance text-[clamp(2.2rem,4.6vw,3.3rem)] leading-[1.04] text-chalk">
            {p.title}
          </h1>
          <p className="mt-5 max-w-[54ch] text-body leading-relaxed text-chalk-muted">{p.lead}</p>

          <div className="mt-7 flex flex-wrap gap-3">
            <ButtonLink to="/coaching/book" size="lg">
              {p.bookCta}
              <CoachIcon name="arrowRight" className="ml-2 h-4 w-4" />
            </ButtonLink>
            <Button variant="secondary" size="lg" onClick={scrollToCoach}>
              {p.meetCta}
            </Button>
          </div>

          <ul className="mt-8 grid gap-4 sm:grid-cols-3">
            {badges.map((badge) => (
              <li key={badge.label} className="flex items-center gap-3 text-[13px] font-medium leading-snug text-chalk">
                <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full border border-brand-500/30 bg-brand-500/[0.06] text-brand-500">
                  <CoachIcon name={badge.icon} />
                </span>
                {badge.label}
              </li>
            ))}
          </ul>
        </div>

        {artwork && (
          <img
            src="/brand/coaching/hero.webp"
            srcSet="/brand/coaching/hero-768.webp 768w, /brand/coaching/hero.webp 1536w"
            sizes="(min-width: 1024px) 52vw, 100vw"
            alt={p.heroAlt}
            width={1536}
            height={1024}
            className="h-auto w-full"
            fetchPriority="high"
            onError={() => setArtwork(false)}
          />
        )}
      </div>
    </section>
  )
}

/* ------------------------------------------------------------------ pricing ---------- */

function Pricing({
  options,
  currency,
}: {
  options: CatalogOption[] | undefined
  currency: string | undefined
}) {
  const t = useT()
  const p = t.coachingPage
  const labels = useCatalogLabels()
  /*
   * The block's saving badge.
   *
   * The block is six 40-minute sessions at Rs.750 each — a different product from
   * the single 1-hour session at Rs.1,000. The list price is therefore 750 × 6 =
   * Rs.4,500, and the block's Rs.4,050 represents a 10% saving on that.
   *
   * Because the block and single are different durations, the list price is derived
   * from the pack's own price (pack / 0.9) rather than from the single-session
   * rate card. If the pack price changes, the badge recalculates automatically.
   */
  const pack = options?.find((o) => o.variant === 'MONTHLY_6_SESSIONS')

  const { listFormatted, savingPercent } = useMemo(() => {
    if (!pack || !currency) return { listFormatted: null, savingPercent: 0 }
    const listMinor = Math.round(pack.unitPriceMinor / 0.9)
    if (listMinor <= pack.unitPriceMinor) return { listFormatted: null, savingPercent: 0 }
    return {
      savingPercent: Math.round(((listMinor - pack.unitPriceMinor) / listMinor) * 100),
      listFormatted: new Intl.NumberFormat(undefined, {
        style: 'currency', currency, maximumFractionDigits: 0,
      }).format(listMinor / 100),
    }
  }, [pack, currency])

  return (
    <Section className="pb-14 pt-2">
      <h2 className="sr-only">{t.coaching.pricingHeading}</h2>

      <div className="grid gap-5 md:grid-cols-2">
        {!options && Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} className="h-60 w-full" />)}

        {options?.map((option, index) => {
          const isPack = option.variant === 'MONTHLY_6_SESSIONS'
          return (
            <Reveal key={option.variant ?? option.label} delay={index * 90}>
              <div
                className={[
                  'relative flex h-full flex-col rounded-panel border p-7',
                  isPack ? 'border-gold-500/40 bg-[#FFFBEF] shadow-e3' : 'border-ink-400 bg-paper shadow-e2',
                ].join(' ')}
              >
                {/*
                  The badge row exists on both cards, empty on the one without a saving.

                  Two cards side by side are read across as much as down: title against
                  title, price against price. Letting the badge and the struck-through
                  list price exist on one card only pushed everything below them down by
                  55px on that side, so the pair read as two different sizes of card
                  rather than two prices for one service. `invisible` keeps the space and
                  takes the placeholder out of the accessibility tree.
                */}
                <Badge
                  tone="gold"
                  className={`self-start ${
                    isPack && savingPercent > 0 ? '' : 'hidden md:invisible md:inline-flex'
                  }`}
                >
                  {p.saveBadge(savingPercent || 10)}
                </Badge>

                <h3 className="display mt-3 text-[1.25rem] text-chalk">
                  {labels.option(option)}
                </h3>

                <div className="mt-4">
                  <p
                    className={`tnum text-body-sm text-chalk-faint line-through ${
                      isPack && listFormatted ? '' : 'hidden md:invisible md:block'
                    }`}
                  >
                    {listFormatted ?? option.unitPriceFormatted}
                  </p>
                  <p className="tnum display text-[clamp(1.9rem,4vw,2.4rem)] leading-none text-chalk">
                    {option.unitPriceFormatted}
                  </p>
                </div>

                <p className="mt-5 flex-1 text-body-sm leading-relaxed text-chalk-muted">
                  {isPack ? p.packBody : p.singleBody}
                </p>

                <ButtonLink
                  to={`/coaching/book?variant=${option.variant ?? ''}`}
                  className="mt-7"
                  full
                  size="md"
                  variant={isPack ? 'primary' : 'secondary'}
                >
                  {isPack ? p.buyPackage : p.buySession}
                </ButtonLink>
              </div>
            </Reveal>
          )
        })}
      </div>
    </Section>
  )
}

/* ------------------------------------------------------------------ process ---------- */

function Process() {
  const p = useT().coachingPage
  const steps: { n: string; icon: CoachIconName; title: string; body: string }[] = [
    { n: '01', icon: 'video', title: p.watchTitle, body: p.watchBody },
    { n: '02', icon: 'bars', title: p.findTitle, body: p.findBody },
    { n: '03', icon: 'bulb', title: p.fixTitle, body: p.fixBody },
    { n: '04', icon: 'checkCircle', title: p.applyTitle, body: p.applyBody },
  ]

  return (
    <section className="border-y border-ink-400 bg-ink-700/30">
      <div className="mx-auto max-w-[1320px] px-5 py-14 sm:px-8 lg:px-10">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <Eyebrow>{p.processEyebrow}</Eyebrow>
            <h2 className="display mt-3 max-w-[18ch] text-balance text-[clamp(1.7rem,3.4vw,2.4rem)] leading-tight text-chalk">
              {p.processTitle}
            </h2>
          </div>
          <p className="text-body-sm text-chalk-muted">{p.processAside}</p>
        </div>

        <ol className="mt-10 grid gap-8 sm:grid-cols-2 lg:grid-cols-4">
          {steps.map((s, i) => (
            <li key={s.n} className="relative flex gap-4">
              <span className="grid h-14 w-14 shrink-0 place-items-center rounded-full bg-brand-500/[0.08] text-brand-500">
                <CoachIcon name={s.icon} className="h-6 w-6" />
              </span>
              <div className="min-w-0">
                <p className="tnum text-[12px] font-bold text-chalk">{s.n}</p>
                <h3 className="text-body-sm font-bold text-chalk">{s.title}</h3>
                <p className="mt-1.5 text-[13px] leading-relaxed text-chalk-muted">{s.body}</p>
              </div>
              {i < steps.length - 1 && (
                <CoachIcon name="arrowRight" className="absolute -right-6 top-5 hidden h-4 w-4 text-chalk-faint lg:block" />
              )}
            </li>
          ))}
        </ol>
      </div>
    </section>
  )
}

/* ------------------------------------------------------------------- coach ---------- */

function MeetCoach() {
  const p = useT().coachingPage

  return (
    <div id="coach" className="scroll-mt-28">
      <Section wide className="py-12">
        {/*
         * `items-start`, not `items-center`. Centring gave every column its own top edge --
         * the photo, the name, the specialities and the quote each floated to the middle of
         * whichever column happened to be tallest, so nothing lined up with anything and the
         * section read as four loose blocks. Top-aligned they share one edge, which is what
         * the rest of the page does.
         *
         * Four columns only from `xl`, and on the wide measure. At `lg` there were 1072px
         * to divide between a photo, a biography, five pills and a pull quote, and the
         * result was four columns none of which had room -- the specialities track came
         * out at 239px against the 310 its first row of pills needs, so "Decision Making"
         * dropped to a line of its own. That is the actual fault: not a spacing value, but
         * a four-column arrangement switched on 256px before it fits. Below `xl` the same
         * blocks pair up two by two, which is what they were always going to do on a
         * laptop anyway.
         *
         * The `minmax` floor then holds the pill row together at every width above that,
         * including translations -- Spanish's "Toma de decisiones" is the longest pill the
         * three dictionaries produce.
         */}
        <div className="grid items-start gap-x-8 gap-y-10 sm:grid-cols-2 xl:grid-cols-[210px_1.45fr_minmax(312px,1.25fr)_1fr]">
          <CoachPortrait alt={p.coachPhotoAlt} />

          <div>
            <Eyebrow>{p.coachEyebrow}</Eyebrow>
            <h2 className="display mt-2.5 text-[2.3rem] leading-none text-chalk">Vinay</h2>
            <p className="mt-1.5 text-[11px] font-semibold uppercase tracking-[0.16em] text-chalk-muted">
              {p.coachTitleLine}
            </p>
            <p className="mt-3 text-body-sm leading-relaxed text-chalk-muted">{p.coachBio}</p>
          </div>

          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-chalk-faint">
              {p.specialtiesLabel}
            </p>
            {/*
             * Fixed height, and no wrapping inside a pill. `py-1` let one grow a line taller
             * the moment its label was long enough to break -- "Decision Making" is one line
             * in English and two in French -- and a taller pill puts its whole row out of
             * step with the row under it. Sized by height rather than by padding, a row stays
             * a row whatever the words are.
             */}
            <ul className="mt-2.5 flex flex-wrap gap-2">
              {p.specialties.map((item) => (
                <li
                  key={item}
                  className="inline-flex h-7 items-center whitespace-nowrap rounded-full border
                             border-ink-400 bg-paper px-3 text-[12px] leading-none text-chalk-muted"
                >
                  {item}
                </li>
              ))}
            </ul>
            <p className="mt-3 flex items-center gap-2 text-[13px] text-chalk-muted">
              <CoachIcon name="globe" className="h-4 w-4" />
              <span className="font-semibold text-chalk">{p.languagesLabel}</span> {p.languagesValue}
            </p>
            {/* V1 has one coach and no profile page: this starts the same booking as every other button. */}
            <ButtonLink to="/coaching/book" variant="secondary" size="md" className="mt-4">
              {p.viewProfile}
            </ButtonLink>
          </div>

          <figure className="rounded-panel border border-ink-400 bg-paper p-5 shadow-e2">
            <CoachIcon name="quote" className="h-7 w-7 text-brand-500" strokeWidth={2.4} />
            <blockquote className="mt-3 text-[1.05rem] leading-relaxed text-chalk">{p.coachQuote}</blockquote>
            <figcaption className="mt-3 text-[13px] font-semibold text-chalk">— Vinay</figcaption>
          </figure>
        </div>
      </Section>
    </div>
  )
}

/**
 * The coach photograph, in its panel.
 *
 * <p>The red wedge and the badge belong to the panel, not to the photograph. In the
 * original design both were baked into the picture -- it was shot against a red studio
 * diagonal, and the badge was the logo printed on the shirt -- so changing the photograph
 * took the treatment with it and left a bare headshot on a grey wall. Built in CSS
 * instead, the composition survives the next photograph, and the one after that.
 *
 * <p>The wedge is a frame, not an overlay: it shows in the panel's padding around the
 * top-left corner and stops at the image. Painting red across the subject would be closer
 * to the reference and worse to look at.
 */
function CoachPortrait({ alt }: { alt: string }) {
  return (
    <div className="relative mx-auto w-[210px] sm:mx-0">
      <div className="relative overflow-hidden rounded-panel bg-gray-100 p-3.5 shadow-e3">
        <span
          aria-hidden="true"
          className="absolute inset-0 bg-brand-500"
          style={{ clipPath: 'polygon(0 0, 88% 0, 0 88%)' }}
        />
        <img
          src="/brand/coaches/vinay-512.webp"
          srcSet="/brand/coaches/vinay-256.webp 256w, /brand/coaches/vinay-512.webp 512w"
          sizes="182px"
          alt={alt}
          width={512}
          height={640}
          loading="lazy"
          className="relative aspect-[4/5] w-full rounded-edge object-cover"
        />
      </div>
      {/* Decorative: the business names itself three times on this page already. */}
      <img
        src="/brand/gfs-badge.png"
        alt=""
        aria-hidden="true"
        width={128}
        height={128}
        loading="lazy"
        className="absolute -bottom-3 -right-3 h-14 w-14 rounded-full object-cover shadow-e2 ring-[3px] ring-paper"
      />
    </div>
  )
}

/* ------------------------------------------------------------------- areas ---------- */

const AREA_ICONS: Record<string, CoachIconName> = {
  attacking: 'target',
  defending: 'shield',
  buildup: 'trend',
  tactics: 'sliders',
  decisions: 'brain',
  champs: 'trophy',
}

function Areas() {
  const p = useT().coachingPage

  return (
    <section className="border-t border-ink-400 bg-ink-700/30">
      <div className="mx-auto max-w-[1320px] px-5 py-14 sm:px-8 lg:px-10">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <Eyebrow>{p.areasEyebrow}</Eyebrow>
            <h2 className="display mt-3 text-balance text-[clamp(1.7rem,3.4vw,2.4rem)] leading-tight text-chalk">
              {p.areasTitle}
            </h2>
          </div>
          <p className="max-w-sm text-body-sm text-chalk-muted">{p.areasAside}</p>
        </div>

        <ul className="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
          {p.areas.map((area) => (
            <li key={area.key} className="rounded-panel border border-ink-400 bg-paper p-5 shadow-e1">
              <CoachIcon name={AREA_ICONS[area.key] ?? 'target'} className="h-7 w-7 text-brand-500" />
              <h3 className="mt-4 text-body-sm font-bold text-chalk">{area.title}</h3>
              <p className="mt-1.5 text-[12.5px] leading-relaxed text-chalk-muted">{area.body}</p>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}

/* ----------------------------------------------------------------- customers --------- */

/** The three published coaching testimonials, word for word, from the data file. */
const FEATURED = ['Manish', 'Deepak', 'Saurabh']

function CustomerWords() {
  const { t, lang } = useI18n()
  const p = t.coachingPage
  const quotes = FEATURED
    .map((name) => TESTIMONIALS.find((q) => q.service === 'coaching' && q.name === name))
    .filter((q): q is (typeof TESTIMONIALS)[number] => !!q)

  return (
    <Section className="py-14">
      <Eyebrow>{p.reviewsEyebrow}</Eyebrow>
      <h2 className="display mt-3 text-balance text-[clamp(1.7rem,3.4vw,2.4rem)] leading-tight text-chalk">
        {p.reviewsTitle}
      </h2>

      <ul className="mt-8 grid gap-5 md:grid-cols-3">
        {quotes.map((q) => {
          const translated = lang === 'es' || lang === 'fr' ? q.translated?.[lang] : undefined
          return (
            <li key={q.name} className="flex flex-col rounded-panel bg-[#3B2FB6] p-6 text-white shadow-e3">
              <CoachIcon name="quote" className="h-6 w-6 text-white/70" strokeWidth={2.4} />
              <blockquote className="mt-3 flex-1 text-[13.5px] leading-relaxed text-white/90">
                {translated ?? q.quote}
              </blockquote>
              {translated && <p className="mt-3 text-[11px] text-white/55">{t.proof.translated}</p>}
              <div className="mt-5 flex items-center justify-between border-t border-white/15 pt-4 text-[12.5px]">
                <span className="flex items-center gap-2 font-semibold">
                  <span aria-hidden="true" className="h-3.5 w-[2px] bg-white/70" />{q.name}
                </span>
                <span className="tracking-[0.14em] text-white/60">{q.country}</span>
              </div>
            </li>
          )
        })}
      </ul>
    </Section>
  )
}

/* -------------------------------------------------------------------- cta ----------- */

function FinalCta() {
  const t = useT()
  const p = t.coachingPage

  return (
    <Section className="pb-10 pt-2">
      {/* One row only from lg: at tablet width the three parts do not fit and the button was clipped. */}
      <div className="flex flex-col items-start gap-6 rounded-panel border border-brand-500/20 bg-brand-500/[0.05] p-7 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex shrink-0 items-center gap-4">
          <CoachIcon name="bars" className="h-10 w-10 shrink-0 text-brand-500" />
          <p className="display text-[1.35rem] leading-tight text-chalk">
            {p.ctaTitle1}
            <br />
            {p.ctaTitle2}
          </p>
        </div>
        <p className="max-w-md text-[13px] leading-relaxed text-chalk-muted lg:min-w-0 lg:flex-1">{p.ctaBody}</p>
        <ButtonLink to="/coaching/book" size="lg" className="shrink-0">
          {p.ctaButton}
          <CoachIcon name="arrowRight" className="ml-2 h-4 w-4" />
        </ButtonLink>
      </div>

      {/* The disclosure stays: it is what makes the quotes above something a reader can weigh. */}
      <div className="mt-8 flex flex-col gap-3 border-t border-ink-400 pt-6 text-[12px] leading-relaxed text-chalk-faint md:flex-row md:justify-between">
        <p className="max-w-3xl">{t.proof.disclosure}</p>
        <p className="flex shrink-0 items-center gap-2">
          <span aria-hidden="true" className="h-[2px] w-5 bg-brand-500" />
          {p.tagline}
        </p>
      </div>
    </Section>
  )
}

/* -------------------------------------------------------------------- booking -------- */

function BookingArea({
  coaches,
  mine,
  onBooked,
}: {
  coaches: Coach[] | null
  mine: MyCoaching | null
  onBooked: () => void
}) {
  const t = useT()
  const [selected, setSelected] = useState<string | null>(null)
  const active = coaches?.find((c) => c.id === selected) ?? coaches?.[0] ?? null

  useEffect(() => {
    if (!selected && coaches && coaches.length > 0) {
      setSelected(coaches[0]!.id)
    }
  }, [coaches, selected])

  if (!coaches) {
    return (
      <Section className="py-6">
        <Skeleton className="h-72 w-full" />
      </Section>
    )
  }
  if (coaches.length === 0) {
    return (
      <Section className="py-6">
        <EmptyState title={t.coaching.noCoachesTitle}>{t.coaching.noCoachesBody}</EmptyState>
      </Section>
    )
  }

  return (
    <Section className="py-6" title={t.coaching.bookTitle}>
      {coaches.length > 1 && (
        <div className="mb-5 flex flex-wrap gap-2">
          {coaches.map((coach) => (
            <button
              key={coach.id}
              type="button"
              onClick={() => setSelected(coach.id)}
              aria-pressed={active?.id === coach.id}
              className={[
                'rounded-edge border px-4 py-2.5 text-[13.5px] font-semibold',
                'transition-[background-color,border-color,color,transform] duration-200',
                'ease-out-expo active:scale-95',
                active?.id === coach.id
                  ? 'border-brand-500 bg-brand-500 text-paper shadow-glow'
                  : 'border-ink-400 bg-ink-700 text-chalk-muted hover:border-ink-300 hover:text-chalk',
              ].join(' ')}
            >
              {coach.displayName}
            </button>
          ))}
        </div>
      )}

      {active && <SlotPicker coach={active} policy={mine?.policy} onBooked={onBooked} />}
    </Section>
  )
}

/** The viewer's own zone. Everything is rendered in it, and it is always named. */
const VIEWER_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone

/**
 * Are two IANA zone names the same place?
 *
 * <p>Not a string comparison. A great many zones have more than one name — India is
 * `Asia/Kolkata` and the legacy `Asia/Calcutta`, and a browser will happily report one
 * while the coach's record holds the other. Compared as strings, an Indian customer
 * booking an Indian coach was told "Times shown in Asia/Calcutta · Vinay coaches from
 * Asia/Kolkata", which reads as two different countries.
 *
 * <p>`Intl` resolves an alias to its canonical form, so this asks the question properly.
 * Comparing current UTC offsets instead would have been wrong in the other direction:
 * London and Lisbon share an offset for part of the year and diverge for the rest, and a
 * customer in one booking a coach in the other very much needs telling.
 */
function sameZone(a: string, b: string): boolean {
  if (a === b) return true
  const canonical = (zone: string) => {
    try {
      return new Intl.DateTimeFormat('en-US', { timeZone: zone }).resolvedOptions().timeZone
    } catch {
      return zone
    }
  }
  return canonical(a) === canonical(b)
}

function SlotPicker({
  coach,
  policy,
  onBooked,
}: {
  coach: Coach
  policy: MyCoaching['policy'] | undefined
  onBooked: () => void
}) {
  const t = useT()
  const [slots, setSlots] = useState<CoachSlots | null>(null)
  const [loading, setLoading] = useState(true)
  const [day, setDay] = useState<string | null>(null)
  const [booking, setBooking] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirmed, setConfirmed] = useState<CoachingSession | null>(null)
  const [month, setMonth] = useState(() => startOfMonth(new Date()))

  /*
   * The booking window, from policy rather than from a constant here.
   *
   * `maxAdvanceDays` is served by the API precisely so this does not have to guess.
   * Hardcoding sixty would mean the calendar keeps offering a month that returns
   * nothing the day somebody shortens the horizon.
   */
  const today = useMemo(() => new Date(), [])
  const maxDate = useMemo(() => {
    const days = policy?.maxAdvanceDays ?? 60
    return new Date(today.getFullYear(), today.getMonth(), today.getDate() + days)
  }, [today, policy?.maxAdvanceDays])

  /*
   * Slots are fetched for the visible month, not once for a fixed fortnight.
   *
   * The endpoint takes `from`/`to` and clamps whatever it is given to the bookable
   * window, so paging to a month that is entirely out of bounds returns an empty
   * list rather than an error — which is exactly what lets the grid page freely.
   */
  const loadSlots = useCallback(async (visible: Date, signal?: { cancelled: boolean }) => {
    setLoading(true)
    setError(null)
    const from = new Date(Math.max(startOfMonth(visible).getTime(), today.getTime()))
    const to = endOfMonth(visible)
    try {
      const data = await api.get<CoachSlots>(
        `/api/v1/coaching/coaches/${encodeURIComponent(coach.id)}/slots`
        + `?from=${encodeURIComponent(from.toISOString())}`
        + `&to=${encodeURIComponent(to.toISOString())}`,
      )
      if (signal?.cancelled) return
      setSlots(data)
    } catch (e: unknown) {
      if (signal?.cancelled) return
      setError(e instanceof ApiError ? e.message : t.coaching.loadSlotsFailed)
    } finally {
      if (!signal?.cancelled) setLoading(false)
    }
  }, [coach.id, today])

  useEffect(() => {
    const signal = { cancelled: false }
    void loadSlots(month, signal)
    return () => {
      signal.cancelled = true
    }
  }, [loadSlots, month])

  /**
   * Group by the viewer's local day.
   *
   * The server sends instants; which day one falls on is a question only the viewer's zone
   * can answer, and it is not the same answer the coach would give.
   */
  const byDay = useMemo(() => {
    const groups = new Map<string, string[]>()
    for (const iso of slots?.slots ?? []) {
      const key = new Date(iso).toLocaleDateString('en-CA') // stable YYYY-MM-DD
      const bucket = groups.get(key)
      if (bucket) bucket.push(iso)
      else groups.set(key, [iso])
    }
    return groups
  }, [slots])

  const days = useMemo(() => Array.from(byDay.keys()), [byDay])
  const activeDay = day && byDay.has(day) ? day : days[0] ?? null
  const times = activeDay ? byDay.get(activeDay) ?? [] : []

  async function book(iso: string) {
    setBooking(iso)
    setError(null)
    try {
      const session = await api.post<CoachingSession>('/api/v1/coaching/sessions', {
        coachId: coach.id,
        startsAt: iso,
        timezone: VIEWER_ZONE,
      })
      setConfirmed(session)
      onBooked()
      // The slot we just took, and anything overlapping it, is gone. Re-read rather than
      // filtering locally — the server knows about other people's bookings too.
      await loadSlots(month)
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : t.coaching.bookingFailed)
      // Somebody else took it first. Re-read so the grid stops offering a dead slot.
      if (e instanceof ApiError && e.code === 'slot_unavailable') {
        await loadSlots(month).catch(() => undefined)
      }
    } finally {
      setBooking(null)
    }
  }

  if (loading) return <Skeleton className="h-64 w-full" />

  return (
    <Card className="p-4 sm:p-6">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-[13px] text-chalk-muted">
          {t.coaching.timesShownIn}{' '}
          {/*
            When the two are the same place, the coach's own spelling is shown rather
            than the browser's. Both are correct; `Asia/Kolkata` is the current IANA
            primary name and `Asia/Calcutta` is the deprecated alias a browser may
            still report, and there is no reason to show a customer the older one.
          */}
          <strong className="text-chalk">
            {sameZone(coach.timezone, VIEWER_ZONE) ? coach.timezone : VIEWER_ZONE}
          </strong>
          {!sameZone(coach.timezone, VIEWER_ZONE) && (
            <> · {t.coaching.coachesFrom(coach.displayName, coach.timezone)}</>
          )}
        </p>
        {policy && (
          <p className="text-[13px] text-chalk-faint">
            {t.coaching.policyLineBoth(policy.sessionMinutes, policy.blockSessionMinutes,
                                      policy.changeCutoffHours)}
          </p>
        )}
        {/*
          Where the session actually gets arranged.

          The terms of service say scheduling and session communication happen on the
          official Discord, so the booking screen is where that has to be said — a customer
          who has just paid should not have to find clause 6 to learn how the coach reaches
          them.
        */}
        <p className="text-[13px] text-chalk-faint">
          {t.coaching.coachDiscord}{' '}
          <a
            className="text-chalk underline"
            href={BUSINESS.discordDm}
            target="_blank"
            rel="noreferrer"
          >
            {BUSINESS.discordName}
          </a>
        </p>
      </div>

      {confirmed && (
        <div className="mt-4">
          <Alert tone="ok">
            {t.coaching.bookedFor}{' '}
            <strong>
              {new Date(confirmed.startsAt).toLocaleString(undefined, {
                weekday: 'long',
                day: 'numeric',
                month: 'long',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </strong>
            {t.coaching.emailedDetails}
          </Alert>
        </div>
      )}

      {error && (
        <div className="mt-4">
          <Alert tone="warn">{error}</Alert>
        </div>
      )}

      {/*
        Calendar and times, side by side.

        The month answers "when could I?" and the column answers "what time?" — two
        different questions, so they get two panes rather than one list the reader has
        to re-scan after every date change. Stacked on a phone, where a month grid and
        a time column cannot both be legible at once.
      */}
      <div className="mt-5 grid gap-4 lg:grid-cols-[minmax(0,320px)_1fr] lg:items-start">
        <Calendar
          month={month}
          onMonthChange={setMonth}
          availableDays={new Set(days)}
          selected={activeDay}
          onSelect={setDay}
          minDate={today}
          maxDate={maxDate}
          loading={loading}
          labels={{
            previousMonth: t.coaching.previousMonth,
            nextMonth: t.coaching.nextMonth,
            available: t.coaching.dayAvailable,
            unavailable: t.coaching.dayUnavailable,
          }}
        />

        <div className="plate p-4 sm:p-5">
          {activeDay ? (
            <>
              <p className="stamp mb-4">
                {new Intl.DateTimeFormat(undefined, {
                  weekday: 'long', day: 'numeric', month: 'long',
                }).format(new Date(`${activeDay}T12:00:00`))}
              </p>

              {/*
                Times are set in tabular figures on a fixed grid.

                A booking grid where 09:00 and 11:30 render at different widths reads
                as a jumble; locked digit widths turn the same list into a column you
                can scan. The whole tile is the target — 48px tall, comfortably past
                the touch minimum — and every other tile dims while one is booking, so
                it is unambiguous which slot the spinner belongs to.
              */}
              <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">
                {times.map((iso) => (
                  <button
                    key={iso}
                    type="button"
                    disabled={booking !== null}
                    onClick={() => void book(iso)}
                    className={[
                      'tnum grid h-12 place-items-center rounded-edge border text-[13.5px]',
                      'font-semibold transition-[background-color,border-color,color,transform]',
                      'duration-200 ease-out-expo active:scale-95',
                      booking === iso
                        ? 'border-brand-500 bg-brand-500 text-paper'
                        : 'border-ink-400 bg-ink-700 text-chalk hover:border-ink-300 hover:bg-ink-600',
                      booking !== null && booking !== iso ? 'pointer-events-none opacity-40' : '',
                    ].join(' ')}
                  >
                    {booking === iso ? (
                      <Spinner size={16} />
                    ) : (
                      new Date(iso).toLocaleTimeString(undefined, {
                        hour: '2-digit',
                        minute: '2-digit',
                      })
                    )}
                  </button>
                ))}
              </div>
            </>
          ) : (
            /*
              An empty state that names the month rather than the service.

              "No free times" on a page where the reader has just paged to December is
              ambiguous — it reads as "this coach never works". Naming the month makes
              it clear the answer is scoped to what is on screen, and that paging back
              is worth trying.
            */
              <div className="grid min-h-[180px] place-items-center px-4 text-center">
              <div>
                <span aria-hidden="true" className="mx-auto mb-4 block h-px w-10 bg-brand-500" />
                <p className="text-body-sm text-chalk">
                  {t.coaching.noSlotsInMonth(
                    new Intl.DateTimeFormat(undefined, { month: 'long' }).format(month),
                  )}
                </p>
                <p className="mt-2 text-[12.5px] text-chalk-faint">
                  {t.coaching.noSlotsBody(coach.displayName)}
                </p>
              </div>
            </div>
          )}
        </div>
      </div>
    </Card>
  )
}

/* ------------------------------------------------------------------ upcoming --------- */

function UpcomingSessions({
  sessions,
  onChanged,
}: {
  sessions: CoachingSession[]
  onChanged: () => void
}) {
  const t = useT()
  return (
    <Section className="py-10" title={t.coaching.upcomingTitle}>
      <div className="grid gap-3">
        {sessions.map((session) => (
          <SessionRow key={session.ref} session={session} onChanged={onChanged} />
        ))}
      </div>
      <p className="mt-4 text-[13px] text-chalk-faint">
        {t.coaching.alsoAppears}{' '}
        <Link to="/account" className="underline hover:text-chalk">
          {t.coaching.yourAccount}
        </Link>
        .
      </p>
    </Section>
  )
}

function SessionRow({
  session,
  onChanged,
}: {
  session: CoachingSession
  onChanged: () => void
}) {
  const t = useT()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function cancel() {
    // The warning and the outcome come from the same server-computed flag, so this can
    // never promise a refund the cancel endpoint then declines to give.
    const warning = session.cancelRefundsCredit
      ? t.coaching.cancelRefunds
      : t.coaching.cancelForfeits
    if (!window.confirm(warning)) return

    setBusy(true)
    setError(null)
    try {
      await api.post(`/api/v1/coaching/sessions/${encodeURIComponent(session.ref)}/cancel`)
      onChanged()
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : t.coaching.cancelFailed)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className="flex flex-wrap items-center justify-between gap-4 p-5">
      <div>
        <p className="font-semibold text-chalk">
          {new Date(session.startsAt).toLocaleString(undefined, {
            weekday: 'long',
            day: 'numeric',
            month: 'long',
            hour: '2-digit',
            minute: '2-digit',
          })}
        </p>
        <p className="text-[13px] text-chalk-muted">
          {t.coaching.withCoach} {session.coachName} · {session.ref}
        </p>
        {error && (
          <p className="mt-1 text-[13px] text-rose-400" role="alert">
            {error}
          </p>
        )}
      </div>

      <div className="flex items-center gap-2">
        {/* A plain anchor, not ButtonLink: the meeting URL points at Discord or Meet,
            and a react-router Link would try to resolve it as an in-app route. */}
        {session.meetingUrl && (
          <a
            href={session.meetingUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center justify-center rounded-lg bg-brand-500 px-3 py-1.5
                       text-[13px] font-semibold text-paper transition-colors hover:bg-brand-400"
          >
            {t.coaching.join}
          </a>
        )}
        <Button variant="ghost" size="sm" disabled={busy} onClick={() => void cancel()}>
          {busy ? <Spinner size={16} /> : t.coaching.cancel}
        </Button>
      </div>
    </Card>
  )
}
