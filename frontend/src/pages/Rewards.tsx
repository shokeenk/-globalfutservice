import { useCallback, useEffect, useState } from 'react'
import { LoyaltyCurrencyNotice } from '../components/LoyaltyNotice'
import { PageHeader } from '../components/PageHeader'
import { Reveal } from '../motion/Reveal'
import { Alert, Badge, Button, ButtonLink, Card, Section, Spinner } from '../components/ui'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { bpsToPercent } from '../lib/format'
import { useMoney } from '../lib/money'
import { useSeo } from '../lib/seo'
import type { LoyaltyStatus } from '../lib/types'
import { useAuth } from '../state/AuthContext'
import { useCatalog } from '../state/CatalogContext'

export default function Rewards() {
  const t = useT()
  const money = useMoney()
  useSeo({ title: t.rewards.seoTitle, description: t.rewards.seoDescription })

  const { policy } = useCatalog()
  const { account } = useAuth()

  if (!policy) {
    // Same rule as the configurator: the masthead does not depend on the policy that
    // has not arrived, so it renders regardless and the page keeps its <h1>.
    return (
      <>
        <PageHeader
          eyebrow={t.rewards.eyebrow}
          title={t.rewards.title}
          lead={t.rewards.lead}
        />
        <Section className="rhythm-section">
          <Alert tone="neutral">{t.rewards.loading}</Alert>
        </Section>
      </>
    )
  }

  const effectiveRate =
    ((policy.earnPointsPerUnit * policy.pointValueMinor) / policy.earnSpendUnitMinor) * 100

  /*
   * The tier ladder, defensively.
   *
   * `policy.loyaltyTiers` is derived server-side from an enum, so it is always present
   * and never empty against the real API — which is precisely why indexing it directly
   * survived review. Against anything else (an older deployment, a proxy that drops
   * unknown fields, a cached payload from before the field existed) the bare
   * `tiers[tiers.length - 1]` threw, and because React unmounts the tree on a render
   * error that TypeError did not break the rewards page — it blanked the entire site
   * until a hard reload.
   *
   * The earn rate above needs none of this and still renders, so a missing ladder now
   * costs the ladder and nothing else.
   */
  const tiers = policy.loyaltyTiers ?? []
  const topTier = tiers.length > 0 ? tiers[tiers.length - 1] : null

  return (
    <>
    <PageHeader
      eyebrow={t.rewards.eyebrow}
      title={t.rewards.title}
      lead={t.rewards.lead}
    />
    <Section className="rhythm-section">
      {/*
        Every number on this page is read from the same configuration the pricing
        engine uses — including the tier ladder itself, which is served rather than
        written out here. That is deliberate: the site this business is modelled on
        advertises "5% cashback on every order" on its homepage while its own
        rewards page tops out at a 5% discount tier. The copy drifted from the
        engine and nobody caught it. Here it cannot.
      */}

      {/* Ahead of everything: the rest of this page describes a rebate that does not
          apply in the currently-selected currency. */}
      <LoyaltyCurrencyNotice className="mb-8" />

      {account && <YourStanding />}

      <dl className="grid gap-px overflow-hidden rounded-edge bg-ink-400 md:grid-cols-3">
        <StatPlate
          label={t.rewards.earningLabel}
          value={t.rewards.earningUnit(policy.earnPointsPerUnit)}
          note={t.rewards.earningPer(money(policy.earnSpendUnitMinor))}
        />
        <StatPlate
          label={t.rewards.spendingLabel}
          value={money(policy.pointValueMinor)}
          note={t.rewards.spendingPer}
        />
        <StatPlate
          label={policy.tierDiscountEnabled ? t.rewards.topLabel : t.rewards.effectiveLabel}
          value={
            policy.tierDiscountEnabled && topTier
              ? `${effectiveRate.toFixed(0)} + ${bpsToPercent(topTier.discountBps)}`
              : `${effectiveRate.toFixed(0)}%`
          }
          note={
            policy.tierDiscountEnabled && topTier
              ? t.rewards.topBody(topTier.displayName)
              : t.rewards.effectiveBody
          }
        />
      </dl>

      {/* ------------------------------------------------------------ the ladder --- */}

      {policy.tierDiscountEnabled && (
        <div className="mt-14">
          <h2 className="display text-display-lg text-sheen">{t.rewards.tiersTitle}</h2>
          <p className="measure mt-3 text-body-sm leading-relaxed text-chalk-muted">
            {t.rewards.tiersLead}
          </p>

          {/*
            A ladder, not a table.

            The data is identical — tier, threshold, discount — but a three-column
            table asks the reader to compare rows, and comparison is not the job
            here. The job is to make someone on tier two want tier three. Laying the
            tiers along a rail that fills with gold as it climbs turns the same
            numbers into a position and a direction, which is what a progression
            actually is.

            It stays a `<dl>` underneath, so the relationship between each tier and
            its numbers survives for a screen reader even though the visual
            presentation is a rail.
          */}
          <div className="fade-x mt-8 overflow-x-auto pb-2">
            <ol className="relative flex min-w-max gap-3">
              {/* The rail. Sits behind the plates at the height of their markers. */}
              <div
                aria-hidden="true"
                className="absolute inset-x-0 top-[13px] h-px bg-gradient-to-r from-ink-400
                           via-gold-500/50 to-gold-400"
              />

              {tiers.map((tier, index) => {
                const top = index === tiers.length - 1
                return (
                  <Reveal
                    as="li"
                    key={tier.name}
                    delay={index * 70}
                    className="relative w-[176px] shrink-0"
                  >
                    {/*
                      The marker fills progressively across the ladder. Earlier tiers
                      are hollow, later ones solid gold — so the eye reads the
                      direction of travel before it reads a single threshold.
                    */}
                    <span
                      aria-hidden="true"
                      className={[
                        'relative z-10 block h-[27px] w-[27px] rounded-full border-2 bg-ink',
                        top
                          ? 'border-gold-400 shadow-[0_0_0_3px_theme(colors.red.ring)]'
                          : 'border-ink-300',
                      ].join(' ')}
                    >
                      <span
                        className="absolute inset-[5px] rounded-full bg-gold-500"
                        style={{ opacity: index / Math.max(1, tiers.length - 1) }}
                      />
                    </span>

                    <div
                      className={[
                        'mt-5 rounded-panel border p-5',
                        top
                          ? 'border-gold-500/40 bg-gold-500/[0.05]'
                          : 'border-ink-400 bg-paper',
                      ].join(' ')}
                    >
                      <dt className="display text-display-sm text-chalk">{tier.displayName}</dt>
                      <dd className="mt-3">
                        <p className="text-[10.5px] uppercase tracking-[0.14em] text-chalk-faint">
                          {t.rewards.colPoints}
                        </p>
                        <p className="tnum mt-1 text-body-sm text-chalk-muted">
                          {tier.thresholdPoints === 0
                            ? t.rewards.fromFirstOrder
                            : tier.thresholdPoints.toLocaleString()}
                        </p>
                      </dd>
                      <dd className="mt-3 border-t border-ink-400 pt-3">
                        <p className="text-[10.5px] uppercase tracking-[0.14em] text-chalk-faint">
                          {t.rewards.colDiscount}
                        </p>
                        <p className="tnum display mt-1 text-display-md text-gold-400">
                          {tier.discountBps === 0 ? '—' : bpsToPercent(tier.discountBps)}
                        </p>
                      </dd>
                    </div>
                  </Reveal>
                )
              })}
            </ol>
          </div>
        </div>
      )}

      {/* ------------------------------------------------------------- the rules --- */}

      <div className="mt-10 grid gap-5 md:grid-cols-2">
        <Card className="p-7">
          <h2 className="display text-[16px] text-chalk">{t.rewards.rulesTitle}</h2>
          <ul className="mt-4 space-y-3 text-[13.5px] leading-relaxed text-chalk-muted">
            <li>
              <strong className="text-chalk">{t.rewards.rulePointsLand}</strong>{' '}
              {t.rewards.rulePointsLandBody(policy.guaranteeDays)}
            </li>
            <li>
              <strong className="text-chalk">
                {t.rewards.ruleCap(bpsToPercent(policy.maxWalletRedemptionBps))}
              </strong>{' '}
              {t.rewards.ruleCapBody}
            </li>
            {policy.tierDiscountEnabled && (
              <li>
                <strong className="text-chalk">{t.rewards.ruleNoDemote}</strong>{' '}
                {t.rewards.ruleNoDemoteBody}
              </li>
            )}
            {policy.dailyBonusPoints > 0 && (
              <li>
                <strong className="text-chalk">
                  {t.rewards.ruleDaily(policy.dailyBonusPoints)}
                </strong>{' '}
                {t.rewards.ruleDailyBody}
              </li>
            )}
            {/*
              Unconditional, unlike the rules above it.

              Those are switched off when the feature behind them is, because a rule
              describing something that does not happen is noise. This one describes what
              points are *not*, and that stays true however the scheme is configured. It is
              also stated outright in the terms of service, so a page omitting it left the
              scheme's single most important limitation to be inferred.
            */}
            <li>
              <strong className="text-chalk">{t.rewards.ruleNoCash}</strong>{' '}
              {t.rewards.ruleNoCashBody}
            </li>
          </ul>
        </Card>

        <Card className="p-7">
          <h2 className="display text-[16px] text-chalk">{t.rewards.startedTitle}</h2>
          <p className="mt-3 text-[13.5px] leading-relaxed text-chalk-muted">
            {t.rewards.startedBody}
          </p>
          <div className="mt-6 flex flex-wrap gap-3">
            <ButtonLink to="/register" size="md">{t.rewards.createAccount}</ButtonLink>
            <ButtonLink to="/order" variant="secondary" size="md">
              {t.rewards.startOrder}
            </ButtonLink>
          </div>
        </Card>
      </div>
    </Section>
    </>
  )
}

/** One reading in the rewards summary strip. */
function StatPlate({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <div className="bg-ink px-6 py-7">
      <dt className="text-[10.5px] font-semibold uppercase tracking-[0.16em] text-gold-400">
        {label}
      </dt>
      <dd className="display tnum mt-3 text-[clamp(1.8rem,4vw,2.2rem)] leading-none text-chalk">
        {value}
      </dd>
      <p className="mt-2.5 text-body-sm text-chalk-muted">{note}</p>
    </div>
  )
}

/**
 * The signed-in customer's own standing, and the daily claim.
 *
 * <p>Every number here comes from the server, including the tier itself. Deriving a tier in
 * the browser from a points balance would be a second implementation of the ladder, and the
 * second implementation is the one that disagrees with checkout.
 */
function YourStanding() {
  const t = useT()
  const [status, setStatus] = useState<LoyaltyStatus | null>(null)
  const [claiming, setClaiming] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    api
      .get<LoyaltyStatus>('/api/v1/account/loyalty')
      .then(setStatus)
      .catch(() => setStatus(null))
  }, [])

  useEffect(load, [load])

  async function claim() {
    setClaiming(true)
    setError(null)
    try {
      setStatus(await api.post<LoyaltyStatus>('/api/v1/account/loyalty/daily'))
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : t.rewards.claimFailed)
    } finally {
      setClaiming(false)
    }
  }

  if (!status) return null

  const tierLabel = status.tier.charAt(0) + status.tier.slice(1).toLowerCase()
  const nextLabel = status.nextTier
    ? status.nextTier.charAt(0) + status.nextTier.slice(1).toLowerCase()
    : null

  return (
    <Card className="mb-8 p-7">
      <div className="flex flex-wrap items-start justify-between gap-6">
        <div>
          <p className="eyebrow text-gold-400">{t.rewards.standingEyebrow}</p>
          <div className="mt-3 flex items-center gap-3">
            <span className="display text-[30px] text-chalk">{tierLabel}</span>
            {status.discountBps > 0 && (
              <Badge tone="gold">
                {t.rewards.discountOffEvery(bpsToPercent(status.discountBps))}
              </Badge>
            )}
          </div>
          <p className="mt-2 text-[13.5px] text-chalk-muted">
            {t.rewards.lifetimeLine(
              status.lifetimePoints.toLocaleString(),
              status.balancePoints.toLocaleString(),
            )}
          </p>

          {nextLabel && status.pointsToNextTier > 0 && (
            <p className="mt-3 text-[13.5px] text-chalk-faint">
              {t.rewards.toNextTier(status.pointsToNextTier.toLocaleString(), nextLabel)}
            </p>
          )}
        </div>

        {status.dailyBonusPoints > 0 && (
          <div className="text-right">
            {status.canClaimDaily ? (
              <Button size="md" disabled={claiming} onClick={() => void claim()}>
                {claiming
                  ? <Spinner size={16} />
                  : t.rewards.claim(status.dailyBonusPoints)}
              </Button>
            ) : (
              <p className="text-[13px] text-chalk-faint">
                {t.rewards.claimedToday(status.dailyBonusPoints)}
                <br />
                {t.rewards.comeBack}
              </p>
            )}
            {error && (
              <p className="mt-2 text-[13px] text-warn" role="alert">
                {error}
              </p>
            )}
          </div>
        )}
      </div>
    </Card>
  )
}
