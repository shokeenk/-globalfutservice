import { BUSINESS } from '../content/business'
import type { DiscordAccess } from './types'

/**
 * Where the Discord step for one order should send the customer.
 *
 * <p>Two screens ask this -- the tracking page and the boosting confirmation -- and they
 * answer it identically or they are a bug. The rendering stays with each screen; only the
 * branching lives here.
 *
 * <ul>
 *   <li>{@code TICKET}: they have been let into their ticket, so the button opens it.</li>
 *   <li>{@code JOIN}: they signed in with Discord, so their ticket opens for them by itself
 *       once they are in the server.</li>
 *   <li>{@code MESSAGE}: we do not know their Discord account. They message us directly,
 *       with their order reference, through the same direct-message link every Discord
 *       link on the site uses. (The /verify command is not offered: Discord refuses to
 *       register it.)</li>
 * </ul>
 */
export type TicketLink =
  | { kind: 'TICKET'; href: string }
  | { kind: 'JOIN'; href: string }
  | { kind: 'MESSAGE'; href: string }

/**
 * @param access the order's {@code discordAccess}, decided server-side
 * @returns null when there is no Discord step for this order at all -- a coin order. The
 *          caller renders nothing.
 */
export function ticketLink(access: DiscordAccess | null | undefined): TicketLink | null {
  if (!access || access.mode === 'NONE') {
    return null
  }
  if (access.mode === 'DIRECT' && access.channelUrl) {
    return { kind: 'TICKET', href: access.channelUrl }
  }
  if (access.mode === 'PENDING' && access.inviteUrl) {
    return { kind: 'JOIN', href: access.inviteUrl }
  }
  return { kind: 'MESSAGE', href: BUSINESS.discordDm }
}
