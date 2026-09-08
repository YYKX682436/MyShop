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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpayPaymentProviderTest {

    @Test
    void exposesOnlyGatewayMerchantIdAndKey() {
        EpayPaymentProvider provider = provider(mock(EpayService.class));

        assertEquals(List.of("alipay", "wxpay"), provider.paymentTypes());
        assertEquals(List.of("api_url", "pid", "key"),
                provider.configSchema().stream().map(PaymentProvider.ConfigField::name).toList());
        assertEquals(List.of("支付网关", "商户号 / PID", "商户密钥 / KEY"),
                provider.configSchema().stream().map(PaymentProvider.ConfigField::label).toList());
        assertEquals(List.of(true, true, true),
                provider.configSchema().stream().map(PaymentProvider.ConfigField::required).toList());
    }

    @Test
    void rejectsEachMissingMerchantField() {
        EpayPaymentProvider provider = provider(mock(EpayService.class));
        for (String missing : List.of("api_url", "pid", "key")) {
            Map<String, String> config = validConfig();
            config.remove(missing);
            BusinessException exception = assertThrows(BusinessException.class, () -> provider.create(
                    new PaymentProvider.PaymentRequest("merchant-1", "alipay", "商品", BigDecimal.TEN,
                            "127.0.0.1", "pc", config, "100001")));
            assertEquals(50001, exception.getCode(), missing);
        }
    }

    @Test
    void delegatesCreationWithOnlyCanonicalMerchantConfig() {
        EpayService epayService = mock(EpayService.class);
        PaymentNextAction.Redirect action = new PaymentNextAction.Redirect("https://pay.example/checkout");
        when(epayService.createPayment(any(), eq("merchant-1"), eq("wxpay"), eq("商品"),
                eq(new BigDecimal("10.00")), eq("127.0.0.1"), eq("pc"), any(), any()))
                .thenReturn(new EpayService.EpayCreateResult("trade-1", action));
        EpayPaymentProvider provider = provider(epayService);

        PaymentProvider.PaymentResult result = provider.create(new PaymentProvider.PaymentRequest(
                "merchant-1", "wxpay", "商品", new BigDecimal("10.00"),
                "127.0.0.1", "pc", validConfig(), "100001"));

        assertEquals("trade-1", result.providerTradeNo());
        assertEquals(action, result.nextAction());
        verify(epayService).createPayment(
                new EpayService.ChannelConfig("https://pay.example", "pid", "secret"),
                "merchant-1", "wxpay", "商品", new BigDecimal("10.00"), "127.0.0.1", "pc",
                "https://shop.example.com/api/payments/webhook/epay",
                "https://shop.example.com/order/query?orderNo=100001&paymentReturn=1");
    }

    @Test
    void verifiesSignedCallbackAndMapsQuery() {
        EpayService epayService = mock(EpayService.class);
        when(epayService.verifySign(eq("secret"), any(), eq("valid-signature"))).thenReturn(true);
        when(epayService.queryOrder(any(), eq("merchant-1"))).thenReturn(
                new EpayService.OrderQueryResult(PaymentStatus.PAID, new BigDecimal("10.00"), "trade-1"));
        EpayPaymentProvider provider = provider(epayService);

        PaymentProvider.WebhookRequest callback = new PaymentProvider.WebhookRequest(
                "POST", Map.of(), Map.of(), Map.of(
                        "out_trade_no", List.of("merchant-1"),
                        "trade_no", List.of("trade-1"),
                        "trade_status", List.of("TRADE_SUCCESS"),
                        "money", List.of("10.00"),
                        "sign", List.of("valid-signature")), "raw");
        PaymentProvider.CallbackResult callbackResult = provider.verifyAndParseCallback(callback, validConfig());
        assertEquals("merchant-1", callbackResult.merchantTradeNo());
        assertEquals(PaymentStatus.PAID, callbackResult.status());

        PaymentProvider.PaymentQueryResult query = provider.query(
                new PaymentProvider.PaymentQueryRequest("merchant-1", validConfig()));
        assertEquals(PaymentStatus.PAID, query.status());
        assertEquals(new BigDecimal("10.00"), query.amount());
        assertEquals("trade-1", query.providerTradeNo());
        verify(epayService).queryOrder(
                new EpayService.ChannelConfig("https://pay.example", "pid", "secret"), "merchant-1");
    }

    private EpayPaymentProvider provider(EpayService service) {
        return new EpayPaymentProvider(service, new PaymentUrlService("https://shop.example.com"));
    }

    private Map<String, String> validConfig() {
        return new LinkedHashMap<>(Map.of(
                "api_url", "https://pay.example",
                "pid", "pid",
                "key", "secret"));
    }
}
