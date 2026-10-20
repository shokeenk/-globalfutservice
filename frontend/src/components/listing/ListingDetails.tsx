import { useEffect, useState, type ReactNode } from 'react'
import { LuCircleCheck, LuClock, LuFileText, LuGamepad2, LuListChecks, LuTrophy } from 'react-icons/lu'
import { useT } from '../../i18n'
import type { CatalogListing, ListingSku } from '../../lib/types'
import { FieldError } from '../ui'
import { listingImageSrc } from '../../lib/listings'

/**
 * The left side of an Objectives or Tournaments checkout: the listing as the GFS team wrote
 * it in Service Listings, and nothing about any particular objective in code.
 *
 * <p>Every block is the listing's own and disappears when the team left it empty: no
 * required games, no time block; no instructions, no instructions box. The overview is
 * theirs when they wrote one, and otherwise built from the listing: what we do, and the
 * reward when one is named.
 *
 * <p>The after-payment note (EA login in the encrypted form, the order's live chat) and the Continue button are the boosting
 * checkout's own, passed in, so they read exactly as they do on Champs and Rivals.
 */
export function ListingDetails({
  sku, listing, hours, onHours, acknowledged, onAcknowledged, touched, guidance, cta,
}: {
  sku: ListingSku
  listing: CatalogListing
  /** Per hour only: the hours chosen, or null until the customer chooses. */
  hours: number | null
  onHours: (next: number | null) => void
  acknowledged: boolean
  onAcknowledged: (next: boolean) => void
  /** Continue has been pressed: missing answers are now errors. */
  touched: boolean
  /** What happens after payment: the boosting checkout's own note. */
  guidance: ReactNode
  cta: ReactNode
}) {
  const t = useT()
  const c = t.listingCheckout
  const hourly = listing.pricingType === 'PER_HOUR'
  const tournaments = sku === 'BOOST_TOURNAMENTS'
  const overview = listing.overview
    ?? [tournaments ? c.generatedTournaments : c.generatedObjectives,
      listing.reward ? c.rewardAvailable(listing.reward) : null].filter(Boolean).join(' ')
  const time = [listing.estimatedTime, listing.schedulingNote].filter(Boolean) as string[]
  const showGames = listing.requiredGames != null
  const showTime = time.length > 0

  return (
    <div className="hairline rounded-panel bg-paper p-5 sm:p-6" data-testid="listing-details">
      <h1 className="display text-display-sm text-chalk">{listing.title}</h1>
      {listing.subtitle && <p className="mt-1 text-body-sm text-chalk-muted">{listing.subtitle}</p>}

      <div className="mt-5 grid grid-cols-1 gap-4 sm:grid-cols-[minmax(0,200px)_minmax(0,1fr)]">
        {listing.imageUrl && (
          <div className="grid place-items-center rounded-panel bg-ink-700/60 p-3 sm:self-start">
            {/* Scaled to fit whole, at its own proportions: never stretched, never cropped. */}
            <img
              src={listingImageSrc(listing.imageUrl) ?? undefined}
              width={listing.imageWidth ?? undefined}
              height={listing.imageHeight ?? undefined}
              alt={listing.title}
              className="h-auto max-h-[260px] w-auto max-w-full object-contain drop-shadow-md"
              data-testid="checkout-artwork"
            />
          </div>
        )}

        <div className={`min-w-0 space-y-3 ${listing.imageUrl ? '' : 'sm:col-span-2'}`}>
          <section className="flex gap-3 rounded-panel border border-brand-500/20 bg-brand-500/[0.05] p-4"
                   aria-labelledby="listing-overview">
            <LuTrophy aria-hidden="true" size={22} className="mt-0.5 shrink-0 text-brand-500" />
            <div>
              <h2 id="listing-overview" className="text-[14px] font-semibold text-chalk">
                {tournaments ? c.detailsTournaments : c.detailsObjectives}
              </h2>
              <p className="mt-1 text-[13px] leading-relaxed text-chalk-muted" data-testid="listing-overview">
                {overview}
              </p>
            </div>
          </section>

          {listing.requirements.length > 0 && (
            <section className="rounded-panel border border-ink-400 p-4" aria-labelledby="listing-tasks">
              <h2 id="listing-tasks" className="flex items-center gap-2.5 text-[14px] font-semibold text-chalk">
                <LuListChecks aria-hidden="true" size={20} className="shrink-0 text-chalk" />
                {c.tasksIncluded}
              </h2>
              <ul className="mt-3 space-y-2.5" data-testid="listing-tasks">
                {listing.requirements.map((task, index) => (
                  <li key={index} className="flex gap-2.5">
                    <LuCircleCheck aria-hidden="true" size={18} className="mt-px shrink-0 text-brand-500" />
                    <span className="whitespace-pre-line text-[13px] leading-relaxed text-chalk-muted">{task}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {(showGames || showTime) && (
            <section className={`grid gap-px overflow-hidden rounded-panel border border-ink-400 bg-ink-400 ${
              showGames && showTime ? 'sm:grid-cols-2' : ''}`}>
              {showGames && (
                <div className="flex gap-3 bg-paper p-4" data-testid="listing-games">
                  <LuGamepad2 aria-hidden="true" size={20} className="mt-0.5 shrink-0 text-chalk" />
                  <div>
                    <p className="text-[12.5px] font-semibold text-chalk">{c.requiredGames}</p>
                    <p className="tnum mt-0.5 text-[13px] text-chalk-muted">{listing.requiredGames}</p>
                  </div>
                </div>
              )}
              {showTime && (
                <div className="flex gap-3 bg-paper p-4" data-testid="listing-time">
                  <LuClock aria-hidden="true" size={20} className="mt-0.5 shrink-0 text-chalk" />
                  <div>
                    <p className="text-[12.5px] font-semibold text-chalk">{c.estimatedTime}</p>
                    {time.map((line) => (
                      <p key={line} className="mt-0.5 text-[13px] leading-snug text-chalk-muted">{line}</p>
                    ))}
                    <p className="mt-1 text-[11.5px] text-chalk-faint">{c.estimateCaveat}</p>
                  </div>
                </div>
              )}
            </section>
          )}
        </div>
      </div>

      {hourly && (
        <HoursPicker
          min={listing.minHours ?? 1}
          max={listing.maxHours ?? 50}
          hours={hours}
          onHours={onHours}
          error={touched && hours == null ? c.needHours : null}
        />
      )}

      {guidance}

      {listing.customerInstructions && (
        <section className="mt-6 flex gap-3 rounded-panel border border-brand-500/20 bg-brand-500/[0.05] p-4"
                 aria-labelledby="listing-instructions" data-testid="listing-instructions">
          <LuFileText aria-hidden="true" size={22} className="mt-0.5 shrink-0 text-brand-500" />
          <div>
            <h2 id="listing-instructions" className="text-[14px] font-semibold text-chalk">{c.customerInstructions}</h2>
            <p className="mt-1 whitespace-pre-line text-[13px] leading-relaxed text-chalk-muted">
              {listing.customerInstructions}
            </p>
          </div>
        </section>
      )}

      {hourly && (
        <div className="mt-6" data-testid="hourly-acknowledgement">
          <p className="rounded-edge border border-brand-500/40 bg-brand-500/[0.07] px-4 py-3 text-[13.5px]
                        font-semibold leading-relaxed text-chalk">
            {c.hourlyNotice}
          </p>
          <label className="mt-3 flex cursor-pointer items-start gap-3 text-[13px] leading-relaxed text-chalk">
            <input
              type="checkbox"
              checked={acknowledged}
              onChange={(e) => onAcknowledged(e.target.checked)}
              aria-describedby={touched && !acknowledged ? 'hourly-ack-error' : undefined}
              className="mt-0.5 h-4 w-4 shrink-0 accent-[#DB1825]"
            />
            <span>{c.hourlyAck}</span>
          </label>
          {touched && !acknowledged && (
            <FieldError id="hourly-ack-error" className="mt-2">{c.needAck}</FieldError>
          )}
        </div>
      )}

      {cta}
    </div>
  )
}

/**
 * Whole hours, from the listing's least to its cap, and nothing chosen until the customer
 * chooses: the price is per hour, and a duration assumed for them is a price they did not
 * agree to. The server checks the same range and prices the hours itself.
 */
function HoursPicker({
  min, max, hours, onHours, error,
}: {
  min: number
  max: number
  hours: number | null
  onHours: (next: number | null) => void
  error: string | null
}) {
  const t = useT()
  const c = t.listingCheckout
  // What is typed, kept as typed: "60" with a cap of 50 stays on screen, marked, rather than vanishing.
  const [text, setText] = useState(hours == null ? '' : String(hours))
  useEffect(() => {
    if (hours != null) setText(String(hours))
  }, [hours])
  const invalid = text.trim() !== '' && hours == null
  const step = (by: number) => {
    const from = hours ?? (by > 0 ? min - 1 : min + 1)
    onHours(Math.min(max, Math.max(min, from + by)))
  }
  const shown = invalid ? c.needHours : error
  const button = 'grid h-11 w-11 shrink-0 place-items-center rounded-edge border border-ink-400 text-[18px] '
    + 'font-semibold text-chalk transition-colors hover:border-ink-300 disabled:opacity-40'
  return (
    <section className="mt-6 border-t border-ink-400 pt-6" aria-labelledby="hours-title" data-testid="hours-picker">
      <h2 id="hours-title" className="display text-[16px] text-chalk">{c.hoursTitle}</h2>
      <p className="mt-1 text-body-sm text-chalk-muted">{c.hoursLead(min, max)}</p>
      <div className="mt-4 flex items-center gap-2">
        <button type="button" className={button} onClick={() => step(-1)} disabled={hours != null && hours <= min}
                aria-label={c.fewerHours}>−</button>
        <input
          type="number"
          inputMode="numeric"
          min={min}
          max={max}
          step={1}
          value={text}
          placeholder={c.hoursChoose}
          aria-label={c.hoursLabel}
          aria-invalid={shown ? true : undefined}
          aria-describedby={shown ? 'hours-error' : undefined}
          onChange={(e) => {
            setText(e.target.value)
            const raw = e.target.value.trim()
            const n = Number(raw)
            onHours(raw !== '' && Number.isInteger(n) && n >= min && n <= max ? n : null)
          }}
          className="tnum h-11 w-36 rounded-edge border border-ink-400 bg-paper px-3 text-center text-[15px]
                     font-semibold text-chalk focus:border-brand-500 focus:outline-none"
        />
        <button type="button" className={button} onClick={() => step(1)} disabled={hours != null && hours >= max}
                aria-label={c.moreHours}>+</button>
      </div>
      {shown && <FieldError id="hours-error" className="mt-2">{shown}</FieldError>}
    </section>
  )
}
