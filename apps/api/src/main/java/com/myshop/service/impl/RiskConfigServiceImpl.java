package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.entity.Order;
import com.myshop.entity.SiteConfig;
import com.myshop.entity.User;
import com.myshop.repository.OrderRepository;
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
    private final UserRepository userRepository;

    private static final List<String> RISK_KEYS = List.of(
            // 人机验证
            "turnstile_enabled",
            // 设备指纹限流
            "device_rate_limit_enabled",
            "device_order_limit_per_hour",
            "device_login_limit_per_hour", "device_register_limit_per_hour",
            // 已有配置
            "rate_limit_per_second", "login_attempt_limit", "max_purchase_per_user", "order_expire_minutes"
    );

    @Override
    public Map<String, Object> getRiskConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : RISK_KEYS) {
            siteConfigRepository.findByConfigKey(key).ifPresent(c -> {
                String val = c.getConfigValue();
                // boolean 类型配置项直接返回 boolean
                if ("true".equalsIgnoreCase(val) || "false".equalsIgnoreCase(val)) {
                    result.put(key, Boolean.parseBoolean(val));
                } else {
                    try { result.put(key, Integer.parseInt(val)); }
                    catch (NumberFormatException e) { result.put(key, val); }
                }
            });
        }
        return result;
    }

    @Override
    @Transactional
    public void updateRiskConfig(Map<String, Object> request) {
        for (String key : RISK_KEYS) {
            if (request.containsKey(key)) {
                SiteConfig config = siteConfigRepository.findByConfigKey(key)
                        .orElseGet(() -> {
                            SiteConfig c = new SiteConfig();
                            c.setConfigKey(key);
                            c.setConfigGroup("risk");
                            return c;
                        });
                config.setConfigValue(request.get(key).toString());
                siteConfigRepository.save(config);
            }
        }
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
        var list = orderPage.getContent().stream().map(o -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", o.getId());
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
            return map;
        }).toList();
        return PageResult.of(orderPage, list);
    }
}
