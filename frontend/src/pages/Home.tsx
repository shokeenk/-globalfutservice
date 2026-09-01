import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { BrandBadge } from '../brand/Logo'
import { Atmosphere } from '../components/Atmosphere'
import { PromoCarousel } from '../components/PromoCarousel'
import { LoyaltyCurrencyNotice } from '../components/LoyaltyNotice'
import { Testimonials } from '../components/Testimonials'
import { Badge, ButtonLink, Section } from '../components/ui'
import { SERVICE_SKINS } from '../content/serviceSkins'
import { StartOrderButton } from '../components/ServicePicker'
import { useT } from '../i18n'
import { bpsToPercent } from '../lib/format'
import { useMoney } from '../lib/money'
import { SEASON, useSeo } from '../lib/seo'
import { CountUp, Reveal } from '../motion/Reveal'
import { useCatalog } from '../state/CatalogContext'

/**
 * The homepage.
 *
 * <p>Sequenced as an argument rather than as a list of sections. It opens with the
 * claim, backs it with the operating numbers, shows the three things you can buy,
 * explains what you are actually paying for, then handles the objection that stops
 * most people (coaching is a person, not a bot), then the reason to come back
 * (rewards), then removes the last obstacle (ask a question), then asks for the sale.
 *
 * <p>Nothing here is decorative. If a section could be deleted without weakening the
 * argument, it has been.
 */
export default function Home() {
  const t = useT()
  useSeo({
    title: t.home.seoTitle(SEASON),
    description: t.home.seoDescription(SEASON),
  })

  return (
    <>
      <Hero />
      <Rail />
      <Proof />
      <Services />
      <Process />
      <CoachingBand />
      <WhyUs />
      <Testimonials />
      <RewardsBand />
      <AskBand />
      <ClosingCta />
    </>
  )
}

/* -------------------------------------------------------------------- hero --- */

function Hero() {
  const { policy } = useCatalog()
  const t = useT()

  return (
    <section className="relative isolate overflow-hidden">
      {/*
        FC26 key art behind the whole hero.

        This replaces the generated <Atmosphere> layer on the homepage: a photographic
        stadium and a synthetic one stacked read as mud, so here the photo wins.
        Atmosphere is kept for the interior page headers (see the section below).

        The site is a light theme — `chalk` type is dark ink on near-white paper — so
        the hero type is flipped to light and seated on a brand-ink scrim. The scrim is
        weighted to the left, under the headline, and eased to almost nothing on the
        right so the play on the pitch still reads. 182KB self-hosted WebP; the CSP
        already allows `img-src 'self'`.
      */}
      <img
        src="/brand/hero-fc26.webp"
        alt=""
        aria-hidden="true"
        draggable={false}
        className="pointer-events-none absolute inset-0 -z-10 h-full w-full object-cover object-center"
      />
      {/*
        The scrim, in two forms, because the layout under it is two different layouts.

        Wide, the statement holds the left column and the carousel the right, so the
        scrim is weighted left and eases off to let the play on the pitch read. Narrow,
        that same gradient is wrong in a way that is easy to miss: the headline now runs
        the full width, and its last words land past the 78% stop where the scrim has
        faded to 0.12 — measured, the red clause dropped to 1.15:1 there. So the phone
        gets a near-flat scrim and keeps its contrast; the desktop keeps its photograph.

        The scrim is the palette's own near-black, #111114. It used to be #17112E — a
        purple-black inherited from the retired lilac palette, and by area the single
        largest colour on the page. Once the palette settled on neutral + red with gold
        and blue as service accents, that violet cast was the one hue on screen that
        belonged to nothing. Neutral is also marginally darker, so every measurement
        taken against the old scrim still holds with a little room to spare.
      */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-10 lg:hidden"
        style={{
          background:
            'linear-gradient(90deg, rgba(17,17,20,0.94) 0%, rgba(17,17,20,0.90) 60%, rgba(17,17,20,0.88) 100%),' +
            'linear-gradient(180deg, rgba(17,17,20,0.55) 0%, rgba(17,17,20,0) 26%, rgba(17,17,20,0) 60%, rgba(17,17,20,0.80) 100%)',
        }}
      />
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-10 hidden lg:block"
        style={{
          background:
            'linear-gradient(90deg, rgba(17,17,20,0.96) 0%, rgba(17,17,20,0.92) 42%, rgba(17,17,20,0.80) 60%, rgba(17,17,20,0.34) 78%, rgba(17,17,20,0.12) 100%),' +
            'linear-gradient(180deg, rgba(17,17,20,0.55) 0%, rgba(17,17,20,0) 26%, rgba(17,17,20,0) 60%, rgba(17,17,20,0.80) 100%)',
        }}
      />

      {/*
        Full height on desktop only.

        A forced 100dvh hero on a phone is how you end up with a headline, half a
        button, and nothing else — the viewport is short and the browser chrome eats
        it. On a phone the hero is exactly as tall as its content needs, and the
        cinematic framing comes back at `lg` where there is room for it.
      */}
      <div
        /*
          The hero reserves a viewport minus the header — and the header is 150px
          now, not 72px. That literal was written when the nav was one bar, and it
          survived the split: the hero was reserving a full screen *below* a header
          that already took 150px of it, so the section was 78px taller than the
          window by construction and the button could not be above the fold on any
          display, at any font size. Reads the variable, which tracks the header.
        */
        className="relative mx-auto flex max-w-[1320px] flex-col justify-center px-5 pb-20 pt-16
                   sm:px-8 lg:min-h-[calc(100dvh-var(--header-h)-6rem)] lg:px-10 lg:pb-16 lg:pt-8"
      >
        <div className="grid items-center gap-10 lg:grid-cols-[1fr_1fr] lg:gap-12">
          {/* ----------------------------------------------------- statement --- */}
          <div>
            {/*
              One headline, one emphasis.

              The clause that names the product is set in red rather than the etched
              outline it used to carry. Etching was a way to emphasise without spending
              colour, which mattered when red belonged to the button alone; at this size
              the headline is not competing with anything, and the plain red says the
              same thing more directly.
            */}
            <Reveal delay={80}>
              {/*
                Sized to land in two lines, not three.

                At `display-2xl` and 14ch this ran to three lines and pushed the button
                past the fold on anything shorter than a 900px window — which is most
                laptops once browser chrome is counted. A step down the scale and a
                wider measure buys back roughly eighty pixels without the headline
                stopping being the loudest thing on the screen.
              */}
              <h1 /*
                  Sized to the column it now lives in.

                  At `display-3xl` this ran to six lines and 470px once the carousel took
                  the other half of the hero — a headline that tall pushes its own button
                  off the screen. The words did not change; the space did.
                */
                /*
                  Word spacing opened, letter spacing left alone.

                  `.display` sets -0.035em tracking, which Poppins needs — it is a
                  geometric face with wide round forms that look loose at default. But
                  tightening letters also tightens the gaps between words, and at this
                  size the headline was running together into single long shapes.
                  Widening the word gap separates the phrases without undoing the
                  tracking the typeface actually wants.
                */
                /*
                  `text-lift-on-image`, not the flat-white variant: this headline sits on
                  a photograph, where a soft glow does nothing and a real shadow is what
                  separates the type from whatever is behind it. The flat-white version
                  exists for headings on the page itself, and is deliberately used on
                  none of them — a text shadow on white reads as cheap unless the type is
                  very large, and only this one is.
                */
                className="display max-w-[24ch] text-balance text-display-xl text-lift-on-image
                           [word-spacing:0.14em]">
                <span className="text-white">{t.home.hero.titleLead}</span>{' '}
                <span className="text-brand-on-dark">{t.home.hero.titleAccent(SEASON)}</span>
              </h1>
            </Reveal>

            <Reveal delay={140}>
              {/*
                Up two steps, from body-sm to body-lg.

                This is the only line that says what the service is for, and it was set
                smaller than the trust facts underneath it — so the eye reached the
                bullet list before the sentence explaining why any of it matters. At
                17px it reads as a standfirst, which is the job it is doing.
              */}
              <p className="measure mt-4 text-pretty text-body-lg text-white/90">
                {t.home.hero.subLine}
              </p>
            </Reveal>

            {/*
              The proof row.

              This replaced a three-line paragraph that said the same things in prose.
              Nobody reads a paragraph in a hero; they scan it, and a scan of prose
              returns nothing. Three facts with separators return all three.

              The guarantee is read from the live policy rather than typed, so it
              cannot drift from what the checkout actually honours — the number in a
              trust line being wrong is worse than the line not being there.
            */}
            <Reveal delay={180}>
              <ul className="mt-5 flex flex-wrap items-center gap-x-3 gap-y-1.5 text-body-sm
                             font-medium text-white">
                {[
                  t.home.hero.trustSpeed,
                  t.home.hero.trustGuarantee(policy?.guaranteeDays ?? 7),
                  t.home.hero.trustTax,
                ].map((fact, i) => (
                  <li key={fact} className="flex w-full items-center gap-3 sm:w-auto">
                    {/*
                      The divider separates two facts sitting side by side. Once the row
                      wraps — which it does on a phone, one fact per line — it is a rule
                      dangling at the start of a line, separating nothing.
                    */}
                    {i > 0 && (
                      <span aria-hidden="true" className="hidden h-3.5 w-px bg-white/25 sm:block" />
                    )}
                    <span className="inline-flex items-center gap-1.5">
                      <Tick />
                      {fact}
                    </span>
                  </li>
                ))}
              </ul>
            </Reveal>

            {/*
              Social proof above the button, not four screens below it.

              The testimonials section is eighth of eleven on this page, which means a
              first-time visitor decides whether to trust the site long before reaching
              it. This is the one thing that would change their mind, put where the
              decision is actually made.

              The count comes from the data. A hardcoded number is wrong the first time
              a quote is added, and a wrong number on a trust signal is worse than none.
              There is deliberately no star rating: none was ever collected, and
              inventing one to fill the space is the exact thing this section exists to
              be the opposite of.
            */}
            <Reveal delay={200}>
              {/*
                A control, not a sentence with a circle in front of it.

                This was an empty `BrandRing` beside a line of text, and an empty ring
                is what a loading spinner looks like — the eye reads it as something
                that has not finished rather than something to click. It is now a real
                target: a chip with a ground, a border, and a mark that means what it
                is next to.

                Still an anchor to `#reviews`, still one tap, still the same place in
                the layout.
              */}
              <a
                href="#reviews"
                className="group mt-6 inline-flex items-center gap-3 rounded-press border
                           border-white/25 bg-white/10 backdrop-blur-sm py-2 pl-2 pr-4 shadow-media
                           transition-[transform,border-color,box-shadow] duration-300
                           ease-out-expo hover:-translate-y-0.5 hover:border-brand-500/45
                           hover:shadow-media-lg focus-visible:-translate-y-0.5"
              >
                {/*
                  A quotation mark rather than a star. Stars would be a rating, and no
                  rating was ever collected; a quote mark says "these are words people
                  wrote", which is exactly what is behind the link.
                */}
                <span
                  aria-hidden="true"
                  className="grid h-9 w-9 shrink-0 place-items-center rounded-edge
                             bg-brand-500/[0.10] text-brand-400 transition-colors
                             duration-300 group-hover:bg-brand-500/[0.16]"
                >
                  <svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor"
                       aria-hidden="true">
                    <path d="M9.6 5.8c-3 1.5-4.9 4.2-4.9 7.6 0 3 1.8 4.8 4 4.8 2 0 3.5-1.5
                             3.5-3.4 0-1.9-1.3-3.2-3.1-3.2-.35 0-.8.07-.9.1.3-1.5 1.8-3.2
                             3.4-4.1Zm9 0c-3 1.5-4.9 4.2-4.9 7.6 0 3 1.8 4.8 4 4.8 2 0
                             3.5-1.5 3.5-3.4 0-1.9-1.3-3.2-3.1-3.2-.35 0-.8.07-.9.1.3-1.5
                             1.8-3.2 3.4-4.1Z" />
                  </svg>
                </span>

                <span className="flex flex-col text-left">
                  <span className="text-body-sm font-semibold leading-snug text-white">
                    {t.home.hero.reviewsLink}
                  </span>
                  <span className="text-[11.5px] font-medium uppercase tracking-[0.1em]
                                   text-white/60">
                    {t.home.hero.reviewsLinkNote}
                  </span>
                </span>

                <span className="ml-1 shrink-0 text-brand-400 transition-transform
                                 duration-300 ease-out-expo group-hover:translate-x-1">
                  <Arrow />
                </span>
              </a>
            </Reveal>

            <Reveal delay={260}>
              <div className="mt-4 flex flex-wrap items-center gap-3">
                {/*
                  Asks which service rather than assuming coins. The three cards further
                  down this page keep their direct links — they are the choice already,
                  and asking somebody to choose twice is worse than choosing for them.
                */}
                <StartOrderButton label={t.home.hero.startOrder} size="lg" />
                <ButtonLink to="/boosting" variant="invert" size="lg">
                  {t.home.hero.seeBoosting}
                </ButtonLink>
              </div>
            </Reveal>
          </div>

          {/* ------------------------------------------------- campaign --- */}
          {/*
            Beside the headline, not behind it.

            These banners carry their own headline, offer and logos. Laid under the
            hero they fought the site's own headline for the same rectangle and both
            lost. In the second column they are what they are — an advert, framed —
            and the statement keeps the column it was written for.
          */}
          <PromoCarousel
            label={t.home.promo.label}
            labels={[
              t.home.hero.slideDiscount,
              t.home.hero.slideBoosting,
              t.home.hero.slideSocial,
            ]}
          />
        </div>
        {/* Scroll cue. Desktop only — on a phone the page is obviously scrollable and
            a cue in the last 40px of a short viewport is just clutter. */}
        <div className="pointer-events-none absolute inset-x-0 bottom-7 hidden justify-center lg:flex">
          <div className="flex flex-col items-center gap-2">
            <span className="text-[10px] uppercase tracking-[0.3em] text-white/60">
              {t.home.hero.scroll}
            </span>
            <span aria-hidden="true" className="relative h-8 w-px bg-white/30">
              <span className="absolute inset-x-0 top-0 h-3 animate-cue bg-brand-500" />
            </span>
          </div>
        </div>
      </div>
    </section>
  )
}

/* -------------------------------------------------------------------- rail --- */

/**
 * A continuously scrolling rail of what the service guarantees.
 *
 * <p>Replaces a static centred row of five ticks. The movement is the point: a band
 * of live text under the hero is the visual grammar of a broadcast lower-third, and
 * it says "this is running right now" in a way a static list cannot.
 *
 * <p>The track holds the items twice and translates exactly -50%, which is what makes
 * the loop seamless — at the moment the animation resets, the second copy is sitting
 * precisely where the first one started. The duplicate is {@code aria-hidden} so a
 * screen reader hears the list once.
 */
function Rail() {
  const t = useT()
  const items = t.home.rail.items

  return (
    <div className="relative border-y border-ink-400 bg-paper py-3.5">
      <div className="fade-x overflow-hidden">
        <div className="rail-track flex w-max animate-ticker items-center">
          {[0, 1].map((copy) => (
            <div key={copy} className="flex items-center" aria-hidden={copy === 1 || undefined}>
              {items.map((item) => (
                <span
                  key={`${copy}-${item}`}
                  className="flex items-center gap-3 whitespace-nowrap px-6 text-[12.5px] text-chalk-faint"
                >
                  <span aria-hidden="true" className="h-1 w-1 rotate-45 bg-brand-500" />
                  {item}
                </span>
              ))}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------- proof --- */

/**
 * The operating numbers.
 *
 * <p>Four figures, and every one of them is a commitment the business is already
 * held to elsewhere in the product — the delivery window, the shift pattern, the
 * guarantee period from the live policy, and the number of loyalty tiers that
 * actually exist in the domain model.
 *
 * <p>There are no "12,000 happy customers" or "₹4 crore traded" here, and there will
 * not be until someone can point at the query that produced them. Invented traction
 * numbers are the fastest way to make a storefront look like every scam it is trying
 * to distinguish itself from, and the audience for this site has seen them all.
 */
function Proof() {
  const { policy } = useCatalog()
  const t = useT()

  const stats = [
    { to: 60, suffix: '', label: t.home.proof.deliveryLabel, note: t.home.proof.deliveryNote },
    { to: 24, suffix: '', label: t.home.proof.shiftLabel, note: t.home.proof.shiftNote },
    {
      to: policy?.guaranteeDays ?? 7,
      suffix: '',
      label: t.home.proof.guaranteeLabel,
      note: t.home.proof.guaranteeNote,
    },
    { to: 6, suffix: '', label: t.home.proof.tiersLabel, note: t.home.proof.tiersNote },
  ]

  return (
    <Section className="section-wash rhythm-page" eyebrow={t.home.proof.eyebrow}
             title={t.home.proof.title} lead={t.home.proof.lead}>
      {/*
        One instrument panel, raised off the page.

        The cells were `bg-ink` — which is the page's own colour, so four numbers sat
        directly on the ground with a hairline grid between them and nothing else. On a
        white card they read as an object; on the page they read as text that happened
        to be arranged in columns. White ground plus one shadow is the whole fix, and it
        is the same correction the operations console needed for the same reason.

        The short rule above each figure alternates red and gold. It is the `.stamp`
        device already used for every section eyebrow on this site, borrowed rather than
        invented, and it exists because four identical near-black numerals in a row give
        the eye no reason to travel along them. Gold is the second colour here and
        nowhere near the numerals themselves: a coloured figure would compete with the
        red that means "act on this" everywhere else.
      */}
      <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-edge bg-ink-400
                     shadow-sm ring-1 ring-ink-400 lg:grid-cols-4">
        {stats.map((stat, index) => (
          <Reveal
            key={stat.label}
            delay={index * 70}
            className="group bg-paper px-6 py-8 transition-colors duration-300 hover:bg-gray-50
                       sm:px-7 sm:py-10"
          >
            <span
              aria-hidden="true"
              className={`mb-5 block h-[3px] w-9 rounded-full ${
                index % 2 === 0 ? 'bg-brand-500' : 'bg-gold-500'
              }`}
            />
            <dd className="display text-[clamp(2.6rem,6vw,3.6rem)] leading-none text-chalk">
              <CountUp to={stat.to} suffix={stat.suffix} />
            </dd>
            <dt className="mt-4 text-body-sm font-medium text-chalk">{stat.label}</dt>
            <p className="mt-1.5 text-[12px] leading-snug text-chalk-faint">{stat.note}</p>
          </Reveal>
        ))}
      </dl>
    </Section>
  )
}

/* ---------------------------------------------------------------- services --- */

/**
 * The three things you can buy, as posters rather than as feature cards.
 *
 * <p>Each one is a tall panel with an oversized index number bled into the corner,
 * a rule, and a single sentence. The height is deliberate: three squat cards in a row
 * is the universal shape of a template feature grid, and the fastest way out of that
 * shape is to give each one a vertical axis and let the type do the work.
 */
function Services() {
  const t = useT()
  const cards = [
    {
      to: '/order',
      title: t.home.services.tradingTitle,
      tag: t.home.services.tradingTag,
      skin: 'sun' as const,
      body: t.home.services.tradingBody,
      cta: t.home.services.tradingCta,
    },
    {
      to: '/boosting',
      title: t.home.services.boostTitle,
      tag: t.home.services.boostTag,
      skin: 'deep' as const,
      body: t.home.services.boostBody,
      cta: t.home.services.boostCta,
    },
    {
      to: '/coaching',
      title: t.home.services.coachTitle,
      tag: t.home.services.coachTag,
      skin: 'red' as const,
      body: t.home.services.coachBody,
      cta: t.home.services.coachCta,
    },
  ]

  return (
    <Section
      className="rhythm-page"
      eyebrow={t.home.services.eyebrow(SEASON)}
      title={t.home.services.title}
      lead={t.home.services.lead}
    >
      <div className="grid gap-4 md:grid-cols-3">
        {cards.map((card, index) => {
          const skin = SERVICE_SKINS[card.skin]
          return (
          <Reveal key={card.title} delay={index * 90}>
            <Link
              to={card.to}
              className={`group relative flex h-full min-h-[22rem] flex-col overflow-hidden
                         rounded-panel border p-7 shadow-e1
                         transition-[transform,background-color,box-shadow] duration-500
                         ease-out-expo shadow-e2 hover:-translate-y-1.5 hover:shadow-e3 ${skin.panel}`}
            >
              {/*
                The index number is set enormous and clipped by the panel edge. It is
                background, not information — the label above it is what tells you
                what this is. Bleeding it off the corner is what stops it looking like
                a numbered list and starts it looking like a printed poster.
              */}
              <span
                aria-hidden="true"
                className={`display pointer-events-none absolute -bottom-6 -right-3 text-[9rem]
                           leading-none transition-[color,transform] duration-700
                           ease-out-expo group-hover:-translate-y-1 ${skin.numeral}`}
              >
                {index + 1}
              </span>

              {/*
                The chip carries the card's own foreground rather than a Badge tone.
                The tones are built for the page ground — a red-tinted chip on a red
                card is invisible, and a lilac one on yellow belongs to another design.
              */}
              <span className={`relative self-start rounded-pill px-2.5 py-1 text-[10.5px]
                               font-semibold uppercase tracking-[0.12em] ${skin.chip}`}>
                {card.tag}
              </span>

              <h3 className={`display relative mt-6 text-display-md ${skin.title}`}>{card.title}</h3>

              <div aria-hidden="true" className={`relative mt-5 h-px w-10 ${skin.rule}`} />

              <p className={`relative mt-5 flex-1 text-body-sm leading-relaxed ${skin.body}`}>
                {card.body}
              </p>

              <span className={`relative mt-7 inline-flex items-center gap-2 text-body-sm
                               font-semibold ${skin.cta}`}>
                {card.cta}
                <span className="transition-transform duration-300 ease-out-expo group-hover:translate-x-1.5">
                  <Arrow />
                </span>
              </span>
            </Link>
          </Reveal>
          )
        })}
      </div>
    </Section>
  )
}

/* ----------------------------------------------------------------- process --- */

/**
 * What actually happens after you pay.
 *
 * <p>Laid out along a horizontal rule with the step markers sitting on it, so the
 * three steps read as one process with a direction rather than as three independent
 * boxes. On a phone the rule turns vertical and runs down the left, which is the same
 * idea rotated rather than a different layout — the reader who learned it on desktop
 * still recognises it.
 */
function Process() {
  const t = useT()
  const steps = [
    { title: t.home.how.step1Title, body: t.home.how.step1Body },
    { title: t.home.how.step2Title, body: t.home.how.step2Body },
    { title: t.home.how.step3Title, body: t.home.how.step3Body },
  ]

  return (
    <Band>
      <Section
        className="rhythm-page"
        eyebrow={t.home.how.eyebrow}
        title={t.home.how.title}
        lead={t.home.how.lead}
      >
        <ol className="relative grid gap-10 md:grid-cols-3 md:gap-8">
          {/* The rail itself. Horizontal on desktop, vertical on phones, sitting
              behind the markers at the exact height of their centres. */}
          <div
            aria-hidden="true"
            className="absolute left-[11px] top-2 h-full w-px bg-gradient-to-b from-brand-500/50
                       via-ink-400 to-transparent md:left-0 md:top-[11px] md:h-px md:w-full
                       md:bg-gradient-to-r"
          />

          {steps.map((step, index) => (
            <Reveal
              as="li"
              key={step.title}
              delay={index * 110}
              className="relative pl-10 md:pl-0 md:pt-12"
            >
              <span
                aria-hidden="true"
                className="absolute left-0 top-[3px] grid h-[23px] w-[23px] place-items-center
                           rounded-full border border-brand-500/60 bg-ink text-[10px]
                           font-bold text-brand-400 md:top-0"
              >
                {index + 1}
              </span>
              <h3 className="display text-display-sm text-chalk">{step.title}</h3>
              <p className="mt-3 text-body-sm leading-relaxed text-chalk-muted">{step.body}</p>
            </Reveal>
          ))}
        </ol>
      </Section>
    </Band>
  )
}

/* ---------------------------------------------------------------- coaching --- */

/**
 * The coaching feature band.
 *
 * <p>Coaching is the highest-margin thing on the site and the hardest sell, because
 * it is the only product where the customer has to believe a specific person will be
 * good. So it gets the full-bleed treatment, its own light, and a specification
 * block — duration, format, validity — presented as hard facts rather than as
 * promises. The plate is doing the same job it does in the hero: turning a claim into
 * a reading.
 */
function CoachingBand() {
  const t = useT()
  const { policy } = useCatalog()
  const points = [
    t.home.coach.point1,
    t.home.coach.point2,
    t.home.coach.point3,
    t.home.coach.point4,
  ]

  return (
    <section className="relative isolate overflow-hidden border-y border-ink-400">
      <Atmosphere intensity={0.55} motes={false} />
      <Section className="section-wash section-wash-soft rhythm-page">
        <div className="grid items-center gap-14 lg:grid-cols-[1fr_0.8fr] lg:gap-20">
          <div>
            <Reveal>
              <p className="stamp">{t.home.coach.eyebrow}</p>
              {/*
                The measure was cut for a thirty-character headline. At sixteen it forced
                a break that left "FC" alone on a second line, which reads as a mistake
                rather than a line break. Wider lets a short headline sit on one line and
                still wraps a longer one before it runs past the column.
              */}
              <h2 className="display mt-5 max-w-[22ch] text-balance text-display-xl text-sheen">
                {t.home.coach.title}
              </h2>
              <p className="measure mt-6 text-pretty text-body-lg text-chalk-muted">
                {t.home.coach.body}
              </p>
            </Reveal>

            <Reveal delay={120}>
              <ul className="mt-9 space-y-3.5">
                {points.map((point) => (
                  <li key={point} className="flex items-start gap-3 text-body-sm text-chalk">
                    <span aria-hidden="true" className="mt-[7px] h-1 w-4 shrink-0 bg-brand-500" />
                    {point}
                  </li>
                ))}
              </ul>
            </Reveal>

            <Reveal delay={200}>
              <div className="mt-10 flex flex-wrap gap-3">
                <ButtonLink to="/coaching" size="lg">
                  {t.home.coach.cta}
                  <Arrow />
                </ButtonLink>
                <ButtonLink to="/coaching" variant="ghost" size="lg">
                  {t.home.coach.secondary}
                </ButtonLink>
              </div>
            </Reveal>
          </div>

          <Reveal delay={260} direction="left">
            <dl className="plate ticks divide-y divide-ink-400/70">
              {/*
                The length comes from the policy, not from this file.
                It was the string "40" typed here, and it stayed 40 after the session
                moved to an hour everywhere else — the config, the slot planner, the
                price list and the booking page all agreed, and the one number a
                visitor actually reads still said something else. A spec plate that
                can disagree with the product is worse than no spec plate.
              */}
              <SpecRow
                label={t.home.coach.durationLabel}
                value={t.home.coach.durationValue(policy?.coachingSessionMinutes ?? 60)}
                note={t.home.coach.durationNote}
              />
              <SpecRow
                label={t.home.coach.formatLabel}
                value={t.home.coach.formatValue}
                note={t.home.coach.formatNote}
              />
              <SpecRow
                label={t.home.coach.validityLabel}
                value={t.home.coach.validityValue}
                note={t.home.coach.validityNote}
              />
            </dl>
          </Reveal>
        </div>
      </Section>
    </section>
  )
}

function SpecRow({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <div className="flex items-baseline justify-between gap-6 px-6 py-5">
      <div>
        <dt className="text-[10.5px] font-semibold uppercase tracking-[0.16em] text-chalk-faint">
          {label}
        </dt>
        <p className="mt-1 text-[12px] text-chalk-faint">{note}</p>
      </div>
      <dd className="display tnum shrink-0 text-display-md text-chalk">{value}</dd>
    </div>
  )
}

/* ------------------------------------------------------------------ why us --- */

/*
 * Six pillars, one per objection a buyer actually has: will I get banned, is this
 * complicated, how long does it take, are you awake when I am, what happens to my
 * password, and is it worth it. Not decoration — an objection-handling grid, which
 * is why each one gets a single sentence and no more.
 */
function WhyUs() {
  const { policy } = useCatalog()
  const t = useT()
  const money = useMoney()

  const pillars = [
    { title: t.home.why.safeTitle, body: t.home.why.safeBody(policy?.guaranteeDays ?? 7) },
    { title: t.home.why.simpleTitle, body: t.home.why.simpleBody },
    { title: t.home.why.fastTitle, body: t.home.why.fastBody },
    { title: t.home.why.alwaysTitle, body: t.home.why.alwaysBody },
    { title: t.home.why.privateTitle, body: t.home.why.privateBody },
  ]

  /*
   * Held out of `pillars` rather than filtered back out of it. The five above share one
   * layout and this one does not, and a map that renders its last element differently is
   * the kind of branch that quietly breaks when somebody adds a seventh reason.
   */
  const reward = {
    title: t.home.why.rewardTitle,
    body: policy
      ? t.home.why.rewardBody(
          policy.earnPointsPerUnit,
          money(policy.earnSpendUnitMinor),
          money(policy.pointValueMinor),
        )
      : t.home.why.rewardBodyFallback,
  }

  return (
    <Section
      className="rhythm-page"
      eyebrow={t.home.why.eyebrow}
      title={t.home.why.title}
      lead={t.home.why.lead}
    >
      <div className="grid gap-x-10 gap-y-10 sm:grid-cols-2 lg:grid-cols-3">
        {pillars.map((pillar, index) => (
          <Reveal
            key={pillar.title}
            delay={(index % 3) * 80}
            className="group border-t border-ink-400 pt-6"
          >
            <span className="tnum text-[11px] font-semibold tracking-widest text-brand-400">
              {String(index + 1).padStart(2, '0')}
            </span>
            <h3 className="display mt-2.5 text-display-sm text-chalk">{pillar.title}</h3>
            <p className="mt-2.5 text-body-sm leading-relaxed text-chalk-muted">{pillar.body}</p>
          </Reveal>
        ))}

        {/*
          The sixth answer, given a panel instead of a column.

          The other five are reasons to trust the service; this is the only one that gives
          the customer something back, and it was the hardest of the six to read — three
          numbers buried in a sentence, in the smallest text on the page, in the last
          position. Spanning the grid and pulling the rates out as figures makes the
          argument at a glance, which is what the other five already manage in four words.

          Gold, because this site reserves gold for anything to do with points. Holding
          that line is how a customer learns the colour means "your balance" without ever
          being told, so the highlight also has to obey it.
        */}
        <Reveal delay={120} className="sm:col-span-2 lg:col-span-3">
          <div className="relative overflow-hidden rounded-panel border border-gold-500/30 bg-gold-500/[0.06] p-6 sm:p-8">
            <div
              aria-hidden="true"
              className="pointer-events-none absolute -right-20 -top-24 h-64 w-64 rounded-full
                         bg-gold-500/10 blur-[100px]"
            />
            <div className="relative">
              <span className="tnum text-[11px] font-semibold tracking-widest text-gold-400">
                {String(pillars.length + 1).padStart(2, '0')}
              </span>

              <div className="mt-2.5 grid gap-8 lg:grid-cols-[1fr_1.1fr] lg:items-center">
                <div>
                  <h3 className="display text-display-md text-chalk">{reward.title}</h3>
                  <p className="measure mt-3 text-body-sm leading-relaxed text-chalk-muted">
                    {reward.body}
                  </p>
                  <ButtonLink to="/rewards" variant="secondary" className="mt-5">
                    {t.home.why.rewardCta}
                  </ButtonLink>
                </div>

                {/*
                  Three figures, not a table. Each is one number and the condition it comes
                  with — the shape a reader can compare against a competitor without doing
                  arithmetic, which a sentence never is.
                */}
                {policy && (
                  <dl className="grid gap-px overflow-hidden rounded-edge bg-gold-500/20 sm:grid-cols-3">
                    <Stat
                      value={String(policy.earnPointsPerUnit)}
                      label={t.home.why.rewardStatEarn}
                      note={t.home.why.rewardStatPer(money(policy.earnSpendUnitMinor))}
                    />
                    <Stat
                      value="6"
                      label={t.home.why.rewardStatTiers}
                      note={t.home.why.rewardStatTiersNote}
                    />
                    <Stat
                      value={money(policy.pointValueMinor)}
                      label={t.home.why.rewardStatValue}
                      note={t.home.why.rewardStatValueNote}
                    />
                  </dl>
                )}
              </div>
            </div>
          </div>
        </Reveal>
      </div>
    </Section>
  )
}

/**
 * One figure from the rewards scheme.
 *
 * <p>Value, then what it is, then the condition attached. That order is deliberate: the
 * number is what a reader compares against another site, so it goes first and largest,
 * and the qualifier that makes it honest goes underneath rather than being dropped.
 */
function Stat({ value, label, note }: { value: string; label: string; note: string }) {
  return (
    <div className="bg-paper/[0.55] p-4">
      <dd className="tnum display text-display-sm leading-none text-gold-400">{value}</dd>
      <dt className="mt-1.5 text-[12.5px] font-semibold text-chalk">{label}</dt>
      <dd className="mt-1 text-[11.5px] leading-snug text-chalk-muted">{note}</dd>
    </div>
  )
}

/* ---------------------------------------------------------------- rewards --- */

/**
 * The rewards band.
 *
 * <p>Gold is reserved, across the entire site, for anything to do with points. That
 * is the only place a second accent colour is allowed, and holding the line on it
 * means a customer learns that gold on screen always means "this is about your
 * balance" without ever being told.
 */
function RewardsBand() {
  const { policy } = useCatalog()
  const t = useT()
  const money = useMoney()
  if (!policy) return null

  const perOrder =
    `${t.home.rewards.pointsUnit(policy.earnPointsPerUnit)} / ${money(policy.earnSpendUnitMinor)}`

  return (
    <Section className="section-wash section-wash-soft rhythm-page">
      <Reveal>
        <div className="relative overflow-hidden rounded-panel border border-gold-500/25 bg-paper">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute -left-24 -top-24 h-72 w-72 rounded-full
                       bg-gold-500/10 blur-[110px]"
          />
          <div className="relative grid gap-10 p-8 md:grid-cols-[1.25fr_1fr] md:items-center md:p-12">
            <div>
              <Badge tone="gold">{t.home.rewards.badge}</Badge>
              <h2 className="display mt-5 max-w-[18ch] text-balance text-display-lg text-chalk">
                {t.home.rewards.title}
              </h2>
              <LoyaltyCurrencyNotice className="mt-5" />
              <p className="measure mt-5 text-body-sm leading-relaxed text-chalk-muted">
                {t.home.rewards.body(
                  perOrder,
                  money(policy.pointValueMinor),
                  bpsToPercent(policy.maxWalletRedemptionBps),
                )}
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <ButtonLink to="/rewards" variant="secondary" size="md">
                  {t.home.rewards.howItWorks}
                </ButtonLink>
                <ButtonLink to="/register" size="md">
                  {t.home.rewards.createAccount}
                </ButtonLink>
              </div>
            </div>

            <div className="plate border-gold-500/20 bg-gold-500/[0.03] p-6">
              <p className="eyebrow mb-5 text-gold-400">{t.home.rewards.exampleTitle}</p>
              <dl className="space-y-3.5 text-body-sm">
                <ExampleRow
                  label={t.home.rewards.youSpend}
                  value={money(policy.earnSpendUnitMinor * 3)}
                />
                <ExampleRow
                  label={t.home.rewards.youEarn}
                  value={t.home.rewards.pointsUnit(policy.earnPointsPerUnit * 3)}
                  gold
                />
                <ExampleRow
                  label={t.home.rewards.worthAtCheckout}
                  value={money(policy.earnPointsPerUnit * 3 * policy.pointValueMinor)}
                  gold
                />
              </dl>
              <p className="mt-5 border-t border-gold-500/15 pt-4 text-[11.5px] leading-relaxed text-chalk-faint">
                {t.home.rewards.pointsLand}
              </p>
            </div>
          </div>
        </div>
      </Reveal>
    </Section>
  )
}

function ExampleRow({ label, value, gold = false }: { label: string; value: string; gold?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <dt className="text-chalk-muted">{label}</dt>
      <dd className={`tnum font-semibold ${gold ? 'text-gold-400' : 'text-chalk'}`}>{value}</dd>
    </div>
  )
}

/* --------------------------------------------------------------------- ask --- */

/**
 * Points at the chat rather than repeating the FAQ.
 *
 * <p>The questions used to be an accordion on this page and a second accordion on
 * the help page — the same answers, maintained twice, diverging the moment either
 * was edited. They now live in one place: the assistant in the corner, which reads
 * the same live policy the checkout does. This band's only job is to tell people it
 * is there, because a chat launcher nobody notices is a chat launcher nobody uses.
 */
function AskBand() {
  const t = useT()

  const openChat = () => {
    // A custom event rather than lifted state: the widget is mounted outside the
    // router at the app root, and threading a setter down through every page to
    // reach it would couple ten components to one button.
    window.dispatchEvent(new CustomEvent('gfs:open-ask'))
  }

  return (
    <Band>
      <Section className="rhythm-page">
        <div className="grid items-center gap-10 md:grid-cols-[1fr_auto] md:gap-16">
          <Reveal>
            <p className="stamp">{t.home.ask.eyebrow}</p>
            <h2 className="display mt-5 max-w-[20ch] text-balance text-display-lg text-sheen">
              {t.home.ask.title}
            </h2>
            <p className="measure mt-5 text-body-sm leading-relaxed text-chalk-muted">
              {t.home.ask.body}
            </p>
          </Reveal>
          <Reveal delay={120}>
            <button
              type="button"
              onClick={openChat}
              className="group inline-flex items-center gap-3 rounded-press border border-ink-400
                         bg-paper px-6 py-4 text-body-sm font-semibold text-chalk
                         shadow-e2 transition-[transform,border-color,box-shadow] duration-300
                         ease-out-expo hover:-translate-y-0.5 hover:border-brand-500/50
                         shadow-e2 hover:shadow-e3"
            >
              <span className="relative grid h-9 w-9 place-items-center rounded-full bg-brand-500/[0.12]">
                <ChatGlyph />
                <span className="absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full bg-brand-500" />
              </span>
              {t.home.ask.cta}
              <span className="text-brand-400 transition-transform duration-300 ease-out-expo group-hover:translate-x-1">
                <Arrow />
              </span>
            </button>
          </Reveal>
        </div>
      </Section>
    </Band>
  )
}

/* ----------------------------------------------------------------- closing --- */

function ClosingCta() {
  const t = useT()
  return (
    /*
     * The closing section is the page's one red field.
     *
     * Red was the primary action colour and had no surface at all — it lived on
     * buttons and the logo and measured two tenths of one percent of the page. This
     * is the right place to spend it: the section whose entire job is the primary
     * action, at the end of the scroll, where a change of ground also signals the
     * page is over.
     *
     * Blue rather than red, and the same blue the page is already made of — #F2F2F4
     * is a 253deg blue-violet, and this is that hue with the lightness spent. Red is
     * still here: it is the button, which is the only thing in the section anyone is
     * meant to press.
     *
     * The mark is plated. Unplated it was red ink on a red field — present in the
     * DOM, invisible on the screen — and it needs the light disc it was drawn for
     * on any ground that is not the page.
     *
     * Type is --paper (8.98:1) and the atmosphere comes off: its blooms are tuned
     * for a light ground and turn to mud on a saturated one.
     */
    <section className="relative isolate overflow-hidden bg-deep">
      <Section className="rhythm-page">
        <Reveal className="relative text-center">
          {/* The full-size source here: at 112px the ring, "SERVICES" and even
              "G.F.S" all resolve, which is the one place on the homepage the crest
              gets to be read rather than recognised. */}
          <BrandBadge size={112} plated className="mx-auto" />
          <p className="stamp mt-7 justify-center text-paper">{t.home.closing.eyebrow}</p>
          <h2 className="display mx-auto mt-6 max-w-[16ch] text-balance text-display-xl text-paper">
            {t.home.closing.title}
          </h2>
          <p className="measure mx-auto mt-6 text-pretty text-body-lg text-paper">
            {t.home.closing.body}
          </p>
          <div className="mt-10 flex flex-wrap justify-center gap-3">
            {/*
              The primary button cannot be red on red. It inverts to cream with red
              type — still the loudest thing in the section, because it is now the
              only light shape in a field of colour.
            */}
            <StartOrderButton label={t.home.closing.startOrder} size="lg" invert />
            <ButtonLink to="/help" variant="ghost" size="lg"
                        className="text-paper hover:bg-paper/15 hover:text-paper">
              {t.home.closing.readFaqs}
            </ButtonLink>
          </div>
        </Reveal>
      </Section>
    </section>
  )
}

/* ------------------------------------------------------------------ pieces --- */

/**
 * A full-bleed tinted band.
 *
 * <p>Alternating the page ground between {@code ink} and a slightly lifted
 * {@code ink-800} is what gives a long scroll its rhythm. Without it every section
 * floats on the same flat black and the page reads as one undifferentiated column,
 * which is the thing that makes long landing pages exhausting to scroll.
 */
function Band({ children }: { children: ReactNode }) {
  /*
   * A quieter band than the last palette needed.
   *
   * On the cream ground these were solid orange and solid teal, because cream was
   * bright enough that a tint vanished into it and the accents had no surface
   * anywhere else on the page. Lilac does not have that problem: it already *is* a
   * colour, so a band one step deeper reads as a distinct section without a second
   * hue being introduced to make the point.
   *
   * That also lets the type inside keep its three levels. The solid fields had to
   * force every descendant to ink, because on a saturated ground there is no quiet
   * grey; here `muted` and `faint` still clear on the band and the hierarchy stays
   * intact.
   */
  return <div className="border-y border-ink-400 bg-ink-800">{children}</div>
}

function Tick() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"
         className="shrink-0 text-brand-400">
      <path d="m4 12.5 5.2 5.2L20 7" />
    </svg>
  )
}

function Arrow() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M5 12h14" />
      <path d="m12 5 7 7-7 7" />
    </svg>
  )
}

function ChatGlyph() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"
         className="text-brand-400" aria-hidden="true">
      <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5Z" />
    </svg>
  )
}
