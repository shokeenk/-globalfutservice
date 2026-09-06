import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Field, Input } from './ui'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import type { ManualPaymentClaim, ManualPaymentMethod, ManualPaymentOption } from '../lib/types'

/**
 * Paying outside the gateway, and telling us you did.
 *
 * There is no automatic confirmation anywhere in here. The customer pays from their own
 * app, types the reference it gave them, and an operator goes and looks for the money.
 * Every piece of copy in this component is written to that fact: nothing says "paid",
 * because at no point on this screen do we know that.
 */

/*
 * Which picture goes with which destination.
 *
 * The addresses themselves come from the API so there is one copy of them. These are
 * the images of those addresses, and they are the thing most customers actually use --
 * far more people scan than copy. That makes them a correctness concern, not an asset
 * concern: an image left behind after an address changes silently sends money to the
 * old account, and the page will look completely normal while it happens. Any change to
 * gfs.manual-payments in the backend has to land with the matching file here.
 */
const QR_IMAGES: Record<string, string> = {
  'UPI:TRADING_SERVICE': '/brand/payment/upi-coins.jpeg',
  'UPI:*': '/brand/payment/upi-boosting.jpeg',
  'PAYPAL:*': '/brand/payment/paypal.jpeg',
  'CRYPTO:*': '/brand/payment/crypto-tron.jpeg',
}

function qrFor(method: ManualPaymentMethod, sku: string): string {
  // The per-method wildcard always exists, so the final fallback is unreachable in
  // practice -- it is here so a method added to the union without an image renders an
  // empty box rather than failing to compile at the call site.
  return QR_IMAGES[`${method}:${sku}`] ?? QR_IMAGES[`${method}:*`] ?? ''
}

export function ManualPayment({
  publicRef, email, sku, totalFormatted,
}: {
  publicRef: string
  /** The email on the order. Guest auth for the claim, exactly as order tracking. */
  email: string
  sku: string
  totalFormatted: string
}) {
  const t = useT()

  const [options, setOptions] = useState<ManualPaymentOption[] | null>(null)
  const [loadFailed, setLoadFailed] = useState(false)
  const [method, setMethod] = useState<ManualPaymentMethod | null>(null)
  const [reference, setReference] = useState('')
  const [touched, setTouched] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [claim, setClaim] = useState<ManualPaymentClaim | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    api.get<ManualPaymentOption[]>(`/api/v1/payments/methods?sku=${encodeURIComponent(sku)}`)
      .then((found) => {
        if (!live) return
        setOptions(found)
        setMethod((current) => current ?? found[0]?.method ?? null)
      })
      .catch(() => { if (live) setLoadFailed(true) })
    return () => { live = false }
  }, [sku])

  const active = useMemo(
    () => options?.find((option) => option.method === method) ?? null,
    [options, method],
  )

  const referenceIsEmpty = reference.trim().length === 0

  async function submit() {
    setTouched(true)
    // The guard is here as well as on the button because a form can also be submitted
    // with the keyboard, and a disabled button does not stop that on its own.
    if (referenceIsEmpty || !active || submitting) return

    setSubmitting(true)
    setError(null)
    try {
      const recorded = await api.post<ManualPaymentClaim>(
        `/api/v1/payments/claims/${encodeURIComponent(publicRef)}`,
        { email, method: active.method, reference: reference.trim() },
      )
      setClaim(recorded)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t.order.payClaimFailed)
    } finally {
      setSubmitting(false)
    }
  }

  if (loadFailed) {
    return <Alert tone="warn">{t.order.payMethodsFailed}</Alert>
  }

  if (claim) {
    return (
      <div className="animate-rise rounded-panel border border-ink-400 bg-paper p-5">
        <h3 className="display text-[15px] text-chalk">{t.order.payClaimTitle}</h3>
        <p className="mt-2 text-[13px] leading-relaxed text-chalk-muted">
          {t.order.payClaimBody(claim.reference)}
        </p>
        {/*
          A way back, because the most common thing to go wrong here is a mistyped
          reference and the customer notices immediately after sending it. The API
          replaces the pending claim rather than adding a second one.
        */}
        <button
          type="button"
          onClick={() => { setClaim(null); setReference(''); setTouched(false) }}
          className="mt-4 text-[13px] font-semibold text-brand-400 hover:underline
                     focus-visible:outline focus-visible:outline-2
                     focus-visible:outline-offset-2 focus-visible:outline-brand-400"
        >
          {t.order.payClaimResubmit}
        </button>
      </div>
    )
  }

  if (!options || !active) {
    return <div className="h-64 animate-pulse rounded-panel border border-ink-400 bg-ink-700/40" />
  }

  const label: Record<ManualPaymentMethod, string> = {
    UPI: t.order.payTabUpi,
    PAYPAL: t.order.payTabPaypal,
    CRYPTO: t.order.payTabCrypto,
  }

  return (
    <div className="animate-rise space-y-4 rounded-panel border border-ink-400 bg-paper p-5">
      <div>
        <h3 className="display text-[15px] text-chalk">{t.order.payTitle}</h3>
        <p className="mt-1.5 text-[13px] leading-relaxed text-chalk-muted">{t.order.payIntro}</p>
      </div>

      {/*
        Only rendered when there is a choice to make. With a single configured method a
        one-tab tablist is a control that cannot do anything, and reads as though options
        failed to load.
      */}
      {options.length > 1 && (
        <div role="tablist" aria-label={t.order.payTitle} className="flex gap-1">
          {options.map((option) => (
            <button
              key={option.method}
              type="button"
              role="tab"
              aria-selected={option.method === active.method}
              onClick={() => { setMethod(option.method); setError(null) }}
              className={[
                'h-11 flex-1 rounded-edge text-[12.5px] font-semibold transition-colors duration-200',
                option.method === active.method
                  ? 'bg-brand-500 text-paper'
                  : 'bg-ink-700 text-chalk-muted hover:text-chalk',
              ].join(' ')}
            >
              {label[option.method]}
            </button>
          ))}
        </div>
      )}

      <Destination option={active} sku={sku} totalFormatted={totalFormatted} />

      <Field
        label={t.order.payReferenceLabel}
        required
        hint={t.order.payReferenceHint}
        error={touched && referenceIsEmpty ? t.order.payReferenceRequired : undefined}
      >
        {(props) => (
          <Input
            {...props}
            value={reference}
            onChange={(e) => setReference(e.target.value)}
            onBlur={() => setTouched(true)}
            placeholder={t.order.payReferencePlaceholder(active.referenceName)}
            /*
             * inputMode text, not numeric: a UTR is digits but a TXID is hex and a PayPal
             * id is alphanumeric, and the field is shared. autoComplete off because a
             * transaction reference is single-use -- offering last month's is noise.
             */
            inputMode="text"
            autoComplete="off"
            spellCheck={false}
            maxLength={120}
          />
        )}
      </Field>

      {error && <Alert tone="warn">{error}</Alert>}

      <Button
        full
        size="lg"
        loading={submitting}
        disabled={referenceIsEmpty}
        onClick={() => void submit()}
      >
        {t.order.paySubmit}
      </Button>
    </div>
  )
}

/* --------------------------------------------------------------- destination --- */

function Destination({
  option, sku, totalFormatted,
}: {
  option: ManualPaymentOption
  sku: string
  totalFormatted: string
}) {
  const t = useT()

  return (
    <div className="rounded-panel border border-ink-400 bg-ink-700/40 p-4">
      <p className="text-[13px] font-semibold text-chalk">{t.order.payAmountDue(totalFormatted)}</p>
      <p className="mt-1 text-[12.5px] leading-snug text-chalk-faint">{t.order.payScanHint}</p>

      <div className="mt-4 flex justify-center">
        <img
          src={qrFor(option.method, sku)}
          /*
           * The alt text names the destination rather than saying "QR code". Somebody
           * who cannot see the image cannot scan it either, so the useful thing to
           * announce is who is being paid -- the address itself is in the copy field
           * immediately below, as text, which is the accessible path to paying.
           */
          alt={option.accountName
            ? `${t.order.payPayTo} ${option.accountName}`
            : `${t.order.payPayTo} ${option.method}`}
          width={240}
          height={240}
          className="h-auto w-[240px] max-w-full rounded-edge"
        />
      </div>

      {option.accountName && (
        <p className="mt-3 text-center text-[13px] font-semibold text-chalk">
          {option.accountName}
        </p>
      )}

      <CopyRow value={option.destination} />

      {option.method === 'PAYPAL' && (
        <a
          href={option.destination}
          target="_blank"
          rel="noopener noreferrer"
          className="mt-3 inline-flex h-11 w-full items-center justify-center rounded-press
                     border border-ink-400 bg-ink-700 text-[13px] font-semibold text-chalk
                     hover:text-chalk focus-visible:outline focus-visible:outline-2
                     focus-visible:outline-offset-2 focus-visible:outline-brand-400"
        >
          {t.order.payOpenPaypal}
        </a>
      )}

      {option.method === 'CRYPTO' && (
        <div className="mt-3">
          <Alert tone="warn">{t.order.payCryptoWarning}</Alert>
        </div>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ copy row --- */

function CopyRow({ value }: { value: string }) {
  const t = useT()
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')
  const timer = useRef<number | null>(null)

  useEffect(() => () => { if (timer.current) window.clearTimeout(timer.current) }, [])

  const copy = useCallback(async () => {
    try {
      /*
       * The clipboard API is unavailable on insecure origins and can be refused by
       * permission, and this is an address somebody is about to send money to -- a copy
       * button that silently does nothing is how a customer pays a truncated address.
       * On failure the page says so and points at the text, which is selectable.
       */
      await navigator.clipboard.writeText(value)
      setState('copied')
    } catch {
      setState('failed')
    }
    if (timer.current) window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setState('idle'), 2000)
  }, [value])

  return (
    <div className="mt-3">
      <div className="flex items-stretch gap-2">
        <code
          className="min-w-0 flex-1 rounded-edge border border-ink-400 bg-paper px-3 py-2.5
                     text-[12.5px] leading-snug text-chalk"
          /*
           * A TRON address is 34 characters with no spaces and a PayPal link is longer
           * still. Without this they push the panel wider than the viewport and the whole
           * checkout scrolls sideways.
           */
          style={{ overflowWrap: 'anywhere' }}
        >
          {value}
        </code>
        <button
          type="button"
          onClick={() => void copy()}
          className="h-auto shrink-0 rounded-press border border-ink-400 bg-ink-700 px-4
                     text-[12.5px] font-semibold text-chalk transition-colors duration-200
                     hover:border-brand-400 focus-visible:outline focus-visible:outline-2
                     focus-visible:outline-offset-2 focus-visible:outline-brand-400"
        >
          {state === 'copied' ? t.order.payCopied : t.order.payCopy}
        </button>
      </div>

      {/*
        Announced rather than only shown. The button's own label changes on success,
        which a sighted user reads at a glance and a screen reader user does not
        necessarily hear, since focus stays on the button and its accessible name change
        is not reliably announced.
      */}
      <p role="status" aria-live="polite" className="sr-only">
        {state === 'copied' ? t.order.payCopied : ''}
      </p>

      {state === 'failed' && (
        <p className="mt-1.5 text-[12px] leading-snug text-warn">{t.order.payCopyFailed}</p>
      )}
    </div>
  )
}
