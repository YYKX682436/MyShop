package com.myshop.service;

import java.util.Map;

public interface WebhookService {

    /**
     * 处理易支付 GET 回调
     */
    String processEpayCallback(Map<String, String> params);

}
