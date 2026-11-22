package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.SiteConfig;
import com.myshop.entity.User;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.RiskConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RiskConfigServiceImpl implements RiskConfigService {

    private final SiteConfigRepository siteConfigRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;

    private static final List<String> RISK_KEYS = List.copyOf(
            com.myshop.config.RiskConfigPolicy.defaults().keySet());

    @org.springframework.beans.factory.annotation.Value("${turnstile.secret-key:}")
    private String turnstileSecretKey;

    @org.springframework.beans.factory.annotation.Value("${turnstile.site-key:}")
    private String turnstileSiteKey;

    @Override
    public Map<String, Object> getRiskConfig() {
        Map<String, Object> result = com.myshop.config.RiskConfigPolicy.defaults();
        for (String key : RISK_KEYS) {
            siteConfigRepository.findByConfigKey(key).ifPresent(config ->
                    result.put(key, com.myshop.config.RiskConfigPolicy.storedValue(key, config.getConfigValue())));
        }
        if (Boolean.TRUE.equals(result.get("turnstile_enabled")) && !turnstileConfigured()) {
            result.put("turnstile_enabled", false);
        }
        return result;
    }

    @Override
    @Transactional
    public void updateRiskConfig(Map<String, Object> request) {
        if (request == null) {
            throw new com.myshop.exception.BusinessException(
                    com.myshop.constant.ErrorCode.BAD_REQUEST, "风控配置不能为空");
        }

        Map<String, String> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : request.entrySet()) {
            String key = entry.getKey();
            if (key == null || !RISK_KEYS.contains(key)) continue;
            validated.put(key, com.myshop.config.RiskConfigPolicy.validateRequestValue(key, entry.getValue()));
        }

        if ("true".equals(validated.get("turnstile_enabled")) && !turnstileConfigured()) {
            throw new com.myshop.exception.BusinessException(
                    com.myshop.constant.ErrorCode.BAD_REQUEST,
                    "无法启用 Turnstile：请先配置服务端密钥和站点密钥");
        }

        for (Map.Entry<String, String> entry : validated.entrySet()) {
            SiteConfig config = siteConfigRepository.findByConfigKey(entry.getKey())
                    .orElseGet(() -> {
                        SiteConfig created = new SiteConfig();
                        created.setConfigKey(entry.getKey());
                        created.setConfigGroup("risk");
                        return created;
                    });
            config.setConfigValue(entry.getValue());
            siteConfigRepository.save(config);
        }
    }

    private boolean turnstileConfigured() {
        return turnstileSecretKey != null && !turnstileSecretKey.isBlank()
                && turnstileSiteKey != null && !turnstileSiteKey.isBlank();
    }

    @Override
    public PageResult<?> getFlaggedOrders(int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        Page<Order> orderPage = orderRepository.findRiskFlaggedOrders(pageable);
        List<UUID> userIds = orderPage.getContent().stream()
                .map(Order::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, User> usersById = userIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<UUID> orderIds = orderPage.getContent().stream().map(Order::getId).toList();
        Map<UUID, List<OrderItem>> itemsByOrderId = orderIds.isEmpty()
                ? Map.of()
                : orderItemRepository.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        var list = orderPage.getContent().stream().map(o -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", o.getId());
            map.put("order_no", o.getOrderNo());
            map.put("total_amount", o.getTotalAmount());
            map.put("actual_amount", o.getActualAmount());
            map.put("status", o.getStatus().name());
            map.put("order_type", o.getOrderType());
            map.put("payment_method", o.getPaymentMethod() == null ? null : o.getPaymentMethod().name());
            map.put("payment_channel_code", o.getPaymentChannelCode());
            map.put("created_at", o.getCreatedAt());
            User user = o.getUserId() == null ? null : usersById.get(o.getUserId());
            map.put("customer_type", user == null ? "GUEST" : "REGISTERED");
            map.put("username", user == null ? null : user.getUsername());
            map.put("user_no", user == null ? null : user.getUserNo());
            map.put("is_risk_flagged", o.isRiskFlagged());
            map.put("items", itemsByOrderId.getOrDefault(o.getId(), List.of()).stream().map(item -> {
                Map<String, Object> itemMap = new LinkedHashMap<>();
                itemMap.put("id", item.getId());
                itemMap.put("product_id", item.getProductId());
                itemMap.put("product_no", item.getProductNo());
                itemMap.put("product_title", item.getProductTitle());
                itemMap.put("spec_name", item.getSpecName());
                itemMap.put("quantity", item.getQuantity());
                itemMap.put("unit_price", item.getUnitPrice());
                itemMap.put("subtotal", item.getSubtotal());
                itemMap.put("delivery_type", item.getDeliveryType());
                itemMap.put("customer_inputs", item.getCustomerInputs() == null ? List.of() : item.getCustomerInputs());
                itemMap.put("stock_mode", item.getStockMode());
                itemMap.put("fulfillment_status", item.getFulfillmentStatus());
                itemMap.put("delivered_at", item.getDeliveredAt());
                return itemMap;
            }).toList());
            return map;
        }).toList();
        return PageResult.of(orderPage, list);
    }
}
