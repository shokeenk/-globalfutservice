package com.globalfutservice.notify;

/**
 * The customer has something to do before their order can carry on: sign in again, make
 * new backup codes, free some transfer slots.
 *
 * <p>For the customer's own channels only -- their email, and their order's Discord
 * ticket, which they can read. So {@code instruction} is always the storefront's own
 * sentence, never a partner's code: the partner's detail goes to staff separately, as a
 * {@link FulfilmentAlert}.
 *
 * @param instruction what to do, in one or two sentences the customer can act on
 */
public record CustomerActionNotification(OrderNotification order, String instruction) {
}
