import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { LoyaltyCurrencyNotice, useLoyaltyActive } from '../components/LoyaltyNotice'
import { PageHeader } from '../components/PageHeader'
import { PlatformCard } from '../components/PlatformCard'
import { PlatformIcon } from '../components/PlatformIcon'
import {
  Alert, Badge, Button, ButtonLink, Checkbox, Field, Input, Section, SelectTile,
  Skeleton, Spinner, StepCard,
} from '../components/ui'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { bpsToPercent, coinsLabel, trimNumber } from '../lib/format'
import { useMoney } from '../lib/money'
import { openCheckout, isStubGateway } from '../lib/razorpay'
import { SEASON, useSeo } from '../lib/seo'
import type { CreateOrderResponse, QuoteLine, SignedQuote } from '../lib/types'
import { useReducedMotion } from '../motion'
import { useAuth } from '../state/AuthContext'
import { useCatalog } from '../state/CatalogContext'
import { CoinIcon } from '../brand/CoinIcon'
import { RankBadge } from '../components/RankBadge'
import { useCatalogLabels } from '../content/catalogLabels'
import { Testimonials } from '../components/Testimonials'
import type { TestimonialService } from '../data/testimonials'

type Step = 'configure' | 'details' | 'paying' | 'placed'

export default function Order() {
  const t = useT()
  useSeo({
    title: t.order.seoTitle(SEASON),
    description: t.order.seoDescription,
    // The configurator produces an unbounded number of near-duplicate URL states
    // and has nothing a search engine should hold. Rank the landing pages instead.
    noindex: true,
  })

  const { catalog, policy, loading, error } = useCatalog()
  const labels = useCatalogLabels()
  const { account } = useAuth()
  const [params] = useSearchParams()

  /*
   * Which service is being bought.
   *
   * The landing pages link here with ?service= and ?variant=. Coin trading is priced
   * per million on a platform, so it gets a platform picker and a slider; boosting
   * tiers and coaching packs are FLAT SKUs with neither. Rather than three
   * configurators, this one has two shapes — and an unknown or not-yet-sellable
   * service falls back to trading rather than rendering an empty page.
   */
  const requestedSku = (params.get('service') ?? 'TRADING_SERVICE').toUpperCase()
  const service =
    catalog?.services.find((s) => s.sku === requestedSku && s.sellable) ??
    catalog?.services.find((s) => s.sku === 'TRADING_SERVICE')
  const options = useMemo(() => service?.options ?? [], [service])
  const isFlat = service?.priceUnit === 'FLAT'

  const [platform, setPlatform] = useState<string>('')
  const [variant, setVariant] = useState<string>(params.get('variant') ?? '')
  const [quantity, setQuantity] = useState(3)
  // Accepts ?coupon= so a code can be shared as a link rather than typed off a stream.
  const [couponCode, setCouponCode] = useState(params.get('coupon') ?? '')
  const [pointsToRedeem, setPointsToRedeem] = useState(0)
  const [step, setStep] = useState<Step>('configure')

  const [quote, setQuote] = useState<SignedQuote | null>(null)
  const [quoting, setQuoting] = useState(false)
  const [quoteError, setQuoteError] = useState<string | null>(null)

  useEffect(() => {
    if (isFlat) {
      // A variant that came from the URL but is not in the live catalogue would price
      // nothing, so fall back to the first real option rather than to a dead request.
      if (options.length > 0 && !options.some((o) => o.variant === variant)) {
        setVariant(options[0]?.variant ?? '')
      }
    } else if (!platform && options.length > 0) {
      setPlatform(options[0]?.platform ?? '')
    }
  }, [options, platform, variant, isFlat])

  const selected = isFlat
    ? options.find((o) => o.variant === variant) ?? options[0]
    : options.find((o) => o.platform === platform) ?? options[0]
  const min = Number(selected?.minQuantity ?? 0.5)
  const max = Number(selected?.maxQuantity ?? 100)
  const stepSize = Number(selected?.stepQuantity ?? 0.5)

  /*
   * Quoting is debounced and every in-flight request is aborted when a newer one
   * starts. Without the abort, dragging the slider fires twenty requests whose
   * responses arrive out of order, and the price on screen ends up being whichever
   * one happened to land last rather than the one the customer is looking at.
   */
  const abortRef = useRef<AbortController | null>(null)

  const fetchQuote = useCallback(async () => {
    if (!catalog || !service) return
    // A flat SKU is identified by its variant; a per-million one by its platform.
    if (isFlat ? !selected?.variant : !platform) return

    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    setQuoting(true)
    setQuoteError(null)
    try {
      const next = await api.post<SignedQuote>('/api/v1/quotes', {
        sku: service.sku,
        platform: isFlat ? null : platform,
        variant: isFlat ? selected?.variant ?? null : null,
        // Quantity is ignored server-side for FLAT SKUs, but sending the slider's
        // value anyway would fail the request's own DecimalMax on a stale render.
        quantity: isFlat ? '1' : String(quantity),
        currency: catalog.currency,
        couponCode: couponCode.trim() || null,
        pointsToRedeem: account ? pointsToRedeem : 0,
      })
      if (!controller.signal.aborted) setQuote(next)
    } catch (e) {
      if (!controller.signal.aborted) {
        setQuote(null)
        setQuoteError(e instanceof ApiError ? e.message : 'We could not price that. Try again.')
      }
    } finally {
      if (!controller.signal.aborted) setQuoting(false)
    }
  }, [platform, quantity, couponCode, pointsToRedeem, catalog, account,
      service, isFlat, selected?.variant])

  useEffect(() => {
    const timer = setTimeout(() => void fetchQuote(), 260)
    return () => clearTimeout(timer)
  }, [fetchQuote])

  /*
   * The masthead survives both failure modes.
   *
   * These two branches used to return a bare skeleton and a bare alert, so a page
   * that was loading or broken had no <h1> at all — the one moment a reader most
   * needs to be told which page they are on. The header renders from the dictionary
   * and the season, neither of which depends on the request that is failing.
   */
  /*
   * Whether to show the season's coin volume beside the heading.
   *
   * Resolved service first, requested SKU while the catalogue is still in flight.
   * Reading only the resolved one would leave the masthead without the plate for
   * the length of the request and then pop it in, which is a layout shift on the
   * first thing the page draws; reading only the requested one would get the
   * fallback case wrong, since an unknown or not-yet-sellable SKU lands on trading.
   */
  const showVolume = service
    ? service.sku === 'TRADING_SERVICE'
    : requestedSku === 'TRADING_SERVICE'

  /*
   * Which customers to show. Unknown SKUs get nothing rather than a default: a
   * "Player Cards" page showing coin testimonials would be attributing quotes to a
   * product nobody in them bought.
   */
  const reviewsFor: TestimonialService | null =
    service?.sku === 'TRADING_SERVICE'
      ? 'trading'
      : service?.sku === 'BOOST_CHAMPS' || service?.sku === 'BOOST_RIVALS'
        ? 'boosting'
        : service?.sku === 'COACHING'
          ? 'coaching'
          : null

  const heading = (
    <PageHeader
      eyebrow={`${SEASON} · ${labels.service(service?.sku, service?.displayName)}`}
      title={t.order.title}
      lead={t.order.lead}
      aside={
        showVolume ? (
          /*
           * Volume as a credential, in the slot the masthead already keeps for a
           * reading — the same plate the account page gives a points balance and
           * the coaching page gives a credit count.
           *
           * It sits here rather than in the body because it answers the question a
           * first-time buyer is actually holding at this moment ("has this shop
           * done this before?") and it has to answer it beside the price, not four
           * screens below it. Stated once, quietly, at the top; the page underneath
           * goes back to talking about the order.
           */
          <div className="plate ticks px-6 py-5">
            <p className="text-[10.5px] font-semibold uppercase tracking-[0.16em] text-gold-400">
              {t.order.volumeLabel}
            </p>
            <div className="mt-2 flex items-center gap-2.5">
              <CoinIcon size={30} />
              <span className="tnum display text-[clamp(2.2rem,5vw,2.8rem)] leading-none text-chalk">
                {t.order.volumeValue}
              </span>
            </div>
            <p className="mt-2 text-[12.5px] text-chalk-muted">{t.order.volumeNote(SEASON)}</p>
          </div>
        ) : undefined
      }
    />
  )

  if (loading) {
    return (
      <>
        {heading}
        <ConfiguratorSkeleton />
      </>
    )
  }
  if (error) {
    return (
      <>
        {heading}
        <Section className="rhythm-section">
          <Alert tone="warn" title={t.order.pricesUnavailable}>{error}</Alert>
        </Section>
      </>
    )
  }

  const maxRedeemable = policy && quote
    ? Math.floor((quote.subtotalMinor * policy.maxWalletRedemptionBps) / 10_000 / policy.pointValueMinor)
    : 0

  return (
    <>
      {heading}
      <Section className="rhythm-section">
      <div className="grid gap-5 lg:grid-cols-[1.25fr_1fr] lg:items-start">
        <div className="space-y-5">
          {/*
            Past the configure step the left column becomes the cart and the forms, and
            the configurator is replaced rather than left above them. Keeping the slider
            on screen while somebody types their EA password invites them back into
            pricing mid-checkout, which re-quotes and moves the total they were about to
            pay.
          */}
          {step !== 'configure' && quote && (
            <>
              <CartCard quote={quote} onEdit={() => setStep('configure')} />
              <button
                type="button"
                onClick={() => setStep('configure')}
                className="inline-flex items-center gap-2 text-body-sm font-semibold text-brand-400
                           hover:underline focus-visible:outline focus-visible:outline-2
                           focus-visible:outline-offset-2 focus-visible:outline-brand-400"
              >
                <span aria-hidden="true">&larr;</span>
                {t.order.continueShopping}
              </button>
            </>
          )}

          {/* Trading only: boosting tiers and coaching packs have no account prerequisites. */}
          {step === 'configure' && !isFlat && <RequirementsPanel />}

          {step === 'configure' && (isFlat ? (
            /* Boosting tiers and coaching packs: one choice, no slider. */
            <StepCard step={1} title={t.order.stepPackage(1, labels.service(service?.sku, service?.displayName))}>
              <div className="grid gap-3 sm:grid-cols-2">
                {options.map((option) => (
                  <SelectTile
                    key={option.variant}
                    active={option.variant === selected?.variant}
                    onSelect={() => setVariant(option.variant ?? '')}
                    label={labels.option(option)}
                    sub={option.unitPriceFormatted}
                    /*
                      The Elite shield, where the tier has one. `icon` renders null for
                      the tiers that do not — Champion I and II, the extra-wins add-on,
                      and every coaching pack — so the same picker serves all of them
                      without a branch here.
                    */
                    icon={<RankBadge variant={option.variant} size={34} />}
                  />
                ))}
              </div>
            </StepCard>
          ) : (
            <>
              <StepCard step={1} title={t.order.stepPlatform(1)}>
                <div className="grid gap-3 sm:grid-cols-3">
                  {options.map((option) => (
                    <PlatformCard
                      key={option.platform}
                      platform={option.platform}
                      active={option.platform === platform}
                      onSelect={() => setPlatform(option.platform ?? '')}
                      label={option.label ?? ''}
                      price={`${option.unitPriceFormatted} ${t.order.perMillion}`}
                      taxNote={service?.marketTaxApplies ? t.order.taxIncludedInline : undefined}
                    />
                  ))}
                </div>
              </StepCard>

              <StepCard
                step={2}
                title={t.order.stepAmount(2)}
                aside={
                  /*
                   * The live amount, set at display size and in tabular figures.
                   *
                   * Tabular is not a nicety here — this number changes on every
                   * pointer move while the slider is dragged, and in proportional
                   * figures the heading beside it would shuffle left and right the
                   * whole time. It is also the only red on the panel, because it is
                   * the value the customer is actually choosing.
                   */
                  <AmountReadout quantity={quantity} />
                }
              >
                {/*
                  A custom track rather than a bare `accent-color` range.
                  The filled portion is drawn as a gradient behind the thumb so the
                  chosen amount reads at a glance from the length of the red, which
                  is the entire reason a slider beats a number field for this.
                */}
                <div className="relative">
                  <input
                    type="range"
                    min={min}
                    max={max}
                    step={stepSize}
                    value={quantity}
                    onChange={(e) => setQuantity(Number(e.target.value))}
                    aria-label={t.order.amountAria}
                    className="slider-range h-11 w-full cursor-pointer appearance-none bg-transparent"
                    style={{
                      ['--fill' as string]: `${((quantity - min) / (max - min)) * 100}%`,
                    }}
                  />
                </div>

                <div className="tnum flex justify-between text-[11.5px] text-chalk-faint">
                  <span>{trimNumber(min)}M</span>
                  <span>{trimNumber(max)}M</span>
                </div>

                {/*
                  The same amount, typed.

                  A slider is the right control for browsing a range and the wrong one
                  for arriving at a number you already know. At 10K granularity the
                  track holds 450 positions, so "I want 1.28M" is a drag nobody wins.
                  Both controls write the same state, so they cannot disagree.

                  Committed on blur rather than on every keystroke: snapping mid-typing
                  turns "1" into the minimum before the "2" is pressed, and the field
                  fights the person using it.
                */}
                <ManualAmount
                  quantity={quantity}
                  min={min}
                  max={max}
                  stepSize={stepSize}
                  onCommit={setQuantity}
                />

                {/*
                  The arithmetic, shown. A slider that only reports a total leaves
                  the customer doing the per-million division in their head to work
                  out whether it is a good price — which is exactly the moment they
                  open a competitor's tab. It fades rather than pops because it
                  changes on every drag frame.
                */}
                <div
                  className="tnum mt-4 flex items-center gap-2 text-[12.5px] text-chalk-muted
                             transition-opacity duration-300"
                  style={{ opacity: selected?.unitPriceFormatted ? 1 : 0 }}
                >
                  <span className="text-chalk-faint">{trimNumber(quantity)}M</span>
                  <span aria-hidden="true" className="text-chalk-faint">&times;</span>
                  <span>{selected?.unitPriceFormatted ?? '—'}</span>
                  <span aria-hidden="true" className="h-3 w-px bg-ink-300" />
                  <span className="text-chalk-faint">{t.order.perMillion}</span>
                  {service?.marketTaxApplies && (
                    <>
                      <span aria-hidden="true" className="h-3 w-px bg-ink-300" />
                      <span className="font-semibold text-ok">{t.order.taxIncludedInline}</span>
                    </>
                  )}
                </div>

                <div className="mt-5 flex flex-wrap gap-2">
                  {[1, 2, 5, 10, 20, 50].filter((v) => v >= min && v <= max).map((preset) => (
                    <button
                      key={preset}
                      type="button"
                      onClick={() => setQuantity(preset)}
                      aria-pressed={quantity === preset}
                      className={[
                        // 44px tall, not the 37px that `py-2` produced. These are the
                        // controls a phone user actually taps on this page, and the
                        // type stays small — so the height has to be set outright
                        // rather than fall out of the padding.
                        'tnum grid h-11 min-w-[3rem] place-items-center rounded-edge px-3.5',
                        'text-[12.5px] font-semibold',
                        'transition-[background-color,color,border-color,transform] duration-200',
                        'ease-out-expo active:scale-95',
                        quantity === preset
                          ? 'border border-brand-500 bg-brand-500 text-paper'
                          : 'border border-ink-400 bg-ink-700 text-chalk-muted hover:border-ink-300 hover:text-chalk',
                      ].join(' ')}
                    >
                      {preset}M
                    </button>
                  ))}
                </div>
              </StepCard>
            </>
          ))}

          {/*
            The discounts step is gone from the configurator, not merely moved.

            Coupon and points are now two tabs in the order panel, beside the total they
            reduce. Leaving a second copy here would give a customer two places to type a
            code into and one of them would win silently.
          */}

          {/*
            The form itself, in the left column beside the summary rather than inside it.

            It used to render within `QuotePanel`, which meant the sticky order panel grew
            to hold an entire checkout and stopped being a summary. Fields belong with the
            cart they describe; the panel keeps the total, the points and the payment.
          */}
          {step !== 'configure' && quote && (
            <CheckoutForm quote={quote} step={step} setStep={setStep} onRequote={() => void fetchQuote()} />
          )}
        </div>

        <QuotePanel
          couponCode={couponCode}
          onApplyCoupon={setCouponCode}
          pointsToRedeem={pointsToRedeem}
          setPointsToRedeem={setPointsToRedeem}
          maxRedeemable={maxRedeemable}
          quote={quote}
          quoting={quoting}
          error={quoteError}
          step={step}
          setStep={setStep}
          onRequote={() => void fetchQuote()}
        />
      </div>
      </Section>

      {/*
        Proof for the thing being bought, directly under the thing that buys it.

        `/order` is the same page for all three services, so the filter follows the
        SKU rather than being fixed — somebody configuring a coaching block is shown
        coaching customers, not coin customers, which is the whole reason the
        component takes `only`.

        Configure step only. Once somebody is entering delivery details or paying,
        they have decided; testimonials at that point are a wall of text between them
        and the form, and the one thing a checkout should never do is get longer.
      */}
      {step === 'configure' && reviewsFor && (
        <div className="border-t border-ink-400 bg-paper">
          <Testimonials only={reviewsFor} />
        </div>
      )}
    </>
  )
}

/**
 * The live coin amount, eased toward its target.
 *
 * <p>The tween is the interesting part, and it is deliberately *not* what the
 * brief asked for. Animating on every change would mean animating on every
 * pointer move while the slider is dragged — the number would lag the thumb, and
 * a readout that trails your own finger feels broken rather than smooth.
 *
 * <p>So it eases only when the jump is large, which in practice means the preset
 * buttons and nothing else. Dragging updates instantly; tapping "20M" counts up
 * to it. The threshold is what separates the two, and no state has to track
 * which input the change came from.
 */
function AmountReadout({ quantity }: { quantity: number }) {
  const [shown, setShown] = useState(quantity)
  const reduced = useReducedMotion()
  const frame = useRef(0)

  useEffect(() => {
    if (reduced) {
      setShown(quantity)
      return
    }
    const from = shown
    const delta = Math.abs(quantity - from)
    // A drag step is 0.5M. Anything bigger came from a button.
    if (delta <= 0.75) {
      setShown(quantity)
      return
    }
    const started = performance.now()
    const duration = 420
    const tick = (now: number) => {
      const p = Math.min(1, (now - started) / duration)
      const eased = 1 - Math.pow(1 - p, 3)
      setShown(from + (quantity - from) * eased)
      if (p < 1) frame.current = requestAnimationFrame(tick)
    }
    frame.current = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame.current)
    // `shown` is read as a starting point, not depended on — including it would
    // restart the tween on every frame it sets.
  }, [quantity, reduced]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <span
      /*
       * The ramp runs light-to-mid, never into `brand-600`.
       *
       * A gradient on text has to clear the contrast floor at *every* stop, not on
       * average, because some glyphs land entirely in the dark end — and the number
       * of coins someone is about to buy is the last thing on this page that may be
       * hard to read. `brand-600` measured 2.95:1 on the card and failed outright.
       * `brand-200` to `brand-400` keeps the whole run at 5.3:1 or better and still
       * reads as hot metal rather than as flat text.
       */
      className="inline-flex items-center gap-2.5"
      // The eased value is decorative; assistive tech gets the real one.
      aria-label={coinsLabel(quantity)}
    >
      {/*
        The coin sits beside the number rather than replacing the word.

        "3M" alone is a quantity of nothing in particular; the coin says what is being
        counted at a glance, and the label still spells it out for anyone who needs it.
      */}
      <CoinIcon size={30} />
      <span
        aria-hidden="true"
        className="tnum display bg-gradient-to-br from-brand-200 to-brand-400 bg-clip-text
                   text-display-md text-transparent"
      >
        {coinsLabel(shown)}
      </span>
    </span>
  )
}

/* ------------------------------------------------------------- quote panel --- */

function QuotePanel({
  quote, quoting, error, step, setStep, onRequote, couponCode, onApplyCoupon,
  pointsToRedeem, setPointsToRedeem, maxRedeemable,
}: {
  quote: SignedQuote | null
  quoting: boolean
  error: string | null
  step: Step
  setStep: (step: Step) => void
  onRequote: () => void
  couponCode: string
  onApplyCoupon: (code: string) => void
  pointsToRedeem: number
  setPointsToRedeem: (points: number) => void
  maxRedeemable: number
}) {
  const t = useT()
  const money = useMoney()
  const { account } = useAuth()

  /*
   * A saving is a negative line, plus the zero-value market tax.
   *
   * The tax line is the odd one: it is 0 because GFS absorbs EA's cut, so
   * arithmetically it is not a discount, but it is the customer's second-biggest
   * reason the total is what it is. Grouping it with the charges would put
   * "Included" in a column of amounts and lose the point of showing it at all.
   */
  const isSaving = (line: QuoteLine) =>
    line.amountMinor < 0 || (line.code === 'MARKET_TAX' && line.amountMinor === 0)

  const lines = quote?.lines ?? []
  const charges = lines.filter((line) => !isSaving(line))
  const savings = lines.filter(isSaving)
  const savedMinor = savings.reduce((sum, line) => sum + Math.abs(Math.min(0, line.amountMinor)), 0)
  const savedFormatted = money(savedMinor)

  return (
    /*
     * Sticky from `lg` up, offset to clear the 72px header plus a little air.
     *
     * The running total is the one thing a customer refers back to constantly while
     * they configure, and making them scroll up to find it is how carts get
     * abandoned. It is deliberately not sticky on a phone: a panel pinned to a short
     * viewport eats the space the configurator needs, and on mobile the summary sits
     * naturally at the end of the flow anyway.
     */
    <div className="lg:sticky lg:top-[88px]">
      <div className="plate ticks overflow-hidden shadow-e3">
        <div className="flex items-center justify-between gap-3 border-b border-ink-400/50 bg-paper px-5 py-3.5">
          <h2 className="display text-[13px] uppercase tracking-[0.16em] text-chalk">
            {t.order.summaryTitle}
          </h2>
          {quoting && quote && <Spinner size={14} className="text-chalk-faint" />}
        </div>

        <div className="space-y-4 p-5">
          <SavingsTabs
            couponKey={quote?.couponCode ?? 'none'}
            couponCode={couponCode}
            appliedCoupon={quote?.couponCode ?? null}
            couponMessage={quote?.couponMessage ?? null}
            busy={quoting}
            onApplyCoupon={onApplyCoupon}
            pointsToRedeem={pointsToRedeem}
            setPointsToRedeem={setPointsToRedeem}
            maxRedeemable={maxRedeemable}
          />

          {quoting && !quote && (
            <>
              <Skeleton className="h-5 w-full" />
              <Skeleton className="h-5 w-3/4" />
              <Skeleton className="h-12 w-full" />
            </>
          )}

          {error && <Alert tone="warn">{error}</Alert>}

          {quote && (
            <>
              {/*
                Two groups, not one list: what you are charged, then what came off.

                The engine emits the lines in the order it applies them, and that order
                is load-bearing arithmetic — gateway fee after discount is a real money
                decision pinned by a test. It is not, however, a reading order. Applied
                sequentially it buries a coupon in the middle of the column, three lines
                above the number it changed, so the customer scrolls back up to work out
                why the total moved.

                Regrouping is display-only and cannot alter the total: the same lines
                are rendered, and addition does not care what order they are read in.
                Savings sit directly above the total because that is the figure they
                explain.
              */}
              <dl className="space-y-2.5">
                {charges.map((line, index) => (
                  <QuoteLineRow key={line.code} line={line} index={index} quote={quote} />
                ))}
              </dl>

              <div className="rounded-edge border border-ok/25 bg-ok/[0.05] px-3.5 py-3">
                <div className="mb-2 flex items-baseline justify-between gap-3">
                  <p className="stamp !mb-0 text-ok">{t.order.savingsTitle}</p>
                  {savedMinor > 0 && (
                    <p className="tnum text-[12.5px] font-semibold text-ok">
                      {t.order.youSave} {savedFormatted}
                    </p>
                  )}
                </div>
                {savings.length > 0 ? (
                  <dl className="space-y-2.5">
                    {savings.map((line, index) => (
                      <QuoteLineRow key={line.code} line={line} index={index} quote={quote} />
                    ))}
                  </dl>
                ) : (
                  /*
                    Shown empty rather than hidden. A discount section that only exists
                    once a discount does is one the customer never learns is there — and
                    "where do I put a coupon code" is the single most common question a
                    checkout gets.
                  */
                  <p className="text-[12px] leading-relaxed text-chalk-faint">
                    {t.order.savingsEmpty}
                  </p>
                )}
              </div>

              {/*
                The total gets a heavier rule above it than the lines get between
                them, so the eye stops there. It is also the only figure on the panel
                set in display type — the size difference is what makes "this is the
                number you are paying" legible without a label doing the work.
              */}
              {/*
                The total gets a ground of its own.

                Everything above it is a hairline-separated list; this is the
                figure the card is charged. A faint brand wash and a left rule
                lift it out of the column without needing a larger type size than
                it already has.
              */}
              <div
                className="relative flex items-baseline justify-between overflow-hidden
                           rounded-edge border-l-2 border-brand-500 px-3.5 py-3"
                style={{
                  background:
                    'linear-gradient(90deg, rgb(193 40 27 / 0.14), rgb(193 40 27 / 0.02) 70%, transparent)',
                }}
              >
                <span className="text-[13px] font-medium text-chalk">{t.order.total}</span>
                <span className="tnum display text-display-md text-chalk">
                  {quote.totalFormatted}
                </span>
              </div>

              {/*
                The claim, immediately under the number it is about.

                Placed after the total rather than above the breakdown on purpose: it is
                an argument about the figure the customer just read, and an argument
                lands where the reader already is. Above the lines it would be a banner
                to scroll past; here it explains the total they are looking at.

                Keyed off the breakdown itself rather than the catalogue flag, so the
                banner and the line it refers to can never disagree: the engine emits a
                MARKET_TAX line exactly when EA's cut is relevant, which on a coaching
                order it is not.
              */}
              {quote.lines.some((l) => l.code === 'MARKET_TAX') && (
                <div className="flex items-start gap-2.5 rounded-edge border border-ok/30 bg-ok/[0.07] px-3.5 py-3">
                  <svg
                    width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                    strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"
                    aria-hidden="true" className="mt-0.5 shrink-0 text-ok"
                  >
                    <path d="M20 6 9 17l-5-5" />
                  </svg>
                  <div>
                    <p className="text-[12.5px] font-semibold text-chalk">
                      {t.order.taxIncludedTitle}
                    </p>
                    <p className="mt-0.5 text-[11.5px] leading-relaxed text-chalk-muted">
                      {t.order.taxIncludedBody}
                    </p>
                  </div>
                </div>
              )}

              {/*
                What this order is worth in points -- and to whom.

                The quote's `pointsEarned` is computed from the total and the currency
                alone; `pointsEarnedOn` never sees an account, so it returns the same
                number for a guest as for a signed-in customer. Rendering it unqualified
                promised a guest points the terms of service say a guest order cannot
                earn.

                Shown rather than hidden for guests, because hiding it loses the reason
                to sign in at the one moment it is worth something. The number is the
                same; the sentence says whose it is.
              */}
              {quote.pointsEarned > 0 && (
                <p className={`flex items-start gap-2 text-[12px] ${
                  account ? 'text-gold-400' : 'text-chalk-muted'
                }`}>
                  <span
                    aria-hidden="true"
                    className={`mt-1.5 h-1 w-1 shrink-0 rotate-45 ${
                      account ? 'bg-gold-400' : 'bg-chalk-faint'
                    }`}
                  />
                  {account
                    ? t.order.earnsPoints(quote.pointsEarned)
                    : t.order.earnsPointsGuest(quote.pointsEarned)}
                </p>
              )}

              <QuoteTimer expiresAt={quote.expiresAt} onExpire={onRequote} />

              {step === 'configure' && (
                <Button full size="lg" onClick={() => setStep('details')}>
                  {t.order.continue}
                </Button>
              )}
            </>
          )}

        </div>
      </div>

      <p className="mt-4 px-2 text-center text-[11.5px] leading-relaxed text-chalk-faint">
        {t.order.priceNote}
      </p>
    </div>
  )
}

/**
 * Counts a quote down and re-prices it when it lapses.
 *
 * Quotes expire because rates move — they fall as a season ages and jump when a new
 * title lands. Rather than letting a customer discover that at the payment sheet,
 * the panel re-prices itself and shows the change.
 */
function QuoteTimer({ expiresAt, onExpire }: { expiresAt: string; onExpire: () => void }) {
  const t = useT()
  const [remaining, setRemaining] = useState(() =>
    Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000)),
  )

  useEffect(() => {
    setRemaining(Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000)))
    const timer = setInterval(() => {
      const next = Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000))
      setRemaining(next)
      if (next === 0) onExpire()
    }, 1000)
    return () => clearInterval(timer)
  }, [expiresAt, onExpire])

  if (remaining === 0) {
    return <p className="text-[12px] text-chalk-faint">{t.order.refreshingPrice}</p>
  }

  const minutes = Math.floor(remaining / 60)
  const seconds = String(remaining % 60).padStart(2, '0')

  /*
   * The hold gets a colour as it runs out.
   *
   * Green for most of the window, amber under two minutes, red under thirty
   * seconds — and the dot pulses only in that last band. A countdown that looks
   * identical at 9:00 and 0:20 is a countdown nobody reads; changing channel at
   * the point where it starts to matter is what makes it worth rendering at all.
   *
   * Colour is never the only signal. The digits themselves are the primary
   * information and they are always legible, so this reads correctly to someone
   * who cannot separate the three hues — WCAG 1.4.1.
   */
  const urgent = remaining <= 30
  const soon = remaining <= 120
  const tone = urgent ? 'text-brand-400' : soon ? 'text-warn' : 'text-ok'
  const dot = urgent ? 'bg-brand-400' : soon ? 'bg-warn' : 'bg-ok'

  return (
    <p className={`tnum flex items-center gap-2 text-[12px] ${tone}`}>
      <span
        aria-hidden="true"
        className={[
          'h-1.5 w-1.5 rounded-full transition-colors duration-500',
          dot,
          // Only the last thirty seconds animate. A pulse that runs for the whole
          // ten minutes is wallpaper by the second minute.
          urgent ? 'animate-pulse-dot' : '',
        ].join(' ')}
      />
      {t.order.priceHeld(`${minutes}:${seconds}`)}
    </p>
  )
}

/* ----------------------------------------------------------- checkout form --- */

function CheckoutForm({
  quote, step, setStep, onRequote,
}: {
  quote: SignedQuote
  step: Step
  setStep: (step: Step) => void
  onRequest?: never
  onRequote: () => void
}) {
  const navigate = useNavigate()
  const { account } = useAuth()
  const { policy } = useCatalog()

  const t = useT()
  const [email, setEmail] = useState(account?.email ?? '')
  const [fullName, setFullName] = useState(account?.displayName ?? '')
  /*
   * Dial code and number are separate fields but one value on the wire.
   *
   * Split because a customer typing a local number should not have to remember the
   * prefix, and joined before sending because the API takes one string and the operator
   * dials one number. Defaulted to India: that is where the business is and where nearly
   * every order comes from, and a default that is right most of the time beats an empty
   * select every customer has to touch.
   */
  const [dialCode, setDialCode] = useState('+91')
  const [phone, setPhone] = useState('')
  const [discord, setDiscord] = useState('')
  /*
   * Fixed, not chosen — see the delivery-method plate below. `useState` rather than a
   * plain const so that a policy arriving after first paint (the catalogue is fetched)
   * does not silently keep the fallback: the initialiser runs once, which is the
   * behaviour the previous picker had, and the server pins the value anyway.
   */
  const [deliveryMethod] = useState(policy?.defaultDeliveryMethod ?? 'PLAYER_AUCTION')
  const isCoaching = quote.sku === 'COACHING'
  const [note, setNote] = useState('')
  const [acceptedTerms, setAcceptedTerms] = useState(false)
  const [readyChecks, setReadyChecks] = useState(false)

  const [submitting, setSubmitting] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [placed, setPlaced] = useState<CreateOrderResponse | null>(null)

  async function submit() {
    setFormError(null)
    if (!acceptedTerms) {
      setFormError(t.order.acceptTermsError)
      return
    }
    // The readiness checks are about the transfer market, which coaching never touches.
    // Requiring them there would block the order on a confirmation that means nothing.
    if (!isCoaching && !readyChecks) {
      setFormError(t.order.readyChecksError)
      return
    }

    setSubmitting(true)
    try {
      const response = await api.post<CreateOrderResponse>('/api/v1/orders', {
        quote,
        email: email.trim(),
        phone: phone.trim() ? `${dialCode} ${phone.trim()}` : null,
        deliveryMethod,
        fullName: fullName.trim() || null,
        /*
         * Dial code and number rejoined. The API takes one string and the column is 20
         * characters, which "+91 98765 43210" fits; a longer international number is
         * caught by the same @Size the operator console relies on.
         */
        discordUsername: discord.trim().replace(/^@/, '') || null,
        // Asked for after the order exists, on the credential form. See note 14.
        eaPlatformHandle: null,
        note: note.trim() || null,
        acceptedTerms: true,
      })
      setPlaced(response)
      setStep('paying')
    } catch (e) {
      if (e instanceof ApiError && e.isStale) {
        setFormError('Prices moved while you were filling this in. We have refreshed it — check the total and try again.')
        onRequote()
        setStep('configure')
      } else {
        setFormError(e instanceof ApiError ? e.message : 'We could not place that order.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (placed) {
    return <PaymentStage order={placed} sku={quote.sku} onDone={() => navigate('/track')} />
  }

  /*
   * `animate-rise` rather than a keyed transition wrapper.
   *
   * The steps of this checkout are separate components that mount and unmount on
   * their own, so a one-shot entrance animation on each root gives the whole flow
   * spatial continuity for free — each stage arrives from below, in the direction
   * the customer is travelling. Keying a shared wrapper on `step` would have
   * animated the same thing and destroyed `placed` on the way to the payment
   * stage, which is the state the payment stage is made of.
   */
  return (
    <div className="animate-rise space-y-5 border-t border-ink-400 pt-5">
      <h3 className="display text-[15px] text-chalk">{t.order.userInfoTitle}</h3>

      <Field label={t.order.email} required hint={t.order.emailHint}>
        {(props) => (
          <Input
            {...props}
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            autoComplete="email"
            required
          />
        )}
      </Field>

      {/*
        EA account name is no longer asked for here.

        It was optional, it was collected before payment, and the same identifier is
        asked for again after the order exists -- `credHandle` on the credential form,
        where it is actually needed and where it arrives encrypted. Asking twice got
        the pre-payment copy wrong more often than not, because a customer filling in
        a checkout types the name they use rather than the one on the account.

        `eaPlatformHandle` still exists on the wire and is still nullable; this posts
        null. Nothing server-side changed, so an order placed from an older cached
        bundle is still accepted.
      */}
      <Field label={t.order.fullName} hint={t.order.fullNameHint}>
        {(props) => (
          <Input
            {...props}
            value={fullName}
            onChange={(e) => setFullName(e.target.value)}
            autoComplete="name"
            maxLength={120}
          />
        )}
      </Field>

      <Field label={t.order.phone} hint={t.order.phoneHelper}>
        {(props) => (
          <div className="flex gap-2">
            <select
              value={dialCode}
              onChange={(e) => setDialCode(e.target.value)}
              aria-label={t.order.countryCode}
              className="tnum h-11 shrink-0 rounded-edge border border-ink-400 bg-paper px-2
                         text-[13px] text-chalk focus-visible:outline focus-visible:outline-2
                         focus-visible:outline-offset-1 focus-visible:outline-brand-400"
            >
              {DIAL_CODES.map((c) => (
                <option key={c.code} value={c.code}>{`${c.flag} ${c.code}`}</option>
              ))}
            </select>
            <Input
              {...props}
              type="tel"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              autoComplete="tel-national"
              inputMode="tel"
              maxLength={20}
            />
          </div>
        )}
      </Field>

      <Field label={t.order.discordLabel} hint={t.order.discordHelper}>
        {(props) => (
          <Input
            {...props}
            value={discord}
            onChange={(e) => setDiscord(e.target.value)}
            placeholder={t.order.discordPlaceholder}
            autoComplete="off"
            maxLength={64}
          />
        )}
      </Field>

      {/*
        Coaching is fulfilled by a calendar, not by a trader, so there is nothing to
        choose here — the server pins it to SCHEDULED_SESSION regardless of what is
        posted. Showing a picker that cannot change anything is worse than showing
        none, and the sign-in warning below would be actively alarming on a product
        that never asks for a password.
      */}
      {/*
        One method, shown rather than chosen.

        GFS runs a single trading method now, so the picker was offering a decision
        that no longer exists. It is rendered as a read-only plate instead of a
        disabled `<select>` on purpose: a greyed-out dropdown reads as "you may not
        have this", which invites a support message asking how to unlock it, where a
        plain statement of fact does not.

        The posted value is still `deliveryMethod`, defaulted from
        `policy.defaultDeliveryMethod`, so the server decides what the method *is* and
        this only decides what it is called. Both trading methods set
        `requiresCredentials`, so nothing about the credential step changes either way.
      */}
      {!isCoaching && (
        <div className="plate p-4">
          <p className="text-[12.5px] font-semibold text-chalk-faint">
            {t.order.deliveryMethod}
          </p>
          <p className="mt-1 text-body-sm font-semibold text-chalk">{t.order.deliveryFixed}</p>
          <p className="mt-1 text-[12.5px] leading-relaxed text-chalk-muted">
            {t.order.deliveryFixedHint}
          </p>
        </div>
      )}

      {isCoaching && (
        <Alert tone="neutral" title={t.order.coachingNextTitle}>
          {t.order.coachingNextBody}
        </Alert>
      )}

      {!isCoaching && deliveryMethod === 'COMFORT_TRADE' && (
        <Alert tone="neutral" title={t.order.signInTitle}>{t.order.signInBody}</Alert>
      )}

      <Field label={t.order.noteLabel}>
        {(props) => (
          <Input
            {...props}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder={t.order.notePlaceholder}
            maxLength={500}
          />
        )}
      </Field>

      {/*
        Not paperwork. Each of these is a failed order turned into a checkbox: an
        account still signed in kicks the trader's session, a locked transfer market
        makes the order impossible, and unassigned items block transfers outright.
        Asking now costs one click; finding out later costs a support thread and a
        delayed delivery.
      */}
      {!isCoaching && (
        <div className="plate p-4">
          <p className="mb-3 text-[12.5px] font-semibold text-chalk">{t.order.beforeYouPay}</p>
          <Checkbox checked={readyChecks} onChange={setReadyChecks}>
            {t.order.readyCheck}
          </Checkbox>
        </div>
      )}

      {/*
        All three documents, named and linked, immediately above the button that binds
        the customer to them.

        One checkbox rather than three: they are not separable -- an order is placed under
        all three or none -- and three boxes would imply a choice that does not exist. The
        AML link is `/aml-kyc`, which is the route that actually resolves; `/aml` is not
        registered and would drop the reader on the 404 page at the exact moment they were
        trying to read a policy before agreeing to it.
      */}
      <Checkbox checked={acceptedTerms} onChange={setAcceptedTerms}>
        {t.order.consentLead}{' '}
        <Link to="/terms" className="text-brand-400 hover:underline">{t.order.consentTerms}</Link>
        {', '}
        <Link to="/privacy" className="text-brand-400 hover:underline">{t.order.consentPrivacy}</Link>
        {', '}
        {t.order.and}{' '}
        <Link to="/aml-kyc" className="text-brand-400 hover:underline">{t.order.consentAml}</Link>
        {'.'}
      </Checkbox>

      {formError && <Alert tone="warn">{formError}</Alert>}

      <div className="flex flex-wrap gap-3">
        <Button variant="secondary" onClick={() => setStep('configure')} disabled={submitting}>
          {t.order.back}
        </Button>
        {/*
          Disabled until the box is ticked, as well as checked on submit. The server-side
          `@AssertTrue` on `acceptedTerms` is the real gate and stays; this is so the
          customer is not invited to press a button that will refuse them.
        */}
        <Button
          full
          size="lg"
          loading={submitting}
          disabled={!acceptedTerms}
          onClick={() => void submit()}
        >
          {t.order.pay(quote.totalFormatted)}
        </Button>
      </div>
      <p className="sr-only">{step}</p>
    </div>
  )
}

/* ------------------------------------------------------------- one quote line --- */

/**
 * One row of the price breakdown.
 *
 * <p>Lifted out of {@code QuotePanel} unchanged when the breakdown was split into
 * charges and savings — both groups render the identical row, and a copy-pasted second
 * version is how the two would drift into styling a discount differently depending on
 * which list it landed in.
 */
function QuoteLineRow({
  line, index, quote,
}: {
  line: QuoteLine
  index: number
  quote: SignedQuote
}) {
  const t = useT()
  const labels = useCatalogLabels()
  const taxIncluded = line.code === 'MARKET_TAX' && line.amountMinor === 0

  return (
    <div
      /*
        Staggered entrance, 45ms apart. Slow enough to read as the summary assembling
        itself, fast enough that the last line is settled before anyone could act on
        it. `animate-rise` is the shared keyframe, so this matches every other reveal
        on the site.
      */
      className="flex animate-rise items-start justify-between gap-4 text-[13px]"
      style={{ animationDelay: `${Math.min(index, 6) * 45}ms` }}
    >
      <dt className={taxIncluded ? 'font-semibold text-ok' : line.amountMinor < 0 ? 'text-ok' : 'text-chalk-muted'}>
        {labels.line(line, {
          sku: quote.sku,
          variant: quote.variant,
          platform: quote.platform,
          quantity: quote.quantity,
          couponCode: quote.couponCode,
          referralCode: quote.referralCode,
          pointsRedeemed: quote.pointsRedeemed,
        })}
      </dt>
      {/*
        The one line that reads as a word rather than a number.

        A market tax at zero is not a rounding artefact, it is the thing the customer
        is being told: EA's cut exists and it is not on this bill. Rendering it as
        "₹0.00" says that in the least convincing way available, so it says "Included"
        and takes the positive colour the discount lines use.
      */}
      <dd
        className={`tnum shrink-0 font-semibold ${
          taxIncluded ? 'text-ok' : line.amountMinor < 0 ? 'text-ok' : 'text-chalk'
        }`}
      >
        {taxIncluded ? t.order.taxIncludedShort : line.amountFormatted}
      </dd>
    </div>
  )
}

/**
 * The dial codes offered at checkout.
 *
 * <p>Short on purpose. This is the set the business actually sells into -- India first,
 * then the four currencies the catalogue is priced in -- rather than a full ISO list,
 * which would be two hundred options a customer scrolls past to reach the one at the top
 * anyway. Adding a market means adding a line here and a rate card, in that order.
 */
const DIAL_CODES = [
  { code: '+91', flag: '🇮🇳' },
  { code: '+1', flag: '🇺🇸' },
  { code: '+44', flag: '🇬🇧' },
  { code: '+353', flag: '🇮🇪' },
  { code: '+61', flag: '🇦🇺' },
  { code: '+49', flag: '🇩🇪' },
  { code: '+33', flag: '🇫🇷' },
  { code: '+34', flag: '🇪🇸' },
  { code: '+971', flag: '🇦🇪' },
] as const

/* ------------------------------------------------------- savings widget --- */

/**
 * Discount and Rewards, as two tabs over one job: taking money off this order.
 *
 * <p><b>There is no Cashback tab, and its absence is a decision rather than an
 * oversight.</b> The brief offered one conditionally, on confirming a real mechanic
 * behind it. There is none: "cashback" appears twice in the entire backend and both are
 * comments warning against the idea, describing the reference site's "5% cashback"
 * homepage copy drifting from its 5%-discount-tier rewards page. The terms of service say
 * points "have no cash value and cannot be withdrawn", so a tab implying money back would
 * contradict the published contract as well as the code.
 *
 * <p>Two tabs rather than two stacked panels because they are alternatives, not additions
 * -- the engine applies a coupon or a tier discount, and a customer deciding between them
 * should see one at a time. The points balance and the redemption ceiling are the
 * server's; nothing here computes either.
 */
function SavingsTabs({
  couponKey, couponCode, appliedCoupon, couponMessage, busy, onApplyCoupon,
  pointsToRedeem, setPointsToRedeem, maxRedeemable,
}: {
  couponKey: string
  couponCode: string
  appliedCoupon: string | null
  couponMessage: string | null
  busy: boolean
  onApplyCoupon: (code: string) => void
  pointsToRedeem: number
  setPointsToRedeem: (points: number) => void
  maxRedeemable: number
}) {
  const t = useT()
  const { account } = useAuth()
  const { policy } = useCatalog()
  const [tab, setTab] = useState<'discount' | 'rewards'>('discount')

  const tabs = [
    { id: 'discount' as const, label: t.order.tabDiscount },
    { id: 'rewards' as const, label: t.order.tabRewards },
  ]

  return (
    <div className="hairline rounded-panel bg-paper p-4">
      <div role="tablist" aria-label={t.order.summaryTitle} className="mb-4 flex gap-1">
        {tabs.map((entry) => (
          <button
            key={entry.id}
            type="button"
            role="tab"
            aria-selected={tab === entry.id}
            onClick={() => setTab(entry.id)}
            className={[
              'h-9 flex-1 rounded-edge text-[12.5px] font-semibold transition-colors duration-200',
              tab === entry.id
                ? 'bg-brand-500 text-paper'
                : 'bg-ink-700 text-chalk-muted hover:text-chalk',
            ].join(' ')}
          >
            {entry.label}
          </button>
        ))}
      </div>

      {tab === 'discount' ? (
        <CouponRow
          key={couponKey}
          initial={couponCode}
          applied={appliedCoupon}
          message={couponMessage}
          busy={busy}
          onApply={onApplyCoupon}
        />
      ) : (
        <RewardsPane
          pointsToRedeem={pointsToRedeem}
          setPointsToRedeem={setPointsToRedeem}
          maxRedeemable={maxRedeemable}
          balance={account?.pointsBalance ?? null}
          capPercent={policy ? bpsToPercent(policy.maxWalletRedemptionBps) : null}
        />
      )}
    </div>
  )
}

/**
 * Spending points on this order.
 *
 * <p>Every number here is the server's. The balance comes from the account, the ceiling
 * from {@code maxWalletRedemptionBps} through the same clamp the pricing engine applies,
 * and the resulting discount from the quote. Nothing is recomputed in the browser -- a
 * rewards panel that does its own arithmetic is how a checkout total ends up disagreeing
 * with the rewards page.
 *
 * <p>A guest sees why the panel is empty rather than an input that cannot work: the terms
 * of service say guest orders cannot earn or store points, so offering the control and
 * failing on submit would be the wrong shape of honest.
 */
function RewardsPane({
  pointsToRedeem, setPointsToRedeem, maxRedeemable, balance, capPercent,
}: {
  pointsToRedeem: number
  setPointsToRedeem: (points: number) => void
  maxRedeemable: number
  balance: number | null
  capPercent: string | null
}) {
  const t = useT()
  const loyaltyActive = useLoyaltyActive()

  if (balance == null) {
    return <p className="text-[12.5px] leading-relaxed text-chalk-muted">{t.order.rewardsNoAccount}</p>
  }
  /*
    Points do not settle in every currency. The engine returns zero redemption outside the
    loyalty currency, so an input here would let someone type 400 and watch the total not
    move -- which is why this notice followed the field rather than being dropped with the
    step that used to hold it.
  */
  if (!loyaltyActive) {
    return <LoyaltyCurrencyNotice />
  }
  if (balance <= 0) {
    return <p className="text-[12.5px] leading-relaxed text-chalk-muted">{t.order.rewardsNoneYet}</p>
  }

  const ceiling = Math.min(balance, maxRedeemable)

  return (
    <div>
      <div className="flex gap-2">
        <input
          type="number"
          min={0}
          max={ceiling}
          value={pointsToRedeem}
          onChange={(e) => setPointsToRedeem(Math.max(0, Math.min(ceiling, Number(e.target.value) || 0)))}
          aria-label={t.order.pointsLabel}
          className="tnum h-11 min-w-0 flex-1 rounded-edge border border-ink-400 bg-paper px-3
                     text-[13px] text-chalk focus-visible:outline focus-visible:outline-2
                     focus-visible:outline-offset-1 focus-visible:outline-brand-400"
        />
        <Button
          variant="secondary"
          onClick={() => setPointsToRedeem(pointsToRedeem === ceiling ? 0 : ceiling)}
          disabled={ceiling <= 0}
        >
          {pointsToRedeem === ceiling ? t.order.rewardsClear : t.order.rewardsUseAll}
        </Button>
      </div>
      <p className="tnum mt-1.5 text-[12px] leading-snug text-chalk-faint">
        {t.order.pointsHintUsable(balance, ceiling)}
        {capPercent && ` · ${t.order.rewardsCapNote(capPercent)}`}
      </p>
    </div>
  )
}

/* ----------------------------------------------------------------- coupon --- */

/**
 * The discount code field, in the order panel.
 *
 * <p>Holds its own draft so typing does not re-quote. The parent's `couponCode` is what
 * the pricing engine is asked about, and it only changes when Apply is pressed -- the
 * quote is re-signed server-side on every change, so a live-applied field turns one code
 * into six requests and five "no such coupon" messages before the sixth succeeds.
 *
 * <p>Remounted by the parent whenever the applied code changes (`key`), so the draft
 * cannot drift out of step with what is actually priced.
 *
 * <p>The message under the field is the server's, never a guess: "already used",
 * "expired", "needs a larger order". A coupon that silently does nothing produces a
 * support ticket every single time.
 */
function CouponRow({
  initial, applied, message, busy, onApply,
}: {
  initial: string
  applied: string | null
  message: string | null
  busy: boolean
  onApply: (code: string) => void
}) {
  const t = useT()
  const [draft, setDraft] = useState(initial)

  const trimmed = draft.trim()
  const isApplied = applied != null && trimmed.toUpperCase() === applied.toUpperCase()
  const canApply = trimmed !== '' && !isApplied && !busy

  return (
    <div>
      <label htmlFor="coupon-code" className="stamp mb-2 block">
        {t.order.couponLabel}
      </label>
      <div className="flex gap-2">
        <input
          id="coupon-code"
          value={draft}
          onChange={(e) => setDraft(e.target.value.toUpperCase())}
          onKeyDown={(e) => { if (e.key === 'Enter' && canApply) { e.preventDefault(); onApply(trimmed) } }}
          placeholder="SAVE10"
          maxLength={32}
          autoComplete="off"
          spellCheck={false}
          className="h-11 min-w-0 flex-1 rounded-edge border border-ink-400 bg-paper px-3
                     text-[13px] uppercase tracking-[0.06em] text-chalk
                     placeholder:normal-case placeholder:tracking-normal placeholder:text-chalk-faint
                     focus-visible:outline focus-visible:outline-2
                     focus-visible:outline-offset-1 focus-visible:outline-brand-400"
        />
        <Button
          variant="secondary"
          onClick={() => canApply && onApply(trimmed)}
          disabled={!canApply}
        >
          {t.order.couponApply}
        </Button>
      </div>

      {/*
        One line, and only one. The server's reason wins over the generic hint, and the
        applied confirmation wins over both -- three stacked messages about a five
        character field is more explanation than the field is worth.
      */}
      <p className={`mt-1.5 text-[12px] leading-snug ${
        message ? 'text-brand-400' : isApplied ? 'text-ok' : 'text-chalk-faint'
      }`}>
        {message ?? (applied ? t.order.couponApplied(applied) : t.order.couponHint)}
      </p>
    </div>
  )
}

/* ------------------------------------------------------------------- cart --- */

/**
 * What is being bought, restated once configuring is done.
 *
 * <p>Not a shopping cart in the usual sense -- an order here is exactly one line, and
 * always will be, because the three services are configured separately and priced by
 * different engines. So this is a receipt of the choice rather than a list to manage:
 * platform, amount, price, and a way back.
 *
 * <p>The amount is read off the quote rather than the slider. Those agree while the page
 * is idle and disagree for the moment between a drag and the re-quote landing, and the
 * number that matters here is the one the server priced.
 */
function CartCard({ quote, onEdit }: { quote: SignedQuote; onEdit: () => void }) {
  const t = useT()
  const labels = useCatalogLabels()

  return (
    <div className="plate flex items-start gap-4 p-5">
      {quote.platform ? (
        <PlatformIcon platform={quote.platform} className="mt-0.5 h-9 w-9 shrink-0 text-chalk-muted" />
      ) : (
        <CoinIcon size={36} className="mt-0.5" />
      )}

      <div className="min-w-0 flex-1">
        <p className="stamp mb-1">{t.order.cartTitle}</p>
        {/*
          Season first, because a cart line has to say which game it is for. "Safe
          Trading Service" is true of FC 25 and FC 26 alike, and a receipt that does not
          name the season is one a customer cannot check against what they bought.
        */}
        <p className="text-body-sm font-semibold text-chalk">
          {`${SEASON} · ${labels.service(quote.sku, null)}`}
        </p>
        <p className="tnum mt-0.5 text-[13px] text-chalk-muted">
          {quote.quantity ? t.catalog.millions(quote.quantity) : labels.variant(quote.variant, null)}
          {quote.platform && ` · ${quote.platform}`}
        </p>
      </div>

      <div className="flex shrink-0 items-center gap-3">
        <span className="tnum display text-display-sm text-chalk">{quote.totalFormatted}</span>
        {/*
          "Remove" on a one-line order would empty a cart that cannot be empty -- the page
          has nothing to show without a selection. It goes back to the configurator
          instead, which is the same gesture with an outcome that exists.
        */}
        <button
          type="button"
          onClick={onEdit}
          aria-label={t.order.cartRemove}
          className="grid h-9 w-9 place-items-center rounded-edge text-chalk-faint
                     transition-colors hover:bg-ink-700 hover:text-brand-400
                     focus-visible:outline focus-visible:outline-2
                     focus-visible:outline-offset-2 focus-visible:outline-brand-400"
        >
          <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor"
               strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M3 6h18" />
            <path d="M8 6V4h8v2" />
            <path d="M19 6l-1 14H6L5 6" />
            <path d="M10 11v6M14 11v6" />
          </svg>
        </button>
      </div>
    </div>
  )
}

/* ------------------------------------------------------ manual amount input --- */

/**
 * A typed coin amount, in thousands, kept in step with the slider.
 *
 * <p>Three separate problems, which is why it is a component rather than an input:
 *
 * <ul>
 *   <li><b>Units.</b> The rate card prices per million and the slider works in
 *       millions; customers think in thousands. The conversion lives here so the
 *       rest of the page keeps working in the unit the pricing engine quotes in.</li>
 *   <li><b>Snapping.</b> A typed value has to land on a real step, or the server is
 *       asked to price a quantity the offer does not contain. It snaps on blur, not
 *       on keystroke, because snapping while someone is still typing rewrites the
 *       digits under their cursor.</li>
 *   <li><b>Float error.</b> 0.01 steps in binary floating point do not land on
 *       round numbers — 0.1 + 0.2 arithmetic applies to coin amounts too. Every
 *       calculation is done in integer thousands and converted once at the edge, so
 *       a snapped value is exact rather than 1.2299999999999998.</li>
 * </ul>
 */
function ManualAmount({
  quantity, min, max, stepSize, onCommit,
}: {
  quantity: number
  min: number
  max: number
  stepSize: number
  onCommit: (millions: number) => void
}) {
  const t = useT()

  // Everything below is integer thousands. `Math.round` rather than a cast: these
  // come from NUMERIC(10,2) that has been through JSON, so 0.5 can arrive as
  // 0.49999999999999994 and truncation would quietly lose a step.
  const stepK = Math.max(1, Math.round(stepSize * 1000))
  const minK = Math.round(min * 1000)
  const maxK = Math.round(max * 1000)
  const currentK = Math.round(quantity * 1000)

  const [draft, setDraft] = useState(String(currentK))
  const [snapped, setSnapped] = useState<string | null>(null)

  // The slider is the other writer. When it moves, the field follows — but only
  // while it is not focused, or typing would be overwritten mid-word.
  const [focused, setFocused] = useState(false)
  useEffect(() => {
    if (!focused) setDraft(String(currentK))
  }, [currentK, focused])

  function commit() {
    setFocused(false)
    const typed = Number(draft)
    if (!Number.isFinite(typed)) {
      setDraft(String(currentK))
      setSnapped(null)
      return
    }
    const clamped = Math.min(maxK, Math.max(minK, typed))
    const steps = Math.round((clamped - minK) / stepK)
    const exactK = Math.min(maxK, minK + steps * stepK)

    setDraft(String(exactK))
    setSnapped(exactK !== typed ? `${formatK(exactK)}K` : null)
    onCommit(exactK / 1000)
  }

  return (
    <div className="mt-5">
      <Field
        label={t.order.amountManualLabel}
        hint={snapped ? t.order.amountSnapped(snapped) : t.order.amountManualHint(stepK, `${formatK(minK)}K`, `${formatK(maxK)}K`)}
      >
        {(props) => (
          <div className="relative">
            <Input
              {...props}
              type="number"
              inputMode="numeric"
              min={minK}
              max={maxK}
              step={stepK}
              value={draft}
              onFocus={() => { setFocused(true); setSnapped(null) }}
              onChange={(e) => setDraft(e.target.value)}
              onBlur={commit}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); commit() } }}
              className="tnum pr-9"
            />
            <span
              aria-hidden="true"
              className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2
                         text-[13px] font-semibold text-chalk-faint"
            >
              {t.order.amountManualUnit}
            </span>
          </div>
        )}
      </Field>
    </div>
  )
}

/** Thousands with separators, so 1250 reads as 1,250 rather than 1250. */
function formatK(value: number): string {
  return value.toLocaleString('en-US')
}

/* --------------------------------------------------------------- requirements --- */

/**
 * What has to be true before an order can be worked, stated before it is configured.
 *
 * <p>The same four facts were already on the page as a single checkbox immediately
 * above the pay button. That is the wrong end of the flow: by then the customer has
 * chosen a platform, an amount and a payment method, and discovering their transfer
 * market is locked makes all of it wasted. The checkbox stays — it is a confirmation,
 * and confirmations belong next to the commitment — but the information now arrives
 * before the first choice rather than after the last one.
 */
function RequirementsPanel() {
  const t = useT()
  const items = [
    { title: t.order.reqCompanion, note: t.order.reqCompanionNote },
    { title: t.order.reqMarket, note: t.order.reqMarketNote },
    { title: t.order.reqMinCoins, note: t.order.reqMinCoinsNote },
    { title: t.order.reqUnassigned, note: t.order.reqUnassignedNote },
  ]

  return (
    <div className="hairline rounded-panel border-brand-500/30 bg-brand-500/[0.04] p-5">
      <p className="stamp mb-1">{t.order.requirementsTitle}</p>
      <p className="measure mb-4 text-[13px] leading-relaxed text-chalk-muted">
        {t.order.requirementsLead}
      </p>
      <ul className="grid gap-3 sm:grid-cols-2">
        {items.map((item) => (
          <li key={item.title} className="flex gap-2.5">
            {/*
              A tick, not a checkbox. Nothing here is togglable — these are conditions
              on the account, and a control that looks interactive but is not is worse
              than a plain mark.
            */}
            <svg
              aria-hidden="true"
              viewBox="0 0 20 20"
              className="mt-[3px] h-4 w-4 shrink-0 text-brand-500"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.1"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="m4 10.5 4 4 8-9" />
            </svg>
            <div>
              <p className="text-[13px] font-semibold text-chalk">{item.title}</p>
              <p className="text-[12px] leading-snug text-chalk-faint">{item.note}</p>
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}

/* ------------------------------------------------------------ payment step --- */

function PaymentStage({
  order, sku, onDone,
}: {
  order: CreateOrderResponse
  /*
   * Taken from the quote, because `CreateOrderResponse` does not carry it. Adding it
   * to the response would have been the tidier fix, but it is a wire-format change on
   * launch day for something the caller already knows.
   */
  sku: string
  onDone: () => void
}) {
  const t = useT()
  const [error, setError] = useState<string | null>(null)
  const [opening, setOpening] = useState(false)
  const [settled, setSettled] = useState(false)
  const stub = isStubGateway(order.payment)
  const isCoaching = sku === 'COACHING'

  async function pay() {
    setOpening(true)
    setError(null)
    try {
      await openCheckout(order.payment, order.publicRef, {
        onDismiss: () => setOpening(false),
        onSuccess: () => {
          setOpening(false)
          setSettled(true)
        },
      })
    } catch {
      setError(t.order.payWindowFailed)
      setOpening(false)
    }
  }

  return (
    <div className="animate-rise space-y-5 border-t border-ink-400 pt-5">
      <div className="flex items-center gap-3">
        <Badge tone="ok">{t.order.orderCreated}</Badge>
        <span className="tnum text-[13px] font-semibold text-chalk">{order.publicRef}</span>
      </div>

      <p className="text-[13px] leading-relaxed text-chalk-muted">
        {t.order.keepReference}
      </p>

      {stub ? (
        // Local and CI environments run without gateway credentials so the whole
        // flow can be walked end to end. Saying so plainly beats a payment window
        // that silently does nothing.
        <Alert tone="neutral" title={t.order.stubTitle}>{t.order.stubBody}</Alert>
      ) : (
        <Button full size="lg" loading={opening} onClick={() => void pay()}>
          {t.order.pay(order.totalFormatted)}
        </Button>
      )}

      {error && <Alert tone="warn">{error}</Alert>}

      {/*
        What a coaching buyer does next.

        Without this the confirmation is a dead end for them: session credits are granted
        on the PAID transition, the booking calendar is hidden until they exist, and
        nothing on this screen said so. Somebody who has just paid for a lesson and been
        shown only an order reference reasonably concludes the booking is broken.
      */}
      {isCoaching && (
        <div className="rounded-panel border border-ink-400 bg-paper p-5">
          <h3 className="display text-[15px] text-chalk">{t.order.coachingNextTitle}</h3>
          <p className="mt-2 text-[13px] leading-relaxed text-chalk-muted">
            {stub ? t.order.coachingNextStub : t.order.coachingNextBody}
          </p>
          {!stub && (
            <ButtonLink to="/coaching" size="md" className="mt-4">
              {settled ? t.order.coachingNextCtaNow : t.order.coachingNextCta}
            </ButtonLink>
          )}
        </div>
      )}

      <Button variant="secondary" full onClick={onDone}>
        {t.order.trackOrder}
      </Button>
    </div>
  )
}

/* ---------------------------------------------------------------- skeleton --- */

function ConfiguratorSkeleton() {
  return (
    <Section className="rhythm-section">
      <div className="grid gap-6 lg:grid-cols-[1.25fr_1fr]">
        <div className="space-y-6">
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-56 w-full" />
          <Skeleton className="h-44 w-full" />
        </div>
        <Skeleton className="h-80 w-full" />
      </div>
    </Section>
  )
}
