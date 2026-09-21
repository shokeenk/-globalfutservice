import type { DiscordAccess } from './types'

/**
 * Where a "Join Discord" button should actually point, for one order.
 *
 * <p>Four screens ask this question — the tracking page and the three checkout
 * confirmations — and they answer it identically or they are a bug. Before this, each
 * one linked a hardcoded shared channel, so a customer who had just paid for coaching
 * landed in the general order channel rather than the ticket opened for their order
 * seconds earlier.
 *
 * <p>The rendering deliberately stays with each screen: they look nothing alike, and a
 * component that unified the markup would have to win an argument with three designs.
 * It is the branching that must not drift, so only the branching lives here.
 */
export type TicketLink = {
  /** Where the button goes. */
  href: string
  /**
   * True when this opens the customer's own ticket.
   *
   * <p>False means the server's front door, which is as far as we can send somebody
   * whose Discord account we do not yet know. The label should say so — promising "your
   * ticket" and delivering a lobby is worse than saying "join the server".
   */
  direct: boolean
  /** The command that gets them the rest of the way, already carrying their reference. */
  command: string | null
}

/**
 * @param access the order's {@code discordAccess}, decided server-side
 * @returns null when there is no Discord step for this order at all — a coin order, or
 *          a deployment with no invite configured. The caller renders nothing.
 */
export function ticketLink(access: DiscordAccess | null | undefined): TicketLink | null {
  if (!access || access.mode === 'NONE') {
    return null
  }

  // Already granted the channel: link straight in. This is the only branch that can
  // honestly call itself the customer's ticket.
  if (access.mode === 'DIRECT' && access.channelUrl) {
    return { href: access.channelUrl, direct: true, command: null }
  }

  if (!access.inviteUrl) {
    return null
  }

  // VERIFY carries the command; PENDING and QUOTE do not — PENDING because the grant is
  // automatic once the ticket exists, QUOTE because the command is not registered on
  // this deployment and pointing at one that does not answer is worse than silence.
  return {
    href: access.inviteUrl,
    direct: false,
    command: access.mode === 'VERIFY' ? access.command : null,
  }
}
