package com.myshop.service;

import java.math.BigDecimal;
import java.util.Map;

public interface EpayService {

    /** Only merchant credentials and the gateway endpoint are persisted. */
    record ChannelConfig(String pid, String key, String apiUrl) {}

    /**
     * 调用易支付 /mapi.php 创建支付订单。
     *
     * 对于已由运维验证支持页面跳转的网关，如果 mapi.php 返回成功但不
     * 返回支付载体，具体实现可以返回同一签名参数对应的 submit.php 页面
     * 作为 payUrl；这不改变 SPI，也不会把商户密钥放入 URL。
     *
     * @param config     渠道配置（pid/key/apiUrl 等）
     * @param outTradeNo 商户订单号（内部 Order UUID，用于回调关联）
     * @param type       易支付支付类型：alipay / wxpay
     * @param name       商品名称
     * @param money      金额
     * @param clientIp   用户 IP
     * @param device     支付设备
     * @param notifyUrl 服务器生成的异步通知地址
     * @param returnUrl 服务器生成的支付结果回跳地址
     * @return 包含 payUrl / qrcodeUrl 的结果
     */
    EpayResult createPayment(ChannelConfig config, String outTradeNo, String type, String name,
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
     * 主动查询网关订单状态（用于 webhook 回调二次验证）
     *
     * @param config     渠道配置
     * @param outTradeNo 商户订单号
     * @return 查询结果，包含 trade_status 和 money
     */
    OrderQueryResult queryOrder(ChannelConfig config, String outTradeNo);

    record EpayResult(int code, String msg, String tradeNo, String payUrl, String qrcodeUrl) {}

    record OrderQueryResult(String tradeStatus, String money, String tradeNo) {}
}
