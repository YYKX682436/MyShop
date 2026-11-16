package com.myshop.constant;

/** Distinguishes real sales from an administrator's internal pickup order. */
public enum OrderType {
    NORMAL,
    ADMIN_PICKUP,
    /** Retained only to read historical channel orders after the integration was retired. */
    SALES_CHANNEL
}
