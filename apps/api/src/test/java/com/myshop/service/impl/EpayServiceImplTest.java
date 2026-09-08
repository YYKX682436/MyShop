package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentQueryUnavailableException;
import com.myshop.payment.PaymentStatus;
import com.myshop.service.EpayService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpayServiceImplTest {

    @Test
    void appendsMapiPhpToAGatewayRootExactlyOnce() {
        assertEquals("https://pay.example.com/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com"));
        assertEquals("https://pay.example.com/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com/"));
        assertEquals("https://pay.example.com/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com/mapi.php"));
        assertEquals("https://pay.example.com/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com/mapi.php/mapi.php"));
        assertEquals("https://pay.example.com/gateway/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com/gateway/mapi.php/"));
        assertEquals("https://pay.example.com/gateway/mapi.php",
                EpayServiceImpl.normalizeApiUrl("https://pay.example.com/gateway"));
    }

    @Test
    void rejectsNonHttpApiUrls() {
        assertThrows(BusinessException.class,
                () -> EpayServiceImpl.normalizeApiUrl("ftp://pay.example.com"));
        assertThrows(BusinessException.class,
                () -> EpayServiceImpl.normalizeApiUrl("https://pay.example.com/mapi.php?x=1"));
    }

    @Test
    void mapsGatewayResponsesToNeutralActions() {
        EpayService.EpayCreateResult deepLink = createPaymentResponse(
                "{\"code\":1,\"trade_no\":\"trade-1\",\"urlscheme\":\"weixin://dl/business/?ticket=xxx\"}",
                "wxpay", false, "auto");
        assertEquals("trade-1", deepLink.providerTradeNo());
        assertEquals(new PaymentNextAction.DeepLink(
                "weixin://dl/business/?ticket=xxx", "wechat"), deepLink.nextAction());

        EpayService.EpayCreateResult qrCode = createPaymentResponse(
                "{\"code\":1,\"qrcode\":\"weixin://qr/123\"}",
                "wxpay", false, "auto");
        assertEquals(new PaymentNextAction.QrCode("weixin://qr/123"), qrCode.nextAction());

        EpayService.EpayCreateResult redirect = createPaymentResponse(
                "{\"code\":1,\"payurl2\":\"https://pay.example/checkout\"}",
                "alipay", false, "auto");
        assertEquals(new PaymentNextAction.Redirect("https://pay.example/checkout"), redirect.nextAction());
    }

    @Test
    void forcedModesFailClosedWhenTheGatewayCannotSupplyThatAction() {
        BusinessException qrFailure = assertThrows(BusinessException.class, () -> createPaymentResponse(
                "{\"code\":1,\"payurl\":\"https://pay.example/checkout\"}",
                "alipay", false, "qr_code"));
        assertTrue(qrFailure.getMessage().contains("兼容"));

        EpayService.EpayCreateResult client = createPaymentResponse(
                "{\"code\":1,\"payurl\":\"https://pay.example/checkout\"}",
                "wxpay", false, "open_in_client");
        assertEquals(new PaymentNextAction.OpenInClient(
                "https://pay.example/checkout", "wechat"), client.nextAction());

        BusinessException invalidMode = assertThrows(BusinessException.class, () -> createPaymentResponse(
                "{\"code\":1,\"qrcode\":\"qr\"}", "wxpay", false, "unsupported"));
        assertTrue(invalidMode.getMessage().contains("载体模式无效"));

        BusinessException incompatible = assertThrows(BusinessException.class, () -> createPaymentResponse(
                "{\"code\":1,\"qrcode\":\"qr\"}", "wxpay", true, "redirect"));
        assertTrue(incompatible.getMessage().contains("兼容"));
    }

    @Test
    void submitPageFallbackIsDisabledByDefaultAndExplicitlyEnabledPerChannel() {
        String noAction = "{\"code\":1,\"trade_no\":\"trade-1\",\"payurl\":\"\",\"qrcode\":\"\"}";
        BusinessException disabled = assertThrows(BusinessException.class,
                () -> createPaymentResponse(noAction, "wxpay", false, "auto"));
        assertTrue(disabled.getMessage().contains("兼容"));

        EpayService.EpayCreateResult fallback = createPaymentResponse(noAction, "wxpay", true, "auto");
        PaymentNextAction.Redirect action = assertInstanceOf(PaymentNextAction.Redirect.class,
                fallback.nextAction());
        assertTrue(action.url().startsWith("https://pay.example.com/submit.php?"));
        assertTrue(action.url().contains("pid=pid"));
        assertTrue(action.url().contains("out_trade_no=merchant-1"));
        assertTrue(action.url().contains("sign_type=MD5"));
        assertFalse(action.url().contains("key=secret"));

        EpayService.EpayCreateResult withoutProviderTradeNo = createPaymentResponse(
                "{\"code\":1,\"payurl\":\"\",\"qrcode\":\"\"}",
                "wxpay", true, "auto");
        assertTrue(withoutProviderTradeNo.nextAction() instanceof PaymentNextAction.Redirect);
    }

    @Test
    void normalizesRelativeResponseValuesAgainstTheGatewayOrigin() {
        EpayService.EpayCreateResult relative = createPaymentResponse(
                "{\"code\":1,\"qrcode\":\"/api/pay/toapp/123\"}",
                "wxpay", false, "auto");
        assertEquals(new PaymentNextAction.QrCode(
                "https://pay.example.com/api/pay/toapp/123"), relative.nextAction());

        EpayService.EpayCreateResult absolute = createPaymentResponse(
                "{\"code\":1,\"payurl\":\"https://other.example.com/checkout\"}",
                "alipay", false, "auto");
        assertEquals(new PaymentNextAction.Redirect(
                "https://other.example.com/checkout"), absolute.nextAction());
    }

    @Test
    void mapsQueryIntoStandardStatusAndAmount() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity(
                eq("https://pay.example.com/api.php?act=order&pid=pid&key=secret&out_trade_no=merchant-1"),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok(
                        "{\"status\":\"TRADE_SUCCESS\",\"money\":\"10.00\",\"trade_no\":\"trade-1\"}"));
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper());

        EpayService.OrderQueryResult result = service.queryOrder(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example.com/mapi.php"),
                "merchant-1");

        assertEquals(PaymentStatus.PAID, result.status());
        assertEquals(new BigDecimal("10.00"), result.amount());
        assertEquals("trade-1", result.providerTradeNo());
        verify(restTemplate).getForEntity(
                eq("https://pay.example.com/api.php?act=order&pid=pid&key=secret&out_trade_no=merchant-1"),
                eq(String.class));
    }

    @Test
    void reportsAnExplicitlyUnsupportedHtmlQueryEndpoint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        when(restTemplate.getForEntity(
                eq("https://pay.example.com/api.php?act=order&pid=pid&key=secret&out_trade_no=merchant-1"),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("<!doctype html><html></html>", headers,
                        org.springframework.http.HttpStatus.OK));
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper());

        assertThrows(PaymentQueryUnavailableException.class, () -> service.queryOrder(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example.com"),
                "merchant-1"));
    }

    @Test
    void acceptsOnlyNumericSuccessCodes() {
        EpayService.EpayCreateResult result = createPaymentResponse(
                "{\"code\":\"1\",\"payurl\":\"https://pay.example/checkout\"}",
                "alipay", false, "auto");
        assertEquals(new PaymentNextAction.Redirect("https://pay.example/checkout"), result.nextAction());

        BusinessException exception = assertThrows(BusinessException.class, () -> createPaymentResponse(
                "{\"code\":true,\"payurl\":\"https://pay.example/checkout\"}",
                "alipay", false, "auto"));
        assertTrue(exception.getMessage().contains("支付创建失败"));
    }

    private EpayService.EpayCreateResult createPaymentResponse(String responseBody,
                                                                 String type,
                                                                 boolean fallbackEnabled,
                                                                 String carrierMode) {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/mapi.php"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper());
        return service.createPayment(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example.com/mapi.php"),
                "merchant-1", type, "商品", BigDecimal.TEN, "127.0.0.1", "pc",
                "https://shop.example.com/api/payments/webhook/epay",
                "https://shop.example.com/pay/order-1?orderNo=100001&method=" + type,
                fallbackEnabled, carrierMode);
    }
}
