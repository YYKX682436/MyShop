package com.myshop.service.impl;

import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderType;
import com.myshop.constant.PricingSource;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.User;
import com.myshop.model.request.AdminOrderSearchRequest;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.FulfillmentService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
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

        assertEquals(orderId, response.get("id"));
        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), response.get("user_level_id_snapshot"));
        assertEquals("黄金会员", response.get("user_level_name_snapshot"));
        assertEquals(new BigDecimal("160.00"), response.get("cost_total"));
        assertEquals(new BigDecimal("-100.00"), response.get("gross_profit_total"));
        assertEquals(new BigDecimal("-166.67"), response.get("gross_margin_total"));
        Map<?, ?> itemResponse = (Map<?, ?>) ((List<?>) response.get("items")).get(0);
        assertEquals(1L, itemResponse.get("product_no"));
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

    @Test
    void searchListBatchLoadsItemsAndUsersWithoutPerOrderQueries() {
        UUID registeredOrderId = UUID.randomUUID();
        UUID guestOrderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order registeredOrder = order(registeredOrderId, userId);
        Order guestOrder = order(guestOrderId, null);

        OrderItem registeredItem = item(registeredOrderId, new BigDecimal("30.00"),
                new BigDecimal("20.00"), new BigDecimal("30.00"), 1);
        OrderItem guestItem = item(guestOrderId, new BigDecimal("10.00"),
                new BigDecimal("4.00"), new BigDecimal("10.00"), 2);
        when(orderRepository.searchAdminOrders(isNull(), isNull(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.eq("desc"), any()))
                .thenReturn(new PageImpl<>(List.of(registeredOrder, guestOrder),
                        PageRequest.of(0, 20), 2));
        when(orderItemRepository.findByOrderIdIn(List.of(registeredOrderId, guestOrderId)))
                .thenReturn(List.of(registeredItem, guestItem));
        User user = new User();
        user.setId(userId);
        user.setUsername("registered-buyer");
        user.setUserNo(12345L);
        user.setEmail("account@example.com");
        user.setPhone("13800138000");
        when(userRepository.findAllById(List.of(userId))).thenReturn(List.of(user));

        var result = new AdminOrderServiceImpl(
                orderRepository, orderItemRepository, userRepository, fulfillmentService)
                .searchOrders(new AdminOrderSearchRequest(1, 20, null, null, null, null, null));

        assertEquals(2L, result.getPagination().getTotal());
        Map<?, ?> registered = (Map<?, ?>) result.getList().get(0);
        assertEquals(registeredOrderId, registered.get("id"));
        assertEquals("REGISTERED", registered.get("customer_type"));
        assertEquals("registered-buyer", registered.get("username"));
        assertEquals(12345L, registered.get("user_no"));
        org.junit.jupiter.api.Assertions.assertFalse(registered.containsKey("email"));
        org.junit.jupiter.api.Assertions.assertFalse(registered.containsKey("order_email"));
        Map<?, ?> guest = (Map<?, ?>) result.getList().get(1);
        assertEquals(guestOrderId, guest.get("id"));
        assertEquals("GUEST", guest.get("customer_type"));
        assertEquals(null, guest.get("username"));
        assertEquals(null, guest.get("user_no"));
        org.junit.jupiter.api.Assertions.assertFalse(guest.containsKey("email"));
        org.junit.jupiter.api.Assertions.assertFalse(guest.containsKey("order_email"));
        assertEquals(new BigDecimal("8.00"), guest.get("cost_total"));
        assertEquals(new BigDecimal("12.00"), guest.get("gross_profit_total"));

        verify(orderItemRepository).findByOrderIdIn(List.of(registeredOrderId, guestOrderId));
        verify(userRepository).findAllById(List.of(userId));
        verify(orderItemRepository, never()).findByOrderId(any());
        verify(userRepository, never()).findById(any());
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
        order.setPaymentMethod(OrderPaymentMethod.ONLINE);
        order.setPaymentChannelCode("alipay");
        return order;
    }

    private OrderItem item(UUID orderId, BigDecimal unitPrice, BigDecimal cost,
                           BigDecimal retail, int quantity) {
        OrderItem item = new OrderItem();
        item.setId(UUID.randomUUID());
        item.setOrderId(orderId);
        item.setProductId(UUID.randomUUID());
        item.setProductNo(1L);
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
