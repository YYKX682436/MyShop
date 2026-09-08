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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void mapsApiGatewayResponsesToNeutralActions() {
        EpayService.EpayCreateResult deepLink = createApiPaymentResponse(
                "{\"code\":1,\"trade_no\":\"trade-1\",\"urlscheme\":\"weixin://dl/business/?ticket=xxx\"}",
                "wxpay", "auto");
        assertEquals("trade-1", deepLink.providerTradeNo());
        assertEquals(new PaymentNextAction.DeepLink(
                "weixin://dl/business/?ticket=xxx", "wechat"), deepLink.nextAction());

        EpayService.EpayCreateResult qrCode = createApiPaymentResponse(
                "{\"code\":1,\"qrcode\":\"weixin://qr/123\"}",
                "wxpay", "auto");
        assertEquals(new PaymentNextAction.QrCode("weixin://qr/123"), qrCode.nextAction());

        EpayService.EpayCreateResult redirect = createApiPaymentResponse(
                "{\"code\":1,\"payurl2\":\"https://pay.example/checkout\"}",
                "alipay", "auto");
        assertEquals(new PaymentNextAction.Redirect("https://pay.example/checkout"), redirect.nextAction());
    }

    @Test
    void forcedModesUseDeclaredSemanticsAndFailClosedForIncompatibleCarriers() {
        EpayService.EpayCreateResult qrCode = createApiPaymentResponse(
                "{\"code\":1,\"payurl\":\"https://pay.example/checkout\"}",
                "alipay", "qr_code");
        assertEquals(new PaymentNextAction.QrCode("https://pay.example/checkout"), qrCode.nextAction());

        EpayService.EpayCreateResult client = createApiPaymentResponse(
                "{\"code\":1,\"payurl\":\"https://pay.example/checkout\"}",
                "wxpay", "open_in_client");
        assertEquals(new PaymentNextAction.OpenInClient(
                "https://pay.example/checkout", "wechat"), client.nextAction());

        BusinessException invalidMode = assertThrows(BusinessException.class, () -> createApiPaymentResponse(
                "{\"code\":1,\"qrcode\":\"qr\"}", "wxpay", "unsupported"));
        assertTrue(invalidMode.getMessage().contains("载体模式无效"));

        BusinessException incompatible = assertThrows(BusinessException.class, () -> createApiPaymentResponse(
                "{\"code\":1,\"qrcode\":\"weixin://qr/123\"}", "wxpay", "redirect"));
        assertTrue(incompatible.getMessage().contains("API 模式下网关未返回可用支付载体"));
    }

    @Test
    void apiModeWithoutCarrierFailsImmediatelyAndNeverRequestsSubmitPage() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/mapi.php"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(
                        "{\"code\":1,\"trade_no\":\"trade-1\",\"payurl\":\"\",\"qrcode\":\"\"}"));
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper());

        BusinessException exception = assertThrows(BusinessException.class, () -> service.createApiPayment(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example.com"),
                "merchant-1", "wxpay", "商品", BigDecimal.TEN, "127.0.0.1", "pc",
                "https://shop.example.com/api/payments/webhook/epay",
                "https://shop.example.com/order/query?orderNo=100001&paymentReturn=1", "auto"));

        assertEquals("支付创建失败：API 模式下网关未返回可用支付载体", exception.getMessage());
        verify(restTemplate).postForEntity(eq("https://pay.example.com/mapi.php"), any(), eq(String.class));
        verify(restTemplate, never()).postForEntity(eq("https://pay.example.com/submit.php"), any(), eq(String.class));
    }

    @Test
    void pageModeReturnsSignedFormPostWithoutCallingEitherGatewayEndpoint() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper());

        EpayService.EpayCreateResult result = service.createPagePayment(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example.com"),
                "merchant-1", "wxpay", "商品", BigDecimal.TEN, "127.0.0.1", "pc",
                "https://shop.example.com/api/payments/webhook/epay",
                "https://shop.example.com/order/query?orderNo=100001&paymentReturn=1");

        PaymentNextAction.FormPost action = assertInstanceOf(
                PaymentNextAction.FormPost.class, result.nextAction());
        assertNull(result.providerTradeNo());
        assertEquals("https://pay.example.com/submit.php", action.url());
        for (String field : List.of(
                "pid", "type", "out_trade_no", "notify_url", "return_url", "name",
                "money", "clientip", "device", "sign", "sign_type")) {
            assertTrue(action.fields().containsKey(field), "missing form field: " + field);
        }
        assertEquals("MD5", action.fields().get("sign_type"));
        assertEquals(action.fields().get("sign"),
                service.buildSign("secret", action.fields()));
        assertTrue(!action.fields().containsKey("key"));
        assertTrue(!action.fields().containsKey("api_url"));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void autoKeepsTraditionalQrFieldSemanticsEvenWhenTheValueIsHttp() {
        EpayService.EpayCreateResult result = createApiPaymentResponse(
                "{\"code\":1,\"qrcode\":\"https://pay.example/qr-content\"}",
                "wxpay", "auto");

        assertEquals(new PaymentNextAction.QrCode("https://pay.example/qr-content"), result.nextAction());
    }

    @Test
    void redirectModeCanConsumeAnHttpQrcodeAfterNormalizingItsGatewayRelativePath() {
        EpayService.EpayCreateResult result = createApiPaymentResponse(
                "{\"code\":1,\"qrcode\":\"/api/pay/toapp/123\"}",
                "wxpay", "redirect");

        assertEquals(new PaymentNextAction.Redirect(
                "https://pay.example.com/api/pay/toapp/123"), result.nextAction());
    }

    @Test
    void explicitRedirectUsesTheFirstLegalCarrierInTheDeclaredFieldOrder() {
        EpayService.EpayCreateResult result = createApiPaymentResponse(
                "{\"code\":1,\"payurl\":\"/api/pay/preferred\",\"qrcode\":\"/api/pay/qr\"}",
                "wxpay", "redirect");

        assertEquals(new PaymentNextAction.Redirect(
                "https://pay.example.com/api/pay/preferred"), result.nextAction());
    }

    @Test
    void openInClientModeCanConsumeAnHttpQrcodeForWechat() {
        EpayService.EpayCreateResult result = createApiPaymentResponse(
                "{\"code\":1,\"qrcode\":\"/api/pay/toapp/123\"}",
                "wxpay", "open_in_client");

        assertEquals(new PaymentNextAction.OpenInClient(
                "https://pay.example.com/api/pay/toapp/123", "wechat"), result.nextAction());
    }

    @Test
    void qrCodeModeCanEncodeAnHttpQrcodeOrPayurlAsQrContent() {
        EpayService.EpayCreateResult httpQrcode = createApiPaymentResponse(
                "{\"code\":1,\"qrcode\":\"https://pay.example/qr-content\"}",
                "wxpay", "qr_code");
        assertEquals(new PaymentNextAction.QrCode("https://pay.example/qr-content"), httpQrcode.nextAction());

        EpayService.EpayCreateResult payurl = createApiPaymentResponse(
                "{\"code\":1,\"payurl\":\"https://pay.example/checkout\"}",
                "alipay", "qr_code");
        assertEquals(new PaymentNextAction.QrCode("https://pay.example/checkout"), payurl.nextAction());
    }

    @Test
    void redirectModeRejectsAnAppSchemeFromTheQrcodeField() {
        BusinessException exception = assertThrows(BusinessException.class, () -> createApiPaymentResponse(
                "{\"code\":1,\"qrcode\":\"weixin://qr/123\"}",
                "wxpay", "redirect"));

        assertTrue(exception.getMessage().contains("API 模式下网关未返回可用支付载体"));
    }

    @Test
    void openInClientModeRejectsNonHttpAndMalformedCarriers() {
        for (String value : List.of(
                "javascript:alert(1)",
                "data:text/html,<script>alert(1)</script>",
                "file:///tmp/payment",
                "https://[malformed")) {
            BusinessException exception = assertThrows(BusinessException.class, () -> createApiPaymentResponse(
                    "{\"code\":1,\"qrcode\":\"" + value + "\"}",
                    "wxpay", "open_in_client"), value);
            assertTrue(exception.getMessage().contains("API 模式下网关未返回可用支付载体"), value);
        }
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
        EpayService.EpayCreateResult result = createApiPaymentResponse(
                "{\"code\":\"1\",\"payurl\":\"https://pay.example/checkout\"}",
                "alipay", "auto");
        assertEquals(new PaymentNextAction.Redirect("https://pay.example/checkout"), result.nextAction());

        BusinessException exception = assertThrows(BusinessException.class, () -> createApiPaymentResponse(
                "{\"code\":true,\"payurl\":\"https://pay.example/checkout\"}",
                "alipay", "auto"));
        assertTrue(exception.getMessage().contains("支付创建失败"));
    }

    private EpayService.EpayCreateResult createApiPaymentResponse(String responseBody,
                                                                   String type,
                                                                   String carrierMode) {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForEntity(eq("https://pay.example.com/mapi.php"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));
        EpayServiceImpl service = new EpayServiceImpl(restTemplate, new ObjectMapper());
        return service.createApiPayment(
                new EpayService.ChannelConfig("pid", "secret", "https://pay.example.com/mapi.php"),
                "merchant-1", type, "商品", BigDecimal.TEN, "127.0.0.1", "pc",
                "https://shop.example.com/api/payments/webhook/epay",
                "https://shop.example.com/pay/order-1?orderNo=100001&method=" + type,
                carrierMode);
    }
}
