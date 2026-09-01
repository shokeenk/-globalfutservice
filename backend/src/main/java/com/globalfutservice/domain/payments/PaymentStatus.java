package com.globalfutservice.domain.payments;

/** Mirror of the gateway's own payment lifecycle, kept deliberately thin. */
public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    REFUNDED
}
