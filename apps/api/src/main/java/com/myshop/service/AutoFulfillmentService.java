package com.myshop.service;

import java.util.UUID;

/** Server-owned payment-to-delivery trigger and low-volume retry loop. */
public interface AutoFulfillmentService {

    void trigger(UUID orderId);

    void retryPendingPaidOrders();
}
