package com.myshop.payment;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import com.myshop.service.EpayService;
import com.myshop.service.PaymentUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EpayPaymentProvider implements PaymentProvider {

    private final EpayService epayService;
    private final PaymentUrlService paymentUrlService;

    @Override
    public String id() { return "epay"; }

    @Override
    public String displayName() { return "易支付兼容协议"; }

    @Override
    public boolean allowSignedCallbackWhenQueryUnavailable() { return true; }

    @Override
    public List<String> paymentTypes() { return List.of("alipay", "wxpay"); }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                new ConfigField("pid", "商户 ID / PID", "text", false, true,
                        "例如：743794", null, List.of()),
                new ConfigField("key", "商户密钥 / KEY", "password", true, true,
                        "MD5 密钥", null, List.of()),
                new ConfigField("api_url", "接口地址 / API URL", "url", false, true,
                        "https://pay.example.com 或 https://pay.example.com/mapi.php", null, List.of())
        );
    }

    @Override
    public PaymentResult create(PaymentRequest request) {
        Map<String, String> config = request.config();
        String type = gatewayPaymentType(request.paymentType());
        String notifyUrl = paymentUrlService.webhookUrl(id());
        String returnUrl = paymentUrlService.paymentReturnUrl(
                request.orderId(), request.returnOrderNo(), request.paymentType());
        EpayService.EpayResult result = epayService.createPayment(
                channelConfig(config),
                request.orderId(), type, request.productName(), request.amount(), request.clientIp(), request.device(),
                notifyUrl, returnUrl);
        return new PaymentResult(result.tradeNo(), result.payUrl(), result.qrcodeUrl());
    }

    @Override
    public boolean verifyCallback(CallbackRequest request) {
        Map<String, String> params = request.params();
        return epayService.verifySign(required(request.config(), "key"), params, params.get("sign"));
    }

    @Override
    public PaymentQueryResult query(PaymentQueryRequest request) {
        EpayService.OrderQueryResult result = epayService.queryOrder(
                channelConfig(request.config()), request.orderId());
        if (result == null) return null;
        return new PaymentQueryResult(result.tradeStatus(), result.money(), result.tradeNo());
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
}
