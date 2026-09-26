import { LuArrowRight } from 'react-icons/lu'
import { Switch, TypeTiles } from './controls'
import { BannerDrop, Card, Required, TextField } from './fields'
import { LIMITS, type FieldErrors, type FieldName, type StepOneFields } from './wizard'

type Setter = <K extends FieldName>(name: K, value: StepOneFields[K]) => void

/** Step 1, the left column: Campaign Details. */
export function StepDetailsForm({
  fields, errors, set, today, onSubmit,
}: {
  fields: StepOneFields
  errors: FieldErrors
  set: Setter
  today: string
  onSubmit: () => void
}) {
  return (
    <form
      id="campaign-step-one"
      noValidate
      onSubmit={(e) => { e.preventDefault(); onSubmit() }}
      className="rounded-admin-card border border-admin-line bg-white px-4 pb-4 pt-3.5 shadow-admin-card sm:px-5"
    >
      <h2 className="text-[19px] font-bold text-admin-ink">Campaign Details</h2>
      <p className="mt-0.5 text-[13px] text-admin-muted">
        Set up the basic information for your promotional email.
      </p>
      <hr className="my-3 border-admin-line" />

      <div className="space-y-3">
        <TextField
          name="title" label="Campaign Name" required value={fields.title} error={errors.title}
          onChange={(v) => set('title', v)} placeholder="e.g. TOTY Special Offer - Coins Sale"
          helper="This is for your internal reference only."
        />
        <TextField
          name="subject" label="Email Subject" required value={fields.subject} error={errors.subject}
          onChange={(v) => set('subject', v)} placeholder="e.g. TOTY is here! Get your coins now"
          helper="This will be visible to your customers." counter={LIMITS.subject}
        />

        <div>
          <p id="campaign-type-label" className="mb-1.5 text-[13px] font-medium text-admin-ink">
            Campaign Type <Required />
          </p>
          <TypeTiles value={fields.type} onChange={(v) => set('type', v)} labelledBy="campaign-type-label" />
        </div>

        <h3 className="pt-1 text-[17px] font-bold text-admin-ink">Promotion Details</h3>

        <div className="grid gap-x-4 gap-y-3 sm:grid-cols-2">
          <TextField
            name="promoTitle" label="Promo Title" required value={fields.promoTitle}
            error={errors.promoTitle} onChange={(v) => set('promoTitle', v)}
            placeholder="e.g. TOTY Coins Sale"
          />
          <TextField
            name="offerText" label="Discount / Offer" value={fields.offerText} error={errors.offerText}
            onChange={(v) => set('offerText', v)} placeholder="e.g. 15% OFF"
            helper="e.g. 15% OFF, Flat ₹500 OFF, etc."
          />
          <TextField
            name="promoCode" label="Promo Code" value={fields.promoCode} error={errors.promoCode}
            onChange={(v) => set('promoCode', v)} placeholder="e.g. HUNTER10"
            helper="Enter promo code if applicable."
          />
          <TextField
            name="offerValidUntil" label="Offer Valid Till" type="date" min={today}
            value={fields.offerValidUntil} error={errors.offerValidUntil}
            onChange={(v) => set('offerValidUntil', v)}
          />
        </div>

        <TextField
          name="description" label="Offer Description" required multiline value={fields.description}
          error={errors.description} onChange={(v) => set('description', v)}
          placeholder="What's on offer, and why now."
          counter={LIMITS.description} counterBelow
        />
      </div>
    </form>
  )
}

/** Step 1, beneath the preview: the banner, the three switches, and Next Step. */
export function StepDetailsCards({
  fields, set, banner, hasBanner, onBanner, saving,
}: {
  fields: StepOneFields
  set: Setter
  banner: { busy: boolean; error: string | null }
  hasBanner: boolean
  onBanner: (file: File) => void
  saving: boolean
}) {
  return (
    <div className="grid gap-3 sm:grid-cols-3">
      <Card title="Upload Banner Image">
        <BannerDrop busy={banner.busy} hasBanner={hasBanner} onFile={onBanner} />
        <p className="mt-2 text-[10.5px] leading-snug text-admin-muted">
          This image will be used in your promotional email.
        </p>
        {banner.error && (
          <p role="alert" className="mt-1.5 text-[11.5px] text-admin-red-text">{banner.error}</p>
        )}
      </Card>

      <Card title="Additional Options">
        <div className="space-y-1.5">
          <Switch checked={fields.showButton} onChange={(v) => set('showButton', v)}>
            Add website button (Order Now)
          </Switch>
          <Switch checked={fields.showPromoCode} onChange={(v) => set('showPromoCode', v)}>
            Include promo code in email
          </Switch>
          <Switch checked={fields.trackingEnabled} onChange={(v) => set('trackingEnabled', v)}>
            Track email opens and clicks
          </Switch>
        </div>
      </Card>

      <Card title="Continue">
        <button
          type="submit"
          form="campaign-step-one"
          disabled={saving}
          className="inline-flex h-[40px] w-full items-center justify-center gap-2 rounded-admin-control
                     bg-admin-red text-[13.5px] font-semibold text-white transition-colors
                     hover:bg-[#C21520] focus-visible:outline-none focus-visible:ring-2
                     focus-visible:ring-admin-red focus-visible:ring-offset-2 disabled:opacity-60"
        >
          {saving ? 'Saving…' : 'Next Step'}
          {!saving && <LuArrowRight aria-hidden="true" className="h-4 w-4" />}
        </button>
        <p className="mt-2.5 text-[11px] leading-snug text-admin-muted">
          Proceed to customize your email content and design.
        </p>
      </Card>
    </div>
  )
}
