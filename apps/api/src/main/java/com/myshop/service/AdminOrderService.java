package com.myshop.service;

import com.myshop.common.PageResult;

import java.util.Map;
import java.util.UUID;

public interface AdminOrderService {

    PageResult<?> listOrders(String status, String orderType, String paymentMethod,
                             Boolean isRiskFlagged, String keyword, int page, int pageSize);

    Object getOrderDetail(UUID id);

    void markPaid(UUID id, String reason, boolean confirmed);

    Map<String, Object> completeManualItem(UUID orderId, UUID orderItemId);
}
