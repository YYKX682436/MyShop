package com.myshop.repository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Successful-order metrics for a page of ordinary users.
 *
 * Spring Data maps the aliases in the aggregate query to these accessors so
 * the user center can fetch all metrics with one GROUP BY query.
 */
public interface UserOrderMetricsProjection {

    UUID getUserId();

    Long getOrderCount();

    BigDecimal getTotalSpent();
}
