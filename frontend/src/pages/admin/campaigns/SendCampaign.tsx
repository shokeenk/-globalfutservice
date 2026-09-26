import { useCallback, useEffect, useId, useRef, useState, type DragEvent, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { LuArrowRight, LuCheck, LuHistory, LuImage } from 'react-icons/lu'
import { ApiError, api } from '../../../lib/api'
import { useSeo } from '../../../lib/seo'
import type { Campaign } from '../../../lib/types'
import { AdminPage } from '../shell/AdminPage'
import { CharCount, StepIndicator, Switch, TypeTiles } from './controls'
import { EmailPreview } from './EmailPreview'
import {
  LIMITS, emptyStepOne, firstInvalid, toDetailsRequest, toPreviewRequest, todayInIndia,
  validateStepOne, type FieldErrors, type FieldName, type StepOneFields,
} from './wizard'

const BANNER_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']
const BANNER_MAX_BYTES = 2 * 1024 * 1024
/** Long enough to skip the keystrokes of a word, short enough to feel live. */
const PREVIEW_DEBOUNCE_MS = 350

const fieldId = (name: FieldName) => `campaign-${name}`

/** A saved draft's values, back into the form. */
function fromCampaign(c: Campaign): StepOneFields {
  return {
    title: c.title,
    subject: c.subject,
    type: c.type ?? 'GENERAL',
    promoTitle: c.heading,
    offerText: c.offerText ?? '',
    promoCode: c.promoCode ?? '',
    offerValidUntil: c.offerValidUntil ?? '',
    description: c.body,
    showButton: c.showButton ?? Boolean(c.ctaPath),
    showPromoCode: c.showPromoCode ?? true,
    trackingEnabled: c.trackingEnabled ?? true,
  }
}

/**
 * Send Campaign: the builder's first step, with the email previewed live beside it.
 *
 * <p><b>When a draft is created.</b> Not on every keystroke, and not only at the end. A
 * banner has to belong to a campaign on the server, so the first upload saves the step
 * as a draft if there is none yet — and "Next Step" saves it too. Once a draft exists its
 * id goes into the address, so reloading the page keeps the work instead of starting a
 * second draft.
 *
 * <p><b>Steps 2 to 5</b> are not built yet. "Next Step" saves the draft and opens it in
 * Campaign History, whose existing controls still schedule and send it.
 */
export default function SendCampaign() {
  useSeo({ title: 'Send campaign', noindex: true })
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const draftId = params.get('draft')

  const [fields, setFields] = useState<StepOneFields>(emptyStepOne)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [summary, setSummary] = useState<string | null>(null)
  const [campaign, setCampaign] = useState<Campaign | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [preview, setPreview] = useState<{ html: string | null; error: string | null; loading: boolean }>(
    { html: null, error: null, loading: true })

  // ---- an existing draft, opened by address -------------------------------------
  useEffect(() => {
    if (!draftId || campaign?.publicId === draftId) return
    let alive = true
    api.get<Campaign>(`/api/v1/admin/campaigns/${encodeURIComponent(draftId)}`)
      .then((c) => {
        if (!alive) return
        if (c.status !== 'DRAFT') {
          setLoadError('That campaign is no longer a draft, so it cannot be edited here.')
          return
        }
        setCampaign(c)
        setFields(fromCampaign(c))
      })
      .catch((e) => {
        if (alive) setLoadError(e instanceof ApiError ? e.message : 'Could not open that draft.')
      })
    return () => { alive = false }
  }, [draftId, campaign?.publicId])

  // ---- the live preview ----------------------------------------------------------
  useEffect(() => {
    const controller = new AbortController()
    setPreview((p) => ({ ...p, loading: true }))
    const timer = setTimeout(async () => {
      try {
        const html = await api.html('/api/v1/admin/campaigns/preview',
          toPreviewRequest(fields, campaign?.publicId ?? null), controller.signal)
        setPreview({ html, error: null, loading: false })
      } catch {
        if (controller.signal.aborted) return
        // Keep the last good render on screen and say it is out of date, rather than
        // blanking the preview because one request failed.
        setPreview((p) => ({ ...p, error: 'The preview could not be updated.', loading: false }))
      }
    }, PREVIEW_DEBOUNCE_MS)
    return () => { clearTimeout(timer); controller.abort() }
  }, [fields, campaign?.publicId, campaign?.updatedAt])

  // ---- editing -----------------------------------------------------------------------
  const set = useCallback(<K extends FieldName>(name: K, value: StepOneFields[K]) => {
    setFields((f) => ({ ...f, [name]: value }))
    // A field's error clears as soon as it is edited; it is re-checked on save.
    setErrors((e) => {
      if (!e[name]) return e
      const next = { ...e }
      delete next[name]
      return next
    })
  }, [])

  const report = (found: FieldErrors) => {
    setErrors(found)
    const first = firstInvalid(found)
    if (!first) {
      setSummary(null)
      return
    }
    const count = Object.keys(found).length
    setSummary(`${count} field${count === 1 ? ' needs' : 's need'} attention before this can be saved.`)
    // Focus is moved to the first problem, so the next thing a keyboard or screen-reader
    // user meets is the field to fix, with its message already attached.
    document.getElementById(fieldId(first))?.focus()
  }

  const save = async (): Promise<Campaign | null> => {
    const found = validateStepOne(fields)
    if (Object.keys(found).length > 0) {
      report(found)
      return null
    }
    setSaving(true)
    try {
      const body = toDetailsRequest(fields)
      const saved = campaign
        ? await api.put<Campaign>(`/api/v1/admin/campaigns/${campaign.publicId}/details`, body)
        : await api.post<Campaign>('/api/v1/admin/campaigns/drafts', body)
      setCampaign(saved)
      setSummary(null)
      if (!campaign) setParams({ draft: saved.publicId }, { replace: true })
      return saved
    } catch (e) {
      if (e instanceof ApiError && Object.keys(e.fieldErrors).length > 0) {
        report(e.fieldErrors as FieldErrors)
      } else {
        setSummary(e instanceof ApiError ? e.message : 'The draft could not be saved.')
      }
      return null
    } finally {
      setSaving(false)
    }
  }

  const next = async () => {
    const saved = await save()
    if (!saved) return
    navigate('/admin/email/history', {
      state: {
        notice: `Draft "${saved.title}" saved. The builder's remaining steps are on their way — `
          + 'until then, schedule or send it from here.',
      },
    })
  }

  // ---- the banner --------------------------------------------------------------------
  const [bannerState, setBannerState] = useState<{ busy: boolean; error: string | null }>(
    { busy: false, error: null })

  const uploadBanner = async (file: File) => {
    if (!BANNER_TYPES.includes(file.type)) {
      setBannerState({ busy: false, error: 'Use a PNG, JPEG, GIF or WebP image.' })
      return
    }
    if (file.size > BANNER_MAX_BYTES) {
      setBannerState({ busy: false, error: 'Banners must be 2MB or smaller.' })
      return
    }
    setBannerState({ busy: true, error: null })
    // A banner belongs to a campaign on the server, so the step is saved first if it
    // has not been. If the step is not valid yet, its errors are shown instead.
    const target = campaign ?? await save()
    if (!target) {
      setBannerState({ busy: false, error: 'Fill in the campaign details first — the banner is saved with the draft.' })
      return
    }
    try {
      const form = new FormData()
      form.append('file', file)
      setCampaign(await api.upload<Campaign>(`/api/v1/admin/campaigns/${target.publicId}/banner`, form))
      setBannerState({ busy: false, error: null })
    } catch (e) {
      setBannerState({ busy: false, error: e instanceof ApiError ? e.message : 'That banner could not be uploaded.' })
    }
  }

  const today = todayInIndia()

  return (
    <AdminPage
      eyebrow="Email Marketing"
      title="Send Promotional Email"
      description="Create and send promotional emails to your customers. Announce new promos, discounts, events and more."
      action={
        <Link
          to="/admin/email/history"
          className="inline-flex h-[34px] items-center gap-2 rounded-admin-control border border-admin-line
                     bg-white px-4 text-[12.5px] font-medium text-admin-ink shadow-admin-card
                     hover:bg-admin-page focus-visible:outline-none focus-visible:ring-2
                     focus-visible:ring-admin-red"
        >
          <LuHistory aria-hidden="true" className="h-4 w-4" />
          Campaign History
        </Link>
      }
    >
      <StepIndicator current={1} />

      {loadError && (
        <p role="alert" className="mb-4 rounded-admin-control border border-[#F3CDD2] bg-admin-pink px-4 py-3
                                   text-[13px] text-admin-red-text">{loadError}</p>
      )}
      <p role="status" aria-live="polite" className={summary
        ? 'mb-4 rounded-admin-control border border-[#F3CDD2] bg-admin-pink px-4 py-3 text-[13px] font-medium text-admin-red-text'
        : 'sr-only'}
      >
        {summary}
      </p>

      <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1.075fr)_minmax(0,1fr)]">
        <form
          id="campaign-step-one"
          noValidate
          onSubmit={(e) => { e.preventDefault(); void next() }}
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

        <div className="space-y-3">
          <EmailPreview html={preview.html} error={preview.error} loading={preview.loading} />

          <div className="grid gap-3 sm:grid-cols-3">
            <Card title="Upload Banner Image">
              <BannerDrop
                busy={bannerState.busy}
                hasBanner={Boolean(campaign?.hasBanner)}
                onFile={(f) => void uploadBanner(f)}
              />
              <p className="mt-2 text-[10.5px] leading-snug text-admin-muted">
                This image will be used in your promotional email.
              </p>
              {bannerState.error && (
                <p role="alert" className="mt-1.5 text-[11.5px] text-admin-red-text">{bannerState.error}</p>
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
        </div>
      </div>
    </AdminPage>
  )
}

function Required() {
  return (
    <>
      <span aria-hidden="true" className="text-admin-red-text">*</span>
      <span className="sr-only">(required)</span>
    </>
  )
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <section aria-labelledby={id}
             className="rounded-admin-card border border-admin-line bg-white p-3 shadow-admin-card">
      <h3 id={id} className="mb-2 text-[12.5px] font-bold text-admin-ink">{title}</h3>
      {children}
    </section>
  )
}

const controlClass =
  'w-full rounded-admin-control border border-[#E3E5E9] bg-white px-3 text-[13px] text-admin-ink ' +
  'placeholder:text-[#9CA0A8] transition-shadow focus:border-admin-red focus:outline-none ' +
  'focus:ring-2 focus:ring-admin-red/20 aria-[invalid=true]:border-admin-red-text'

/**
 * A labelled field with its helper, counter and error wired to the control.
 *
 * <p>The label is a real {@code <label for>}, the helper and counter are attached with
 * aria-describedby, and an error sets aria-invalid and is read out with the field, so a
 * screen reader hears "Email Subject, required, invalid, Keep the subject to 100
 * characters" rather than only seeing a red border.
 */
function TextField({
  name, label, value, onChange, required = false, helper, error, placeholder,
  counter, counterBelow = false, multiline = false, type = 'text', min,
}: {
  name: FieldName
  label: string
  value: string
  onChange: (value: string) => void
  required?: boolean
  helper?: string
  error?: string
  placeholder?: string
  counter?: number
  counterBelow?: boolean
  multiline?: boolean
  type?: 'text' | 'date'
  min?: string
}) {
  const id = fieldId(name)
  const described = [
    helper && `${id}-helper`,
    counter && `${id}-count`,
    error && `${id}-error`,
  ].filter(Boolean).join(' ') || undefined
  const count = counter ? <CharCount id={`${id}-count`} value={value} limit={counter} /> : null

  return (
    <div>
      <div className="mb-1 flex items-baseline justify-between gap-3">
        <label htmlFor={id} className="text-[13px] font-medium text-admin-ink">
          {label} {required && <Required />}
        </label>
        {!counterBelow && count}
      </div>
      {multiline ? (
        <textarea
          id={id}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          rows={3}
          aria-required={required || undefined}
          aria-invalid={Boolean(error) || undefined}
          aria-describedby={described}
          className={`${controlClass} min-h-[68px] resize-y py-2 leading-relaxed`}
        />
      ) : (
        <input
          id={id}
          type={type}
          value={value}
          min={min}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          aria-required={required || undefined}
          aria-invalid={Boolean(error) || undefined}
          aria-describedby={described}
          className={`${controlClass} h-[31px]`}
        />
      )}
      {(helper || (counterBelow && count)) && (
        <div className="mt-1 flex items-start justify-between gap-3">
          {helper ? <p id={`${id}-helper`} className="text-[11px] text-admin-muted">{helper}</p> : <span />}
          {counterBelow && count}
        </div>
      )}
      {error && (
        <p id={`${id}-error`} className="mt-1 text-[12px] font-medium text-admin-red-text">{error}</p>
      )}
    </div>
  )
}

/**
 * The banner drop zone. A real file input, visually hidden, inside the label that is the
 * drop zone, so it is reachable by keyboard and opens the picker on Enter or Space.
 */
function BannerDrop({ busy, hasBanner, onFile }: {
  busy: boolean
  hasBanner: boolean
  onFile: (file: File) => void
}) {
  const [over, setOver] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  const drop = (event: DragEvent) => {
    event.preventDefault()
    setOver(false)
    const file = event.dataTransfer.files[0]
    if (file) onFile(file)
  }

  return (
    <label
      onDragOver={(e) => { e.preventDefault(); setOver(true) }}
      onDragLeave={() => setOver(false)}
      onDrop={drop}
      className={`flex min-h-[92px] cursor-pointer flex-col items-center justify-center rounded-admin-control
                  border border-dashed px-2 py-3 text-center transition-colors focus-within:ring-2
                  focus-within:ring-admin-red focus-within:ring-offset-2
                  ${over ? 'border-admin-red bg-admin-pink' : 'border-[#CBD0D8] hover:bg-admin-page'}`}
    >
      <input
        ref={input}
        type="file"
        accept={BANNER_TYPES.join(',')}
        className="sr-only"
        disabled={busy}
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) onFile(file)
          // Choosing the same file twice should upload twice.
          e.target.value = ''
        }}
      />
      {hasBanner && !busy
        ? <LuCheck aria-hidden="true" className="h-5 w-5 text-[#12692F]" />
        : <LuImage aria-hidden="true" className="h-5 w-5 text-admin-ink" />}
      <span className="mt-1.5 text-[12px] font-semibold text-admin-ink">
        {busy ? 'Uploading…' : hasBanner ? 'Banner added — click to replace' : 'Click to upload banner'}
      </span>
      <span className="mt-0.5 text-[10.5px] text-admin-muted">Recommended size: 1200 x 600 px (JPG/PNG)</span>
    </label>
  )
}
