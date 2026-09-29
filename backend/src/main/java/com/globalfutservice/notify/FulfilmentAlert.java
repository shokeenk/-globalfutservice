package com.globalfutservice.notify;

/**
 * Something about sending an order to the fulfilment partner needs a person.
 *
 * <p>For staff channels only. Never carries a sign-in, a backup code or a response body:
 * {@code detail} is written by us, and {@code code} is a bare identifier such as
 * {@code TIMEOUT} or {@code InvalidPassword}.
 *
 * @param headline one line, e.g. "Needs review: could not confirm the order was created"
 * @param detail   what happened and what to do, for the admin reading it
 */
public record FulfilmentAlert(String publicRef, String headline, String detail, String code,
                              String adminDeepLink) {
}
