package com.myshop.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PaymentChannel;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentProvider;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentChannelRepository;
import com.myshop.service.EpayService;
import com.myshop.service.PaymentService;
import com.myshop.utils.LookupTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final int REPAY_COOLDOWN_SECONDS = 10;

    private final PaymentChannelRepository paymentChannelRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final List<PaymentProvider> paymentProviders;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> createPayment(UUID orderId, String paymentMethod, BigDecimal amount) {
        return createPayment(orderId, paymentMethod, amount, null);
    }

    @Override
    @Transactional
    public Map<String, Object> createPayment(UUID orderId, String paymentMethod, BigDecimal amount, String device) {
        PaymentChannel channel = paymentChannelRepository.findByChannelCodeAndIsDeleted(paymentMethod, 0)
                .filter(PaymentChannel::isEnabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付渠道不可用"));
        PaymentProvider provider = paymentProviders.stream()
                .filter(candidate -> candidate.id().equals(channel.getProviderType()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付提供商未注册"));
        if (!provider.paymentTypes().contains(channel.getPaymentType().toLowerCase())) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付方式不受该提供商支持");
        }

        // Lock the order before reading/writing cached gateway fields. Retries
        // for the same order therefore cannot create two remote payments.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单状态不允许发起支付");
        }
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(LocalDateTime.now())) {
            order.setStatus(OrderStatus.EXPIRED);
            orderRepository.save(order);
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单已过期");
        }
        if (order.getActualAmount() == null || amount == null || order.getActualAmount().compareTo(amount) != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单金额异常");
        }
        if ((order.getPaymentUrl() != null && !order.getPaymentUrl().isBlank())
                || (order.getQrcodeUrl() != null && !order.getQrcodeUrl().isBlank())) {
            return buildResult(order);
        }

        PaymentProvider.PaymentResult result = provider.create(new PaymentProvider.PaymentRequest(
                order.getId().toString(),
                channel.getPaymentType(),
                buildProductName(order.getId()),
                amount,
                order.getClientIp(),
                device,
                parseConfigData(channel.getConfigData())));
        order.setPaymentUrl(result.payUrl());
        order.setQrcodeUrl(result.qrcodeUrl());
        order.setEpayTradeNo(result.tradeNo());
        orderRepository.save(order);
        return buildResult(order);
    }

    /** Used by the Epay callback verifier to resolve the exact channel profile. */
    public EpayService.ChannelConfig buildChannelConfig(PaymentChannel channel) {
        Map<String, String> config = parseConfigData(channel.getConfigData());
        return new EpayService.ChannelConfig(
                required(config, "pid", channel.getChannelCode()),
                required(config, "key", channel.getChannelCode()),
                required(config, "api_url", channel.getChannelCode()),
                required(config, "notify_url", channel.getChannelCode()),
                required(config, "return_url", channel.getChannelCode()));
    }

    @Override
    @Transactional
    public Map<String, Object> repay(UUID orderId, String device, UUID requestUserId, String lookupToken) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        boolean owner = requestUserId != null && requestUserId.equals(order.getUserId());
        if (!owner && !LookupTokens.matches(lookupToken, order.getLookupTokenHash())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作此订单");
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单状态不允许重新支付");
        }
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(LocalDateTime.now())) {
            order.setStatus(OrderStatus.EXPIRED);
            orderRepository.save(order);
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单已过期");
        }
        if (order.getUpdatedAt() != null && order.getUpdatedAt().plusSeconds(REPAY_COOLDOWN_SECONDS).isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "操作过于频繁，请稍后再试");
        }
        order.setPaymentUrl(null);
        order.setQrcodeUrl(null);
        order.setEpayTradeNo(null);
        orderRepository.save(order);
        return createPayment(order.getId(), order.getPaymentMethod(), order.getActualAmount(), device);
    }

    private Map<String, Object> buildResult(Order order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("payment_url", order.getQrcodeUrl() != null ? order.getQrcodeUrl() : order.getPaymentUrl());
        result.put("qrcode_url", order.getQrcodeUrl());
        result.put("pay_url", order.getPaymentUrl());
        result.put("expires_at", order.getExpiresAt());
        return result;
    }

    private String buildProductName(UUID orderId) {
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        if (items.isEmpty()) return "MyShop 订单";
        String firstName = items.get(0).getProductTitle();
        return items.size() == 1 ? firstName : firstName + " 等" + items.size() + "件商品";
    }

    private Map<String, String> parseConfigData(String configData) {
        if (configData == null || configData.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(configData, new TypeReference<>() {});
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((key, value) -> { if (value != null) result.put(key, value.toString()); });
            return result;
        } catch (Exception exception) {
            log.warn("Payment channel configuration is not valid JSON for a configured channel.");
            return Map.of();
        }
    }

    private String required(Map<String, String> config, String field, String channelCode) {
        String value = config.get(field);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                    "支付渠道 [" + channelCode + "] 缺少必填配置项: " + field);
        }
        return value;
    }
}
