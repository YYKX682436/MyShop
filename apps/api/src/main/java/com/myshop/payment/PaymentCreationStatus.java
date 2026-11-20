package com.myshop.payment;

/** Creation of the payment carrier is independent from settlement of the payment. */
public enum PaymentCreationStatus {
    INTENT, SUBMITTING, READY, REJECTED, UNKNOWN
}
