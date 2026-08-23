package com.myshop.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DeliverService {

    List<?> queryOrders(Map<String, Object> request, UUID requestUserId);

    List<?> deliverOrders(Map<String, Object> request, UUID requestUserId);

    String exportCardKeys(UUID orderId, String lookupToken, UUID requestUserId);
}
