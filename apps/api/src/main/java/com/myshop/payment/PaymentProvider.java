package com.myshop.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Small SPI for adding a payment provider without changing order code. */
public interface PaymentProvider {
    String id();
    String displayName();
    List<String> paymentTypes();
    List<ConfigField> configSchema();
    PaymentResult create(PaymentRequest request);

    /**
     * Verify an untrusted provider callback using the same channel profile
     * that created the order. Providers that do not use callbacks may keep
     * the default implementation, but an Epay channel must implement it.
     */
    default boolean verifyCallback(CallbackRequest request) {
        return false;
    }

    /**
     * Query the provider server-to-server before a callback can change order
     * state. This is deliberately part of the SPI so another provider does
     * not require changes in the order or webhook services.
     */
    default PaymentQueryResult query(PaymentQueryRequest request) {
        throw new UnsupportedOperationException("Payment provider does not support server-side query");
    }

    record ConfigField(String name, String label, String type, boolean secret, boolean required) { }

    record PaymentRequest(String orderId, String paymentType, String productName,
                          BigDecimal amount, String clientIp, String device,
                          Map<String, String> config) { }

    record CallbackRequest(Map<String, String> params, Map<String, String> config) { }

    record PaymentQueryRequest(String orderId, Map<String, String> config) { }

    record PaymentQueryResult(String tradeStatus, String money, String tradeNo) { }

    record PaymentResult(String tradeNo, String payUrl, String qrcodeUrl) { }
}
