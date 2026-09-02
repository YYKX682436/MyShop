package com.myshop.service.impl;

import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.PricingSource;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.User;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.FulfillmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private FulfillmentService fulfillmentService;

    @Test
    void adminOrderUsesHistoricalSnapshotsForProfitAndLevelContext() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order order = order(orderId, userId);
        OrderItem item = item(orderId, new BigDecimal("30.00"), new BigDecimal("80.00"),
                new BigDecimal("100.00"), 2);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));
        User user = new User();
        user.setUsername("buyer");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Map<String, Object> response = (Map<String, Object>) new AdminOrderServiceImpl(
                orderRepository, orderItemRepository, userRepository, fulfillmentService).getOrderDetail(orderId);

        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), response.get("user_level_id_snapshot"));
        assertEquals("黄金会员", response.get("user_level_name_snapshot"));
        Map<?, ?> itemResponse = (Map<?, ?>) ((List<?>) response.get("items")).get(0);
        assertEquals("P000001", itemResponse.get("product_code"));
        assertEquals(new BigDecimal("30.00"), itemResponse.get("unit_price"));
        assertEquals(new BigDecimal("100.00"), itemResponse.get("retail_price_snapshot"));
        assertEquals(new BigDecimal("80.00"), itemResponse.get("cost_price_snapshot"));
        assertEquals(PricingSource.LEVEL_OVERRIDE, itemResponse.get("pricing_source"));
        assertEquals(new BigDecimal("-100.00"), itemResponse.get("gross_profit"));
        assertEquals(new BigDecimal("-166.67"), itemResponse.get("gross_margin"));
        verify(orderItemRepository).findByOrderId(orderId);
    }

    @Test
    void adminPickupStillExposesRetailSnapshotsWithoutAUserLevel() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, null);
        order.setOrderType(OrderType.ADMIN_PICKUP);
        order.setUserLevelIdSnapshot(null);
        order.setUserLevelNameSnapshot(null);
        OrderItem item = item(orderId, new BigDecimal("100.00"), new BigDecimal("80.00"),
                new BigDecimal("100.00"), 1);
        item.setPricingSource(PricingSource.RETAIL);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));

        Map<String, Object> response = (Map<String, Object>) new AdminOrderServiceImpl(
                orderRepository, orderItemRepository, userRepository, fulfillmentService).getOrderDetail(orderId);

        assertEquals(null, response.get("user_level_id_snapshot"));
        assertEquals(null, response.get("user_level_name_snapshot"));
        Map<?, ?> itemResponse = (Map<?, ?>) ((List<?>) response.get("items")).get(0);
        assertEquals(new BigDecimal("20.00"), itemResponse.get("gross_profit"));
        assertEquals(new BigDecimal("20.00"), itemResponse.get("gross_margin"));
        assertEquals(PricingSource.RETAIL, itemResponse.get("pricing_source"));
    }

    private Order order(UUID id, UUID userId) {
        Order order = new Order();
        order.setId(id);
        order.setUserId(userId);
        order.setTotalAmount(new BigDecimal("60.00"));
        order.setActualAmount(new BigDecimal("60.00"));
        order.setStatus(OrderStatus.PAID);
        order.setOrderType(OrderType.NORMAL);
        order.setUserLevelIdSnapshot(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        order.setUserLevelNameSnapshot("黄金会员");
        order.setPaymentMethod("alipay");
        return order;
    }

    private OrderItem item(UUID orderId, BigDecimal unitPrice, BigDecimal cost,
                           BigDecimal retail, int quantity) {
        OrderItem item = new OrderItem();
        item.setId(UUID.randomUUID());
        item.setOrderId(orderId);
        item.setProductId(UUID.randomUUID());
        item.setProductCode("P000001");
        item.setProductTitle("Test product");
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setCostPriceSnapshot(cost);
        item.setRetailPriceSnapshot(retail);
        item.setPricingSource(PricingSource.LEVEL_OVERRIDE);
        item.setSubtotal(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        return item;
    }
}
