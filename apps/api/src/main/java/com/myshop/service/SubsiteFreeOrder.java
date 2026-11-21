package com.myshop.service;

import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderType;
import com.myshop.constant.PricingSource;
import com.myshop.constant.StockMode;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** A zero-price purchase is allowed only from immutable server-created snapshots. */
public final class SubsiteFreeOrder {
    private SubsiteFreeOrder() {}

    public static boolean isFreeOrder(Order order, List<OrderItem> items) {
        if (order == null || order.getSubsiteId() == null || order.getUserId() == null
                || order.getSubsiteOwnerIdSnapshot() == null
                || order.getOrderType() != OrderType.NORMAL || order.getPaymentMethod() != OrderPaymentMethod.BALANCE
                || !zero(order.getActualAmount()) || !zero(order.getTotalAmount()) || !zero(order.getSubsiteProfit())
                || items == null || items.isEmpty()) return false;
        return items.stream().allMatch(item -> item != null && Objects.equals(order.getId(), item.getOrderId())
                && item.getQuantity() > 0 && zero(item.getUnitPrice()) && zero(item.getSubtotal())
                && zero(item.getSubsiteSupplyPriceSnapshot()) && item.getPricingSource() == PricingSource.SUBSITE
                && item.getStockMode() != StockMode.SUPPLIER);
    }

    private static boolean zero(BigDecimal value) { return value != null && value.signum() == 0; }
}
