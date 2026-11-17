package com.resilient.payment.domain;

public enum PaymentStatus {
    /** The customer was charged. */
    CHARGED,
    /** The charge was refused (no funds or an invalid amount); remembered so a repeated command gets the same answer. */
    FAILED,
    /** A charged payment was given back. */
    REFUNDED,
    /** A refund arrived before the charge: remembered so the late charge is refused. */
    CANCELLED
}
