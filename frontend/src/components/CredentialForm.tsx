import { useState } from 'react'
import { Link } from 'react-router-dom'
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
  /*
   * Three inputs, not one textarea.
   *
   * EA issues codes in a block and customers paste the block, which is why this used to
   * be a textarea splitting on newlines. Three labelled boxes ask for exactly what the
   * order needs and make a short paste visibly incomplete, where a textarea with two
   * codes in it looks as finished as one with three.
   *
   * Still assembled into the same `backupCodes` array the API already takes, so the
   * wire format and the vault are untouched.
   */
  const [backupCodes, setBackupCodes] = useState(['', '', ''])
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
        // Trimmed and blanks dropped: the boxes are optional individually, and a
        // customer with only two codes should not send an empty third.
        backupCodes: backupCodes.map((code) => code.trim()).filter(Boolean),
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
      setBackupCodes(['', '', ''])
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
        {/*
          Both credential fields carry a help link, pointed at the existing "what do you
          need from me" answer. There is no separate entry for the EA email or the
          password on their own, and inventing two thin ones to satisfy a label would
          scatter the same explanation across three places.
        */}
        <div className="flex items-center justify-end">
          <HowToFind anchor="credentials" />
        </div>

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
              <div className="grid grid-cols-3 gap-2" role="group" aria-describedby={props['aria-describedby']}>
                {backupCodes.map((code, index) => (
                  <input
                    key={index}
                    id={index === 0 ? props.id : undefined}
                    value={code}
                    onChange={(e) => setBackupCodes((prev) => {
                      const next = [...prev]
                      next[index] = e.target.value
                      return next
                    })}
                    aria-label={t.track.credBackupCodeN(index + 1)}
                    placeholder={`${index + 1}`}
                    maxLength={32}
                    /*
                      `off` plus `data-1p-ignore`: a password manager offering to save a
                      one-time backup code would store a value that is worthless by the
                      time it is offered back, and would keep it long after the vault has
                      purged its own copy.
                    */
                    autoComplete="off"
                    data-1p-ignore
                    spellCheck={false}
                    className="tnum h-11 w-full rounded-edge border border-ink-400 bg-paper px-3
                               text-center text-[13px] text-chalk placeholder:text-chalk-faint
                               focus-visible:outline focus-visible:outline-2
                               focus-visible:outline-offset-1 focus-visible:outline-brand-400"
                  />
                ))}
              </div>
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

        {/*
          The promise the terms of service already make, repeated where it is being
          relied on.

          Clause 11 says these are encrypted, opened only to fulfil the order and
          deleted after use. A customer meets that clause once, if ever, and meets this
          form at the moment they are deciding whether to trust it — so the undertaking
          is restated here and linked, rather than left somewhere they would have to go
          and find. The wording tracks the clause deliberately: a reassurance that
          promises more than the contract is worse than none.
        */}
        <p className="hairline rounded-panel bg-paper p-4 text-[12.5px] leading-relaxed text-chalk-muted">
          {t.track.credReassureLead}{' '}
          <Link to="/terms" className="font-semibold text-brand-400 hover:underline">
            {t.track.credReassureLink}
          </Link>{' '}
          {t.track.credReassureTail}
        </p>

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

/**
 * A "how to find?" link beside a field label.
 *
 * <p>Points at the help centre rather than explaining inline: the answer is several steps
 * on somebody else's website, and unfolding that next to a label would bury the field it
 * belongs to. Opens in a new tab so a half-filled credential form is never thrown away to
 * go and read it -- which matters more here than anywhere else on the site, because
 * everything in this form was typed out of a password manager.
 */
function HowToFind({ anchor }: { anchor: string }) {
  const t = useT()
  return (
    <a
      href={`/help#faq-${anchor}`}
      target="_blank"
      rel="noreferrer"
      className="text-[12px] font-semibold text-brand-400 underline-offset-2 hover:underline
                 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                 focus-visible:outline-brand-400"
    >
      {t.track.howToFind}
    </a>
  )
}
