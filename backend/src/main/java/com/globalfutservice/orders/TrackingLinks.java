package com.globalfutservice.orders;

/**
 * The customer's tracking page for one order: the address every order email links to, and
 * the one staff open from the admin to see what the customer sees. Built in one place so
 * the two can never drift apart.
 *
 * <p>The order reference and nothing else: no email, no token. The page itself still asks
 * who is looking before it shows anything.
 */
public final class TrackingLinks {

    private TrackingLinks() {
    }

    public static String trackUrl(String publicUrl, String publicRef) {
        return publicUrl + "/track?ref=" + publicRef;
    }
}
