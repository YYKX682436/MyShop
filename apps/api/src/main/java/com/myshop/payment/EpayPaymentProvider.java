package com.myshop.payment;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import com.myshop.service.EpayService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EpayPaymentProvider implements PaymentProvider {

    private final EpayService epayService;

    @Override
    public String id() { return "epay"; }

    @Override
    public String displayName() { return "Epay 聚合支付"; }

    @Override
    public List<String> paymentTypes() { return List.of("alipay", "wechat"); }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                new ConfigField("pid", "商户 ID", "text", false, true,
                        "例如：743794", null, List.of()),
                new ConfigField("key", "商户密钥", "password", true, true,
                        "MD5 密钥", null, List.of()),
                new ConfigField("api_url", "接口地址", "url", false, true,
                        "https://pay.example.com/", null, List.of()),
                new ConfigField("notify_url", "异步通知地址", "url", false, true,
                        "https://yourdomain.com/api/payments/webhook/epay", null, List.of()),
                new ConfigField("return_url", "同步回跳地址", "url", false, true,
                        "https://yourdomain.com/order/query", null, List.of())
        );
    }

    @Override
    public PaymentResult create(PaymentRequest request) {
        Map<String, String> config = request.config();
        String type = "wechat".equalsIgnoreCase(request.paymentType()) ? "wxpay" : "alipay";
        EpayService.EpayResult result = epayService.createPayment(
                channelConfig(config),
                request.orderId(), type, request.productName(), request.amount(), request.clientIp(), request.device(),
                request.returnOrderNo());
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
                required(config, "api_url"),
                required(config, "notify_url"),
                required(config, "return_url"));
    }

    private String required(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "Epay 渠道缺少配置项: " + key);
        }
        return value;
    }
}
