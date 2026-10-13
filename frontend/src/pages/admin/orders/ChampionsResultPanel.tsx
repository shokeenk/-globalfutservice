import { useEffect, useState } from 'react'
import { LuTrophy, LuUpload } from 'react-icons/lu'
import { Alert, Button, Checkbox, Field, Input, Select, Textarea } from '../../../components/ui'
import { ApiError, api } from '../../../lib/api'
import { dateTime } from '../../../lib/format'
import { Modal } from '../ui/Modal'
import { REFUND_METHOD_LABEL } from '../ui/status'
import { EMPTY_DRAFT, resultInput, winsLabel, type ResultView, type WinsDraft } from './champs'

const NOTE_MAX = 500
const RAILS: { value: string; label: string }[] = [
  { value: 'UPI', label: 'UPI' },
  { value: 'PAYPAL', label: 'PayPal' },
  { value: 'CRYPTO', label: 'USDT (TRON)' },
  { value: 'PAYOP', label: 'Payop (refund ID)' },
]

/**
 * "Champions Result (Admin Only)", on a Champions order's admin page.
 *
 * <p>Before delivery it shares Wins Achieved with the Actions dropdown -- one value, entered
 * once -- and previews what it settles to. After delivery the wins stay editable until the
 * settlement is Paid or Issued; then everything locks. Method, status and proof are an
 * admin's. Paid needs an explicit confirmation and the refund's reference; Issued adds the
 * credit in the same step.
 */
export function ChampionsResultPanel({
  publicRef, view, isAdmin, draft, onDraft, onChanged,
}: {
  publicRef: string
  view: ResultView
  isAdmin: boolean
  /** Wins Achieved before delivery: the same draft the Actions dropdown edits. */
  draft: WinsDraft
  onDraft: (next: WinsDraft) => void
  /** The result changed: the new view, and the order should be read again for its history. */
  onChanged: (next: ResultView) => void
}) {
  const recorded = view.result
  const locked = recorded?.locked ?? false

  // After delivery, edits are held here until Save.
  const [edit, setEdit] = useState<WinsDraft>(EMPTY_DRAFT)
  useEffect(() => {
    if (!recorded) return
    setEdit({
      level: recorded.belowLowest ? 'BELOW' : recorded.achievedLevel,
      actualWins: recorded.belowLowest ? String(recorded.achievedLevel) : '',
      amount: recorded.belowLowest ? (recorded.settlementMinor / 100).toFixed(2) : '',
      note: '',
    })
  }, [recorded])

  const current = recorded ? edit : draft
  const setCurrent = recorded ? setEdit : onDraft
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [settling, setSettling] = useState<'PAID' | 'ISSUED' | null>(null)

  if (!view.applies) {
    return (
      <div className="mt-6 rounded-panel border border-brand-500/30 bg-brand-500/[0.04] p-5">
        <Header />
        <p className="mt-3 text-[13px] text-chalk-muted">{view.why}</p>
      </div>
    )
  }

  const option = typeof current.level === 'number'
    ? view.options.find((o) => o.level === current.level) ?? null : null
  const below = current.level === 'BELOW'

  async function save(body: Record<string, unknown>, what: string) {
    setBusy(what)
    setError(null)
    try {
      onChanged(await api.put<ResultView>(`/api/v1/admin/orders/${publicRef}/result`, body))
      return true
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'That change could not be saved.')
      return false
    } finally {
      setBusy(null)
    }
  }

  async function saveResult() {
    const built = resultInput({ ...edit, note: edit.note }, view)
    if (!built.ok) {
      setError(built.error)
      return
    }
    if (await save({ ...built.input }, 'save')) {
      setEdit((e) => ({ ...e, note: '' }))
    }
  }

  async function uploadProof(file: File) {
    setBusy('proof')
    setError(null)
    try {
      const form = new FormData()
      form.append('file', file)
      onChanged(await api.upload<ResultView>(`/api/v1/admin/orders/${publicRef}/result/proof`, form))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The screenshot could not be uploaded.')
    } finally {
      setBusy(null)
    }
  }

  async function viewProof() {
    try {
      const url = await api.blobUrl(`/api/v1/admin/orders/${publicRef}/result/proof`)
      window.open(url, '_blank', 'noopener')
    } catch {
      setError('The screenshot could not be opened.')
    }
  }

  const status = recorded?.status ?? 'PENDING'
  const owed = recorded ? recorded.settlementMinor > 0 : (option?.settlementMinor ?? 0) > 0 || below
  const settlementText = below
    ? (current.amount.trim() ? `${view.currency} ${Number(current.amount || 0).toFixed(2)}` : '—')
    : option?.settlementFormatted ?? recorded?.settlementFormatted ?? '—'

  return (
    <div className="mt-6 rounded-panel border border-brand-500/30 bg-brand-500/[0.04] p-5" data-testid="champs-admin">
      <Header />

      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Field label="Wins Ordered">
          {(props) => <Input {...props} value={view.targetLevel != null ? winsLabel(view.targetLevel) : '—'} readOnly disabled />}
        </Field>

        <Field label="Wins Achieved">
          {(props) => (
            <WinsSelect
              {...props}
              view={view}
              value={current.level}
              disabled={locked || busy !== null}
              onChange={(level) => setCurrent({ ...current, level })}
              testId="wins-card"
            />
          )}
        </Field>

        <Field
          label="Settlement Amount"
          hint={below ? `Entered by hand. At most ${view.servicePaidFormatted}.` : 'Auto-calculated based on achieved wins'}
        >
          {(props) => below && !locked
            ? (
              <Input
                {...props}
                inputMode="decimal"
                placeholder="0.00"
                value={current.amount}
                onChange={(e) => setCurrent({ ...current, amount: e.target.value })}
                data-testid="manual-amount"
              />
            )
            : <Input {...props} value={settlementText} readOnly disabled data-testid="settlement-preview" />}
        </Field>

        {below && (
          <Field label="Actual wins" hint={`Fewer than ${view.lowestLevel ?? ''}`}>
            {(props) => (
              <Input
                {...props}
                inputMode="numeric"
                value={current.actualWins}
                disabled={locked}
                onChange={(e) => setCurrent({ ...current, actualWins: e.target.value.replace(/\D/g, '') })}
                data-testid="actual-wins"
              />
            )}
          </Field>
        )}

        <Field label="Settlement Method" hint={methodHint(recorded)}>
          {(props) => (
            <Select
              {...props}
              value={recorded?.method ?? ''}
              disabled={!recorded || !isAdmin || locked || status !== 'PENDING' || busy !== null}
              onChange={(e) => void save({ method: e.target.value }, 'method')}
              data-testid="settlement-method"
            >
              <option value="" disabled>Not chosen yet</option>
              <option value="REFUND" disabled={recorded ? !recorded.refundAllowed : false}>Refund</option>
              <option value="STORE_CREDIT">GFS Store Credit</option>
            </Select>
          )}
        </Field>

        <Field label="Settlement Status">
          {(props) => (
            <Select
              {...props}
              value={status === 'PAID' || status === 'ISSUED' ? status : 'PENDING'}
              disabled={!recorded || !isAdmin || locked || status !== 'PENDING' || !recorded.method || busy !== null}
              onChange={(e) => {
                const next = e.target.value
                if (next === 'PAID' || next === 'ISSUED') setSettling(next)
              }}
              data-testid="settlement-status"
            >
              <option value="PENDING">Pending</option>
              {recorded?.method === 'STORE_CREDIT'
                ? <option value="ISSUED">Issued</option>
                : <option value="PAID">Paid</option>}
            </Select>
          )}
        </Field>
      </div>

      {option && !below && option.settlementMinor > 0 && view.orderedPriceFormatted && (
        <p className="mt-2 text-[12px] text-chalk-faint" data-testid="calculation">
          ({view.orderedPriceFormatted} − {option.achievedPriceFormatted}) × ({view.servicePaidFormatted} ÷{' '}
          {view.orderedPriceFormatted}) = {option.settlementFormatted}. The payment fee is not refunded.
        </p>
      )}

      {recorded && (recorded.status === 'PAID' || recorded.status === 'ISSUED') && (
        <p className="mt-3 text-[12.5px] text-chalk-muted" data-testid="settled-line">
          {recorded.status === 'PAID'
            ? `Refund sent by ${REFUND_METHOD_LABEL[recorded.refundMethod ?? ''] ?? recorded.refundMethod ?? ''}`
              + ` (reference ${recorded.refundReference ?? ''}). Marked Paid`
            : 'Store credit issued'}
          {recorded.settledBy ? ` by ${recorded.settledBy}` : ' automatically'} on {dateTime(recorded.settledAt)}.
          {recorded.pointsRemoved > 0 ? ` ${recorded.pointsRemoved} points taken back.` : ''} Locked.
        </p>
      )}
      {recorded?.status === 'CANCELLED' && (
        <p className="mt-3 text-[12.5px] text-chalk-muted">The order was refunded in full; nothing more is owed.</p>
      )}

      <div className="mt-4 grid gap-4 lg:grid-cols-[1fr_1.4fr]">
        <div>
          <p className="mb-2 text-[12.5px] font-semibold text-chalk">Settlement Proof (Optional)</p>
          <label
            className={`flex items-center gap-3 rounded-edge border border-dashed border-ink-300 bg-paper px-4 py-3
                        ${!recorded || !isAdmin || !owed ? 'opacity-60' : 'cursor-pointer hover:border-brand-400'}`}
          >
            <LuUpload aria-hidden="true" className="h-5 w-5 text-chalk-muted" />
            <span className="text-[12.5px]">
              <span className="block font-semibold text-chalk">
                {recorded?.proof ? 'Replace screenshot' : 'Upload Screenshot'}
              </span>
              <span className="block text-chalk-faint">JPG, PNG (Max 5MB)</span>
            </span>
            <input
              type="file"
              accept="image/jpeg,image/png"
              className="sr-only"
              disabled={!recorded || !isAdmin || !owed || busy !== null}
              onChange={(e) => {
                const file = e.target.files?.[0]
                if (file) void uploadProof(file)
                e.target.value = ''
              }}
              data-testid="proof-input"
            />
          </label>
          {recorded?.proof && (
            <button type="button" className="mt-1.5 text-[12px] font-semibold text-brand-400 hover:underline"
                    onClick={() => void viewProof()}>
              View screenshot ({dateTime(recorded.proof.uploadedAt)})
            </button>
          )}
          <p className="mt-1.5 text-[11.5px] text-chalk-faint">The payment reference is entered when marking Paid.</p>
        </div>

        <Field
          label="Admin Note (Optional)"
          hint={below ? 'Required below the lowest tier. Staff only: never shown to the customer.'
            : 'Staff only: saved to the order history, never shown to the customer.'}
        >
          {(props) => (
            <div className="relative">
              <Textarea
                {...props}
                rows={3}
                maxLength={NOTE_MAX}
                value={current.note}
                disabled={locked}
                onChange={(e) => setCurrent({ ...current, note: e.target.value.slice(0, NOTE_MAX) })}
                data-testid="admin-note"
              />
              <span className="pointer-events-none absolute bottom-2 right-3 text-[11px] text-chalk-faint">
                {current.note.length}/{NOTE_MAX}
              </span>
            </div>
          )}
        </Field>
      </div>

      {recorded && !locked && (
        <div className="mt-4 flex justify-end">
          <Button variant="secondary" loading={busy === 'save'} onClick={() => void saveResult()}>
            Save result
          </Button>
        </div>
      )}
      {!recorded && (
        <p className="mt-3 text-[12px] text-chalk-faint">
          Method, status and proof are set once the order is delivered. Delivering settles nothing.
        </p>
      )}

      {error && <div className="mt-4"><Alert tone="warn">{error}</Alert></div>}

      {settling && recorded && (
        <SettleDialog
          publicRef={publicRef}
          kind={settling}
          amount={recorded.settlementFormatted}
          onClose={() => setSettling(null)}
          onDone={(next) => { setSettling(null); onChanged(next) }}
        />
      )}
    </div>
  )
}

function Header() {
  return (
    <div className="flex items-start gap-3">
      <LuTrophy aria-hidden="true" size={26} className="mt-0.5 shrink-0 text-brand-500" />
      <div>
        <h2 className="display text-[15px] text-chalk">Champions Result (Admin Only)</h2>
        <p className="mt-0.5 text-[12.5px] text-chalk-muted">
          Update the final results and settlement details. This will reflect on the customer tracking
          page and in the email.
        </p>
      </div>
    </div>
  )
}

function methodHint(recorded: ResultView['result']): string | undefined {
  if (!recorded?.method) return recorded?.chooseBy ? `Customer can choose until ${dateTime(recorded.chooseBy)}` : undefined
  const by = recorded.methodSetBy === 'CUSTOMER' ? 'Chosen by the customer'
    : recorded.methodSetBy === 'AUTOMATIC' ? 'Set automatically' : 'Set by an admin'
  return `${by}, ${dateTime(recorded.methodSetAt)}`
}

/** Wins Achieved: every level from the lowest tier to the one ordered, and below the lowest. */
export function WinsSelect({
  view, value, onChange, disabled, testId, ...props
}: {
  view: ResultView
  value: number | 'BELOW' | null
  onChange: (next: number | 'BELOW' | null) => void
  disabled?: boolean
  testId: string
  id?: string
  'aria-describedby'?: string
  'aria-invalid'?: boolean
}) {
  return (
    <Select
      {...props}
      value={value === null ? '' : String(value)}
      disabled={disabled}
      onChange={(e) => {
        const raw = e.target.value
        onChange(raw === '' ? null : raw === 'BELOW' ? 'BELOW' : Number(raw))
      }}
      data-testid={testId}
    >
      <option value="">Select wins</option>
      {view.options.map((o) => <option key={o.level} value={String(o.level)}>{winsLabel(o.level)}</option>)}
      <option value="BELOW">Below lowest tier</option>
    </Select>
  )
}

/** Paid or Issued, confirmed in a dialog: the refund's rail and reference, or the credit to add. */
function SettleDialog({
  publicRef, kind, amount, onClose, onDone,
}: {
  publicRef: string
  kind: 'PAID' | 'ISSUED'
  amount: string
  onClose: () => void
  onDone: (next: ResultView) => void
}) {
  const [rail, setRail] = useState('UPI')
  const [reference, setReference] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit() {
    if (!confirmed) {
      setError(kind === 'PAID' ? 'Tick the box to confirm the refund was sent.' : 'Tick the box to confirm.')
      return
    }
    if (kind === 'PAID' && !reference.trim()) {
      setError('The refund’s reference is required.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      onDone(kind === 'PAID'
        ? await api.post<ResultView>(`/api/v1/admin/orders/${publicRef}/result/paid`,
          { method: rail, reference: reference.trim(), confirmed: true })
        : await api.post<ResultView>(`/api/v1/admin/orders/${publicRef}/result/issue`, { confirmed: true }))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'That could not be saved.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={kind === 'PAID' ? 'Mark the refund Paid' : 'Issue GFS Store Credit'} onClose={onClose}>
      <div className="space-y-4" data-testid="settle-dialog">
        {kind === 'PAID' ? (
          <>
            <Field label="Sent by">
              {(props) => (
                <Select {...props} value={rail} onChange={(e) => setRail(e.target.value)} data-testid="refund-rail">
                  {RAILS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
                </Select>
              )}
            </Field>
            <Field label="Reference" required hint="The UTR, PayPal transaction id, transaction hash or Payop refund ID">
              {(props) => (
                <Input {...props} value={reference} maxLength={120}
                       onChange={(e) => setReference(e.target.value)} data-testid="refund-reference" />
              )}
            </Field>
            <Checkbox checked={confirmed} onChange={setConfirmed}>
              Confirm the {amount} refund has actually been sent
            </Checkbox>
          </>
        ) : (
          <Checkbox checked={confirmed} onChange={setConfirmed}>
            Add {amount} GFS Store Credit to the customer&rsquo;s account now
          </Checkbox>
        )}
        <p className="text-[12px] text-chalk-faint">
          This is final: the result locks, the customer is emailed and their page updates.
        </p>
        {error && <Alert tone="warn">{error}</Alert>}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button loading={busy} onClick={() => void submit()} data-testid="settle-confirm">
            {kind === 'PAID' ? 'Mark Paid' : 'Issue credit'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
