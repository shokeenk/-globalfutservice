import type { ReactNode } from 'react'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { PageHeader } from '../components/PageHeader'
import { Alert, Badge, Section, Skeleton } from '../components/ui'
import { Testimonials } from '../components/Testimonials'
import { RankBadge, hasBadge, tierAccent } from '../components/RankBadge'
import { BUSINESS } from '../content/business'
import { useT } from '../i18n'
import { useCatalogLabels } from '../content/catalogLabels'
import { SEASON, useSeo } from '../lib/seo'
import type { CatalogOption } from '../lib/types'
import { Reveal } from '../motion/Reveal'
import { useCatalog } from '../state/CatalogContext'

export default function Boosting() {
  const labels = useCatalogLabels()
  const t = useT()
  useSeo({
    title: t.boosting.seoTitle(SEASON),
    description: t.boosting.seoDescription(SEASON),
  })

  const { catalog, policy, loading, error } = useCatalog()
  const [tab, setTab] = useState<'BOOST_CHAMPS' | 'BOOST_RIVALS'>('BOOST_CHAMPS')

  const champs = catalog?.services.find((s) => s.sku === 'BOOST_CHAMPS')
  const rivals = catalog?.services.find((s) => s.sku === 'BOOST_RIVALS')
  const active = tab === 'BOOST_CHAMPS' ? champs : rivals

  const tiers: CatalogOption[] = useMemo(() => active?.options ?? [], [active])

  return (
    <>
      <PageHeader
        eyebrow={t.boosting.eyebrow(SEASON)}
        title={t.boosting.title}
        lead={t.boosting.lead}
        aside={
          <Segmented
            value={tab}
            onChange={setTab}
            options={[
              { value: 'BOOST_CHAMPS', label: t.boosting.tabChamps },
              { value: 'BOOST_RIVALS', label: t.boosting.tabRivals },
            ]}
          />
        }
      />

      <Section className="rhythm-section" wide>
        {error && <Alert tone="warn">{error}</Alert>}

        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {loading &&
            Array.from({ length: 8 }).map((_, i) => <Skeleton key={i} className="h-44 w-full" />)}

          {!loading &&
            tiers.map((tier, index) => {
              const best = index === tiers.length - 1
              return (
                <Reveal key={tier.variant} delay={(index % 4) * 70}>
                  {/*
                    The whole tier is one link.
                    It was previously a card containing a small button, which meant
                    the 44px target sat inside a 200px card the customer had already
                    aimed at. Making the card itself the control removes the aiming
                    problem entirely, and the arrow just indicates direction.
                  */}
                  <Link
                    to={`/order?service=${tab}&variant=${tier.variant}`}
                    className={[
                      'group relative flex h-full flex-col overflow-hidden rounded-panel border p-6',
                      'transition-[transform,border-color,background-color,box-shadow] duration-500',
                      /*
                        A shadow at rest, not only on hover. The card lifted when you
                        pointed at it and lay perfectly flat otherwise, so the whole grid
                        read as painted onto the page until you touched it — and on a
                        phone, where there is no hover, it never lifted at all.
                      */
                      'ease-out-expo shadow-e2 hover:-translate-y-1.5 hover:shadow-e3',
                      // Opaque grounds: the translucent ones composited to within a
                      // percent of the page and left the border doing all the work.
                      best
                        ? 'border-brand-500/50 bg-[#FDF3F2] hover:border-brand-500'
                        : 'border-ink-400 bg-paper hover:border-ink-300 hover:bg-gray-50',
                    ].join(' ')}
                  >
                    {/*
                      A hairline in the crest's own colour along the top edge — gold for
                      Champion, maroon for Elite. Three pixels, and only on tiers that
                      actually have a crest to echo.
                    */}
                    {tierAccent(tier.variant) && (
                      <span
                        aria-hidden="true"
                        className={`absolute inset-x-0 top-0 h-[3px] ${tierAccent(tier.variant)}`}
                      />
                    )}

                    {/*
                      The Elite shield where the tier has one, the numeral where it does
                      not. Both sit in the same corner slot, so a row mixing Champion and
                      Elite tiers still lines up.

                      The shield lifts and brightens on hover rather than sitting flat —
                      it is the thing being bought, so it should feel like an object
                      rather than a decal.
                    */}
                    {hasBadge(tier.variant) ? (
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

                    {best && (
                      <Badge tone="gold" className="relative mb-3 self-start">
                        {t.boosting.bestValue}
                      </Badge>
                    )}

                    <p className="display relative text-display-sm text-chalk">{labels.option(tier)}</p>

                    <p className="tnum display relative mt-auto pt-6 text-display-md text-brand-400">
                      {tier.unitPriceFormatted}
                    </p>

                    <span className="relative mt-4 inline-flex items-center gap-2 border-t border-ink-400 pt-4 text-[13px] font-semibold text-chalk">
                      {t.boosting.choose}
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none"
                           stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"
                           strokeLinejoin="round" aria-hidden="true"
                           className="text-brand-400 transition-transform duration-300 ease-out-expo group-hover:translate-x-1.5">
                        <path d="M5 12h14" />
                        <path d="m12 5 7 7-7 7" />
                      </svg>
                    </span>
                  </Link>
                </Reveal>
              )
            })}

          {!loading && tiers.length === 0 && (
            <p className="col-span-full text-sm text-chalk-faint">{t.boosting.tiersUpdating}</p>
          )}
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
                href={BUSINESS.discordInvite}
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
 * A segmented control.
 *
 * <p>The selected pill slides between positions rather than switching. It is a
 * translate on a single absolutely-positioned element sized to one segment, which
 * means the movement is compositor-only and the label text never re-renders — the
 * naive version (background on the active button) cannot animate at all, because
 * there is nothing continuous between the two states to interpolate.
 *
 * <p>Fixed at two segments by the width maths. That is deliberate: this control is
 * for a binary choice, and a segmented control with five options is a tab bar
 * wearing the wrong clothes.
 */
function Segmented<T extends string>({
  value, onChange, options,
}: {
  value: T
  onChange: (next: T) => void
  options: { value: T; label: string }[]
}) {
  const index = Math.max(0, options.findIndex((option) => option.value === value))

  return (
    <div className="relative inline-flex rounded-press border border-ink-400 bg-paper p-1 backdrop-blur-sm">
      <span
        aria-hidden="true"
        className="absolute inset-y-1 left-1 rounded-edge bg-brand-500 shadow-glow
                   transition-transform duration-400 ease-out-expo"
        style={{
          width: `calc((100% - 0.5rem) / ${options.length})`,
          transform: `translateX(${index * 100}%)`,
        }}
      />
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          onClick={() => onChange(option.value)}
          aria-pressed={value === option.value}
          className={[
            'relative z-10 flex-1 whitespace-nowrap rounded-edge px-5 py-2.5 text-[13.5px]',
            'font-semibold transition-colors duration-200',
            value === option.value ? 'text-paper' : 'text-chalk-muted hover:text-chalk',
          ].join(' ')}
        >
          {option.label}
        </button>
      ))}
    </div>
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
