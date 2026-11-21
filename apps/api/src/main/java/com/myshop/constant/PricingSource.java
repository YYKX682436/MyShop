package com.myshop.constant;

/** Immutable source recorded on an order item when its price is selected. */
public enum PricingSource {
    RETAIL,
    LEVEL_MARKUP,
    LEVEL_OVERRIDE,
    USER_SECRET,
    SUBSITE,
    /** Retained only for the immutable price snapshots of historical channel orders. */
    SALES_CHANNEL
}
