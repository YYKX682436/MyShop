package com.myshop.constant;

/** Fulfillment state of one order line, independent from the order status. */
public enum FulfillmentStatus {
    PENDING,
    WAITING_MANUAL,
    DELIVERED,
    CANCELLED
}
