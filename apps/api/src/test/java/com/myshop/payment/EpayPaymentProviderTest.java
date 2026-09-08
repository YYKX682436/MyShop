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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpayPaymentProviderTest {

    @Test
    void exposesOnlyTheProviderCredentialsAndChannelLevelActionControls() {
        EpayPaymentProvider provider = provider(mock(EpayService.class));

        assertEquals(List.of("alipay", "wxpay"), provider.paymentTypes());
        assertEquals("易支付兼容协议", provider.displayName());
        assertEquals(List.of("pid", "key", "api_url", "submit_fallback_enabled", "carrier_mode"),
                provider.configSchema().stream().map(PaymentProvider.ConfigField::name).toList());
        assertEquals(5, provider.configSchema().size());
        assertFalse(provider.configSchema().stream().anyMatch(field ->
                "notify_url".equals(field.name()) || "return_url".equals(field.name())));
        assertEquals(List.of(true, true, true, false, false),
                provider.configSchema().stream().map(PaymentProvider.ConfigField::required).toList());
        assertEquals(false, provider.configSchema().get(3).defaultValue());
        assertEquals("auto", provider.configSchema().get(4).defaultValue());
    }

    @Test
    void rejectsEachMissingCredential() {
        EpayPaymentProvider provider = provider(mock(EpayService.class));

        for (String missing : List.of("pid", "key", "api_url")) {
            Map<String, String> config = validConfig();
            config.remove(missing);

            BusinessException exception = assertThrows(BusinessException.class, () ->
                    provider.create(new PaymentProvider.PaymentRequest(
                            "merchant-1", "alipay", "商品", BigDecimal.TEN,
                            "127.0.0.1", "pc", config, "100001")));

            assertEquals(50001, exception.getCode(), missing);
        }
    }

    @Test
    void delegatesIndependentMerchantTradeNoAndReturnsNeutralNextAction() {
        EpayService epayService = mock(EpayService.class);
        PaymentNextAction.Redirect action = new PaymentNextAction.Redirect("https://pay.example/checkout");
        when(epayService.createPayment(any(), anyString(), anyString(), anyString(), any(), anyString(),
                anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(new EpayService.EpayCreateResult("trade-1", action));
        EpayPaymentProvider provider = provider(epayService);

        PaymentProvider.PaymentResult result = provider.create(new PaymentProvider.PaymentRequest(
                "merchant-1", "wxpay", "商品", new BigDecimal("10.00"),
                "127.0.0.1", "pc", validConfig(), "100001"));

        assertEquals("trade-1", result.providerTradeNo());
        assertEquals(action, result.nextAction());
        verify(epayService).createPayment(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example"),
                "merchant-1", "wxpay", "商品", new BigDecimal("10.00"), "127.0.0.1", "pc",
                "https://shop.example.com/api/payments/webhook/epay",
                "https://shop.example.com/order/query?orderNo=100001&paymentReturn=1",
                false, "auto");
    }

    @Test
    void normalizesSignedCallbackIntoTheStandardResult() {
        EpayService epayService = mock(EpayService.class);
        when(epayService.verifySign(eq("secret"), any(), eq("valid-signature"))).thenReturn(true);
        EpayPaymentProvider provider = provider(epayService);

        Map<String, String> values = new LinkedHashMap<>();
        values.put("out_trade_no", "merchant-1");
        values.put("trade_no", "trade-1");
        values.put("trade_status", "TRADE_SUCCESS");
        values.put("money", "10.00");
        values.put("sign", "valid-signature");
        PaymentProvider.WebhookRequest request = new PaymentProvider.WebhookRequest(
                "POST", Map.of("X-Test", "1"), Map.of(), Map.of(
                        "out_trade_no", List.of(values.get("out_trade_no")),
                        "trade_no", List.of(values.get("trade_no")),
                        "trade_status", List.of(values.get("trade_status")),
                        "money", List.of(values.get("money")),
                        "sign", List.of(values.get("sign"))), "raw");

        PaymentProvider.CallbackResult result = provider.verifyAndParseCallback(request, validConfig());

        assertEquals("epay:trade-1", result.eventId());
        assertEquals("merchant-1", result.merchantTradeNo());
        assertEquals("trade-1", result.providerTradeNo());
        assertEquals(PaymentStatus.PAID, result.status());
        assertEquals(new BigDecimal("10.00"), result.amount());
        assertEquals("merchant-1", provider.extractMerchantTradeNo(request));
    }

    @Test
    void mapsQueryIntoTheStandardStatusAndAmount() {
        EpayService epayService = mock(EpayService.class);
        when(epayService.queryOrder(any(), eq("merchant-1")))
                .thenReturn(new EpayService.OrderQueryResult(
                        PaymentStatus.PAID, new BigDecimal("10.00"), "trade-1"));
        EpayPaymentProvider provider = provider(epayService);

        PaymentProvider.PaymentQueryResult result = provider.query(
                new PaymentProvider.PaymentQueryRequest("merchant-1", validConfig()));

        assertEquals(PaymentStatus.PAID, result.status());
        assertEquals(new BigDecimal("10.00"), result.amount());
        assertEquals("trade-1", result.providerTradeNo());
    }

    private EpayPaymentProvider provider(EpayService service) {
        return new EpayPaymentProvider(service, new PaymentUrlService("https://shop.example.com"));
    }

    private Map<String, String> validConfig() {
        return new LinkedHashMap<>(Map.of(
                "pid", "pid",
                "key", "secret",
                "api_url", "https://pay.example"));
    }
}
