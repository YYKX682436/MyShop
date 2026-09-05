package com.myshop.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentUrlServiceTest {

    @Test
    void usesTheConfiguredPublicOriginWithoutRequestHostGuessing() {
        PaymentUrlService service = new PaymentUrlService("https://shop.example.com///");

        assertEquals("https://shop.example.com/api/payments/webhook/epay",
                service.webhookUrl("epay"));
        assertEquals("https://shop.example.com/pay/550e8400-e29b-41d4-a716-446655440000?orderNo=100001&method=wxpay",
                service.paymentReturnUrl("550e8400-e29b-41d4-a716-446655440000", "100001", "wxpay"));
    }

    @Test
    void rejectsAConfiguredPublicUrlWithQueryOrFragment() {
        assertThrows(RuntimeException.class,
                () -> new PaymentUrlService("https://shop.example.com/store?tenant=1"));
        assertThrows(RuntimeException.class,
                () -> new PaymentUrlService("http://"));
    }
}
