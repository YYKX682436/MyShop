package com.myshop.service;

import java.util.Map;
import java.util.UUID;

public interface PaymentService {

    /**
     * Create payment for an order.
     * Returns provider-neutral payment info including payment_id, status,
     * next_action, and expires_at.
     */
    Map<String, Object> createPayment(UUID orderId, String paymentMethod, java.math.BigDecimal amount);

    /**
     * Create payment with device hint (pc/mobile/wxpay/alipay).
     */
    Map<String, Object> createPayment(UUID orderId, String paymentMethod, java.math.BigDecimal amount, String device);

    /**
     * Re-initiate payment for a PENDING order by closing its current attempt
     * and creating a new independent attempt.
     * @param requestUserId 当前请求用户的 ID（可为 null，表示未登录/游客）
     */
    Map<String, Object> repay(UUID orderId, String device, UUID requestUserId, String lookupToken);
}
