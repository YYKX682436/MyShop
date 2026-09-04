package com.myshop.service;

import com.myshop.common.PageResult;
import com.myshop.model.request.AdminOrderSearchRequest;

import java.util.Map;
import java.util.UUID;

public interface AdminOrderService {

    PageResult<?> searchOrders(AdminOrderSearchRequest request);

    Object getOrderDetail(UUID id);

    void markPaid(UUID id, String reason, boolean confirmed);

    Map<String, Object> completeManualItem(UUID orderId, UUID orderItemId);
}
