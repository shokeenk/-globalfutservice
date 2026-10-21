import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api, ApiError } from '../../../lib/api'
import { POPUP_PAGES } from '../../../lib/popups'
import type { PopupFrequency, PopupPage, PopupTrigger, PopupType, PublicPopup } from '../../../lib/popups'
import { PopupCard, PopupModal } from '../../../components/popups/PopupViews'
import { AdminButton } from '../ui/controls'
import { PAGE_LABELS } from './types'
import type { AdminPopup, PopupChange } from './types'

/**
 * Create or edit one popup: its words in three languages, up to two buttons, where and
 * when it shows, and its images -- with a live preview at desktop and phone size.
 *
 * <p>Saving never publishes and never switches anything on. Those are separate buttons on
 * the list, so an edit to a live popup is a choice to make it live again, not a side effect.
 */

type Lang = 'en' | 'es' | 'fr'
const LANGS: Lang[] = ['en', 'es', 'fr']

type CopyForm = { headline: string; body: string; referral: string; trust: string }
type ButtonForm = { en: string; es: string; fr: string; destination: string }

type Form = {
  name: string
  type: PopupType
  copy: Record<Lang, CopyForm>
  buttons: ButtonForm[]
  targetPages: PopupPage[]
  targetPaths: string
  triggerType: PopupTrigger
  triggerValue: string
  triggerEvent: string
  startsAt: string
  endsAt: string
  frequency: PopupFrequency
  frequencyDays: string
  priority: string
}

function toLocalInput(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formFrom(p?: AdminPopup | null): Form {
  const copy = (l: Lang): CopyForm => {
    const c = p?.copy?.[l]
    return { headline: c?.headline ?? '', body: c?.body ?? '', referral: c?.referral ?? '', trust: c?.trust ?? '' }
  }
  return {
    name: p?.name ?? '',
    type: p?.type ?? 'CENTER_MODAL',
    copy: { en: copy('en'), es: copy('es'), fr: copy('fr') },
    buttons: (p?.buttons ?? []).map((b) => ({ en: b.label.en ?? '', es: b.label.es ?? '', fr: b.label.fr ?? '',
      destination: b.destination })),
    targetPages: p?.targetPages ?? [],
    targetPaths: (p?.targetPaths ?? []).join('\n'),
    triggerType: p?.triggerType ?? 'PAGE_LOAD',
    triggerValue: p?.triggerValue != null ? String(p.triggerValue) : '',
    triggerEvent: p?.triggerEvent ?? '',
    startsAt: toLocalInput(p?.startsAt),
    endsAt: toLocalInput(p?.endsAt),
    frequency: p?.frequency ?? 'SESSION',
    frequencyDays: p?.frequencyDays != null ? String(p.frequencyDays) : '7',
    priority: String(p?.priority ?? 0),
  }
}

const blank = (s: string) => (s.trim() ? s.trim() : null)
const num = (s: string) => (s.trim() === '' ? null : Number(s))

/** The form as the API takes it. */
export function inputFrom(f: Form) {
  const copy = (l: Lang) => {
    const c = f.copy[l]
    const out = { headline: blank(c.headline), body: blank(c.body), referral: blank(c.referral), trust: blank(c.trust) }
    return l !== 'en' && Object.values(out).every((v) => v == null) ? null : out
  }
  return {
    name: f.name,
    type: f.type,
    copy: { en: copy('en'), es: copy('es'), fr: copy('fr') },
    buttons: f.buttons.map((b) => ({ label: { en: blank(b.en), es: blank(b.es), fr: blank(b.fr) }, destination: b.destination })),
    targetPages: f.targetPages,
    targetPaths: f.targetPaths.split(/\s+/).filter(Boolean),
    triggerType: f.triggerType,
    triggerValue: f.triggerType === 'DELAY' || f.triggerType === 'SCROLL' ? num(f.triggerValue) : null,
    triggerEvent: f.triggerType === 'STEP' ? blank(f.triggerEvent) : null,
    startsAt: f.startsAt ? new Date(f.startsAt).toISOString() : null,
    endsAt: f.endsAt ? new Date(f.endsAt).toISOString() : null,
    frequency: f.frequency,
    frequencyDays: f.frequency === 'DAYS' ? num(f.frequencyDays) : null,
    priority: num(f.priority) ?? 0,
  }
}

const INPUT = 'h-10 w-full rounded-admin-control border border-admin-line bg-white px-3 text-[13px] text-admin-ink focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20'

function Row({ label, error, hint, children }: { label: string; error?: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block space-y-1">
      <span className="block text-[12.5px] font-medium text-admin-ink">{label}</span>
      {children}
      {error ? <span role="alert" className="block text-[12px] text-admin-red-ink">{error}</span>
        : hint ? <span className="block text-[12px] text-admin-muted">{hint}</span> : null}
    </label>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-admin-card border border-admin-line bg-white p-4 shadow-admin-card">
      <h2 className="mb-3 text-[13.5px] font-semibold text-admin-ink">{title}</h2>
      <div className="space-y-3">{children}</div>
    </section>
  )
}

export function PopupEditor({ popup, steps, onSaved, onClose }: {
  popup: AdminPopup | null
  steps: string[]
  onSaved: (p: AdminPopup) => void
  onClose: () => void
}) {
  const [form, setForm] = useState<Form>(() => formFrom(popup))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [message, setMessage] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [lang, setLang] = useState<Lang>('en')
  const [device, setDevice] = useState<'desktop' | 'phone'>('desktop')
  const [uploading, setUploading] = useState<'desktop' | 'mobile' | null>(null)
  const [changes, setChanges] = useState<PopupChange[]>([])

  useEffect(() => {
    if (!popup) return
    api.get<PopupChange[]>(`/api/v1/admin/popups/${popup.id}/changes`).then(setChanges).catch(() => setChanges([]))
  }, [popup])

  const set = <K extends keyof Form>(k: K, v: Form[K]) => setForm((f) => ({ ...f, [k]: v }))
  const setCopy = (l: Lang, k: keyof CopyForm, v: string) =>
    setForm((f) => ({ ...f, copy: { ...f.copy, [l]: { ...f.copy[l], [k]: v } } }))

  async function save() {
    setSaving(true)
    setErrors({})
    setMessage(null)
    try {
      const body = inputFrom(form)
      const saved = popup
        ? await api.put<AdminPopup>(`/api/v1/admin/popups/${popup.id}`, body)
        : await api.post<AdminPopup>('/api/v1/admin/popups', body)
      onSaved(saved)
      setMessage('Saved.')
    } catch (e) {
      if (e instanceof ApiError) {
        setErrors(e.fieldErrors)
        setMessage(Object.keys(e.fieldErrors).length ? 'Please check the highlighted fields.' : e.message)
      } else setMessage('That did not save. Try again.')
    } finally {
      setSaving(false)
    }
  }

  async function upload(slot: 'desktop' | 'mobile', file: File | undefined) {
    if (!popup || !file) return
    setUploading(slot)
    setMessage(null)
    try {
      const data = new FormData()
      data.append('file', file)
      onSaved(await api.upload<AdminPopup>(`/api/v1/admin/popups/${popup.id}/images/${slot}`, data))
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : 'That image did not upload.')
    } finally {
      setUploading(null)
    }
  }

  async function removeMobile() {
    if (!popup) return
    onSaved(await api.del<AdminPopup>(`/api/v1/admin/popups/${popup.id}/images/mobile`))
  }

  /** The form as the storefront would draw it, right now. */
  const previewPopup = useMemo<Pick<PublicPopup, 'id' | 'copy' | 'buttons' | 'desktopImage' | 'mobileImage'>>(() => {
    const input = inputFrom(form)
    return {
      id: popup?.id ?? 'preview',
      copy: { en: input.copy.en ?? {}, es: input.copy.es, fr: input.copy.fr },
      buttons: input.buttons.map((b) => ({ label: b.label, destination: b.destination })),
      desktopImage: popup?.desktopImage ?? null,
      mobileImage: popup?.mobileImage ?? null,
    }
  }, [form, popup])

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,460px)] xl:items-start" data-testid="popup-editor">
      <div className="space-y-4">
        <Section title="Popup">
          <Row label="Name (only staff see it)" error={errors.name}>
            <input className={INPUT} value={form.name} maxLength={120} onChange={(e) => set('name', e.target.value)} />
          </Row>
          <Row label="Type" error={errors.type}>
            <select className={INPUT} value={form.type} onChange={(e) => set('type', e.target.value as PopupType)}>
              <option value="CENTER_MODAL">Centred modal</option>
              <option value="BOTTOM_RIGHT">Bottom-right card</option>
            </select>
          </Row>
          <Row label="Priority (higher shows first)" error={errors.priority}>
            <input className={INPUT} type="number" min={0} max={1000} value={form.priority}
                   onChange={(e) => set('priority', e.target.value)} />
          </Row>
        </Section>

        <Section title="Words">
          <div role="group" aria-label="Language" className="flex gap-1">
            {LANGS.map((l) => (
              <AdminButton key={l} size="sm" variant={lang === l ? 'soft' : 'outline'} aria-pressed={lang === l}
                           onClick={() => setLang(l)}>
                {l.toUpperCase()}{l === 'en' ? ' (required)' : ''}
              </AdminButton>
            ))}
          </div>
          {lang !== 'en' && <p className="text-[12px] text-admin-muted">Left empty, each line shows in English.</p>}
          {(['headline', 'body', 'referral', 'trust'] as const).map((k) => (
            <Row key={k} label={{ headline: 'Headline', body: 'Body', referral: 'Referral line', trust: 'Trust line' }[k]}
                 error={errors[`copy.${lang}.${k}`]}>
              {k === 'body'
                ? <textarea className={`${INPUT} h-24 py-2`} value={form.copy[lang][k]} maxLength={600}
                            onChange={(e) => setCopy(lang, k, e.target.value)} />
                : <input className={INPUT} value={form.copy[lang][k]} maxLength={k === 'headline' ? 120 : 200}
                         onChange={(e) => setCopy(lang, k, e.target.value)} />}
            </Row>
          ))}
        </Section>

        <Section title="Buttons (up to 2)">
          {form.buttons.map((b, i) => (
            <div key={i} className="space-y-2 rounded-admin-control border border-admin-line p-3">
              <div className="grid gap-2 sm:grid-cols-3">
                {LANGS.map((l) => (
                  <Row key={l} label={`Label ${l.toUpperCase()}`} error={l === 'en' ? errors[`buttons.${i}.label`] : undefined}>
                    <input className={INPUT} value={b[l]} maxLength={40}
                           onChange={(e) => set('buttons', form.buttons.map((x, j) => (j === i ? { ...x, [l]: e.target.value } : x)))} />
                  </Row>
                ))}
              </div>
              <Row label="Destination" error={errors[`buttons.${i}.destination`]}
                   hint="A path on this site, e.g. /order, or https://globalfutservices.com/…">
                <input className={INPUT} value={b.destination}
                       onChange={(e) => set('buttons', form.buttons.map((x, j) => (j === i ? { ...x, destination: e.target.value } : x)))} />
              </Row>
              <AdminButton size="sm" onClick={() => set('buttons', form.buttons.filter((_, j) => j !== i))}>Remove button</AdminButton>
            </div>
          ))}
          {errors.buttons && <p role="alert" className="text-[12px] text-admin-red-ink">{errors.buttons}</p>}
          {form.buttons.length < 2 && (
            <AdminButton size="sm" onClick={() => set('buttons', [...form.buttons, { en: '', es: '', fr: '', destination: '/' }])}>
              Add button
            </AdminButton>
          )}
        </Section>

        <Section title="Where">
          <fieldset>
            <legend className="mb-1.5 text-[12.5px] font-medium text-admin-ink">Pages</legend>
            <div className="grid grid-cols-2 gap-1.5 sm:grid-cols-3">
              {POPUP_PAGES.map((p) => (
                <label key={p} className="flex items-center gap-2 text-[13px] text-admin-ink">
                  <input type="checkbox" checked={form.targetPages.includes(p)} className="accent-[#E10B2E]"
                         onChange={(e) => set('targetPages', e.target.checked ? [...form.targetPages, p] : form.targetPages.filter((x) => x !== p))} />
                  {PAGE_LABELS[p]}
                </label>
              ))}
            </div>
            {errors.targetPages && <p role="alert" className="mt-1 text-[12px] text-admin-red-ink">{errors.targetPages}</p>}
          </fieldset>
          <Row label="Selected pages (one path per line)" error={errors.targetPaths} hint="e.g. /help or /coaching">
            <textarea className={`${INPUT} h-20 py-2 font-mono`} value={form.targetPaths}
                      onChange={(e) => set('targetPaths', e.target.value)} />
          </Row>
        </Section>

        <Section title="When">
          <Row label="Trigger">
            <select className={INPUT} value={form.triggerType} onChange={(e) => set('triggerType', e.target.value as PopupTrigger)}>
              <option value="PAGE_LOAD">On page load</option>
              <option value="DELAY">After a number of seconds</option>
              <option value="SCROLL">After scrolling part of the page</option>
              <option value="STEP">At a page step</option>
            </select>
          </Row>
          {(form.triggerType === 'DELAY' || form.triggerType === 'SCROLL') && (
            <Row label={form.triggerType === 'DELAY' ? 'Seconds' : 'Percent of the page'} error={errors.triggerValue}>
              <input className={INPUT} type="number" value={form.triggerValue} onChange={(e) => set('triggerValue', e.target.value)} />
            </Row>
          )}
          {form.triggerType === 'STEP' && (
            <Row label="Step" error={errors.triggerEvent} hint={`The storefront fires: ${steps.join(', ')}`}>
              <input className={INPUT} list="popup-steps" value={form.triggerEvent}
                     onChange={(e) => set('triggerEvent', e.target.value)} />
            </Row>
          )}
          <datalist id="popup-steps">{steps.map((s) => <option key={s} value={s} />)}</datalist>
          <div className="grid gap-3 sm:grid-cols-2">
            <Row label="Starts (your time zone)" error={errors.startsAt}>
              <input className={INPUT} type="datetime-local" value={form.startsAt} onChange={(e) => set('startsAt', e.target.value)} />
            </Row>
            <Row label="Ends" error={errors.endsAt}>
              <input className={INPUT} type="datetime-local" value={form.endsAt} onChange={(e) => set('endsAt', e.target.value)} />
            </Row>
          </div>
          <Row label="How often one visitor sees it">
            <select className={INPUT} value={form.frequency} onChange={(e) => set('frequency', e.target.value as PopupFrequency)}>
              <option value="SESSION">Once per session</option>
              <option value="DAYS">Once every few days</option>
              <option value="EVERY_VISIT">Every visit</option>
            </select>
          </Row>
          {form.frequency === 'DAYS' && (
            <Row label="Days" error={errors.frequencyDays}>
              <input className={INPUT} type="number" min={1} max={365} value={form.frequencyDays}
                     onChange={(e) => set('frequencyDays', e.target.value)} />
            </Row>
          )}
        </Section>

        <Section title="Images">
          {!popup ? (
            <p className="text-[12.5px] text-admin-muted">Save the popup first, then add its images.</p>
          ) : (
            <div className="grid gap-3 sm:grid-cols-2">
              {(['desktop', 'mobile'] as const).map((slot) => {
                const image = slot === 'desktop' ? popup.desktopImage : popup.mobileImage
                return (
                  <div key={slot} className="space-y-2">
                    <p className="text-[12.5px] font-medium text-admin-ink">
                      {slot === 'desktop' ? 'Desktop image (required to publish)' : 'Mobile image (optional)'}
                    </p>
                    {image ? (
                      <img src={`${api.baseUrl}${image.url}`} alt={`${slot} preview`}
                           className="max-h-40 w-full rounded-admin-control border border-admin-line object-contain" />
                    ) : <div className="grid h-24 place-items-center rounded-admin-control border border-dashed border-admin-line text-[12px] text-admin-muted">None</div>}
                    {image && <p className="text-[11.5px] text-admin-muted">{image.width} × {image.height} px, {Math.round(image.sizeBytes / 1024)} KB</p>}
                    {errors[`${slot}Image`] && <p role="alert" className="text-[12px] text-admin-red-ink">{errors[`${slot}Image`]}</p>}
                    <label className="block">
                      <span className="sr-only">{image ? 'Replace' : 'Upload'} {slot} image</span>
                      <input type="file" accept="image/jpeg,image/png,image/webp" disabled={uploading !== null}
                             aria-label={`${image ? 'Replace' : 'Upload'} ${slot} image`}
                             onChange={(e) => void upload(slot, e.target.files?.[0])}
                             className="block w-full text-[12px] text-admin-muted" />
                    </label>
                    {slot === 'mobile' && image && <AdminButton size="sm" onClick={() => void removeMobile()}>Remove mobile image</AdminButton>}
                  </div>
                )
              })}
              <p className="text-[11.5px] text-admin-muted sm:col-span-2">JPG, PNG or WebP, at most 1.5 MB.</p>
            </div>
          )}
        </Section>

        <div className="flex flex-wrap items-center gap-3">
          <AdminButton variant="primary" onClick={() => void save()} disabled={saving}>
            {saving ? 'Saving…' : popup ? 'Save changes' : 'Create draft'}
          </AdminButton>
          <AdminButton onClick={onClose}>Back to the list</AdminButton>
          {message && <p role="status" className="text-[12.5px] text-admin-muted">{message}</p>}
        </div>
        <p className="text-[12px] text-admin-muted">
          Saving never publishes or switches a popup on: those are on the list. An edit is a new version, so
          visitors who closed the old one may see it again.
        </p>

        {changes.length > 0 && (
          <Section title="Changes">
            <ul className="space-y-1 text-[12.5px] text-admin-ink">
              {changes.map((c, i) => (
                <li key={i}>
                  <span className="font-medium">{c.action.replace(/_/g, ' ').toLowerCase()}</span>
                  {c.detail ? ` — ${c.detail}` : ''}
                  <span className="text-admin-muted"> · {c.adminEmail ?? 'unknown'} · {new Date(c.at).toLocaleString('en-GB')}</span>
                </li>
              ))}
            </ul>
          </Section>
        )}
      </div>

      <section aria-label="Preview" className="space-y-2 xl:sticky xl:top-4">
        <div className="flex items-center justify-between">
          <h2 className="text-[13.5px] font-semibold text-admin-ink">Preview</h2>
          <div role="group" aria-label="Preview size" className="flex gap-1">
            {(['desktop', 'phone'] as const).map((d) => (
              <AdminButton key={d} size="sm" variant={device === d ? 'soft' : 'outline'} aria-pressed={device === d}
                           onClick={() => setDevice(d)}>{d === 'desktop' ? 'Desktop' : 'Phone'}</AdminButton>
            ))}
          </div>
        </div>
        <div data-testid="popup-preview" data-device={device}
             className={`relative mx-auto overflow-hidden rounded-admin-card border border-admin-line bg-ink
                         ${device === 'phone' ? 'h-[680px] w-[390px] max-w-full' : 'h-[520px] w-full'}`}>
          <div className="absolute inset-0 grid place-items-center text-[12px] text-chalk-faint">The page behind it</div>
          {form.type === 'CENTER_MODAL'
            ? <PopupModal popup={previewPopup} lang={lang} onClose={() => {}} onButton={() => {}} preview
                          phone={device === 'phone'} closeLabel="Close" />
            : <PopupCard popup={previewPopup} lang={lang} onClose={() => {}} onButton={() => {}} preview
                         phone={device === 'phone'} closeLabel="Close" />}
        </div>
        <p className="text-[11.5px] text-admin-muted">Showing the {lang.toUpperCase()} words, as saved or as typed.</p>
      </section>
    </div>
  )
}
