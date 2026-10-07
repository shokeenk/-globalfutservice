/**
 * Whether a typed email address is shaped like one: something, an @, a domain with a dot.
 *
 * <p>A courtesy, not a control. The server validates the address and decides; this only
 * means a customer who typed "name@gmail" hears so under the field before pressing Pay,
 * rather than from an error at the bottom of the form after it.
 */
export function looksLikeEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim())
}
