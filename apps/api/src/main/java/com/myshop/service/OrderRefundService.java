package com.myshop.service;

import com.myshop.model.response.AdminOrderRefundResponse;

import java.util.UUID;

/** Owns the formal one-way order refund state transition. */
public interface OrderRefundService {

    AdminOrderRefundResponse refund(UUID orderId, String reason, boolean confirmed);
}
