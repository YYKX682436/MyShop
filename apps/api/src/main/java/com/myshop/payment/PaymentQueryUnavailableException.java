package com.myshop.payment;

/**
 * Signals that a provider explicitly reported that its server-side order
 * query endpoint is unavailable or unsupported. This is different from a
 * transient query failure, which must remain a callback failure.
 */
public class PaymentQueryUnavailableException extends RuntimeException {

    public PaymentQueryUnavailableException(String message) {
        super(message);
    }
}
