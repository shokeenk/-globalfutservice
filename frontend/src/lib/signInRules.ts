/**
 * What an EA sign-in must look like: the storefront's copy of the server's rules.
 *
 * <p>The same numbers as `backend/.../credentials/SignInRules.java`, which is the check
 * that counts -- the server refuses anything that breaks them. These are here so a
 * customer hears about a mistake under the field it is in, before pressing Pay sends
 * anything.
 *
 * <p>FUT Transfer documents a password of at least eight characters and backup codes of
 * at least six; EA issues them as exactly eight digits, and that is what is asked for.
 */

/** FUT Transfer's minimum EA password length. */
export const EA_PASSWORD_MIN = 8

/** An EA backup code: exactly eight digits. */
export const BACKUP_CODE_LENGTH = 8
const BACKUP_CODE = new RegExp(`^\\d{${BACKUP_CODE_LENGTH}}$`)

/**
 * How many backup codes are asked for when the policy has not said yet -- the frame before
 * `/catalog/policy` lands. The number itself is the server's `GFS_BACKUP_CODES_REQUIRED`.
 */
export const DEFAULT_BACKUP_CODES = 3

export function isBackupCode(value: string): boolean {
  return BACKUP_CODE.test(value.trim())
}

/** `count` empty boxes, keeping whatever was already typed into the first ones. */
export function resizeCodes(codes: string[], count: number): string[] {
  return Array.from({ length: count }, (_, index) => codes[index] ?? '')
}
