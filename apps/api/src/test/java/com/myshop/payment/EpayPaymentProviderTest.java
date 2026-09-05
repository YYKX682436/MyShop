package com.myshop.payment;

import com.myshop.exception.BusinessException;
import com.myshop.service.EpayService;
import com.myshop.service.PaymentUrlService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpayPaymentProviderTest {

    @Test
    void exposesOnlyTheThreeCoreConfigurationFields() {
        EpayPaymentProvider provider = new EpayPaymentProvider(mock(EpayService.class),
                new PaymentUrlService("https://shop.example.com"));

        assertEquals(List.of("alipay", "wxpay"), provider.paymentTypes());
        assertEquals("易支付兼容协议", provider.displayName());
        assertEquals(List.of("pid", "key", "api_url"),
                provider.configSchema().stream().map(PaymentProvider.ConfigField::name).toList());
        assertEquals(3, provider.configSchema().size());
        assertFalse(provider.configSchema().stream().anyMatch(field ->
                "notify_url".equals(field.name()) || "return_url".equals(field.name())));
        assertEquals(List.of(true, true, true),
                provider.configSchema().stream().map(PaymentProvider.ConfigField::required).toList());
    }

    @Test
    void rejectsEachMissingCoreConfigurationField() {
        EpayPaymentProvider provider = new EpayPaymentProvider(mock(EpayService.class),
                new PaymentUrlService("https://shop.example.com"));

        for (String missing : List.of("pid", "key", "api_url")) {
            Map<String, String> config = validConfig();
            config.remove(missing);

            BusinessException exception = assertThrows(BusinessException.class, () ->
                    provider.create(new PaymentProvider.PaymentRequest(
                            "order-id", "alipay", "商品", BigDecimal.TEN,
                            "127.0.0.1", "pc", config, "100001")));

            assertEquals(50001, exception.getCode(), missing);
        }
    }

    @Test
    void generatesServerOwnedCallbackAndReturnUrlsAndMapsWxpay() {
        EpayService epayService = mock(EpayService.class);
        when(epayService.createPayment(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new EpayService.EpayResult(1, "ok", "trade-1",
                        "https://pay.example/checkout", "qr-data"));
        EpayPaymentProvider provider = new EpayPaymentProvider(epayService,
                new PaymentUrlService("https://shop.example.com/"));

        PaymentProvider.PaymentResult result = provider.create(new PaymentProvider.PaymentRequest(
                "order-id", "wxpay", "商品", new BigDecimal("10.00"),
                "127.0.0.1", "pc", validConfig(), "100001"));

        assertEquals("https://pay.example/checkout", result.payUrl());
        verify(epayService).createPayment(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example"),
                "order-id", "wxpay", "商品", new BigDecimal("10.00"), "127.0.0.1", "pc",
                "https://shop.example.com/api/payments/webhook/epay",
                "https://shop.example.com/order/query?orderNo=100001&paymentReturn=1");
    }

    private Map<String, String> validConfig() {
        return new LinkedHashMap<>(Map.of(
                "pid", "pid",
                "key", "secret",
                "api_url", "https://pay.example"));
    }
}
