package com.myshop.service;

import com.myshop.entity.Order;

import java.util.Map;
import java.util.UUID;

public interface OrderService {

    Map<String, Object> createDirectOrder(Map<String, Object> request, UUID userId, String clientIp, String sessionToken);

    void expireIfNeeded(Order order);

    void expireOrders();
}
