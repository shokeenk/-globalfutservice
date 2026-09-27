package com.globalfutservice.notify;

/**
 * Staff wrote to a customer about a support ticket.
 *
 * @param email   where the reply goes: the ticket's email, the customer's own
 * @param link    the ticket on the site, with the private key a guest needs to open it
 * @param opened  true when staff started the ticket, rather than answered one
 */
public record SupportReplyNotification(String email, String ticketRef, String subject, String message,
                                       String link, boolean opened) {
}
