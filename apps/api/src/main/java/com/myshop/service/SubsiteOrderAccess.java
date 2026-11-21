package com.myshop.service;

import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Order;
import java.util.Objects;
import java.util.UUID;

/** Caller must still verify the customer identity or guest session separately. */
public final class SubsiteOrderAccess {
    private SubsiteOrderAccess() {}
    public static boolean permits(Order order, UUID userId) {
        UUID current = SiteRequestContext.getSiteId();
        if (Objects.equals(current, order.getSubsiteId())) return true;
        // Transferred users and owners may access their own old orders from the main site.
        return current == null && userId != null && userId.equals(order.getUserId());
    }
}
