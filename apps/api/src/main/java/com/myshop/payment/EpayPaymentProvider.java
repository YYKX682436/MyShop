package com.myshop.payment;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import com.myshop.service.EpayService;
import com.myshop.service.PaymentUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EpayPaymentProvider implements PaymentProvider {
    private final EpayService epayService;
    private final PaymentUrlService paymentUrlService;

    @Override public String id() { return "epay"; }
    @Override public String displayName() { return "易支付兼容协议"; }
    @Override public boolean supportsServerSideQuery() { return true; }
    @Override public boolean allowSignedCallbackWhenQueryUnavailable() { return true; }
    @Override public List<String> paymentTypes() { return List.of("alipay", "wxpay"); }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                new ConfigField("api_url", "支付网关", "url", false, true,
                        "https://pay.example.com", null, List.of()),
                new ConfigField("pid", "商户号 / PID", "text", false, true,
                        "请输入商户号", null, List.of()),
                new ConfigField("key", "商户密钥 / KEY", "password", true, true,
                        "请输入商户密钥", null, List.of())
        );
    }

    @Override
    public PaymentResult create(PaymentRequest request) {
        EpayService.EpayCreateResult result = epayService.createPayment(
                channelConfig(request.config()), request.merchantTradeNo(), gatewayPaymentType(request.paymentType()),
                request.productName(), request.amount(), request.clientIp(), request.device(),
                paymentUrlService.webhookUrl(id()), paymentUrlService.paymentReturnUrl(request.orderId()));
        return new PaymentResult(result.providerTradeNo(), result.nextAction());
    }

    @Override public String extractMerchantTradeNo(WebhookRequest request) { return clean(request.parameter("out_trade_no")); }

    @Override
    public CallbackResult verifyAndParseCallback(WebhookRequest request, Map<String, String> config) {
        Map<String, String> params = request.parameters();
        String merchantTradeNo = clean(params.get("out_trade_no"));
        String providerTradeNo = clean(params.get("trade_no"));
        String signature = clean(params.get("sign"));
        if (merchantTradeNo == null || providerTradeNo == null || signature == null
                || !epayService.verifySign(required(config, "key"), params, signature)) return null;
        return new CallbackResult(id() + ":" + providerTradeNo, merchantTradeNo, providerTradeNo,
                mapStatus(params.get("trade_status")), parseAmount(params.get("money")));
    }

    @Override
    public PaymentQueryResult query(PaymentQueryRequest request) {
        EpayService.OrderQueryResult result = epayService.queryOrder(channelConfig(request.config()), request.merchantTradeNo());
        return result == null ? null : new PaymentQueryResult(result.status(), result.amount(), result.providerTradeNo());
    }

    private EpayService.ChannelConfig channelConfig(Map<String, String> config) {
        return new EpayService.ChannelConfig(required(config, "api_url"), required(config, "pid"), required(config, "key"));
    }

    private String gatewayPaymentType(String paymentType) {
        if ("alipay".equalsIgnoreCase(paymentType)) return "alipay";
        if ("wxpay".equalsIgnoreCase(paymentType)) return "wxpay";
        throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "易支付兼容协议不支持支付类型: " + paymentType);
    }

    private String required(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "易支付兼容协议渠道缺少配置项: " + key);
        return value;
    }

    private PaymentStatus mapStatus(String rawStatus) {
        String status = clean(rawStatus);
        if ("TRADE_SUCCESS".equalsIgnoreCase(status) || "1".equals(status)) return PaymentStatus.PAID;
        if (status == null || "TRADE_CREATED".equalsIgnoreCase(status) || "WAIT_BUYER_PAY".equalsIgnoreCase(status) || "0".equals(status)) return PaymentStatus.PENDING;
        if ("TRADE_CLOSED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status) || "4".equals(status)) return PaymentStatus.CLOSED;
        return PaymentStatus.FAILED;
    }

    private BigDecimal parseAmount(String value) {
        String clean = clean(value);
        if (clean == null) return null;
        try { return new BigDecimal(clean); } catch (NumberFormatException ignored) { return null; }
    }

    private String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
