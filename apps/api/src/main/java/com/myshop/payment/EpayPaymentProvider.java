package com.myshop.payment;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import com.myshop.service.EpayService;
import com.myshop.service.PaymentUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EpayPaymentProvider implements PaymentProvider {

    private final EpayService epayService;
    private final PaymentUrlService paymentUrlService;

    @Override
    public String id() {
        return "epay";
    }

    @Override
    public String displayName() {
        return "易支付兼容协议";
    }

    @Override
    public boolean supportsServerSideQuery() {
        return true;
    }

    @Override
    public boolean allowSignedCallbackWhenQueryUnavailable() {
        return true;
    }

    @Override
    public List<String> paymentTypes() {
        return List.of("alipay", "wxpay");
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                new ConfigField("pid", "商户 ID / PID", "text", false, true,
                        "例如：743794", null, List.of()),
                new ConfigField("key", "商户密钥 / KEY", "password", true, true,
                        "MD5 密钥", null, List.of()),
                new ConfigField("api_url", "接口地址 / API URL", "url", false, true,
                        "https://pay.example.com 或 https://pay.example.com/mapi.php", null, List.of()),
                new ConfigField("create_mode", "支付发起模式", "select", false, false,
                        null, "api", List.of(
                                new ConfigOption("api", "API 模式（mapi.php 返回支付载体）"),
                                new ConfigOption("page", "页面模式（浏览器提交 submit.php）")
                        )),
                new ConfigField("carrier_mode", "API 载体语义（仅 API 模式）", "select", false, false,
                        null, "auto", List.of(
                                new ConfigOption("auto", "自动（按易支付常见字段）"),
                                new ConfigOption("qr_code", "二维码（将返回载体编码展示）"),
                                new ConfigOption("redirect", "跳转（直接打开 HTTP(S) 载体）"),
                                new ConfigOption("open_in_client", "在客户端打开（交给微信/支付宝）")
                        ))
        );
    }

    @Override
    public PaymentResult create(PaymentRequest request) {
        Map<String, String> config = request.config();
        String type = gatewayPaymentType(request.paymentType());
        String notifyUrl = paymentUrlService.webhookUrl(id());
        String returnUrl = paymentUrlService.paymentReturnUrl(request.returnOrderNo());
        EpayService.ChannelConfig channelConfig = channelConfig(config);
        String createMode = textConfig(config, "create_mode", "api");
        EpayService.EpayCreateResult result = switch (createMode) {
            case "api" -> epayService.createApiPayment(
                    channelConfig, request.merchantTradeNo(), type, request.productName(),
                    request.amount(), request.clientIp(), request.device(), notifyUrl, returnUrl,
                    textConfig(config, "carrier_mode", "auto"));
            case "page" -> epayService.createPagePayment(
                    channelConfig, request.merchantTradeNo(), type, request.productName(),
                    request.amount(), request.clientIp(), request.device(), notifyUrl, returnUrl);
            default -> throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                    "易支付兼容协议支付发起模式无效: " + createMode);
        };
        return new PaymentResult(result.providerTradeNo(), result.nextAction());
    }

    @Override
    public String extractMerchantTradeNo(WebhookRequest request) {
        return clean(request.parameter("out_trade_no"));
    }

    @Override
    public CallbackResult verifyAndParseCallback(WebhookRequest request,
                                                 Map<String, String> config) {
        Map<String, String> params = request.parameters();
        String merchantTradeNo = clean(params.get("out_trade_no"));
        String providerTradeNo = clean(params.get("trade_no"));
        String signature = clean(params.get("sign"));
        if (merchantTradeNo == null || providerTradeNo == null || signature == null
                || !epayService.verifySign(required(config, "key"), params, signature)) {
            return null;
        }
        BigDecimal amount = parseAmount(params.get("money"));
        PaymentStatus status = mapStatus(params.get("trade_status"));
        return new CallbackResult(id() + ":" + providerTradeNo, merchantTradeNo,
                providerTradeNo, status, amount);
    }

    @Override
    public PaymentQueryResult query(PaymentQueryRequest request) {
        EpayService.OrderQueryResult result = epayService.queryOrder(
                channelConfig(request.config()), request.merchantTradeNo());
        if (result == null) return null;
        return new PaymentQueryResult(result.status(), result.amount(), result.providerTradeNo());
    }

    private EpayService.ChannelConfig channelConfig(Map<String, String> config) {
        return new EpayService.ChannelConfig(
                required(config, "pid"),
                required(config, "key"),
                required(config, "api_url"));
    }

    private String gatewayPaymentType(String paymentType) {
        if ("alipay".equalsIgnoreCase(paymentType)) return "alipay";
        if ("wxpay".equalsIgnoreCase(paymentType)) return "wxpay";
        throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                "易支付兼容协议不支持支付类型: " + paymentType);
    }

    private String required(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                    "易支付兼容协议渠道缺少配置项: " + key);
        }
        return value;
    }

    private String textConfig(Map<String, String> config, String key, String fallback) {
        String value = clean(config.get(key));
        return value == null ? fallback : value.toLowerCase(Locale.ROOT);
    }

    private PaymentStatus mapStatus(String rawStatus) {
        String status = clean(rawStatus);
        if ("TRADE_SUCCESS".equalsIgnoreCase(status) || "1".equals(status)) {
            return PaymentStatus.PAID;
        }
        if (status == null || "TRADE_CREATED".equalsIgnoreCase(status)
                || "WAIT_BUYER_PAY".equalsIgnoreCase(status)
                || "0".equals(status)) {
            return PaymentStatus.PENDING;
        }
        if ("TRADE_CLOSED".equalsIgnoreCase(status)
                || "CLOSED".equalsIgnoreCase(status)
                || "4".equals(status)) {
            return PaymentStatus.CLOSED;
        }
        return PaymentStatus.FAILED;
    }

    private BigDecimal parseAmount(String value) {
        String clean = clean(value);
        if (clean == null) return null;
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
