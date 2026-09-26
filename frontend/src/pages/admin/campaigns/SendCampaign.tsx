import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { LuHistory } from 'react-icons/lu'
import { ApiError, api } from '../../../lib/api'
import { useSeo } from '../../../lib/seo'
import type { Campaign, CampaignOptions } from '../../../lib/types'
import { useAuth } from '../../../state/AuthContext'
import { AdminPage } from '../shell/AdminPage'
import { StepIndicator } from './controls'
import { EmailPreview } from './EmailPreview'
import { BANNER_MAX_BYTES, BANNER_TYPES, fieldId } from './fields'
import { StepAudience, StepContent, StepDesign, StepReview, type Quota } from './LaterSteps'
import { StepDetailsCards, StepDetailsForm } from './StepDetails'
import {
  emptyStepOne, firstInvalid, toDetailsRequest, toPreviewRequest, todayInIndia, validateStepOne,
  validateStepTwo, type ContentErrors, type FieldErrors, type FieldName, type StepOneFields,
  type StepTwoFields,
} from './wizard'

/** Long enough to skip the keystrokes of a word, short enough to feel live. */
const PREVIEW_DEBOUNCE_MS = 350
const LAST_STEP = 5

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
 * Send Campaign: the five-step campaign builder.
 *
 * <p><b>Where you are is in the address.</b> {@code ?draft=…&step=N}, so a reload, a
 * bookmark or the back button lands on the same step of the same draft instead of a
 * blank form. Without a draft only step 1 exists: the later steps edit a saved campaign.
 *
 * <p><b>Every move saves the step you are leaving</b> — Next, Back, or a step chosen from
 * the strip — so nothing typed is lost by moving around, and a step that does not validate
 * keeps you on it with its errors shown.
 *
 * <p><b>The preview follows the form</b>, not the last save: it is the server's own
 * template rendering what is on screen, on every step.
 */
export default function SendCampaign() {
  useSeo({ title: 'Send campaign', noindex: true })
  const navigate = useNavigate()
  const { account } = useAuth()
  const [params, setParams] = useSearchParams()
  const draftId = params.get('draft')
  const requested = Number(params.get('step') ?? '1')
  const step = draftId && requested >= 1 && requested <= LAST_STEP ? Math.floor(requested) : 1

  const [fields, setFields] = useState<StepOneFields>(emptyStepOne)
  const [content, setContent] = useState<StepTwoFields>({ kicker: '', subline: '' })
  const [audience, setAudience] = useState('ALL_OPTED_IN')
  const [errors, setErrors] = useState<FieldErrors>({})
  const [contentErrors, setContentErrors] = useState<ContentErrors>({})
  const [summary, setSummary] = useState<string | null>(null)
  const [campaign, setCampaign] = useState<Campaign | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [options, setOptions] = useState<CampaignOptions | null>(null)
  const [quota, setQuota] = useState<Quota | null>(null)
  const [preview, setPreview] = useState<{ html: string | null; error: string | null; loading: boolean }>(
    { html: null, error: null, loading: true })
  const [banner, setBanner] = useState<{ busy: boolean; error: string | null }>({ busy: false, error: null })

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
        setContent({ kicker: c.heroKicker ?? '', subline: c.heroSubline ?? '' })
        setAudience(c.audience)
      })
      .catch((e) => {
        if (alive) setLoadError(e instanceof ApiError ? e.message : 'Could not open that draft.')
      })
    return () => { alive = false }
  }, [draftId, campaign?.publicId])

  // ---- the audience counts and the allowance, when a step needs them ---------------
  useEffect(() => {
    if (step < 4 || options) return
    api.get<CampaignOptions>('/api/v1/admin/campaigns/options').then(setOptions).catch(() => undefined)
  }, [step, options])
  useEffect(() => {
    if (step !== 5) return
    // Fetched afresh each time the review opens: the allowance moves as other sends land.
    api.get<Quota>('/api/v1/admin/campaigns/quota').then(setQuota).catch(() => setQuota(null))
  }, [step])

  // ---- the live preview ----------------------------------------------------------
  useEffect(() => {
    const controller = new AbortController()
    setPreview((p) => ({ ...p, loading: true }))
    const timer = setTimeout(async () => {
      try {
        const html = await api.html('/api/v1/admin/campaigns/preview',
          toPreviewRequest({ ...fields, ...content }, campaign?.publicId ?? null), controller.signal)
        setPreview({ html, error: null, loading: false })
      } catch {
        if (controller.signal.aborted) return
        // Keep the last good render on screen and say it is out of date, rather than
        // blanking the preview because one request failed.
        setPreview((p) => ({ ...p, error: 'The preview could not be updated.', loading: false }))
      }
    }, PREVIEW_DEBOUNCE_MS)
    return () => { clearTimeout(timer); controller.abort() }
  }, [fields, content, campaign?.publicId, campaign?.updatedAt])

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

  const report = (found: Record<string, string | undefined>, first: string | undefined) => {
    const count = Object.values(found).filter(Boolean).length
    if (!first || count === 0) {
      setSummary(null)
      return
    }
    setSummary(`${count} field${count === 1 ? ' needs' : 's need'} attention before this can be saved.`)
    // Focus moves to the first problem, so the next thing a keyboard or screen-reader
    // user meets is the field to fix, with its message already attached.
    document.getElementById(fieldId(first))?.focus()
  }

  const fail = (e: unknown, fallback: string) => {
    setSummary(e instanceof ApiError ? e.message : fallback)
  }

  /** Save the step being left. Returns the saved campaign, or null to stay put. */
  const saveStep = async (): Promise<Campaign | null> => {
    if (step === 1) {
      const found = validateStepOne(fields)
      if (Object.keys(found).length > 0) {
        setErrors(found)
        report(found, firstInvalid(found))
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
        return saved
      } catch (e) {
        if (e instanceof ApiError && Object.keys(e.fieldErrors).length > 0) {
          setErrors(e.fieldErrors as FieldErrors)
          report(e.fieldErrors, firstInvalid(e.fieldErrors as FieldErrors))
        } else {
          fail(e, 'The draft could not be saved.')
        }
        return null
      } finally {
        setSaving(false)
      }
    }
    if (!campaign) return null
    if (step === 2) {
      const found = validateStepTwo(content)
      setContentErrors(found)
      if (Object.keys(found).length > 0) {
        report(found, found.kicker ? 'kicker' : 'subline')
        return null
      }
      return put(`/api/v1/admin/campaigns/${campaign.publicId}/content`,
        { kicker: content.kicker, subline: content.subline })
    }
    if (step === 4) {
      return put(`/api/v1/admin/campaigns/${campaign.publicId}/audience`, { audience })
    }
    return campaign
  }

  const put = async (path: string, body: unknown): Promise<Campaign | null> => {
    setSaving(true)
    try {
      const saved = await api.put<Campaign>(path, body)
      setCampaign(saved)
      setSummary(null)
      return saved
    } catch (e) {
      fail(e, 'That step could not be saved.')
      return null
    } finally {
      setSaving(false)
    }
  }

  const goTo = async (target: number) => {
    const saved = await saveStep()
    if (!saved) return
    setParams({ draft: saved.publicId, step: String(target) })
    window.scrollTo({ top: 0 })
  }

  // ---- the banner --------------------------------------------------------------------
  const uploadBanner = async (file: File) => {
    if (!BANNER_TYPES.includes(file.type)) {
      setBanner({ busy: false, error: 'Use a PNG, JPEG, GIF or WebP image.' })
      return
    }
    if (file.size > BANNER_MAX_BYTES) {
      setBanner({ busy: false, error: 'Banners must be 2MB or smaller.' })
      return
    }
    setBanner({ busy: true, error: null })
    // A banner belongs to a campaign on the server, so the first step is saved first if
    // it has not been. If it is not valid yet, its errors are shown instead.
    const target = campaign ?? (step === 1 ? await saveStep() : null)
    if (!target) {
      setBanner({ busy: false, error: 'Fill in the campaign details first — the banner is saved with the draft.' })
      return
    }
    if (!campaign) setParams({ draft: target.publicId, step: String(step) }, { replace: true })
    try {
      const form = new FormData()
      form.append('file', file)
      setCampaign(await api.upload<Campaign>(`/api/v1/admin/campaigns/${target.publicId}/banner`, form))
      setBanner({ busy: false, error: null })
    } catch (e) {
      setBanner({ busy: false, error: e instanceof ApiError ? e.message : 'That banner could not be uploaded.' })
    }
  }

  // ---- sending -----------------------------------------------------------------------
  const sendTest = async (): Promise<string | null> => {
    if (!campaign) return null
    try {
      const { sentTo } = await api.post<{ sentTo: string }>(`/api/v1/admin/campaigns/${campaign.publicId}/test`)
      return sentTo
    } catch (e) {
      throw new Error(e instanceof ApiError ? e.message : 'The test could not be sent.')
    }
  }

  /**
   * Hand the campaign to the background job rather than send it in this request.
   *
   * <p>"Send now" is scheduled a minute ahead: the job that runs scheduled campaigns
   * picks it up and sends it. Sending inside the request would hold it open for as long
   * as the relay takes, and the proxy in front of the API gives up after 60 seconds —
   * the admin would be told it failed while it carried on sending.
   */
  const schedule = async (whenIso: string, notice: string) => {
    if (!campaign) return
    setSaving(true)
    try {
      await api.post(`/api/v1/admin/campaigns/${campaign.publicId}/schedule`, { sendAt: whenIso })
      navigate('/admin/email/history', { state: { notice } })
    } catch (e) {
      fail(e, 'The campaign could not be scheduled.')
    } finally {
      setSaving(false)
    }
  }

  const today = todayInIndia()
  const chosen = options?.audiences.find((a) => a.value === audience)

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
      <StepIndicator
        current={step}
        // The later steps edit a saved draft, so until there is one only the first exists.
        reachable={campaign ? LAST_STEP : 1}
        onSelect={(n) => void goTo(n)}
      />

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
        {step === 1 && (
          <StepDetailsForm fields={fields} errors={errors} set={set} today={today} onSubmit={() => void goTo(2)} />
        )}
        {step === 2 && (
          <StepContent
            fields={content} errors={contentErrors}
            onChange={(name, value) => {
              setContent((c) => ({ ...c, [name]: value }))
              setContentErrors((e) => ({ ...e, [name]: undefined }))
            }}
            promoTitle={fields.promoTitle} onEditHeadline={() => void goTo(1)}
            onBack={() => void goTo(1)} onNext={() => void goTo(3)} saving={saving}
          />
        )}
        {step === 3 && campaign && (
          <StepDesign
            campaign={campaign} adminEmail={account?.email} banner={banner}
            onBanner={(f) => void uploadBanner(f)} onSendTest={sendTest}
            onBack={() => void goTo(2)} onNext={() => void goTo(4)}
          />
        )}
        {step === 4 && (
          <StepAudience
            options={options} value={audience} onChange={setAudience}
            onBack={() => void goTo(3)} onNext={() => void goTo(5)} saving={saving}
          />
        )}
        {step === 5 && campaign && (
          <StepReview
            campaign={campaign} fields={fields} content={content}
            audienceLabel={chosen?.label ?? audience} audienceCount={chosen?.count ?? null}
            quota={quota} today={today} onEdit={(n) => void goTo(n)} onBack={() => void goTo(4)}
            busy={saving}
            onSendNow={() => void schedule(new Date(Date.now() + 60_000).toISOString(),
              `"${fields.title}" is queued and goes out to ${chosen?.count ?? 'its'} `
                + `${chosen?.count === 1 ? 'person' : 'people'} within about a minute.`)}
            onSchedule={(whenIso) => void schedule(whenIso,
              `"${fields.title}" is scheduled for ${new Date(whenIso).toLocaleString()}.`)}
          />
        )}

        <div className="space-y-3">
          <EmailPreview html={preview.html} error={preview.error} loading={preview.loading} />
          {step === 1 && (
            <StepDetailsCards
              fields={fields} set={set} banner={banner} hasBanner={Boolean(campaign?.hasBanner)}
              onBanner={(f) => void uploadBanner(f)} saving={saving}
            />
          )}
        </div>
      </div>
    </AdminPage>
  )
}
