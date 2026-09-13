package com.myshop.service;

import com.myshop.model.response.AdminOrderRefundResponse;

import java.util.UUID;

/** Owns the formal one-way order refund state transition. */
public interface OrderRefundService {

    AdminOrderRefundResponse refund(UUID orderId, String reason, boolean confirmed,
                                    boolean reclaimCardKeys);

    /**
     * Approve the current pending after-sale request through the same formal
     * refund transaction used by the normal administrator refund action.
     */
    AdminOrderRefundResponse approveAfterSale(UUID orderId, boolean confirmed,
                                              boolean reclaimCardKeys);
}
