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

/**
 * The tabs across the top. `INTERNATIONAL` is the storefront's own: nothing sits behind it
 * on the API yet, which is why it is not a ManualPaymentMethod.
 */
type PaymentTab = ManualPaymentMethod | 'INTERNATIONAL'

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
  const [method, setMethod] = useState<PaymentTab | null>(null)
  const [reference, setReference] = useState('')
  const [touched, setTouched] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [claim, setClaim] = useState<ManualPaymentClaim | null>(null)
  const [file, setFile] = useState<File | null>(null)
  // Kept apart from `error`: the reference was recorded and only the screenshot failed,
  // which is a different thing to tell somebody who has already sent money.
  const [proofError, setProofError] = useState<string | null>(null)
  const [proofRetrying, setProofRetrying] = useState(false)
  // Set by pressing submit, not by leaving the reference field: the screenshot sits below
  // the reference, and flagging it missing before anyone has reached it is nagging.
  const [attempted, setAttempted] = useState(false)
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

  /** Whether the screenshot landed. A failure is shown with a retry, never thrown. */
  async function uploadProof(picked: File): Promise<boolean> {
    try {
      const form = new FormData()
      form.append('email', email)
      form.append('file', picked)
      await api.upload(
        `/api/v1/payments/claims/${encodeURIComponent(publicRef)}/proof`, form)
      setProofError(null)
      return true
    } catch (uploadFailed) {
      setProofError(uploadFailed instanceof ApiError
        ? uploadFailed.message : t.order.payProofFailed)
      return false
    }
  }

  async function retryProof() {
    if (!file || proofRetrying) return
    setProofRetrying(true)
    await uploadProof(file)
    setProofRetrying(false)
  }

  async function submit() {
    setTouched(true)
    setAttempted(true)
    // The guard is here as well as on the button because a form can also be submitted
    // with the keyboard, and a disabled button does not stop that on its own.
    if (referenceIsEmpty || !file || !active || submitting) return

    setSubmitting(true)
    setError(null)
    try {
      const recorded = await api.post<ManualPaymentClaim>(
        `/api/v1/payments/claims/${encodeURIComponent(publicRef)}`,
        { email, method: active.method, reference: reference.trim() },
      )

      /*
       * The screenshot is required, but it still goes second.
       *
       * It attaches to the claim this request just created -- and to the Discord ticket
       * that claim opens -- so it cannot go first. And its failure is not the claim's
       * failure: discarding a recorded reference because a 4 MB upload timed out would
       * tell somebody who has already sent money that their payment was not recorded.
       * So the reference stands, and the next screen offers the upload again.
       */
      await uploadProof(file)

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
        {proofError && (
          <div className="mt-3 space-y-3">
            <p className="text-[12.5px] leading-snug text-warn">{proofError}</p>
            <ProofPicker file={file} onPick={setFile} required />
            <Button size="md" loading={proofRetrying} disabled={!file}
                    onClick={() => void retryProof()}>
              {t.order.payProofRetry}
            </Button>
          </div>
        )}
        {/*
          A way back, because the most common thing to go wrong here is a mistyped
          reference and the customer notices immediately after sending it. The API
          replaces the pending claim rather than adding a second one.
        */}
        <button
          type="button"
          onClick={() => {
            setClaim(null); setReference(''); setTouched(false); setAttempted(false); setProofError(null)
          }}
          className="mt-4 text-[13px] font-semibold text-brand-400 hover:underline
                     focus-visible:outline focus-visible:outline-2
                     focus-visible:outline-offset-2 focus-visible:outline-brand-400"
        >
          {t.order.payClaimResubmit}
        </button>
      </div>
    )
  }

  if (!options) {
    return <div className="h-64 animate-pulse rounded-panel border border-ink-400 bg-ink-700/40" />
  }
  if (options.length === 0) {
    return <Alert tone="warn">{t.order.payMethodsFailed}</Alert>
  }

  const label: Record<PaymentTab, string> = {
    UPI: t.order.payTabUpi,
    PAYPAL: t.order.payTabPaypal,
    CRYPTO: t.order.payTabCrypto,
    INTERNATIONAL: t.order.payTabInternational,
  }
  const tabs: PaymentTab[] = [...options.map((option) => option.method), 'INTERNATIONAL']

  return (
    <div className="animate-rise space-y-4 rounded-panel border border-ink-400 bg-paper p-5">
      <div>
        <h3 className="display text-[15px] text-chalk">{t.order.payTitle}</h3>
        <p className="mt-1.5 text-[13px] leading-relaxed text-chalk-muted">{t.order.payIntro}</p>
      </div>

      {/*
        Always a choice now: whatever the API offers, plus International. That one is a
        placeholder -- selectable, so the customer can read what it says, but with no
        destination, no reference and no submit behind it, so it cannot pass for a way
        to pay that silently does nothing.
      */}
      {/*
        Two by two on a phone, one row from tablet width up. Four tabs share about 77px
        each on a 375px screen, which fits "UPI" and clips "International" -- and a
        shorter label ("Intl") reads as an abbreviation somebody has to decode.
      */}
      <div role="tablist" aria-label={t.order.payTitle} className="grid grid-cols-2 gap-1 sm:flex">
        {tabs.map((tab) => (
          <button
            key={tab}
            type="button"
            role="tab"
            aria-selected={tab === method}
            onClick={() => { setMethod(tab); setError(null) }}
            className={[
              'h-11 min-w-0 flex-1 rounded-edge px-1.5 text-[12.5px] font-semibold',
              'transition-colors duration-200',
              tab === method
                ? 'bg-brand-500 text-paper'
                : 'bg-ink-700 text-chalk-muted hover:text-chalk',
            ].join(' ')}
          >
            {label[tab]}
          </button>
        ))}
      </div>

      {method === 'INTERNATIONAL' ? (
        <InternationalSoon
          alternatives={options
            .filter((option) => option.method === 'PAYPAL' || option.method === 'CRYPTO')
            .map((option) => ({ method: option.method, name: label[option.method] }))}
          onUse={(next) => { setMethod(next); setError(null) }}
        />
      ) : active && (
        <>
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

          <ProofPicker file={file} onPick={setFile} required missing={attempted} />

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
        </>
      )}
    </div>
  )
}

/* --------------------------------------------------------------- the proof --- */

/** What the server will accept. Kept in step with ImageType on the backend. */
const ACCEPTED = ['image/jpeg', 'image/png', 'image/webp']
const MAX_PROOF_BYTES = 5 * 1024 * 1024

/**
 * The screenshot of the payment. Required.
 *
 * <p>It used to be optional, on the reasoning that it is evidence rather than
 * authorisation -- a claim without one is verified the same way, only slower. Required
 * is the business's call: the Discord ticket an operator works from is built around the
 * image, and without one they are searching a bank account by reference alone. The cost
 * is somebody whose phone will not share an image being unable to submit here; support is
 * the way through for them.
 *
 * <p>Enforced in the storefront, not on the server. The image is a second request that
 * attaches to the claim the first one creates, so the server cannot refuse a claim for
 * lacking something that has not been sent yet.
 */
function ProofPicker({
  file, onPick, required = false, missing = false,
}: {
  file: File | null
  onPick: (f: File | null) => void
  required?: boolean
  /** The customer tried to submit without one. */
  missing?: boolean
}) {
  const t = useT()
  const [preview, setPreview] = useState<string | null>(null)
  const [rejected, setRejected] = useState<string | null>(null)
  // Cleared on remove and on rejection. A file input keeps its own value, and without the
  // reset, choosing the same file again after removing it fires no change event at all.
  const inputRef = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    if (!file) {
      setPreview(null)
      return
    }
    const url = URL.createObjectURL(file)
    setPreview(url)
    // Revoked on replacement and unmount. Without this, a customer trying three
    // screenshots leaks all three for the life of the tab.
    return () => URL.revokeObjectURL(url)
  }, [file])

  function choose(picked: File | null) {
    setRejected(null)
    const reset = () => { if (inputRef.current) inputRef.current.value = '' }
    if (!picked) {
      reset()
      onPick(null)
      return
    }
    /*
     * Checked here as a courtesy, not as a control. The server sniffs the bytes and is
     * the only thing that decides -- this just means a customer learns their PDF will
     * not do before they wait for it to upload, rather than after.
     */
    if (!ACCEPTED.includes(picked.type)) {
      setRejected(t.order.payProofWrongType)
      reset()
      onPick(null)
      return
    }
    if (picked.size > MAX_PROOF_BYTES) {
      setRejected(t.order.payProofTooBig)
      reset()
      onPick(null)
      return
    }
    onPick(picked)
  }

  return (
    <div className={`rounded-edge border border-dashed p-3 ${
      missing && !file ? 'border-warn' : 'border-ink-400'}`}>
      <label className="block text-[13px] font-medium text-chalk-muted" htmlFor="payment-proof">
        {t.order.payProofLabel}
        {required && <span className="ml-1 text-brand-400">*</span>}
      </label>
      <p className="mt-1 text-[12px] leading-snug text-chalk-faint">{t.order.payProofHint}</p>

      <input
        ref={inputRef}
        id="payment-proof"
        type="file"
        required={required}
        aria-invalid={missing && !file ? true : undefined}
        accept={ACCEPTED.join(',')}
        onChange={(e) => choose(e.target.files?.[0] ?? null)}
        className="mt-2 block w-full text-[12.5px] text-chalk-muted
                   file:mr-3 file:h-9 file:cursor-pointer file:rounded-edge file:border-0
                   file:bg-ink-700 file:px-3 file:text-[12.5px] file:font-semibold
                   file:text-chalk hover:file:bg-ink-600"
      />

      {rejected && <p className="mt-2 text-[12px] leading-snug text-warn">{rejected}</p>}
      {missing && !file && !rejected && (
        <p role="alert" className="mt-2 text-[12px] leading-snug text-warn">
          {t.order.payProofRequired}
        </p>
      )}

      {preview && file && (
        <div className="mt-3 flex items-start gap-3">
          <img
            src={preview}
            alt={t.order.payProofPreviewAlt}
            className="h-20 w-20 rounded-edge object-cover"
          />
          <div className="min-w-0 flex-1">
            <p className="truncate text-[12.5px] text-chalk">{file.name}</p>
            <p className="text-[12px] text-chalk-faint">
              {(file.size / 1024).toFixed(0)} KB
            </p>
            <button
              type="button"
              onClick={() => choose(null)}
              className="mt-1 text-[12px] font-semibold text-brand-400 hover:underline
                         focus-visible:outline focus-visible:outline-2
                         focus-visible:outline-offset-2 focus-visible:outline-brand-400"
            >
              {t.order.payProofRemove}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

/* ------------------------------------------------------------ international --- */

/**
 * A placeholder, and it says so.
 *
 * <p>Selectable rather than disabled: a tab that cannot be chosen gives no way to read why.
 * Choosing this one shows what is coming and the ways to pay today, with a button straight
 * to each -- nothing to type and nothing to submit, so it cannot pass for a payment method
 * that failed. The alternatives come from what is configured, so this never names a method
 * that has been switched off.
 */
function InternationalSoon({
  alternatives, onUse,
}: {
  alternatives: { method: ManualPaymentMethod; name: string }[]
  onUse: (method: ManualPaymentMethod) => void
}) {
  const t = useT()
  return (
    <div
      role="tabpanel"
      className="rounded-panel border border-dashed border-ink-400 bg-ink-700/40 px-5 py-8 text-center"
    >
      <span className="inline-block rounded-full bg-brand-500/10 px-2.5 py-1 text-[11px]
                       font-semibold uppercase tracking-[0.14em] text-brand-400">
        {t.order.payIntlBadge}
      </span>
      <h4 className="display mt-3 text-[15px] text-chalk">{t.order.payIntlTitle}</h4>
      {alternatives.length > 0 && (
        <p className="mx-auto mt-2 max-w-sm text-[13px] leading-relaxed text-chalk-muted">
          {t.order.payIntlBody(alternatives.map((a) => a.name))}
        </p>
      )}
      <p className="mx-auto mt-1.5 max-w-sm text-[12px] leading-snug text-chalk-faint">
        {t.order.payIntlNote}
      </p>
      {alternatives.length > 0 && (
        <div className="mt-5 flex flex-wrap justify-center gap-2">
          {alternatives.map((a) => (
            <Button key={a.method} size="md" variant="secondary" onClick={() => onUse(a.method)}>
              {t.order.payIntlUse(a.name)}
            </Button>
          ))}
        </div>
      )}
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

      {/*
        The white frame is the QR's quiet zone, and it is functional rather than styling.

        A QR needs a clear margin around it for a scanner to lock onto the finder
        patterns -- ISO/IEC 18004 asks for four modules. These images are cropped by hand
        and that margin is the first thing a tidy crop takes: the PayPal code arrived
        trimmed to roughly one module. Guaranteeing the margin here rather than in each
        file means every code has one, including the next one somebody crops close.

        Sizing is left alone deliberately. A downscale-and-decode test appeared to show
        the tighter crop failing at this width, which would have argued for rendering
        bigger -- but the same test failed the *old* image at 300px while passing it at
        240px, and no real resolution limit behaves like that. It was measuring
        resampling aliasing between the module grid and the target pixel grid, not
        scannability, so nothing here is built on it.
      */}
      <div className="mt-4 flex justify-center">
        <div className="rounded-edge bg-white p-3">
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
          // `block` removes the inline-element baseline gap, which would otherwise show
          // as a few pixels of extra white below the code and make the frame look wonky.
          className="block h-auto w-[240px] max-w-full"
        />
        </div>
      </div>

      {option.accountName && (
        <p className="mt-3 text-center text-[13px] font-semibold text-chalk">
          {option.accountName}
        </p>
      )}

      <CopyRow value={option.destination} />

      {/*
        Driven by `link`, not by the destination. The destination for PayPal is now the
        account email -- which is what gets copied, and what an operator reconciles
        against -- so pointing an anchor at it would produce a link to `mailto`-less
        plain text. The button only exists when there is something to open.
      */}
      {option.link && (
        <a
          href={option.link}
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
