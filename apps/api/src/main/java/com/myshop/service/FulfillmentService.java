package com.myshop.service;

import java.util.Map;
import java.util.UUID;

/** Internal, authorization-free fulfillment API used after payment confirmation. */
public interface FulfillmentService {

    /**
     * Fulfill one order in its own transaction. The caller must already have
     * established that this is an internal operation; this method never accepts
     * a browser authorization value.
     */
    Map<String, Object> fulfillOrder(UUID orderId);

    /** Idempotently completes one manual-delivery order item. */
    Map<String, Object> completeManualItem(UUID orderId, UUID orderItemId);
}
