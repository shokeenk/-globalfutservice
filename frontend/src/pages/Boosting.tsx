import type { ReactNode } from 'react'
import { useMemo } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { LuGamepad2, LuShieldCheck, LuTarget, LuTrophy } from 'react-icons/lu'
import { ListingCard } from '../components/ListingCard'
import { PageHeader } from '../components/PageHeader'
import { Alert, Section, Skeleton } from '../components/ui'
import { Testimonials } from '../components/Testimonials'
import { RankBadge, hasBadge, tierAccent } from '../components/RankBadge'
import { BUSINESS } from '../content/business'
import { useT } from '../i18n'
import { useCatalogLabels } from '../content/catalogLabels'
import { SEASON, useSeo } from '../lib/seo'
import type { CatalogOption } from '../lib/types'
import { isHourly, isListingSku, listingImageSrc } from '../lib/listings'
import { Reveal } from '../motion/Reveal'
import { useCatalog } from '../state/CatalogContext'

/** The four categories, in the order the row shows them, and how each is named in the address. */
const CATEGORIES = [
  { key: 'champs', sku: 'BOOST_CHAMPS', icon: LuTrophy },
  { key: 'rivals', sku: 'BOOST_RIVALS', icon: LuShieldCheck },
  { key: 'objectives', sku: 'BOOST_OBJECTIVES', icon: LuTarget },
  { key: 'tournaments', sku: 'BOOST_TOURNAMENTS', icon: LuGamepad2 },
] as const

type CategoryKey = (typeof CATEGORIES)[number]['key']

export default function Boosting() {
  const labels = useCatalogLabels()
  const t = useT()
  useSeo({
    title: t.boosting.seoTitle(SEASON),
    description: t.boosting.seoDescription(SEASON),
  })

  const { catalog, policy, loading, error } = useCatalog()
  /*
   * The category lives in the address, so a customer sent back from checkout -- or sharing
   * the link -- lands on the tab they were looking at rather than on Champs.
   */
  const [params, setParams] = useSearchParams()
  const category = CATEGORIES.find((c) => c.key === params.get('category')) ?? CATEGORIES[0]
  const choose = (key: CategoryKey) => {
    const next = new URLSearchParams(params)
    if (key === 'champs') next.delete('category')
    else next.set('category', key)
    setParams(next, { replace: true })
  }

  const active = catalog?.services.find((s) => s.sku === category.sku)
  const tiers: CatalogOption[] = useMemo(() => active?.options ?? [], [active])
  const listings = isListingSku(category.sku)

  const tabLabel: Record<CategoryKey, string> = {
    champs: t.boosting.tabChamps,
    rivals: t.boosting.tabRivals,
    objectives: t.boosting.tabObjectives,
    tournaments: t.boosting.tabTournaments,
  }

  return (
    <>
      <PageHeader
        eyebrow={t.boosting.eyebrow(SEASON)}
        title={t.boosting.title}
        lead={t.boosting.lead}
        below={
          <div role="group" aria-label={t.boosting.categoriesLabel} className="flex flex-wrap gap-2.5 sm:gap-3">
            {CATEGORIES.map((c) => {
              const on = c.key === category.key
              const Icon = c.icon
              return (
                <button
                  key={c.key}
                  type="button"
                  onClick={() => choose(c.key)}
                  aria-pressed={on}
                  className={[
                    'inline-flex min-h-[48px] items-center gap-2.5 rounded-edge border px-4 text-[14px] font-semibold',
                    'transition-colors duration-200 sm:px-5',
                    on
                      ? 'border-brand-500 bg-brand-500 text-paper shadow-glow'
                      : 'border-ink-400 bg-paper text-chalk hover:border-ink-300',
                  ].join(' ')}
                >
                  <Icon aria-hidden="true" size={20} className={on ? 'text-paper' : 'text-chalk'} />
                  {tabLabel[c.key]}
                </button>
              )
            })}
          </div>
        }
      />

      <Section className="rhythm-section" wide>
        {error && <Alert tone="warn">{error}</Alert>}

        {/*
          Note 17. The headline statement about the top packages -- on Champs only: it is a
          claim about win packages, and the other categories sell none.

          Derived from the same per-package rates the cards use rather than stated
          separately, so the prominent claim and the small print cannot disagree —
          which is the failure the brief was written to prevent. It renders only when
          every package it names has been measured: quoting a range as "13-15 wins"
          while one of the three has no data would be a claim about something nobody
          counted, dressed up as an aggregate.

          The percentage shown is the lowest of them, not the mean. A customer reading
          "13-15 wins reached in 92% of orders" and buying the 13-win package should
          get at least what they were told; an average lets the strongest package carry
          a claim the weakest cannot meet.
        */}
        {category.sku === 'BOOST_CHAMPS' && <SuccessHeadline tiers={tiers} />}

        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" data-testid="boosting-grid">
          {loading &&
            Array.from({ length: 8 }).map((_, i) => <Skeleton key={i} className="h-44 w-full" />)}

          {!loading && listings &&
            tiers.map((option, index) => {
              const listing = option.listing
              if (!listing) return null
              const art = listing.showImage && listing.imageUrl
                ? { src: listingImageSrc(listing.imageUrl) ?? '', width: listing.imageWidth, height: listing.imageHeight }
                : null
              return (
                <Reveal key={option.variant} delay={(index % 4) * 70}>
                  <ListingCard
                    to={`/boosting/checkout?listing=${listing.id}`}
                    title={listing.title}
                    subtitle={listing.subtitle}
                    price={isHourly(listing) ? t.boosting.perHour(option.unitPriceFormatted) : option.unitPriceFormatted}
                    accent="bg-brand-500"
                    artwork={art}
                    chooseLabel={t.boosting.choose}
                  />
                </Reveal>
              )
            })}

          {!loading && !listings &&
            tiers.map((tier, index) => {
              // The server says which tier carries the tag: an admin's choice on the
              // Listings page, else the last tier, as this page always drew it.
              const best = tier.bestValue ?? index === tiers.length - 1
              return (
                <Reveal key={tier.variant} delay={(index % 4) * 70}>
                  <ListingCard
                    to={`/boosting/checkout?service=${category.sku}&variant=${tier.variant}`}
                    title={labels.option(tier)}
                    price={tier.unitPriceFormatted}
                    best={best}
                    bestLabel={t.boosting.bestValue}
                    /*
                      A hairline in the crest's own colour along the top edge — gold for
                      Champion, maroon for Elite. Three pixels, and only on tiers that
                      actually have a crest to echo.
                    */
                    accent={tierAccent(tier.variant)}
                    /*
                      The Elite shield where the tier has one, the numeral where it does
                      not. Both sit in the same corner slot, so a row mixing Champion and
                      Elite tiers still lines up.

                      The shield lifts and brightens on hover rather than sitting flat —
                      it is the thing being bought, so it should feel like an object
                      rather than a decal.
                    */
                    corner={hasBadge(tier.variant) ? (
                      <span
                        aria-hidden="true"
                        className="pointer-events-none absolute -right-3 -top-3 transition-transform
                                   duration-500 ease-out-expo group-hover:-translate-y-0.5
                                   group-hover:scale-105"
                      >
                        <RankBadge variant={tier.variant} size={84} className="opacity-90
                                   drop-shadow-md transition-opacity
                                   duration-500 group-hover:opacity-100" />
                      </span>
                    ) : (
                      <span
                        aria-hidden="true"
                        className="display pointer-events-none absolute -right-2 -top-4 text-[5rem]
                                   leading-none text-ink-300/60 transition-colors duration-500
                                   group-hover:text-ink-400/60"
                      >
                        {String(index + 1).padStart(2, '0')}
                      </span>
                    )}
                    /*
                      Note 18. Absent, not zero, not "—", when nothing was measured.

                      Behind a null check rather than rendering a placeholder, because
                      there is no honest placeholder for this: a percentage next to a
                      price is read as a measurement whatever caveat sits beside it, and
                      a customer cannot tell an invented one from a counted one.

                      Visually secondary on purpose, as the brief asked — it sits under
                      the price in muted type rather than competing with it.
                    */
                    footnote={tier.successRateBps != null
                      ? <SuccessRate bps={tier.successRateBps} className="relative mt-auto pt-6" />
                      : null}
                    chooseLabel={t.boosting.choose}
                  />
                </Reveal>
              )
            })}

          {!loading && tiers.length === 0 && (listings ? (
            <div className="col-span-full rounded-panel border border-ink-400 bg-paper px-6 py-12 text-center"
                 data-testid="boosting-empty">
              <p className="display text-[18px] text-chalk">
                {category.sku === 'BOOST_TOURNAMENTS' ? t.boosting.emptyTournaments : t.boosting.emptyObjectives}
              </p>
              <p className="mx-auto mt-2 max-w-[52ch] text-body-sm text-chalk-muted">{t.boosting.emptyBody}</p>
              <Link to="/support" className="mt-4 inline-block text-[13px] font-semibold text-brand-400 hover:underline">
                {t.boosting.emptyCta}
              </Link>
            </div>
          ) : (
            <p className="col-span-full text-sm text-chalk-faint">{t.boosting.tiersUpdating}</p>
          ))}
        </div>
      </Section>

      {/*
        Boosting customers, directly under the tiers they are choosing between.

        Both tabs are the same service as far as the quotes go — a Rivals push and a
        Champs run are bought by the same person for the same reason — so the filter
        does not follow the tab. Splitting eight quotes across two tabs would leave
        four under each, which reads as thin rather than selective.
      */}
      <div className="border-t border-ink-400 bg-paper">
        <Testimonials only="boosting" />
      </div>

      <div className="border-t border-ink-400 bg-paper">
        <Section
          className="rhythm-page"
          eyebrow={t.boosting.knowEyebrow}
          title={t.boosting.knowTitle}
        >
          <div className="grid gap-x-10 gap-y-10 md:grid-cols-3">
            <InfoBlock index={1} title={t.boosting.signInTitle}>
              {t.boosting.signInBody}
            </InfoBlock>
            <InfoBlock index={2} title={t.boosting.timingTitle}>
              {t.boosting.timingBody}
            </InfoBlock>
            <InfoBlock index={3} title={t.boosting.coveredTitle}>
              {t.boosting.coveredBody(
                (policy?.guaranteeCashBps ?? 5000) / 100,
                (policy?.guaranteeCreditBps ?? 10000) / 100,
              )}
            </InfoBlock>
            {/*
              Clause 5 of the terms, where a customer will actually meet it.

              A run that lands short of the rank ordered is the one boosting outcome with a
              money consequence, and the terms route the claim through Discord. Both belong
              on the page selling the run, not only in the document nobody opens until
              something has already gone wrong.
            */}
            <InfoBlock index={4} title={t.boosting.discordTitle}>
              {t.boosting.discordBody}{' '}
              <a
                className="text-chalk underline"
                href={BUSINESS.discordDm}
                target="_blank"
                rel="noreferrer"
              >
                {BUSINESS.discordName}
              </a>
            </InfoBlock>
          </div>
        </Section>
      </div>
    </>
  )
}

/**
 * The aggregate success-rate statement for the top win packages.
 *
 * <p>Renders nothing unless <em>every</em> package it would name carries a measured
 * rate. That is stricter than it needs to be for a headline and it is deliberate: an
 * aggregate covering a package with no data is not an aggregate, it is an extrapolation
 * with a range on it, and it reads to a customer as though all three were counted.
 *
 * <p>Today it renders nowhere, because the API sends null for every package. It is
 * written now so the claim has one implementation rather than being retyped as prose
 * on the day the numbers arrive.
 */
function SuccessHeadline({ tiers }: { tiers: CatalogOption[] }) {
  const t = useT()

  // The top packages are the ones the brief asks about, identified by variant rather
  // than by position: the catalogue's last three rows are whatever sorts last, which
  // is not a promise that they are the 13, 14 and 15 win tiers.
  // Looked up in the order the statement names them, rather than filtered out of the
  // catalogue in whatever order it happens to return — the sentence says "13 to 15",
  // so the first and last must be the 13 and the 15, not the first and last rows.
  const headline = TOP_WIN_VARIANTS.map(
    (variant) => tiers.find((tier) => tier.variant === variant),
  )

  const measured = headline.filter(
    (tier): tier is CatalogOption & { successRateBps: number } =>
      tier != null && typeof tier.successRateBps === 'number',
  )
  if (measured.length !== TOP_WIN_VARIANTS.length) return null

  const first = measured[0]
  const last = measured[measured.length - 1]
  if (!first || !last) return null

  // The floor, not the mean. See the call site.
  const lowest = Math.min(...measured.map((tier) => tier.successRateBps))
  const pct = `${Number((lowest / 100).toFixed(1))}%`

  /*
   * "13-15 wins", not the two full option labels.
   *
   * `labels.option` returns the decorated card title -- "13 wins · Elite III · Rank 3"
   * -- which is right on a card and unreadable in a sentence: joining two of them
   * produced "13 wins · Elite III · Rank 3 - 15 wins · Elite I · Rank 1 reached in
   * 87.6% of eligible orders". The win count is the part the claim is about, and it is
   * taken from the variant rather than parsed back out of a translated label.
   */
  const range = `${winCount(first.variant)}–${winCount(last.variant)} ${t.boosting.wins}`

  return (
    <div className="hairline mb-5 rounded-panel border-ok/30 bg-ok/[0.05] p-5">
      <p className="text-body-sm font-semibold text-chalk">
        {t.boosting.successHeadline(range, pct)}
      </p>
      <p className="mt-1 text-[12px] text-chalk-faint">{t.boosting.successRateNote}</p>
    </div>
  )
}

/** The packages note 17's statement covers. */
const TOP_WIN_VARIANTS = ['WINS_13', 'WINS_14', 'WINS_15']

/** `WINS_13` -> `13`. Returns the variant unchanged if it is not of that shape. */
function winCount(variant: string | null): string {
  return variant?.startsWith('WINS_') ? variant.slice('WINS_'.length) : (variant ?? '')
}

/**
 * A measured success rate, or nothing at all.
 *
 * <p>The null check is the component's entire reason for existing, and it is why this
 * is not two lines inlined at each call site. Rendering "—" or a default would put a
 * number that nobody counted next to a buy button; leaving the caller to remember the
 * guard is how one of three call sites eventually forgets.
 *
 * <p>Basis points in, percent out, so a rate of 9500 shows as 95% and 9250 as 92.5%
 * rather than as 92.50%. The precision follows the data instead of being fixed, because
 * a measured rate that lands on a round number should look like one.
 */
function SuccessRate({ bps, className = '' }: { bps?: number | null; className?: string }) {
  const t = useT()
  if (bps == null) return null

  const pct = `${Number((bps / 100).toFixed(1))}%`

  return (
    <p className={`relative text-[12.5px] font-semibold text-ok ${className}`}>
      {t.boosting.successRateLabel(pct)}
    </p>
  )
}

function InfoBlock({ index, title, children }: { index: number; title: string; children: ReactNode }) {
  return (
    <Reveal delay={(index - 1) * 90} className="border-t border-ink-400 pt-6">
      <span className="tnum text-[11px] font-semibold tracking-widest text-brand-400">
        {String(index).padStart(2, '0')}
      </span>
      <h3 className="display mt-2.5 text-display-sm text-chalk">{title}</h3>
      <p className="mt-2.5 text-body-sm leading-relaxed text-chalk-muted">{children}</p>
    </Reveal>
  )
}
