/**
 * The operating entity, written once.
 *
 * <p>These facts have to appear identically wherever they appear at all. A payment
 * gateway's reviewer checks that they match, and typing them out five times is how the
 * address on one page ends up a flat number different from another.
 *
 * <p><b>There is no phone number here any more.</b> It was removed from every page at
 * the owner's instruction, and the constant went with it rather than being left unused:
 * a spare `phone` field is how a number nobody meant to publish reappears on one page a
 * year later. Discord is the contact channel that replaced it.
 *
 * <p>The address is the exception to "everywhere". It is a residential one, so it is
 * published on exactly one page: /contact, which is where the payment gateway's
 * verification points. It is deliberately absent from the sitewide footer, the about
 * page and the operator block on the policy documents — every one of which names and
 * links the operator, so a reader who wants the address is one click from it.
 *
 * <p>Taken from the KYC submission, and formatted exactly as filed. The legal name is
 * the one on the PAN, which is why it is a person rather than a trading name — the
 * business trades as Global FUT Services and is operated by an individual, and the
 * policy pages have to say both.
 */
export const BUSINESS = {
  /** As per PAN. Appears wherever the operator must be named. */
  legalName: 'Vinay Kumar Sharma',
  tradingName: 'Global FUT Services',
  registeredAddress: '2/40 KBHB, Jodhpur, Rajasthan - 342005',
  email: 'globalfutservices@gmail.com',

  /**
   * The official Discord.
   *
   * <p>Named in the terms of service as the route for coaching scheduling, support,
   * safety-policy claims and disputes — which makes it a contractual contact channel
   * rather than a social link, and it is why it sits here with the address and the
   * phone number rather than beside the Instagram icon.
   *
   * <p><b>Every link on the site opens a direct message rather than the server</b>, at
   * the owner's instruction, so a customer reaches a person instead of a channel.
   *
   * <p>The operator line in the footer and on the policy pages is the exception: there
   * the handle is printed as <i>text</i>, also at the owner's instruction. It sits beside
   * a legal name and an email as a way of identifying who you are dealing with, and a
   * link there would read as a support button rather than a contact detail.
   *
   * <p>Discord has no link that opens a DM from a username, which is why this needs the
   * numeric account id: {@code discord.com/users/<id>} is the only form that works, and
   * {@code globalfutservices} on its own cannot be linked to at all.
   *
   * <p><b>This depends on a setting outside the codebase.</b> The profile shows a Message
   * button only to people allowed to DM the account. With "direct messages from server
   * members" disabled, or friend-only messaging, a customer following any of these links
   * arrives at a profile they cannot write to — and the whole point of the change is lost
   * silently, with nothing on the site able to detect it.
   *
   * <p>The invite is kept, unused by the storefront, because the terms name a Discord as
   * a contractual channel and a server outlives one person's inbox. Restoring it anywhere
   * is a one-word change.
   */
  discordName: 'globalfutservices',
  discordUserId: '1300551868174569595',
  discordDm: 'https://discord.com/users/1300551868174569595',
  discordInvite: 'https://discord.com/invite/8FeP7C6tXt',

  /**
   * The line-of-business statement.
   *
   * <p>Written to be accurate rather than flattering: a gateway reviewing this
   * compares it against what the site actually sells, and a description that
   * undersells ("gaming services") reads as evasive next to a checkout selling
   * virtual currency. All three revenue lines are named.
   */
  lineOfBusiness:
    'Global FUT Services provides digital gaming services including virtual currency '
    + 'sales, coaching, and competitive boosting for EA Sports FC Ultimate Team players.',
} as const

export const EMAIL_HREF = `mailto:${BUSINESS.email}`
