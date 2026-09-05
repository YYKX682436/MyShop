package com.myshop.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentUrlServiceTest {

    @Test
    void usesTheConfiguredPublicOriginWithoutRequestHostGuessing() {
        PaymentUrlService service = new PaymentUrlService("https://shop.example.com///");

        assertEquals("https://shop.example.com/api/payments/webhook/epay",
                service.webhookUrl("epay"));
        String returnUrl = service.paymentReturnUrl("100001");
        assertEquals("https://shop.example.com/order/query?orderNo=100001&paymentReturn=1", returnUrl);
        assertFalse(returnUrl.contains("lookup_token"));
    }

    @Test
    void rejectsAConfiguredPublicUrlWithQueryOrFragment() {
        assertThrows(RuntimeException.class,
                () -> new PaymentUrlService("https://shop.example.com/store?tenant=1"));
        assertThrows(RuntimeException.class,
                () -> new PaymentUrlService("http://"));
    }
}
