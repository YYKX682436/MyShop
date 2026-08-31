package com.myshop.service.impl;

import com.myshop.common.PageResult;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.AdminOrderService;
import com.myshop.service.FulfillmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AdminOrderServiceImpl implements AdminOrderService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final FulfillmentService fulfillmentService;

    @Override
    public PageResult<?> listOrders(String status, String paymentMethod, Boolean isRiskFlagged,
                                     String keyword, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        OrderStatus os = null;
        try {
            if (status != null) os = OrderStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的筛选参数: " + e.getMessage());
        }
        Page<Order> orderPage;
        if (keyword != null && !keyword.isBlank()) {
            orderPage = orderRepository.findAdminOrdersByKeyword(os, paymentMethod, isRiskFlagged, "%" + keyword + "%", pageable);
        } else {
            orderPage = orderRepository.findAdminOrders(os, paymentMethod, isRiskFlagged, pageable);
        }

        var list = orderPage.getContent().stream().map(this::toAdminOrder).toList();
        return PageResult.of(orderPage, list);
    }

    @Override
    public Object getOrderDetail(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        return toAdminOrder(order);
    }

    @Override
    @Transactional
    public void markPaid(UUID id, String reason, boolean confirmed) {
        if (!confirmed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须明确确认手动标记已支付");
        }
        if (reason == null || reason.trim().length() < 3 || reason.trim().length() > 500) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写 3 至 500 个字符的操作原因");
        }
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅 PENDING 或 EXPIRED 状态订单可标记为已支付");
        }
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        orderRepository.save(order);
    }

    @Override
    @Transactional
    public Map<String, Object> completeManualItem(UUID orderId, UUID orderItemId) {
        return fulfillmentService.completeManualItem(orderId, orderItemId);
    }

    private Map<String, Object> toAdminOrder(Order o) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", o.getId());
        map.put("total_amount", o.getTotalAmount());
        map.put("actual_amount", o.getActualAmount());
        map.put("status", o.getStatus().name());
        map.put("order_type", o.getOrderType());
        map.put("payment_method", o.getPaymentMethod());
        map.put("created_at", o.getCreatedAt());
        map.put("email", o.getEmail());
        map.put("expires_at", o.getExpiresAt());
        map.put("paid_at", o.getPaidAt());
        map.put("delivered_at", o.getDeliveredAt());
        map.put("user_id", o.getUserId());
        map.put("user_level_id_snapshot", o.getUserLevelIdSnapshot());
        map.put("user_level_name_snapshot", o.getUserLevelNameSnapshot());
        map.put("is_risk_flagged", o.isRiskFlagged());

        if (o.getUserId() != null) {
            userRepository.findById(o.getUserId()).ifPresent(u -> map.put("username", u.getUsername()));
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(o.getId());
        map.put("items", items.stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id", i.getId());
            im.put("product_id", i.getProductId());
            im.put("product_title", i.getProductTitle());
            im.put("spec_name", i.getSpecName());
            im.put("quantity", i.getQuantity());
            im.put("unit_price", i.getUnitPrice());
            im.put("subtotal", i.getSubtotal());
            im.put("cost_price_snapshot", i.getCostPriceSnapshot());
            im.put("retail_price_snapshot", i.getRetailPriceSnapshot());
            im.put("pricing_source", i.getPricingSource());
            im.put("gross_profit", grossProfit(i));
            im.put("gross_margin", grossMargin(i));
            im.put("delivery_type", i.getDeliveryType());
            im.put("customer_inputs", i.getCustomerInputs() == null ? List.of() : i.getCustomerInputs());
            im.put("stock_mode", i.getStockMode());
            im.put("fulfillment_status", i.getFulfillmentStatus());
            im.put("delivered_at", i.getDeliveredAt());
            im.put("manual_stock_reserved", i.getManualStockReserved());
            return im;
        }).toList());
        return map;
    }

    private BigDecimal grossProfit(OrderItem item) {
        if (item.getUnitPrice() == null || item.getCostPriceSnapshot() == null) return null;
        return item.getUnitPrice().subtract(item.getCostPriceSnapshot())
                .multiply(BigDecimal.valueOf(item.getQuantity()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal grossMargin(OrderItem item) {
        if (item.getUnitPrice() == null || item.getCostPriceSnapshot() == null
                || item.getUnitPrice().signum() <= 0) return null;
        return item.getUnitPrice().subtract(item.getCostPriceSnapshot())
                .multiply(ONE_HUNDRED)
                .divide(item.getUnitPrice(), 2, RoundingMode.HALF_UP);
    }
}
