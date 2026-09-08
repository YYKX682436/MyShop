package com.myshop.service;

import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentStatus;

import java.math.BigDecimal;
import java.util.Map;

public interface EpayService {

    /** The complete merchant configuration persisted for an Epay-compatible channel. */
    record ChannelConfig(String gateway, String pid, String key) {}

    /**
     * Create a payment. The gateway value determines the protocol internally:
     * an explicit mapi.php/pay/apisubmit path uses the JSON API; submit.php or
     * a gateway base URL uses the universal browser form protocol.
     */
    EpayCreateResult createPayment(ChannelConfig config, String merchantTradeNo, String type, String name,
                                   BigDecimal money, String clientIp, String device,
                                   String notifyUrl, String returnUrl);

    String buildSign(String merchantKey, Map<String, String> params);

    boolean verifySign(String merchantKey, Map<String, String> params, String sign);

    OrderQueryResult queryOrder(ChannelConfig config, String merchantTradeNo);

    record EpayCreateResult(String providerTradeNo, PaymentNextAction nextAction) { }

    record OrderQueryResult(PaymentStatus status, BigDecimal amount, String providerTradeNo) { }
}
