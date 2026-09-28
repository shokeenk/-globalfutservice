package com.globalfutservice.notify;

/**
 * Staff replied to a customer's support ticket.
 *
 * @param email where the reply goes: the ticket's email, the customer's own
 * @param link  the ticket on the site, with the private key a guest needs to open it
 */
public record SupportReplyNotification(String email, String ticketRef, String subject, String message,
                                       String link) {
}
