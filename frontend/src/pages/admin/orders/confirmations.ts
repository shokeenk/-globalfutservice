/**
 * The questions the console asks before an action that cannot be taken back.
 *
 * <p>Kept in one place because the same action is offered in two: the Orders table's
 * Next Action button and the order page. Both must ask exactly the same question — an
 * operator who has read it once on the order page should meet the same words in the
 * table, not a shorter version that leaves out what happens to the customer's password.
 */

/** Releasing a coin order sends the customer's EA sign-in to the fulfilment partner. */
export function releaseQuestion(publicRef: string): string {
  return `Release ${publicRef} to the fulfilment partner?\n\n`
    + `This sends the customer's EA sign-in — email, password and backup codes — to `
    + `FUT Transfer so they can work the order.\n\n`
    + `It cannot be undone. Once sent, the credentials are with a third party.`
}

/** Starting a boosting order moves it to In progress; the customer sees it as being delivered. */
export function startQuestion(publicRef: string): string {
  return `Start ${publicRef}?\n\nIt moves to In progress, and the customer's order page says so.`
}

/** The sign-in reminder emails the customer. */
export function remindQuestion(publicRef: string, email: string | null): string {
  return `Email ${email ?? 'the customer'} again asking for the EA sign-in on ${publicRef}?\n\n`
    + `It is the same request they were sent when they paid. The reminder is noted on the order.`
}
