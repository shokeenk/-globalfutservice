import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { LuArrowLeft, LuArrowRight, LuMailCheck, LuSend, LuTriangleAlert } from 'react-icons/lu'
import type { Campaign, CampaignOptions } from '../../../lib/types'
import { BannerDrop, Card, TextField, controlClass } from './fields'
import {
  CONTENT_LIMITS, quotaCheck, type ContentErrors, type StepOneFields, type StepTwoFields,
} from './wizard'

// ---- the frame every later step shares --------------------------------------------------

const primaryButton =
  'inline-flex h-[40px] items-center justify-center gap-2 rounded-admin-control bg-admin-red px-5 ' +
  'text-[13.5px] font-semibold text-white transition-colors hover:bg-[#C21520] focus-visible:outline-none ' +
  'focus-visible:ring-2 focus-visible:ring-admin-red focus-visible:ring-offset-2 disabled:cursor-not-allowed ' +
  'disabled:opacity-50'
const secondaryButton =
  'inline-flex h-[40px] items-center justify-center gap-2 rounded-admin-control border border-admin-line ' +
  'bg-white px-4 text-[13px] font-medium text-admin-ink transition-colors hover:bg-admin-page ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red focus-visible:ring-offset-2 ' +
  'disabled:opacity-50'

/**
 * A step's card: title, one line on what the step is for, the step's content, and Back /
 * Next at the foot. It is a form, so Enter in any field moves on the same way the Next
 * button does.
 */
function StepPanel({
  title, description, children, onBack, onNext, nextLabel = 'Next Step', saving = false,
  hideNext = false,
}: {
  title: string
  description: string
  children: ReactNode
  onBack: () => void
  onNext?: () => void
  nextLabel?: string
  saving?: boolean
  hideNext?: boolean
}) {
  return (
    <form
      noValidate
      onSubmit={(e) => { e.preventDefault(); onNext?.() }}
      className="rounded-admin-card border border-admin-line bg-white px-4 pb-4 pt-3.5 shadow-admin-card sm:px-5"
    >
      <h2 className="text-[19px] font-bold text-admin-ink">{title}</h2>
      <p className="mt-0.5 text-[13px] text-admin-muted">{description}</p>
      <hr className="my-3 border-admin-line" />
      <div className="space-y-3.5">{children}</div>
      <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-admin-line pt-4">
        <button type="button" onClick={onBack} disabled={saving} className={secondaryButton}>
          <LuArrowLeft aria-hidden="true" className="h-4 w-4" /> Back
        </button>
        {!hideNext && (
          <button type="submit" disabled={saving} className={primaryButton}>
            {saving ? 'Saving…' : nextLabel}
            {!saving && <LuArrowRight aria-hidden="true" className="h-4 w-4" />}
          </button>
        )}
      </div>
    </form>
  )
}

// ---- step 2: Email Content ----------------------------------------------------------------

export function StepContent({
  fields, errors, onChange, promoTitle, onEditHeadline, onBack, onNext, saving,
}: {
  fields: StepTwoFields
  errors: ContentErrors
  onChange: (name: keyof StepTwoFields, value: string) => void
  promoTitle: string
  onEditHeadline: () => void
  onBack: () => void
  onNext: () => void
  saving: boolean
}) {
  return (
    <StepPanel
      title="Email Content"
      description="The lines around your headline. Both are optional; leave either empty to leave it out."
      onBack={onBack} onNext={onNext} saving={saving}
    >
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-admin-control bg-admin-page
                      px-3 py-2.5 text-[12.5px] text-admin-muted">
        <span>Headline: <strong className="text-admin-ink">{promoTitle || '—'}</strong></span>
        <button type="button" onClick={onEditHeadline}
                className="font-medium text-admin-red-text underline-offset-2 hover:underline
                           focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red">
          Edit in Campaign Details
        </button>
      </div>
      <TextField
        name="kicker" label="Line above the headline" value={fields.kicker} error={errors.kicker}
        onChange={(v) => onChange('kicker', v)} placeholder="e.g. Team of the Year"
        helper="Set small and red above the headline, e.g. TEAM OF THE YEAR." counter={CONTENT_LIMITS.kicker}
      />
      <TextField
        name="subline" label="Line below the headline" value={fields.subline} error={errors.subline}
        onChange={(v) => onChange('subline', v)} placeholder="e.g. Build your dream squad"
        helper="Set in capitals beneath the headline, e.g. BUILD YOUR DREAM SQUAD." counter={CONTENT_LIMITS.subline}
      />
    </StepPanel>
  )
}

// ---- step 3: Design & Preview ---------------------------------------------------------------

export function StepDesign({
  campaign, adminEmail, banner, onBanner, onSendTest, onBack, onNext,
}: {
  campaign: Campaign
  adminEmail: string | undefined
  banner: { busy: boolean; error: string | null }
  onBanner: (file: File) => void
  onSendTest: () => Promise<string | null>
  onBack: () => void
  onNext: () => void
}) {
  const [test, setTest] = useState<{ busy: boolean; sentTo: string | null; error: string | null }>(
    { busy: false, sentTo: null, error: null })

  const sendTest = async () => {
    setTest({ busy: true, sentTo: null, error: null })
    try {
      const sentTo = await onSendTest()
      setTest({ busy: false, sentTo, error: null })
    } catch (e) {
      setTest({ busy: false, sentTo: null, error: e instanceof Error ? e.message : 'The test could not be sent.' })
    }
  }

  return (
    <StepPanel
      title="Design & Preview"
      description="Check the email as customers will get it, then send yourself a copy."
      onBack={onBack} onNext={onNext}
    >
      <Card title="Banner image">
        <BannerDrop busy={banner.busy} hasBanner={campaign.hasBanner} onFile={onBanner} />
        <p className="mt-2 text-[11px] leading-snug text-admin-muted">
          Shown beside the headline. PNG, JPEG, GIF or WebP, up to 2MB.
        </p>
        {banner.error && <p role="alert" className="mt-1.5 text-[11.5px] text-admin-red-text">{banner.error}</p>}
      </Card>

      <Card title="Send a test">
        <p className="text-[12.5px] leading-relaxed text-admin-muted">
          One copy goes to {adminEmail ? <strong className="text-admin-ink">{adminEmail}</strong> : 'your address'}.
          It is not recorded and carries no tracking, so it will not count in the campaign's numbers
          or unsubscribe you.
        </p>
        <button type="button" onClick={() => void sendTest()} disabled={test.busy}
                className={`${secondaryButton} mt-3`}>
          <LuMailCheck aria-hidden="true" className="h-4 w-4" />
          {test.busy ? 'Sending…' : 'Send test to me'}
        </button>
        <p role="status" aria-live="polite" className="mt-2 text-[12px]">
          {test.sentTo && <span className="text-[#12692F]">Sent to {test.sentTo}. Check the inbox, and the spam folder.</span>}
          {test.error && <span className="text-admin-red-text">{test.error}</span>}
        </p>
      </Card>

      <p className="text-[11.5px] leading-relaxed text-admin-muted">
        Mail apps differ. Outlook squares the rounded corners, and many apps hide images until the reader
        allows them — the email stays readable either way, but look at the test copy in the app your
        customers use most.
      </p>
    </StepPanel>
  )
}

// ---- step 4: Audience ---------------------------------------------------------------------

const AUDIENCE_NOTES: Record<string, string> = {
  ALL_OPTED_IN: 'Everyone who has opted in to promotional email.',
  COINS_BUYERS: 'Opted in, and has bought coins before.',
  BOOSTING_BUYERS: 'Opted in, and has bought boosting before.',
  COACHING_BUYERS: 'Opted in, and has bought coaching before.',
}

/**
 * The four audiences, each with how many people it reaches right now.
 *
 * <p>Only the fixed segments, all of them consent-only. There is no way to type an
 * address or paste a list here, and the server has no endpoint that would accept one.
 */
export function StepAudience({
  options, value, onChange, onBack, onNext, saving,
}: {
  options: CampaignOptions | null
  value: string
  onChange: (value: string) => void
  onBack: () => void
  onNext: () => void
  saving: boolean
}) {
  const labelId = useId()
  const refs = useRef<(HTMLButtonElement | null)[]>([])
  const audiences = options?.audiences ?? []

  const move = (event: KeyboardEvent, index: number) => {
    const step = event.key === 'ArrowDown' || event.key === 'ArrowRight' ? 1
      : event.key === 'ArrowUp' || event.key === 'ArrowLeft' ? -1 : 0
    if (!step || audiences.length === 0) return
    event.preventDefault()
    const next = (index + step + audiences.length) % audiences.length
    onChange(audiences[next]!.value)
    refs.current[next]?.focus()
  }

  return (
    <StepPanel
      title="Audience"
      description="Who receives this campaign. Every option is only people who have opted in."
      onBack={onBack} onNext={onNext} saving={saving}
    >
      <p id={labelId} className="text-[13px] font-medium text-admin-ink">Send to</p>
      {!options && <p className="text-[13px] text-admin-muted">Counting who has opted in…</p>}
      <div role="radiogroup" aria-labelledby={labelId} className="grid gap-2.5 sm:grid-cols-2">
        {audiences.map((a, i) => {
          const selected = a.value === value
          return (
            <button
              key={a.value}
              ref={(el) => { refs.current[i] = el }}
              type="button"
              role="radio"
              aria-checked={selected}
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(a.value)}
              onKeyDown={(e) => move(e, i)}
              className={`rounded-admin-control border p-3.5 text-left transition-colors focus-visible:outline-none
                          focus-visible:ring-2 focus-visible:ring-admin-red focus-visible:ring-offset-2
                          ${selected ? 'border-[#E8A5AE] bg-admin-pink-card' : 'border-admin-line bg-[#FAFBFC] hover:border-[#D5D8DE]'}`}
            >
              <span className={`block text-[13.5px] font-semibold ${selected ? 'text-admin-red-text' : 'text-admin-ink'}`}>
                {a.label}
              </span>
              <span className="tnum mt-1 block text-[20px] font-bold leading-none text-admin-ink">
                {a.count ?? '—'}
                <span className="ml-1.5 text-[12px] font-medium text-admin-muted">opted in</span>
              </span>
              <span className="mt-1.5 block text-[11.5px] leading-snug text-admin-muted">
                {AUDIENCE_NOTES[a.value] ?? ''}
              </span>
            </button>
          )
        })}
      </div>
    </StepPanel>
  )
}

// ---- step 5: Review & Send ----------------------------------------------------------------

const TYPE_LABELS: Record<string, string> = {
  COINS: 'Coins — Sales & Offers',
  BOOSTING: 'FUT Champs — Boosting Services',
  COACHING: 'Coaching — 1-to-1 Coaching',
  GENERAL: 'General — News & Updates',
}

export interface Quota {
  dailyCap: number
  sentLast24h: number
  remaining: number
}

/**
 * Everything the campaign will do, once more, with the one number that can make a send
 * lose people for good: how the audience compares with what is left of the provider's
 * daily allowance.
 */
export function StepReview({
  campaign, fields, content, audienceLabel, audienceCount, quota, today, onEdit, onBack,
  onSendNow, onSchedule, busy,
}: {
  campaign: Campaign
  fields: StepOneFields
  content: StepTwoFields
  audienceLabel: string
  audienceCount: number | null
  quota: Quota | null
  today: string
  onEdit: (step: number) => void
  onBack: () => void
  onSendNow: () => void
  onSchedule: (whenIso: string) => void
  busy: boolean
}) {
  const [acknowledged, setAcknowledged] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [when, setWhen] = useState('')
  const ackId = useId()
  const whenId = useId()

  const check = audienceCount !== null && quota ? quotaCheck(audienceCount, quota.remaining) : null
  const expired = Boolean(fields.offerValidUntil && fields.offerValidUntil < today)
  const empty = audienceCount === 0
  const blocked = expired || empty || audienceCount === null || !quota
  const canSend = !blocked && (check?.fits || acknowledged)

  const rows: [string, ReactNode, number][] = [
    ['Campaign name', fields.title, 1],
    ['Subject', fields.subject, 1],
    ['Type', TYPE_LABELS[fields.type] ?? fields.type, 1],
    ['Headline', fields.promoTitle, 1],
    ['Above / below', [content.kicker, content.subline].filter(Boolean).join(' · ') || 'None', 2],
    ['Offer', fields.offerText || 'None', 1],
    ['Promo code', fields.promoCode ? `${fields.promoCode.toUpperCase()}${fields.showPromoCode ? '' : ' (hidden from the email)'}` : 'None', 1],
    ['Valid till', fields.offerValidUntil || 'No end date', 1],
    ['Button', fields.showButton ? 'ORDER NOW' : 'None', 1],
    ['Tracking', fields.trackingEnabled ? 'Opens and clicks recorded' : 'Off — nothing recorded', 1],
    ['Banner', campaign.hasBanner ? 'Added' : 'None', 3],
    ['Audience', `${audienceLabel} · ${audienceCount ?? '…'} people`, 4],
  ]

  return (
    <StepPanel
      title="Review & Send"
      description="Check everything once more. A campaign cannot be recalled once it goes out."
      onBack={onBack} hideNext saving={busy}
    >
      <dl className="divide-y divide-admin-line rounded-admin-control border border-admin-line">
        {rows.map(([label, value, step]) => (
          <div key={label} className="flex items-start gap-3 px-3 py-2">
            <dt className="w-[110px] shrink-0 text-[12px] text-admin-muted">{label}</dt>
            <dd className="min-w-0 flex-1 break-words text-[12.5px] font-medium text-admin-ink">{value}</dd>
            <button type="button" onClick={() => onEdit(step)}
                    className="shrink-0 text-[12px] font-medium text-admin-red-text underline-offset-2 hover:underline
                               focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red">
              Edit<span className="sr-only"> {label}</span>
            </button>
          </div>
        ))}
      </dl>

      <section aria-labelledby="quota-title" className="rounded-admin-control border border-admin-line p-3.5">
        <h3 id="quota-title" className="text-[13.5px] font-bold text-admin-ink">Sending allowance</h3>
        {quota ? (
          <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1.5 text-[12.5px] sm:grid-cols-4">
            <Figure label="Daily allowance" value={quota.dailyCap} />
            <Figure label="Sent in the last 24h" value={quota.sentLast24h} />
            <Figure label="Left" value={quota.remaining} />
            <Figure label="This campaign" value={audienceCount ?? '…'} strong />
          </dl>
        ) : (
          <p className="mt-2 text-[12.5px] text-admin-muted">Checking the allowance…</p>
        )}
        <p className="mt-2 text-[11px] leading-snug text-admin-muted">
          Order emails use the same allowance and are not counted here, so what is really left can be lower.
        </p>
      </section>

      {check && !check.fits && !empty && (
        <div role="alert" className="rounded-admin-control border border-[#F3CDD2] bg-admin-pink p-3.5">
          <p className="flex items-start gap-2 text-[13px] font-semibold text-admin-red-text">
            <LuTriangleAlert aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
            {check.over} {check.over === 1 ? 'person is' : 'people are'} over what is left of today's allowance.
          </p>
          <p className="mt-1.5 text-[12.5px] leading-relaxed text-admin-ink">
            The provider will refuse the rest. A refused recipient is not retried automatically — they miss
            this campaign unless you retry them from Campaign History once the allowance resets. Schedule it
            for tomorrow, or choose a smaller audience.
          </p>
          <label htmlFor={ackId} className="mt-2.5 flex items-start gap-2 text-[12.5px] text-admin-ink">
            <input id={ackId} type="checkbox" checked={acknowledged}
                   onChange={(e) => setAcknowledged(e.target.checked)}
                   className="mt-0.5 h-4 w-4 accent-[#DB1825]" />
            I understand {check.over} {check.over === 1 ? 'person' : 'people'} may not receive this campaign.
          </label>
        </div>
      )}
      {expired && (
        <p role="alert" className="rounded-admin-control border border-[#F3CDD2] bg-admin-pink p-3 text-[12.5px] text-admin-red-text">
          This offer ended on {fields.offerValidUntil}. Change the date in Campaign Details before sending.
        </p>
      )}
      {empty && (
        <p role="alert" className="rounded-admin-control border border-[#F3CDD2] bg-admin-pink p-3 text-[12.5px] text-admin-red-text">
          Nobody who has opted in matches this audience, so there is nobody to send to.
        </p>
      )}

      <div className="grid gap-3 sm:grid-cols-2">
        <div className="rounded-admin-control border border-admin-line p-3.5">
          <h3 className="text-[13.5px] font-bold text-admin-ink">Send now</h3>
          <p className="mt-1 text-[11.5px] leading-snug text-admin-muted">
            Goes out within about a minute, in the background, so a large send cannot be cut off halfway.
          </p>
          {!confirming ? (
            <button type="button" disabled={!canSend || busy} onClick={() => setConfirming(true)}
                    className={`${primaryButton} mt-3 w-full`}>
              <LuSend aria-hidden="true" className="h-4 w-4" /> Send now
            </button>
          ) : (
            <div className="mt-3 space-y-2" role="group" aria-label="Confirm sending">
              <button type="button" disabled={busy} onClick={onSendNow} className={`${primaryButton} w-full`}>
                {busy ? 'Queuing…' : `Send to ${audienceCount} ${audienceCount === 1 ? 'person' : 'people'}`}
              </button>
              <button type="button" disabled={busy} onClick={() => setConfirming(false)}
                      className={`${secondaryButton} w-full`}>
                Cancel
              </button>
            </div>
          )}
        </div>

        <div className="rounded-admin-control border border-admin-line p-3.5">
          <h3 className="text-[13.5px] font-bold text-admin-ink">Schedule for later</h3>
          <label htmlFor={whenId} className="mt-1 block text-[11.5px] text-admin-muted">Your local time</label>
          <input id={whenId} type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)}
                 className={`${controlClass} mt-1 h-[34px]`} />
          {/* Not gated on the acknowledgement: scheduling for tomorrow is the advice the
              warning gives, and by then the allowance has reset. */}
          <button type="button" disabled={blocked || !when || busy}
                  onClick={() => onSchedule(new Date(when).toISOString())}
                  className={`${secondaryButton} mt-2.5 w-full`}>
            Schedule
          </button>
        </div>
      </div>
    </StepPanel>
  )
}

function Figure({ label, value, strong = false }: { label: string; value: ReactNode; strong?: boolean }) {
  return (
    <div>
      <dt className="text-[11px] text-admin-muted">{label}</dt>
      <dd className={`tnum text-[17px] font-bold leading-tight ${strong ? 'text-admin-red-text' : 'text-admin-ink'}`}>
        {value}
      </dd>
    </div>
  )
}
