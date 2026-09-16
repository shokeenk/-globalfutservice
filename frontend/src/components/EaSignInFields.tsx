import { useState } from 'react'
import { Field, Input } from './ui'
import { useT } from '../i18n'

/**
 * The EA sign-in, as fields. One copy, used by every checkout that needs one.
 *
 * <p>There are two of those now -- the coin checkout and the boosting one -- and a second
 * copy of these particular inputs is the kind of duplication that goes wrong quietly: the
 * rules that matter here are not visual. Nothing is written to storage, nothing is offered
 * to a password manager ({@code autoComplete="off"} plus {@code data-1p-ignore}, because a
 * saved one-time backup code is worthless by the time it is offered back and outlives the
 * vault's own copy), and no value is ever logged. Those rules have to hold in both places
 * or they hold in neither.
 *
 * <p>The surrounding explanation is not here. A coin order needs the comfort-trade
 * checklist above these fields; a boosting order needs a sentence about a player signing
 * in. The fields are the shared part; what is said around them is the page's business.
 */
export function EaSignInFields({
  eaEmail, setEaEmail,
  eaPassword, setEaPassword,
  backupCodes, setBackupCodes,
  errors,
}: {
  eaEmail: string
  setEaEmail: (v: string) => void
  eaPassword: string
  setEaPassword: (v: string) => void
  backupCodes: string[]
  setBackupCodes: (codes: string[]) => void
  /** Keyed `eaEmail`, `eaPassword`, `backup0`…`backup2` — see {@link validateEaSignIn}. */
  errors: Record<string, string>
}) {
  const t = useT()
  const [showPassword, setShowPassword] = useState(false)

  return (
    <div className="space-y-5">
      <Field label={t.track.credEmail} required hint={t.track.credEmailHint} error={errors.eaEmail}>
        {(props) => (
          <Input
            {...props}
            type="email"
            value={eaEmail}
            onChange={(e) => setEaEmail(e.target.value)}
            placeholder={t.order.eaEmailPlaceholder}
            autoComplete="off"
          />
        )}
      </Field>

      <Field label={t.track.credPassword} required hint={t.track.credPasswordHint} error={errors.eaPassword}>
        {(props) => (
          <div className="relative">
            <Input
              {...props}
              type={showPassword ? 'text' : 'password'}
              value={eaPassword}
              onChange={(e) => setEaPassword(e.target.value)}
              autoComplete="off"
              data-1p-ignore
              className="pr-16"
            />
            <button
              type="button"
              onClick={() => setShowPassword((on) => !on)}
              className="absolute right-2 top-1/2 -translate-y-1/2 rounded-edge px-2 py-1
                         text-[11.5px] font-semibold text-chalk-muted hover:text-chalk
                         focus-visible:outline focus-visible:outline-2
                         focus-visible:outline-offset-2 focus-visible:outline-brand-400"
            >
              {showPassword ? t.track.credHide : t.track.credShow}
            </button>
          </div>
        )}
      </Field>

      <div className="grid gap-3 sm:grid-cols-3">
        {backupCodes.map((code, index) => (
          <Field
            key={index}
            label={t.track.credBackupCodeN(index + 1)}
            required
            error={errors[`backup${index}`]}
          >
            {(props) => (
              <Input
                {...props}
                value={code}
                onChange={(e) => setBackupCodes(
                  backupCodes.map((c, i) => (i === index ? e.target.value : c)),
                )}
                placeholder={t.order.backupCodePlaceholder}
                inputMode="numeric"
                maxLength={8}
                autoComplete="off"
                data-1p-ignore
                spellCheck={false}
                className="tnum"
              />
            )}
          </Field>
        ))}
      </div>
    </div>
  )
}

/**
 * What is wrong with the sign-in, field by field.
 *
 * <p>Empty and malformed are different failures and get different messages: a customer who
 * typed seven digits has done something, and telling them the field is required says the
 * opposite of what happened. Backup codes are exactly eight digits — that is EA's format,
 * not a house rule — so anything else is rejected before it can be sealed into the vault
 * and found to be useless by a trader at three in the morning.
 */
export function validateEaSignIn(
  t: ReturnType<typeof useT>,
  eaEmail: string,
  eaPassword: string,
  backupCodes: string[],
): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!eaEmail.trim()) errors.eaEmail = t.order.errEaEmail
  if (!eaPassword) errors.eaPassword = t.order.errEaPassword
  // Eight is the fulfilment partner's minimum. Caught here so it is a correction while
  // the customer is typing, rather than a refused order after they have paid.
  else if (eaPassword.length < 8) errors.eaPassword = t.order.errEaPasswordShort
  backupCodes.forEach((code, index) => {
    const value = code.trim()
    if (!value) {
      errors[`backup${index}`] = t.order.errBackupCode(index + 1)
    } else if (!/^\d{8}$/.test(value)) {
      errors[`backup${index}`] = t.order.errBackupCodeFormat(index + 1)
    }
  })
  return errors
}
