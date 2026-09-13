package com.myshop.service;

import com.myshop.common.PageResult;
import com.myshop.model.request.AdminManualFulfillRequest;
import com.myshop.model.request.AdminOrderSearchRequest;
import com.myshop.model.response.AdminOrderRefundResponse;

import java.util.Map;
import java.util.UUID;

public interface AdminOrderService {

    PageResult<?> searchOrders(AdminOrderSearchRequest request);

    Object getOrderDetail(UUID id);

    void markPaid(UUID id, String reason, boolean confirmed);

    AdminOrderRefundResponse refund(UUID id, String reason, boolean confirmed,
                                    boolean reclaimCardKeys);

    void rejectAfterSale(UUID id, String adminReply);

    AdminOrderRefundResponse approveAfterSale(UUID id, boolean confirmed,
                                              boolean reclaimCardKeys);

    Map<String, Object> completeManualItems(UUID orderId, AdminManualFulfillRequest request);
}
