import { useEffect, useState } from 'react'
import { Alert, Badge, Button, Card, Field, Input, Textarea } from '../../../components/ui'
import { ApiError, api } from '../../../lib/api'
import { dateTime } from '../../../lib/format'
import type { VendorActionName, VendorBalance, VendorNextSend, VendorSection } from '../../../lib/types'
import {
  VENDOR_ACTION_LABEL, VENDOR_ACTION_PATH, VENDOR_ACTIONS, VENDOR_FINAL_ACTIONS, VENDOR_STATE_LABEL,
  asReported, orderModeLabel, vendorQuestion, vendorStateTone, vendorTimeline,
} from './vendor'

/**
 * The order at FUT Transfer, for admins: where it stands there, every call we made about
 * it, every admin action with who took it, and the actions that apply now.
 *
 * <p>Every action asks first, in words that say what reaches the partner and what cannot
 * be undone. The server checks everything again; a refusal comes back as the reason, and
 * the section reloads either way so what is on screen is what the database says.
 */
export function VendorPanel({
  publicRef, section, onChanged,
}: {
  publicRef: string
  section: VendorSection
  /** Told after any action, whatever its outcome, so the page reloads the order and this. */
  onChanged: () => Promise<void> | void
}) {
  const [busy, setBusy] = useState<VendorActionName | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [linkId, setLinkId] = useState('')
  const [note, setNote] = useState('')

  const v = section.vendorOrder
  const available = VENDOR_ACTIONS.filter((a) => section.available.includes(a))

  /*
   * The balance FUT Transfer reports, read live (the server keeps it for a minute). Its own
   * request, so a slow partner never holds up the rest of this section; "Unavailable" when
   * it cannot be read, never a zero.
   */
  const [balance, setBalance] = useState<VendorBalance | null | 'loading'>('loading')
  useEffect(() => {
    if (!section.enabled) return
    let live = true
    ;(async () => {
      try {
        const res = await api.get<VendorBalance>('/api/v1/admin/vendor/balance')
        if (live) setBalance(res ?? null)
      } catch {
        if (live) setBalance(null)
      }
    })()
    return () => { live = false }
  }, [section.enabled])
  const balanceText = balance === 'loading' ? '…'
    : balance && balance.available && balance.balance != null ? asReported(balance.balance) : 'Unavailable'
  const modeChanged = v?.orderMode != null && section.currentOrderMode != null
    && v.orderMode !== section.currentOrderMode

  async function run(action: VendorActionName) {
    const input = { vendorOrderId: linkId.trim() || undefined, note: note.trim() }
    if (action === 'RESOLVE' && input.note.length < 5) {
      setError('Say how it was settled first, in at least 5 characters.')
      return
    }
    if (!window.confirm(vendorQuestion(action, publicRef, section, input))) return

    const body = action === 'RETRY' ? { confirmedAbsent: true }
      : action === 'LINK' ? { vendorOrderId: input.vendorOrderId ?? null }
        : action === 'RESOLVE' ? { note: input.note }
          : undefined
    setBusy(action)
    setError(null)
    setDone(null)
    try {
      const res = await api.post<{ status: string; message: string }>(
        `/api/v1/admin/orders/${publicRef}/vendor/${VENDOR_ACTION_PATH[action]}`, body)
      setDone(res.message)
      if (action === 'LINK') setLinkId('')
      if (action === 'RESOLVE') setNote('')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'That did not go through. Reload and check before trying again.')
    } finally {
      setBusy(null)
      await onChanged()
    }
  }

  return (
    <section aria-labelledby="vendor-heading">
    <Card className="p-6 sm:p-7">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="vendor-heading" className="display text-[15px] text-chalk">FUT Transfer</h2>
        {v && <Badge tone={vendorStateTone(v.state)}>{VENDOR_STATE_LABEL[v.state] ?? v.state}</Badge>}
      </div>

      {!section.enabled && (
        <p className="mt-3 text-[13px] text-chalk-muted">
          The fulfilment partner is switched off here. Nothing is sent or read.
        </p>
      )}
      {section.enabled && (
        <p className="mt-2 text-[12.5px] text-chalk-muted">
          FUT Transfer balance (currency unconfirmed):{' '}
          <span className="tnum font-semibold text-chalk">{balanceText}</span>
        </p>
      )}
      {section.paused && (
        <div className="mt-4">
          <Alert tone="warn" title="Calls to FUT Transfer are paused">
            It refused our API credentials. Fix them, then resume calls from the Orders page.
          </Alert>
        </div>
      )}

      {/*
        What Approve would send, before anyone clicks it: only where Approve sends something,
        which is before the first send or after a definite refusal. Once the partner has the
        order, Approve sends nothing, and "Sent as / Configured now" below says the rest.
      */}
      {section.enabled && section.nextSend && (!v || v.state === 'FAILED') && (
        <NextSend next={section.nextSend} />
      )}
      {!v ? (
        <p className="mt-3 text-[13px] text-chalk-muted">Not sent to the partner.</p>
      ) : (
        <>
          <dl className="mt-5 grid gap-px overflow-hidden rounded-edge bg-ink-400 sm:grid-cols-3">
            <Cell label="Partner order" value={v.vendorOrderId ?? '—'} mono />
            <Cell label="Ordered" value={`${v.amountOrderedK.toLocaleString('en-IN')}K`} />
            <Cell label="Delivered" value={v.deliveredK == null ? '—' : `${v.deliveredK.toLocaleString('en-IN')}K`} />
            <Cell label="Partner status" value={v.vendorStatus ?? '—'} mono />
            <Cell label="Account check" value={v.vendorAccountCheck ?? '—'} mono />
            <Cell label="Economy state" value={v.vendorEconomyState ?? '—'} mono />
            <Cell label="Coins used" value={v.coinsUsed == null ? '—' : v.coinsUsed.toLocaleString('en-IN')} />
            <Cell label="To pay (currency unconfirmed)" value={v.toPay == null ? '—' : String(v.toPay)} />
            <Cell label="Sends" value={String(v.attempts)} />
            <Cell label="Sent" value={dateTime(v.submittedAt ?? null)} />
            <Cell label="Last report" value={dateTime(v.lastPolledAt ?? null)} />
            <Cell label="Last progress" value={dateTime(v.lastProgressAt ?? null)} />
          </dl>

          <p className="stamp mb-3 mt-5">How it was sent</p>
          <dl className="grid gap-px overflow-hidden rounded-edge bg-ink-400 sm:grid-cols-3">
            <Cell label="Order mode" value={orderModeLabel(v.orderMode)} />
            <Cell label="Transfer method" value={v.transferMethod ?? 'Not recorded'} mono />
            <Cell label="buyNowThreshold sent" value={v.buyNowThresholdSent == null ? 'Not sent' : asReported(v.buyNowThresholdSent)} />
            <Cell label="maxPrice sent" value={v.maxPriceSent == null ? 'Not sent' : asReported(v.maxPriceSent)} />
            <Cell label="Balance at send (currency unconfirmed)" value={asReported(v.balanceAtSend)} />
          </dl>
          {modeChanged && (
            <p className="mt-3 text-[12.5px] text-warn">
              Sent as {orderModeLabel(v.orderMode)}. Configured now: {orderModeLabel(section.currentOrderMode)} —
              approving it again would use that.
            </p>
          )}

          {v.aborted && (
            <p className="mt-3 text-[12.5px] text-warn">The partner reports this order was aborted.</p>
          )}
          {v.missingPolls > 0 && (
            <p className="mt-3 text-[12.5px] text-chalk-muted">
              Left out of the partner&apos;s last {v.missingPolls} status {v.missingPolls === 1 ? 'check' : 'checks'}.
            </p>
          )}
          {(v.reviewReason || v.lastErrorCode) && (
            <div className="mt-4">
              <Alert tone={v.state === 'NEEDS_REVIEW' || v.state === 'PARTIALLY_DELIVERED' ? 'warn' : 'neutral'}
                     title={v.lastErrorCode ?? undefined}>
                {v.reviewReason ?? 'No further detail.'}
              </Alert>
            </div>
          )}
          {v.customerAction && (
            <p className="mt-3 text-[12.5px] text-chalk-muted">
              Customer asked to: <span className="font-mono text-chalk">{v.customerAction}</span>
            </p>
          )}
        </>
      )}

      {available.length > 0 && (
        <div className="mt-6 border-t border-ink-400 pt-5">
          <p className="stamp mb-3">Actions</p>

          {available.includes('LINK') && (
            <Field label="Partner order id" hint="Optional: from the FUT Transfer dashboard. Without it we look the order up by its reference.">
              {(props) => (
                <Input {...props} value={linkId} onChange={(e) => setLinkId(e.target.value)} placeholder="e.g. 123e4567-…" />
              )}
            </Field>
          )}
          {available.includes('RESOLVE') && (
            <div className="mt-3">
              <Field label="How it was settled" hint="Needed to resolve. Kept with the order at the partner.">
                {(props) => (
                  <Textarea {...props} rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
                )}
              </Field>
            </div>
          )}

          <div className="mt-4 flex flex-wrap gap-2">
            {available.map((action) => (
              <Button
                key={action}
                variant={VENDOR_FINAL_ACTIONS.has(action) ? 'danger' : action === 'SEND_SIGN_IN' || action === 'RESUME' ? 'primary' : 'secondary'}
                loading={busy === action}
                disabled={busy !== null && busy !== action}
                onClick={() => void run(action)}
              >
                {VENDOR_ACTION_LABEL[action]}
              </Button>
            ))}
          </div>
        </div>
      )}

      {done && <div className="mt-4"><Alert tone="ok">{done}</Alert></div>}
      {error && <div className="mt-4"><Alert tone="warn">{error}</Alert></div>}

      {(section.calls.length > 0 || section.actions.length > 0) && (
        <div className="mt-6 border-t border-ink-400 pt-5">
          <p className="stamp mb-3">Calls and actions</p>
          <ol className="space-y-3">
            {vendorTimeline(section).map((item) => (
              <li key={item.key} className="text-[12.5px]">
                {item.kind === 'call' ? (
                  <>
                    <p className="text-chalk">
                      <span className="font-mono">{item.call.endpoint}</span>
                      {' · '}{item.call.result}
                      {item.call.errorCode ? ` (${item.call.errorCode})` : ''}
                    </p>
                    <p className="text-[11.5px] text-chalk-faint">
                      {dateTime(item.call.at)} · {item.call.domain === 'BACKUP' ? 'backup domain' : 'primary domain'}
                      {' · '}{item.call.httpStatus == null ? 'no answer' : `HTTP ${item.call.httpStatus}`}
                      {' · '}{item.call.durationMs} ms
                    </p>
                  </>
                ) : (
                  <>
                    <p className="text-chalk">
                      <span className="font-semibold">{VENDOR_ACTION_LABEL[item.entry.action] ?? item.entry.action}</span>
                      {' · '}{item.entry.outcome.toLowerCase()}
                      {item.entry.code ? ` (${item.entry.code})` : ''}
                    </p>
                    {item.entry.detail && <p className="text-chalk-muted">{item.entry.detail}</p>}
                    <p className="text-[11.5px] text-chalk-faint">
                      {dateTime(item.entry.at)}{item.entry.actorLabel ? ` · ${item.entry.actorLabel}` : ''}
                    </p>
                  </>
                )}
              </li>
            ))}
          </ol>
        </div>
      )}
    </Card>
    </section>
  )
}

/** The mode Approve will use, and exactly what it sends, in the partner's own field names. */
function NextSend({ next }: { next: VendorNextSend }) {
  const own = next.orderMode === 'OWN_SENDERS'
  const warn = own || next.refusal != null
  const fields = [
    `transferMethod ${next.transferMethod}`,
    ...(own ? [] : [`buyNowThreshold ${asReported(next.buyNowThreshold)}${next.buyNowThresholdSource
      ? ` (${next.buyNowThresholdSource})` : ''}`]),
    `topUpEnabled ${next.topUpEnabled}`,
    `autoFinishCycle ${next.autoFinishCycle}`,
    own ? `senderGroup ${next.senderGroup}` : 'no senderGroup, no supplierID',
    ...(own ? [] : [next.maxPrice == null ? 'maxPrice not sent' : `maxPrice ${asReported(next.maxPrice)}`]),
  ]
  return (
    <div
      role="note"
      aria-label="What Approve sends"
      className={`mt-4 rounded-edge border p-4 ${warn ? 'border-warn/40 bg-warn/10' : 'border-ink-400 bg-ink-700/40'}`}
    >
      <p className="text-[10.5px] uppercase tracking-[0.14em] text-chalk-faint">Approve sends this order to</p>
      <p className="mt-1 text-[15px] font-semibold text-chalk">
        {orderModeLabel(next.orderMode)} · <span className="font-mono">{next.endpoint}</span>
      </p>
      {next.refusal ? (
        <p className="mt-1.5 text-[13px] text-warn">Approve would send nothing: {next.refusal}</p>
      ) : (
        <p className="mt-1.5 font-mono text-[12px] leading-relaxed text-chalk-muted">{fields.join(' · ')}</p>
      )}
      {own && !next.refusal && (
        <p className="mt-1.5 text-[12.5px] text-warn">
          This is our own sender accounts, not the public pool. To buy from the public pool, set
          GFS_FUTTRANSFER_ORDER_MODE=PUBLIC_POOL and redeploy.
        </p>
      )}
    </div>
  )
}

function Cell({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="bg-paper p-4">
      <dt className="text-[10.5px] uppercase tracking-[0.14em] text-chalk-faint">{label}</dt>
      <dd className={`mt-1.5 break-all text-[13.5px] font-semibold text-chalk ${mono ? 'font-mono' : 'tnum'}`}>{value}</dd>
    </div>
  )
}
