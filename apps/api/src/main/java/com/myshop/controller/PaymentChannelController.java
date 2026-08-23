package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.entity.PaymentChannel;
import com.myshop.repository.PaymentChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/payment-channels")
@RequiredArgsConstructor
public class PaymentChannelController {

    private final PaymentChannelRepository paymentChannelRepository;

    @GetMapping
    public ApiResponse<?> listEnabledChannels() {
        List<Map<String, Object>> channels = paymentChannelRepository
                .findByEnabledAndIsDeletedOrderBySortOrderAsc(true, 0)
                .stream()
                // Phase 1 storefront is intentionally limited to the two
                // supported mainland payment types. Future providers can
                // still participate by declaring alipay/wechat channels.
                .filter(channel -> "alipay".equalsIgnoreCase(channel.getPaymentType())
                        || "wechat".equalsIgnoreCase(channel.getPaymentType()))
                .map(this::toPublicMap)
                .toList();
        return ApiResponse.success(channels);
    }

    private Map<String, Object> toPublicMap(PaymentChannel c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("channel_code", c.getChannelCode());
        map.put("channel_name", c.getChannelName());
        map.put("provider_type", c.getProviderType());
        map.put("payment_type", c.getPaymentType());
        map.put("icon", "wechat".equalsIgnoreCase(c.getPaymentType()) ? "wechat" : "alipay");
        map.put("is_enabled", c.isEnabled());
        map.put("sort_order", c.getSortOrder());
        map.put("created_at", c.getCreatedAt());
        return map;
    }
}
