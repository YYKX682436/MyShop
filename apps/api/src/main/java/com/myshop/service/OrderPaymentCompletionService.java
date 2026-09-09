package com.myshop.service;

import com.myshop.entity.Order;

/** Shared first-payment state transition for balance and online payments. */
public interface OrderPaymentCompletionService {

    /**
     * Marks a pending order paid and schedules fulfillment after commit.
     * Returns true only when this invocation performed the first transition.
     */
    boolean completeFirstPayment(Order order);
}
