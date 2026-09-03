import { useState } from 'react'
import { Alert, Button, Checkbox, Field, Input, Textarea } from './ui'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import type { Order } from '../lib/types'

/**
 * Where a customer hands over an EA sign-in for a comfort-trade order.
 *
 * <p>This is the most sensitive form on the site, and until now it did not exist: the
 * track page told the customer "we need your sign-in to start" and gave them nowhere to
 * put it. The backend had the whole vault — sealed with a per-order key, purged on
 * completion, swept again on a timer — with no door into it.
 *
 * <p><b>Nothing is remembered.</b> Not in component state after submit, not in
 * `localStorage`, not in a form autofill. The password lives in a React state variable
 * for as long as the customer is typing it and is cleared the moment the request
 * resolves. `autoComplete="off"` and `data-1p-ignore` keep password managers from
 * offering to save an EA password into a Global FUT Services entry, which is a
 * confusing thing to find in your vault later.
 *
 * <p><b>The acknowledgements are not paperwork.</b> Each is a failed order turned into a
 * checkbox — an account still signed in kicks the trader's session, a locked transfer
 * market makes the order impossible, unassigned items block transfers outright. The
 * server asserts all four independently; these are here so the customer finds out before
 * paying the price of a support thread.
 */
export function CredentialForm({
  publicRef,
  onSubmitted,
}: {
  publicRef: string
  onSubmitted: (order: Order) => void
}) {
  const t = useT()

  const [eaEmail, setEaEmail] = useState('')
  const [eaPassword, setEaPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [backupCodes, setBackupCodes] = useState('')
  const [platformHandle, setPlatformHandle] = useState('')
  const [note, setNote] = useState('')

  const [signedOut, setSignedOut] = useState(false)
  const [marketUnlocked, setMarketUnlocked] = useState(false)
  const [itemsClear, setItemsClear] = useState(false)
  const [acceptedTerms, setAcceptedTerms] = useState(false)

  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const acknowledged = signedOut && marketUnlocked && itemsClear && acceptedTerms
  const ready = eaEmail.trim() !== '' && eaPassword !== '' && acknowledged

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!ready || submitting) return

    setSubmitting(true)
    setError(null)
    try {
      const order = await api.post<Order>(`/api/v1/orders/${publicRef}/credentials`, {
        eaEmail: eaEmail.trim(),
        eaPassword,
        /*
         * One code per line, blanks dropped. Customers paste these straight out of the
         * text file EA gives them, which arrives with ragged whitespace.
         */
        backupCodes: backupCodes
          .split('\n')
          .map((code) => code.trim())
          .filter(Boolean),
        platformHandle: platformHandle.trim() || null,
        note: note.trim() || null,
        acknowledgedSignedOut: signedOut,
        acknowledgedMarketUnlocked: marketUnlocked,
        acknowledgedItemsClear: itemsClear,
        acceptedTerms,
      })

      /*
       * Cleared before anything else happens. Even though this component is about to
       * unmount, leaving a password sitting in state while a parent re-renders is the
       * kind of thing that survives a refactor and ends up in a devtools screenshot.
       */
      setEaPassword('')
      setBackupCodes('')
      onSubmitted(order)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t.track.credError)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="surface p-5 sm:p-6">
      <h3 className="stamp">{t.track.credFormTitle}</h3>
      <p className="mt-2 text-[13px] leading-relaxed text-chalk-muted">{t.track.credFormLead}</p>

      {error && (
        <div className="mt-4">
          <Alert tone="warn">{error}</Alert>
        </div>
      )}

      <div className="mt-5 space-y-4">
        <Field label={t.track.credEmail} hint={t.track.credEmailHint} required>
          {(props) => (
            <Input
              {...props}
              type="email"
              value={eaEmail}
              onChange={(e) => setEaEmail(e.target.value)}
              placeholder="you@example.com"
              autoComplete="off"
              required
            />
          )}
        </Field>

        <Field label={t.track.credPassword} hint={t.track.credPasswordHint} required>
          {(props) => (
            <div className="relative">
              <Input
                {...props}
                type={showPassword ? 'text' : 'password'}
                value={eaPassword}
                onChange={(e) => setEaPassword(e.target.value)}
                className="pr-20"
                /*
                  Off, deliberately. A password manager offering to save an EA password
                  under this site's name puts the wrong credential behind the wrong
                  domain, and the customer finds it months later with no idea what it is.
                */
                autoComplete="off"
                data-1p-ignore
                data-lpignore="true"
                required
              />
              {/*
                Showing the password is a real accessibility need — long passwords are
                mistyped, and a mistyped one here means a failed order and a support
                thread. It defaults to hidden and is a button, not a checkbox, so it
                never gets submitted with the form.
              */}
              <button
                type="button"
                onClick={() => setShowPassword((v) => !v)}
                className="absolute right-2 top-1/2 -translate-y-1/2 rounded-edge px-2 py-1
                           text-[11.5px] font-semibold text-chalk-muted
                           hover:text-chalk focus-visible:outline focus-visible:outline-2
                           focus-visible:outline-offset-2 focus-visible:outline-brand-400"
              >
                {showPassword ? t.track.credHide : t.track.credShow}
              </button>
            </div>
          )}
        </Field>

        <div>
          <Field label={t.track.credBackupCodes} hint={t.track.credBackupCodesHint}>
            {(props) => (
              <Textarea
                {...props}
                rows={3}
                value={backupCodes}
                onChange={(e) => setBackupCodes(e.target.value)}
                autoComplete="off"
                data-1p-ignore
                spellCheck={false}
              />
            )}
          </Field>

          {/*
            The one field on this form that asks for something most players have never
            gone looking for, and the step orders stall on.

            Deep-linked to the help centre rather than expanded inline: the answer is
            six steps on somebody else's website, and unfolding that here would bury
            the remaining fields under it. Opens in a new tab so a half-filled
            credential form is not thrown away to go and read it — which is the one
            thing this form must never cost, because everything in it was typed from
            a password manager.
          */}
          <a
            href="/help#faq-backup-codes"
            target="_blank"
            rel="noreferrer"
            className="mt-2 inline-flex items-center gap-1.5 text-[12.5px] font-semibold
                       text-brand-400 underline-offset-2 hover:underline
                       focus-visible:outline focus-visible:outline-2
                       focus-visible:outline-offset-2 focus-visible:outline-brand-400"
          >
            <svg
              aria-hidden="true"
              viewBox="0 0 20 20"
              className="h-3.5 w-3.5"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <circle cx="10" cy="10" r="7.5" />
              <path d="M7.8 7.7a2.2 2.2 0 1 1 2.9 2.1c-.5.2-.8.6-.8 1.1v.4" />
              <path d="M10 14.2h.01" />
            </svg>
            {t.track.credBackupCodesFind}
          </a>
        </div>

        <Field label={t.track.credHandle} hint={t.track.credHandleHint}>
          {(props) => (
            <Input
              {...props}
              value={platformHandle}
              onChange={(e) => setPlatformHandle(e.target.value)}
              autoComplete="off"
              maxLength={64}
            />
          )}
        </Field>

        <Field label={t.track.credNote}>
          {(props) => (
            <Textarea
              {...props}
              rows={2}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              maxLength={500}
            />
          )}
        </Field>
      </div>

      <div className="mt-6 space-y-3 rounded-edge bg-paper p-4">
        <Checkbox checked={signedOut} onChange={setSignedOut}>
          {t.track.credAckSignedOut}
        </Checkbox>
        <Checkbox checked={marketUnlocked} onChange={setMarketUnlocked}>
          {t.track.credAckMarket}
        </Checkbox>
        <Checkbox checked={itemsClear} onChange={setItemsClear}>
          {t.track.credAckItems}
        </Checkbox>
        <Checkbox checked={acceptedTerms} onChange={setAcceptedTerms}>
          {t.track.credAckTerms}
        </Checkbox>
      </div>

      <Button type="submit" size="lg" className="mt-5 w-full" disabled={!ready} loading={submitting}>
        {submitting ? t.track.credSubmitting : t.track.credSubmit}
      </Button>
    </form>
  )
}
