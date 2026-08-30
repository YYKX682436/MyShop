package com.myshop.service;

import java.util.Map;
import java.util.UUID;

public interface OrderService {

    Map<String, Object> createDirectOrder(Map<String, Object> request, UUID userId, String clientIp, String sessionToken);

    Map<String, Object> getOrderStatus(UUID orderId, String lookupToken, UUID requestUserId);

    Map<String, Object> refreshOrderStatus(UUID orderId, String lookupToken, UUID requestUserId);

    void expireOrders();
}
