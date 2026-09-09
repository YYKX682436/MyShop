package com.myshop.service;

import java.util.Map;
import java.util.UUID;

/** Authorized customer access to exactly one order and its lifecycle state. */
public interface OrderAccessService {

    Map<String, Object> getOrder(UUID orderId, UUID requestUserId, String sessionToken);

    Map<String, Object> refreshOrder(UUID orderId, UUID requestUserId, String sessionToken);

    Map<String, Object> repayOrder(UUID orderId, String device, UUID requestUserId, String sessionToken);

    String exportCardKeys(UUID orderId, UUID requestUserId, String sessionToken);
}
