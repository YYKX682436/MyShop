package com.myshop.service;

import java.util.Map;
import java.util.UUID;

public interface PaymentService {

    /**
     * Create an online payment for an ONLINE order using its payment channel code.
     * Returns provider-neutral payment info including payment_id, status,
     * next_action, and expires_at.
     */
    Map<String, Object> createPayment(UUID orderId, String paymentChannelCode, java.math.BigDecimal amount);

    /**
     * Create payment with device hint (pc/mobile/wxpay/alipay).
     */
    Map<String, Object> createPayment(UUID orderId, String paymentChannelCode, java.math.BigDecimal amount, String device);

    /** Confirm the active attempt through its provider without changing authorization context. */
    void refreshPayment(UUID orderId);

    /**
     * Re-initiate payment for a PENDING order by closing its current attempt
     * and creating a new independent attempt.
     */
    Map<String, Object> repay(UUID orderId, String device);
}
