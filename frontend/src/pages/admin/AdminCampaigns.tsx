import { PageHeader } from '../../components/PageHeader'
import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Badge, Button, Field, Input, Section, Select, Skeleton, Textarea,
} from '../../components/ui'
import { ApiError, api } from '../../lib/api'
import { dateTime } from '../../lib/format'
import { useSeo } from '../../lib/seo'
import type { Campaign, CampaignOptions } from '../../lib/types'

type Tab = 'compose' | 'DRAFT' | 'SCHEDULED' | 'FINISHED'

const TABS: [Tab, string][] = [
  ['compose', 'Create campaign'],
  ['DRAFT', 'Drafts'],
  ['SCHEDULED', 'Scheduled'],
  ['FINISHED', 'Sent'],
]

/**
 * Promotional campaigns.
 *
 * <p>One screen rather than the seven the brief lists, because five of those seven are
 * the same list filtered differently and the sixth — preview — only makes sense beside
 * the thing being previewed. Splitting them into separate routes would mean an admin
 * composing a campaign has to navigate away to see it.
 *
 * <p>Two things here are deliberately not free text: the audience and the CTA. Both are
 * chosen from what the server offers, because a campaign goes to every opted-in customer
 * at once and there is no recall. A typed URL in that position is four hundred people
 * landing on a 404.
 */
export default function AdminCampaigns() {
  useSeo({ title: 'Campaigns', noindex: true })

  const [tab, setTab] = useState<Tab>('compose')
  const [options, setOptions] = useState<CampaignOptions | null>(null)
  const [list, setList] = useState<Campaign[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const loadOptions = useCallback(async () => {
    try {
      setOptions(await api.get<CampaignOptions>('/api/v1/admin/campaigns/options'))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not load campaign options.')
    }
  }, [])

  const loadList = useCallback(async (status: Tab) => {
    if (status === 'compose') return
    setList(null)
    try {
      setList(await api.get<Campaign[]>(`/api/v1/admin/campaigns?status=${status}`))
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not load campaigns.')
      setList([])
    }
  }, [])

  useEffect(() => { void loadOptions() }, [loadOptions])
  useEffect(() => { void loadList(tab) }, [tab, loadList])

  return (
    <>
      <PageHeader eyebrow="Operations" title="Campaigns" intensity={0.3} />
      <Section wide className="rhythm-section">
        {error && <Alert tone="warn">{error}</Alert>}
        {notice && <Alert tone="ok">{notice}</Alert>}

        <Alert tone="neutral">
          Campaigns go only to customers who opted in to marketing. Order emails are
          separate and are never affected by an unsubscribe.
        </Alert>

        <div className="plate my-5 flex flex-wrap gap-2 p-2">
          {TABS.map(([value, label]) => (
            <Button
              key={value}
              variant={tab === value ? 'primary' : 'secondary'}
              size="md"
              onClick={() => { setTab(value); setNotice(null) }}
            >
              {label}
            </Button>
          ))}
        </div>

        {tab === 'compose' && (
          <Compose
            options={options}
            onCreated={(message) => { setNotice(message); setTab('DRAFT') }}
            onError={setError}
          />
        )}

        {tab !== 'compose' && (
          <CampaignList
            campaigns={list}
            options={options}
            showAnalytics={tab === 'FINISHED'}
            onChanged={(message) => { setNotice(message); void loadList(tab) }}
            onError={setError}
          />
        )}
      </Section>
    </>
  )
}

/* ------------------------------------------------------------------ compose */

function Compose({ options, onCreated, onError }: {
  options: CampaignOptions | null
  onCreated: (message: string) => void
  onError: (message: string) => void
}) {
  const [title, setTitle] = useState('')
  const [subject, setSubject] = useState('')
  const [heading, setHeading] = useState('')
  const [body, setBody] = useState('')
  const [audience, setAudience] = useState('ALL_OPTED_IN')
  const [busy, setBusy] = useState(false)

  const chosen = options?.audiences.find((a) => a.value === audience)

  const submit = async () => {
    setBusy(true)
    try {
      const created = await api.post<Campaign>('/api/v1/admin/campaigns', {
        title, subject, heading, body, audience,
      })
      setTitle(''); setSubject(''); setHeading(''); setBody('')
      onCreated(`Draft "${created.title}" created. Add a promo code, banner and button below.`)
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'Could not create the campaign.')
    } finally {
      setBusy(false)
    }
  }

  const ready = title.trim() && subject.trim() && heading.trim() && body.trim()

  return (
    <div className="surface max-w-3xl space-y-5 p-6">
      <Field label="Campaign name" hint="Internal only — customers never see this.">
        {(p) => (
          <Input {...p} value={title} onChange={(e) => setTitle(e.target.value)}
                 placeholder="Friday Coin Sale" />
        )}
      </Field>

      <Field label="Email subject" hint="What shows in the inbox.">
        {(p) => (
          <Input {...p} value={subject} onChange={(e) => setSubject(e.target.value)}
                 placeholder="Friday Coin Sale — 10% off all coins" />
        )}
      </Field>

      <Field label="Heading" hint="The headline inside the email.">
        {(p) => (
          <Input {...p} value={heading} onChange={(e) => setHeading(e.target.value)}
                 placeholder="FRIDAY COIN SALE" />
        )}
      </Field>

      <Field label="Message" hint="Blank line between paragraphs.">
        {(p) => (
          <Textarea {...p} rows={7} value={body} onChange={(e) => setBody(e.target.value)}
                    placeholder={'The transfer market does not wait, and neither should you.\n\nFor the next 48 hours every coin order is 10% off.'} />
        )}
      </Field>

      <Field label="Audience" hint={chosen ? chosen.detail : 'Loading counts…'}>
        {(p) => (
          <Select {...p} value={audience} onChange={(e) => setAudience(e.target.value)}>
            {(options?.audiences ?? []).map((a) => (
              <option key={a.value} value={a.value}>{a.label} — {a.detail}</option>
            ))}
          </Select>
        )}
      </Field>

      <Button variant="primary" onClick={() => void submit()} disabled={busy || !ready}>
        {busy ? 'Creating…' : 'Create draft'}
      </Button>
      <p className="text-[12.5px] text-chalk-faint">
        Nothing is sent yet. A draft can be previewed, given a banner and a button, then
        sent or scheduled.
      </p>
    </div>
  )
}

/* --------------------------------------------------------------------- list */

function CampaignList({ campaigns, options, showAnalytics, onChanged, onError }: {
  campaigns: Campaign[] | null
  options: CampaignOptions | null
  showAnalytics: boolean
  onChanged: (message: string) => void
  onError: (message: string) => void
}) {
  if (!campaigns) return <Skeleton className="h-52 w-full" />
  if (campaigns.length === 0) {
    return <Alert tone="neutral">Nothing here yet.</Alert>
  }
  return (
    <div className="space-y-4">
      {campaigns.map((c) => (
        <CampaignCard key={c.publicId} campaign={c} options={options}
                      showAnalytics={showAnalytics} onChanged={onChanged} onError={onError} />
      ))}
    </div>
  )
}

const STATUS_TONE: Record<Campaign['status'], 'ok' | 'warn' | 'neutral' | 'brand'> = {
  DRAFT: 'neutral', SCHEDULED: 'brand', SENDING: 'brand',
  SENT: 'ok', CANCELLED: 'neutral', FAILED: 'warn',
}

function CampaignCard({ campaign, options, showAnalytics, onChanged, onError }: {
  campaign: Campaign
  options: CampaignOptions | null
  showAnalytics: boolean
  onChanged: (message: string) => void
  onError: (message: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [cta, setCta] = useState(
    options?.ctas.find((o) => o.label === campaign.ctaText)?.value ?? '')
  const [promo, setPromo] = useState(campaign.promoCode ?? '')
  const [when, setWhen] = useState('')

  // Object URLs are a leak if they are never revoked, and a preview is opened and
  // closed repeatedly while an admin edits copy.
  useEffect(() => () => { if (previewUrl) URL.revokeObjectURL(previewUrl) }, [previewUrl])

  const togglePreview = async () => {
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl)
      setPreviewUrl(null)
      return
    }
    try {
      setPreviewUrl(await api.blobUrl(
        `/api/v1/admin/campaigns/${campaign.publicId}/preview`))
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'Could not render the preview.')
    }
  }

  const act = async (fn: () => Promise<unknown>, message: string) => {
    setBusy(true)
    try {
      await fn()
      onChanged(message)
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'That did not work.')
    } finally {
      setBusy(false)
    }
  }

  const saveDetails = () => act(
    () => api.post(`/api/v1/admin/campaigns/${campaign.publicId}`, {
      promoCode: promo || null, cta: cta || null,
    }),
    'Saved.')

  const send = () => act(
    () => api.post(`/api/v1/admin/campaigns/${campaign.publicId}/send`, {}),
    'Campaign sent.')

  const schedule = () => act(
    () => api.post(`/api/v1/admin/campaigns/${campaign.publicId}/schedule`, {
      sendAt: new Date(when).toISOString(),
    }),
    'Campaign scheduled.')

  const cancel = () => act(
    () => api.post(`/api/v1/admin/campaigns/${campaign.publicId}/cancel`, {}),
    'Campaign cancelled.')

  const uploadBanner = async (file: File) => {
    const form = new FormData()
    form.append('file', file)
    await act(
      () => api.upload(`/api/v1/admin/campaigns/${campaign.publicId}/banner`, form),
      'Banner uploaded.')
  }

  return (
    <div className="surface p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-3">
            <span className="font-semibold">{campaign.title}</span>
            <Badge tone={STATUS_TONE[campaign.status]}>{campaign.status}</Badge>
          </div>
          <div className="mt-1 text-[13px] text-chalk-muted">{campaign.subject}</div>
          <div className="mt-1 text-[12px] text-chalk-faint">
            {campaign.audienceLabel}
            {campaign.scheduledAt && ` · sends ${dateTime(campaign.scheduledAt)}`}
            {campaign.completedAt && ` · finished ${dateTime(campaign.completedAt)}`}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" size="md" onClick={() => void togglePreview()}>
            {previewUrl ? 'Hide preview' : 'Preview'}
          </Button>
          {campaign.status === 'DRAFT' && (
            <Button variant="secondary" size="md" onClick={() => setOpen((v) => !v)}>
              {open ? 'Close' : 'Edit'}
            </Button>
          )}
        </div>
      </div>

      {showAnalytics && <Analytics campaign={campaign} />}

      {previewUrl && (
        /*
         * The real rendered email, in an iframe.
         *
         * Fetched through api.blobUrl rather than pointed at the endpoint directly: auth
         * here is a bearer token in a header, and an <iframe src> cannot send one, so a
         * direct src would simply 401. The blob is the response body this session already
         * fetched with its own credentials.
         *
         * Sandboxed because it is HTML assembled from admin-written copy — trusted input,
         * but there is no reason for it to be able to run anything.
         */
        <iframe
          title={`Preview of ${campaign.title}`}
          sandbox=""
          className="mt-4 h-[620px] w-full rounded-edge border border-ink-400 bg-white"
          src={previewUrl}
        />
      )}

      {open && campaign.status === 'DRAFT' && (
        <div className="mt-5 space-y-4 border-t border-ink-400 pt-5">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Promo code" hint="Optional.">
              {(p) => (
                <Input {...p} value={promo} onChange={(e) => setPromo(e.target.value)}
                       placeholder="FRIDAY10" />
              )}
            </Field>
            <Field label="Button" hint="Chosen from real site pages, so it cannot 404.">
              {(p) => (
                <Select {...p} value={cta} onChange={(e) => setCta(e.target.value)}>
                  <option value="">No button</option>
                  {(options?.ctas ?? []).map((o) => (
                    <option key={o.value} value={o.value}>{o.label} → {o.detail}</option>
                  ))}
                </Select>
              )}
            </Field>
          </div>

          <Field label="Banner" hint="PNG, JPEG, GIF or WebP, up to 2MB.">
            {(p) => (
              <input {...p} type="file" accept="image/png,image/jpeg,image/gif,image/webp"
                     className="text-[13px] text-chalk-muted"
                     onChange={(e) => {
                       const f = e.target.files?.[0]
                       if (f) void uploadBanner(f)
                     }} />
            )}
          </Field>

          <Button variant="secondary" onClick={() => void saveDetails()} disabled={busy}>
            Save details
          </Button>

          <div className="border-t border-ink-400 pt-5">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Schedule for" hint="Your local time.">
                {(p) => (
                  <Input {...p} type="datetime-local" value={when}
                         onChange={(e) => setWhen(e.target.value)} />
                )}
              </Field>
            </div>
            <div className="mt-4 flex flex-wrap gap-3">
              <Button variant="primary" onClick={() => void send()} disabled={busy}>
                {busy ? 'Working…' : 'Send now'}
              </Button>
              <Button variant="secondary" onClick={() => void schedule()}
                      disabled={busy || !when}>
                Schedule
              </Button>
              <Button variant="secondary" onClick={() => void cancel()} disabled={busy}>
                Cancel campaign
              </Button>
            </div>
            <p className="mt-3 text-[12.5px] text-chalk-faint">
              Sending cannot be undone. Preview first.
            </p>
          </div>
        </div>
      )}

      {campaign.status === 'SCHEDULED' && (
        <div className="mt-4">
          <Button variant="secondary" size="md" onClick={() => void cancel()} disabled={busy}>
            Cancel scheduled send
          </Button>
        </div>
      )}
    </div>
  )
}

/* ---------------------------------------------------------------- analytics */

function Analytics({ campaign }: { campaign: Campaign }) {
  const s = campaign.stats
  const pct = (n: number) => (s.sent > 0 ? `${Math.round((n / s.sent) * 100)}%` : '—')
  return (
    <>
      <dl className="mt-4 grid grid-cols-2 gap-px overflow-hidden rounded-edge bg-ink-400
                     shadow-sm ring-1 ring-ink-400 sm:grid-cols-6">
        <Stat label="Recipients" value={String(s.total)} />
        <Stat label="Sent" value={String(s.sent)} />
        <Stat label="Failed" value={String(s.failed)} warn={s.failed > 0} />
        <Stat label="Opened" value={`${s.opened} (${pct(s.opened)})`} />
        <Stat label="Clicked" value={`${s.clicked} (${pct(s.clicked)})`} accent />
        <Stat label="Unsubscribed" value={`${s.unsubscribed} (${pct(s.unsubscribed)})`}
              warn={s.unsubscribed > 0} />
      </dl>
      {/*
        Stated beside the numbers rather than in a footnote nobody reads. Opens are the
        least trustworthy figure here and the one people quote most.
      */}
      <p className="mt-2 text-[12px] leading-relaxed text-chalk-faint">
        <strong className="text-chalk-muted">Sent</strong> means the mail server accepted
        it, not that it reached an inbox — that needs bounce webhooks from the provider,
        which are not wired up.{' '}
        <strong className="text-chalk-muted">Opened</strong> undercounts anyone with images
        off and overcounts anyone whose provider pre-fetches images.{' '}
        <strong className="text-chalk-muted">Clicked</strong> is the only one that needed a
        deliberate action.{' '}
        <strong className="text-chalk-muted">Unsubscribed</strong> counts people who left
        marketing through this campaign specifically; they still receive order email.
      </p>
    </>
  )
}

function Stat({ label, value, accent, warn }: {
  label: string; value: string; accent?: boolean; warn?: boolean
}) {
  return (
    <div className="bg-ink-500 px-4 py-3">
      <dt className="stamp">{label}</dt>
      <dd className={`tnum mt-1 text-lg font-semibold ${
        warn ? 'text-warn' : accent ? 'text-brand-300' : 'text-chalk'}`}>
        {value}
      </dd>
    </div>
  )
}
