package com.globalfutservice.notify;

/**
 * A payment screenshot, on its way to the channels that can show one.
 *
 * <p>Carries the image itself rather than a link to it: the operator reading the alert is
 * usually on a phone that is not signed into the admin console, and a link they cannot
 * open is the same as no screenshot.
 */
public record PaymentProofNotification(String publicRef, byte[] image, String contentType) {

    /** Never the bytes -- they are a customer's banking screen. */
    @Override
    public String toString() {
        return "PaymentProofNotification[" + publicRef + ", " + contentType + ", "
                + (image == null ? 0 : image.length) + " bytes]";
    }
}
