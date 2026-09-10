package com.myshop.service;

import java.util.List;
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

    /**
     * Completes the exact current set of waiting manual items under the order
     * lock. A duplicate or stale item is rejected before any mutation.
     */
    Map<String, Object> completeManualItems(UUID orderId, List<ManualFulfillmentItem> items);

    record ManualFulfillmentItem(UUID orderItemId, String customerDeliveryContent) {
    }
}
