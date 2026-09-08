package com.myshop.payment;

/** Provider-neutral lifecycle of a payment attempt. */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    CLOSED
}
