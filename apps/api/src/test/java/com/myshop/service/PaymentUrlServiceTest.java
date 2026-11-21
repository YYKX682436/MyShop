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
        String returnUrl = service.paymentReturnUrl("6d7f2a1e-7f5d-4b17-a1d1-9bf80f0d9b2a");
        assertEquals("https://shop.example.com/order/6d7f2a1e-7f5d-4b17-a1d1-9bf80f0d9b2a?paymentReturn=1", returnUrl);
    }

    @Test
    void rejectsAConfiguredPublicUrlWithQueryOrFragment() {
        assertThrows(RuntimeException.class,
                () -> new PaymentUrlService("https://shop.example.com/store?tenant=1"));
        assertThrows(RuntimeException.class,
                () -> new PaymentUrlService("http://"));
    }
    @org.junit.jupiter.api.Test
    void subsiteReturnUsesValidatedPersistedPrefix() {
        PaymentUrlService service = new PaymentUrlService("https://mc.mc520.top");
        org.junit.jupiter.api.Assertions.assertEquals("https://alice.mc520.top/order/123?paymentReturn=1", service.paymentReturnUrl("123", "alice"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.paymentReturnUrl("123", "evil.example"));
        org.junit.jupiter.api.Assertions.assertEquals("https://mc.mc520.top/api/payments/webhook/epay", service.webhookUrl("epay"));
    }
}
