import type { Dictionary } from '../i18n/en'
import { ApiError } from './api'

/**
 * The customer's own words for an error from the sign-in and password endpoints.
 *
 * <p>The server answers in English with a stable code; the code is what is translated. A
 * failed sign-in is always the one generic sentence -- never which of the two was wrong --
 * and being slowed down is always "too many attempts", whichever limit it was.
 */
export function authErrorMessage(e: unknown, t: Dictionary): string {
  if (!(e instanceof ApiError)) return t.auth.genericError
  if (e.status === 429) return t.auth.tooManyAttempts
  switch (e.code) {
    case 'invalid_credentials':
      return t.auth.badCredentials
    case 'reset_link_invalid':
      return t.auth.resetInvalid
    case 'current_password_incorrect':
      return t.account.currentPasswordWrong
    case 'network_unreachable':
      return e.message
    default:
      return t.auth.genericError
  }
}
