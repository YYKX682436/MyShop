package com.myshop.service;

import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentStatus;

import java.math.BigDecimal;
import java.util.Map;

public interface EpayService {

    /** Only merchant credentials and the gateway endpoint are persisted. */
    record ChannelConfig(String pid, String key, String apiUrl) {}

    /** Call the Epay-compatible mapi endpoint and normalize its carrier to nextAction. */
    EpayCreateResult createApiPayment(ChannelConfig config, String merchantTradeNo, String type, String name,
                                      BigDecimal money, String clientIp, String device,
                                      String notifyUrl, String returnUrl, String carrierMode);

    /** Build the browser form for the independent Epay submit.php protocol. */
    EpayCreateResult createPagePayment(ChannelConfig config, String merchantTradeNo, String type, String name,
                                       BigDecimal money, String clientIp, String device,
                                       String notifyUrl, String returnUrl);

    /**
     * 生成 MD5 签名
     */
    String buildSign(String merchantKey, Map<String, String> params);

    /**
     * 验证回调签名
     */
    boolean verifySign(String merchantKey, Map<String, String> params, String sign);

    /**
     * Query the gateway and convert its protocol status and amount to standard values.
     */
    OrderQueryResult queryOrder(ChannelConfig config, String merchantTradeNo);

    record EpayCreateResult(String providerTradeNo, PaymentNextAction nextAction) { }

    record OrderQueryResult(PaymentStatus status, BigDecimal amount, String providerTradeNo) { }
}
