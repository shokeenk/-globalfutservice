import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { LuCircleAlert, LuPlus, LuTrash2 } from 'react-icons/lu'
import { ApiError, api } from '../../../lib/api'
import { dateTime } from '../../../lib/format'
import type {
  CoinMarketCode, CoinPricingLimits, CoinPricingPreview, CoinPreviewRow, CoinStructure,
} from '../../../lib/types'
import { controlClass } from '../campaigns/fields'
import { AdminButton } from '../ui/controls'
import { Modal } from '../ui/Modal'
import { TabRow } from '../ui/Tabs'
import {
  describeChanges, describeK, hasErrors, perMillionAmount, perMillionMinor, perMillionText, stepIsWholeMinorUnit,
  toDraft, toRequest,
  validate, type StructureDraft, CURRENCY_SYMBOL,
} from './coinPricing'

export const TITLES: Record<CoinMarketCode, string> = {
  PC: 'PC',
  CONSOLE: 'PlayStation + Xbox (shared market)',
}

const USED_FOR: Record<CoinMarketCode, string> = {
  PC: 'Prices coins for PC.',
  CONSOLE: 'Prices coins for PlayStation and Xbox: one market, one set of prices.',
}

/**
 * One coin price structure, editable: its slider, its prices per currency with any volume
 * brackets, a calculator that asks the server what a customer would pay, and a save that
 * says exactly what it changes before it changes it.
 *
 * <p>Errors are this page's own checks, word for word the server's, shown under the field
 * as it is typed. Warnings -- a bracket that makes more coins cost less, a price that looks
 * typed in the wrong unit, a minimum below FUT Transfer's -- come from the server, which
 * sees the other structure and the version live now. They never stop a save.
 */
export function StructureCard({
  market, live, limits, currencies, onSaved,
}: {
  market: CoinMarketCode
  live: CoinStructure | null
  limits: CoinPricingLimits
  currencies: string[]
  onSaved: (saved: CoinStructure) => void
}) {
  const titleId = useId()
  const [draft, setDraft] = useState<StructureDraft>(() => toDraft(live, limits, currencies))
  const [tab, setTab] = useState(currencies[0] ?? 'INR')
  const [checked, setChecked] = useState<CoinPricingPreview | null>(null)
  const [amount, setAmount] = useState(() => String(defaultAmount(live, limits)))
  const [previewCurrency, setPreviewCurrency] = useState(currencies[0] ?? 'INR')
  const [previewFailed, setPreviewFailed] = useState(false)
  const [confirm, setConfirm] = useState<{ changes: string[]; warnings: string[] } | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string[] | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [showHistory, setShowHistory] = useState(false)
  const [blocked, setBlocked] = useState(false)

  // A new live version -- this card's save, or a reload -- starts the draft again from it.
  useEffect(() => {
    setDraft(toDraft(live, limits, currencies))
  }, [live, limits, currencies])

  const errors = useMemo(() => validate(draft, limits), [draft, limits])
  const invalid = hasErrors(errors)
  const changes = useMemo(() => describeChanges(live, draft), [live, draft])

  const amountK = /^\d+$/.test(amount.trim()) ? Number(amount.trim()) : null
  const sliderMin = Number(draft.minK)
  const sliderMax = Number(draft.maxK)
  const sliderStep = Number(draft.stepK)
  const amountProblem = invalid ? null
    : amountK === null || amountK < sliderMin || amountK > sliderMax || (amountK - sliderMin) % sliderStep !== 0
      ? `Pick an amount the slider stops on: ${describeK(sliderMin)} to ${describeK(sliderMax)} in steps of `
        + `${describeK(sliderStep)}.`
      : null

  /*
   * The server checks the draft and prices the chosen amount, a moment after typing stops.
   * Only the newest answer is kept: an older request that lands late is not what is on screen.
   */
  const asked = useRef(0)
  useEffect(() => {
    if (invalid) {
      setChecked(null)
      return
    }
    const id = ++asked.current
    const timer = window.setTimeout(() => {
      api.post<CoinPricingPreview>(`/api/v1/admin/coin-pricing/${market}/preview`,
        toRequest(draft, amountK !== null && !amountProblem ? [amountK] : undefined))
        .then((result) => {
          if (id !== asked.current) return
          setChecked(result)
          setPreviewFailed(false)
        })
        .catch(() => {
          if (id === asked.current) setPreviewFailed(true)
        })
    }, 350)
    return () => window.clearTimeout(timer)
  }, [draft, invalid, market, amountK, amountProblem])

  const warnings = checked?.warnings ?? live?.warnings ?? []
  const row = checked?.rows.find((r) => r.currency === previewCurrency && r.amountK === amountK) ?? null

  const set = (patch: Partial<StructureDraft>) => {
    setNotice(null)
    setBlocked(false)
    setDraft((d) => ({ ...d, ...patch }))
  }
  const setRate = (currency: string, next: StructureDraft['rates'][string]) =>
    set({ rates: { ...draft.rates, [currency]: next } })

  /** Checked again with the server first, so the confirmation lists the warnings as they stand now. */
  async function review() {
    setSaveError(null)
    if (invalid) {
      setBlocked(true)
      return
    }
    try {
      const fresh = await api.post<CoinPricingPreview>(`/api/v1/admin/coin-pricing/${market}/preview`, toRequest(draft))
      if (fresh.errors.length > 0) {
        setSaveError(fresh.errors)
        return
      }
      setConfirm({ changes, warnings: fresh.warnings })
    } catch (e) {
      setSaveError([e instanceof ApiError ? e.message : 'Could not check these prices. Nothing was saved.'])
    }
  }

  async function save() {
    setSaving(true)
    setSaveError(null)
    try {
      const saved = await api.put<CoinStructure>(`/api/v1/admin/coin-pricing/${market}`, toRequest(draft))
      setConfirm(null)
      setNotice(`Saved. ${market === 'PC' ? 'PC' : 'PlayStation and Xbox'} prices apply to the next quote.`)
      onSaved(saved)
    } catch (e) {
      setConfirm(null)
      setSaveError(e instanceof ApiError ? e.message.split('\n') : ['Could not save. Nothing was changed.'])
    } finally {
      setSaving(false)
    }
  }

  const current = draft.rates[tab] ?? { base: '', brackets: [] }
  const currentErrors = errors.rates[tab]
  const basePerMillion = perMillionMinor(current.base)

  return (
    <section aria-labelledby={titleId}
             className="rounded-admin-card border border-admin-line bg-white shadow-admin-card">
      <header className="border-b border-admin-line px-5 py-4">
        <h2 id={titleId} className="text-[16px] font-semibold text-admin-ink">{TITLES[market]}</h2>
        <p className="mt-0.5 text-[12.5px] text-admin-muted">{USED_FOR[market]}</p>
        <p className="mt-2 text-[12px] text-admin-faint" data-testid="last-changed">
          {live
            ? live.setBy
              ? <>Last changed by <span className="font-medium text-admin-ink">{live.setBy}</span> at {dateTime(live.validFrom)}</>
              : <>Set up at {dateTime(live.validFrom)}, when coin pricing moved to these structures</>
            : 'Not priced yet.'}
          {live && (
            <>
              {' · '}
              <button type="button" onClick={() => setShowHistory(true)}
                      className="font-medium text-admin-red-text underline-offset-2 hover:underline">
                Change history
              </button>
            </>
          )}
        </p>
      </header>

      <div className="space-y-6 px-5 py-5">
        <Group title="Slider">
          <div className="grid gap-3 sm:grid-cols-3">
            <NumberField id={`${market}-min`} label="Minimum (K)" value={draft.minK} error={errors.minK}
                         onChange={(minK) => set({ minK })} />
            <NumberField id={`${market}-max`} label="Maximum (K)" value={draft.maxK} error={errors.maxK}
                         helper={`At most ${describeK(limits.maxCapK)}.`} onChange={(maxK) => set({ maxK })} />
            <NumberField id={`${market}-step`} label="Step (K)" value={draft.stepK} error={errors.stepK}
                         helper="Multiples of 10K." onChange={(stepK) => set({ stepK })} />
          </div>
          <div className="mt-3">
            <NumberField id={`${market}-picks`} label="Quick-pick chips (K)" value={draft.picks} error={errors.picks}
                         inputMode="text"
                         helper={`Amounts in K, separated by commas. Default: ${limits.defaultQuickPicksK.join(', ')}.`}
                         onChange={(picks) => set({ picks })} />
          </div>
        </Group>

        <Group title="Prices per 100,000 coins">
          <div className="overflow-hidden rounded-admin-control border border-admin-line">
            <TabRow label={`${TITLES[market]} currency`} active={tab} onChange={setTab}
                    items={currencies.map((c) => ({
                      key: c, label: c, count: null,
                      ...(errors.rates[c] ? { icon: LuCircleAlert, iconClass: 'text-admin-red-text' } : {}),
                    }))} />
          </div>

          <div className="mt-3">
            <NumberField id={`${market}-${tab}-base`} label={`Base price per 100K (${tab})`} value={current.base}
                         error={currentErrors?.base} inputMode="decimal"
                         prefix={CURRENCY_SYMBOL[tab]}
                         suffix={perMillionText(current.base, tab)}
                         helper="For every amount below the first bracket."
                         onChange={(base) => setRate(tab, { ...current, base })} />
            {basePerMillion !== null && basePerMillion > 0 && !stepIsWholeMinorUnit(basePerMillion) && (
              <p className="mt-1 text-[11.5px] text-admin-amber-ink">
                A 10,000-coin step is {CURRENCY_SYMBOL[tab]}{(basePerMillion / 10_000).toString()} at this price, not a
                whole {tab === 'INR' ? 'paisa' : 'cent'}: totals stay exact, but steps on the slider differ by one
                either way.
              </p>
            )}
          </div>

          <div className="mt-4">
            <p className="text-[13px] font-medium text-admin-ink">Volume brackets <span className="font-normal text-admin-faint">(optional)</span></p>
            <p className="text-[11.5px] text-admin-muted">From an amount on, every coin in the order is at that price.</p>
            {current.brackets.length > 0 && (
              <table className="mt-2 w-full text-[12.5px]">
                <thead>
                  <tr className="text-left text-admin-faint">
                    <th className="pb-1 pr-2 font-medium">From (K)</th>
                    <th className="pb-1 pr-2 font-medium">Price per 100K</th>
                    <th className="pb-1 pr-2 font-medium">Per 1M</th>
                    <th className="pb-1"><span className="sr-only">Remove</span></th>
                  </tr>
                </thead>
                <tbody>
                  {current.brackets.map((b, i) => {
                    const e = currentErrors?.brackets[i]
                    const update = (patch: Partial<typeof b>) => setRate(tab, {
                      ...current, brackets: current.brackets.map((x, j) => (j === i ? { ...x, ...patch } : x)),
                    })
                    return (
                      <tr key={i} className="align-top">
                        <td className="py-1 pr-2">
                          <CellInput label={`${tab} bracket ${i + 1} from (K)`} value={b.fromK} error={e?.fromK}
                                     onChange={(fromK) => update({ fromK })} />
                        </td>
                        <td className="py-1 pr-2">
                          <CellInput label={`${tab} bracket ${i + 1} price per 100K`} value={b.per100k}
                                     error={e?.per100k} inputMode="decimal" onChange={(per100k) => update({ per100k })} />
                        </td>
                        <td className="whitespace-nowrap py-2 pr-2 tabular-nums text-admin-muted">
                          {perMillionAmount(b.per100k, tab)}
                        </td>
                        <td className="py-1 text-right">
                          <button type="button" aria-label={`Remove ${tab} bracket ${i + 1}`}
                                  onClick={() => setRate(tab, { ...current, brackets: current.brackets.filter((_, j) => j !== i) })}
                                  className="grid h-[31px] w-8 place-items-center rounded-admin-control text-admin-faint
                                             hover:bg-admin-page hover:text-admin-red-text">
                            <LuTrash2 aria-hidden="true" className="h-4 w-4" />
                          </button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            )}
            {currentErrors?.list && <p className="mt-1 text-[12px] font-medium text-admin-red-text">{currentErrors.list}</p>}
            <AdminButton size="sm" className="mt-2" disabled={current.brackets.length >= limits.maxBrackets}
                         onClick={() => setRate(tab, { ...current, brackets: [...current.brackets, { fromK: '', per100k: '' }] })}>
              <LuPlus aria-hidden="true" className="h-4 w-4" />
              Add {tab} bracket
            </AdminButton>
          </div>
        </Group>

        <Group title="Preview">
          <div className="grid gap-3 sm:grid-cols-2">
            <NumberField id={`${market}-preview-amount`} label="Amount (K)" value={amount} error={amountProblem ?? undefined}
                         onChange={setAmount} />
            <div>
              <label htmlFor={`coin-${market}-preview-currency`} className="mb-1 block text-[13px] font-medium text-admin-ink">
                Currency
              </label>
              <select id={`coin-${market}-preview-currency`} value={previewCurrency}
                      onChange={(e) => setPreviewCurrency(e.target.value)}
                      className={`${controlClass} h-[31px]`}>
                {currencies.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
          </div>
          <PreviewResult row={row} invalid={invalid} failed={previewFailed} amountProblem={amountProblem} />
        </Group>

        {warnings.length > 0 && (
          <div role="status" data-testid="coin-warnings"
               className="rounded-admin-control border border-[#F1D3A8] bg-admin-amber-tint px-4 py-3 text-[12.5px] text-admin-amber-ink">
            <p className="font-semibold">Check before saving</p>
            <ul className="mt-1 list-disc space-y-1 pl-4">
              {warnings.map((w) => <li key={w}>{w}</li>)}
            </ul>
          </div>
        )}
        {checked && checked.errors.length > 0 && <ErrorList items={checked.errors} />}
        {saveError && <ErrorList items={saveError} />}
        {blocked && invalid && <ErrorList items={['Fix the errors above before saving.']} />}
        {notice && (
          <p role="status" className="rounded-admin-control bg-admin-green-tint px-4 py-3 text-[13px] text-admin-green-ink">{notice}</p>
        )}

        <div className="flex flex-wrap items-center gap-3 border-t border-admin-line pt-4">
          <AdminButton variant="primary" disabled={saving || changes.length === 0} onClick={() => void review()}>
            Save {market === 'PC' ? 'PC' : 'PlayStation + Xbox'} prices
          </AdminButton>
          <span className="text-[12.5px] text-admin-faint">
            {changes.length === 0 ? 'No changes to save.'
              : `${changes.length} ${changes.length === 1 ? 'change' : 'changes'} not saved yet.`}
          </span>
        </div>
      </div>

      {confirm && (
        <Modal title={`Save ${TITLES[market]} prices?`} onClose={() => setConfirm(null)} width="max-w-[560px]">
          <div className="space-y-4 px-5 py-4 text-[13px] text-admin-ink">
            <div>
              <p className="font-semibold">What changes</p>
              <ul className="mt-1 list-disc space-y-1 pl-4" data-testid="confirm-changes">
                {confirm.changes.map((c) => <li key={c}>{c}</li>)}
              </ul>
            </div>
            {confirm.warnings.length > 0 && (
              <div className="rounded-admin-control bg-admin-amber-tint px-3 py-2 text-admin-amber-ink">
                <p className="font-semibold">Warnings</p>
                <ul className="mt-1 list-disc space-y-1 pl-4">
                  {confirm.warnings.map((w) => <li key={w}>{w}</li>)}
                </ul>
              </div>
            )}
            <p className="text-admin-muted">
              The new prices apply to the next quote. The current version is kept in the change history.
            </p>
          </div>
          <div className="flex justify-end gap-2 border-t border-admin-line px-5 py-3">
            <AdminButton onClick={() => setConfirm(null)}>Cancel</AdminButton>
            <AdminButton variant="primary" disabled={saving} onClick={() => void save()}>
              {saving ? 'Saving…' : 'Save prices'}
            </AdminButton>
          </div>
        </Modal>
      )}

      {showHistory && <History market={market} onClose={() => setShowHistory(false)} />}
    </section>
  )
}

function defaultAmount(live: CoinStructure | null, limits: CoinPricingLimits): number {
  const min = live?.minK ?? limits.vendorMinTransferK
  const max = live?.maxK ?? 1000
  const step = live?.stepK ?? limits.smallestStepK
  return 100 >= min && 100 <= max && (100 - min) % step === 0 ? 100 : min
}

function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <h3 className="mb-2 text-[12px] font-semibold uppercase tracking-[0.08em] text-admin-faint">{title}</h3>
      {children}
    </div>
  )
}

function NumberField({
  id, label, value, onChange, error, helper, inputMode = 'numeric', prefix, suffix,
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  error?: string
  helper?: string
  inputMode?: 'numeric' | 'decimal' | 'text'
  prefix?: string
  suffix?: string
}) {
  const inputId = `coin-${id}`
  const described = [helper && `${inputId}-helper`, error && `${inputId}-error`].filter(Boolean).join(' ') || undefined
  return (
    <div>
      <label htmlFor={inputId} className="mb-1 block text-[13px] font-medium text-admin-ink">{label}</label>
      <div className="flex items-center gap-2">
        {prefix && <span aria-hidden="true" className="text-[13px] text-admin-muted">{prefix}</span>}
        <input id={inputId} type="text" inputMode={inputMode} value={value} onChange={(e) => onChange(e.target.value)}
               aria-invalid={Boolean(error) || undefined} aria-describedby={described}
               className={`${controlClass} h-[31px] tabular-nums`} />
        {suffix && <span className="whitespace-nowrap text-[12px] tabular-nums text-admin-muted">= {suffix}</span>}
      </div>
      {helper && <p id={`${inputId}-helper`} className="mt-1 text-[11px] text-admin-muted">{helper}</p>}
      {error && <p id={`${inputId}-error`} className="mt-1 text-[12px] font-medium text-admin-red-text">{error}</p>}
    </div>
  )
}

function CellInput({
  label, value, onChange, error, inputMode = 'numeric',
}: {
  label: string
  value: string
  onChange: (value: string) => void
  error?: string
  inputMode?: 'numeric' | 'decimal'
}) {
  const id = useId()
  return (
    <div>
      <input aria-label={label} type="text" inputMode={inputMode} value={value} onChange={(e) => onChange(e.target.value)}
             aria-invalid={Boolean(error) || undefined} aria-describedby={error ? id : undefined}
             className={`${controlClass} h-[31px] tabular-nums`} />
      {error && <p id={id} className="mt-1 text-[11.5px] font-medium text-admin-red-text">{error}</p>}
    </div>
  )
}

function ErrorList({ items }: { items: string[] }) {
  return (
    <div role="alert" className="rounded-admin-control bg-admin-red-tint px-4 py-3 text-[12.5px] text-admin-red-ink">
      <ul className="list-disc space-y-1 pl-4">{items.map((e) => <li key={e}>{e}</li>)}</ul>
    </div>
  )
}

/** The customer's price for the chosen amount, as the server's quote engine works it out. */
function PreviewResult({
  row, invalid, failed, amountProblem,
}: {
  row: CoinPreviewRow | null
  invalid: boolean
  failed: boolean
  amountProblem: string | null
}) {
  if (invalid) return <p className="mt-3 text-[12.5px] text-admin-muted">Fix the errors above to see a price.</p>
  if (amountProblem) return null
  if (failed && !row) return <p className="mt-3 text-[12.5px] text-admin-red-text">The preview did not load. It will try again as you type.</p>
  if (!row) return <p className="mt-3 text-[12.5px] text-admin-muted">Working out the price…</p>
  return (
    <dl className="mt-3 divide-y divide-admin-line rounded-admin-control border border-admin-line text-[13px]"
        data-testid="coin-preview">
      <Line label="Rate" value={`${row.per100kFormatted} per 100K · ${row.perMillionFormatted} per 1M`} />
      <Line label="Before market tax" value={row.coinPriceFormatted} />
      <Line label={row.marketTaxLabel}
            value={row.marketTaxIncluded ? 'Included in the price' : `+${row.marketTaxFormatted}`} />
      <Line label="After market tax" value={row.afterTaxFormatted} strong />
      <Line label="A guest pays, card fee included" value={row.guestTotalFormatted} />
    </dl>
  )
}

function Line({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3 px-3 py-2">
      <dt className="text-admin-muted">{label}</dt>
      <dd className={`tabular-nums ${strong ? 'font-semibold text-admin-ink' : 'text-admin-ink'}`}>{value}</dd>
    </div>
  )
}

/** Every version of this structure, newest first: who set it, when, and what it said. */
function History({ market, onClose }: { market: CoinMarketCode; onClose: () => void }) {
  const [versions, setVersions] = useState<CoinStructure[] | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    api.get<CoinStructure[]>(`/api/v1/admin/coin-pricing/${market}/history`)
      .then(setVersions)
      .catch(() => setFailed(true))
  }, [market])

  return (
    <Modal title={`${TITLES[market]}: change history`} onClose={onClose} width="max-w-[640px]">
      <div className="px-5 py-4 text-[13px]">
        {failed && <p className="text-admin-red-text">The history did not load. Close this and try again.</p>}
        {!versions && !failed && <p className="text-admin-muted">Loading…</p>}
        {versions && (
          <ol className="space-y-4" data-testid="coin-history">
            {versions.map((v) => (
              <li key={v.version ?? v.validFrom} className="rounded-admin-control border border-admin-line px-4 py-3">
                <p className="font-medium text-admin-ink">
                  {dateTime(v.validFrom)} · {v.setBy ?? 'set up when coin pricing moved to structures'}
                  {!v.validTo && <span className="ml-2 rounded bg-admin-green-tint px-1.5 py-0.5 text-[11px] text-admin-green-ink">Live</span>}
                </p>
                <p className="mt-1 text-admin-muted">
                  {describeK(v.minK)} to {describeK(v.maxK)} in steps of {describeK(v.stepK)} · quick picks{' '}
                  {v.quickPicksK.length === 0 ? 'none' : v.quickPicksK.map(describeK).join(', ')}
                </p>
                <ul className="mt-1 space-y-0.5 text-admin-ink">
                  {v.rates.filter((r) => r.base).map((r) => (
                    <li key={r.currency}>
                      {r.currency}: {r.base!.per100kFormatted} per 100K ({r.base!.perMillionFormatted} per 1M)
                      {r.brackets.map((b) => `; from ${describeK(b.fromK)} ${b.per100kFormatted} (${b.perMillionFormatted})`).join('')}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ol>
        )}
      </div>
    </Modal>
  )
}

