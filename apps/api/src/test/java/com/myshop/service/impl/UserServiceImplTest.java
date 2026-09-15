package com.myshop.service.impl;

import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.StockMode;
import com.myshop.constant.UserRole;
import com.myshop.common.PageResult;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.User;
import com.myshop.model.request.ChangePasswordRequest;
import com.myshop.model.CustomerInputValue;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.ManualStockService;
import com.myshop.service.AfterSaleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ManualStockService manualStockService;
    @Mock
    private UserLevelRepository userLevelRepository;
    @Mock
    private AfterSaleService afterSaleService;
    @Test
    void changingPasswordIncrementsAuthVersionSoTheOriginalJwtCannotBeReused() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setRole(UserRole.USER);
        user.setPasswordHash("old-hash");
        user.setAuthVersion(4);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("old-password");
        request.setNewPassword("new-password");

        new UserServiceImpl(userRepository, orderRepository, orderItemRepository,
                passwordEncoder, manualStockService, userLevelRepository, afterSaleService)
                .changePassword(userId, request);

        assertEquals("new-hash", user.getPasswordHash());
        assertEquals(5, user.getAuthVersion());
        verify(userRepository).save(user);
    }

    @Test
    void orderHistoryLoadsCurrentPageItemsInOneBatchAndReturnsOnlyOverviewFields() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setOrderNo(1000000001L);
        order.setTotalAmount(new java.math.BigDecimal("12.00"));
        order.setActualAmount(new java.math.BigDecimal("10.00"));
        order.setStatus(OrderStatus.DELIVERED);

        OrderItem item = new OrderItem();
        item.setId(UUID.randomUUID());
        item.setOrderId(orderId);
        item.setProductId(UUID.randomUUID());
        item.setProductNo(1L);
        item.setProductTitle("Historical product");
        item.setProductCoverUrl("https://cdn.example/historical-cover.png");
        item.setQuantity(1);
        item.setUnitPrice(new java.math.BigDecimal("10.00"));
        item.setSubtotal(new java.math.BigDecimal("10.00"));
        item.setDeliveryType("AUTO");
        item.setStockMode(StockMode.CARD_KEY);
        item.setFulfillmentStatus(FulfillmentStatus.DELIVERED);
        item.setCustomerInputs(List.of(new CustomerInputValue("account", "Account", "secret-value")));
        item.setCustomerDeliveryContent("merchant content");

        when(orderRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1));
        when(orderItemRepository.findByOrderIdIn(List.of(orderId))).thenReturn(List.of(item));

        PageResult<?> result = new UserServiceImpl(userRepository, orderRepository, orderItemRepository,
                passwordEncoder, manualStockService, userLevelRepository, afterSaleService)
                .getOrders(userId, null, 1, 20);

        Map<?, ?> orderView = (Map<?, ?>) result.getList().get(0);
        assertEquals(Set.of("id", "order_no", "actual_amount", "status", "created_at", "items"), orderView.keySet());
        Map<?, ?> itemView = (Map<?, ?>) ((List<?>) orderView.get("items")).get(0);
        assertEquals(Set.of("id", "product_title", "product_cover_url", "spec_name", "quantity",
                "unit_price", "delivery_type", "stock_mode", "fulfillment_status"), itemView.keySet());
        assertEquals("https://cdn.example/historical-cover.png", itemView.get("product_cover_url"));
        org.junit.jupiter.api.Assertions.assertFalse(itemView.containsKey("customer_inputs"));
        org.junit.jupiter.api.Assertions.assertFalse(itemView.containsKey("customer_delivery_content"));
        org.junit.jupiter.api.Assertions.assertFalse(orderView.containsKey("payment_method"));
        org.junit.jupiter.api.Assertions.assertFalse(orderView.containsKey("payment_channel_code"));
        org.junit.jupiter.api.Assertions.assertFalse(orderView.containsKey("refunded_at"));
        org.mockito.Mockito.verify(orderItemRepository).findByOrderIdIn(List.of(orderId));
        org.mockito.Mockito.verify(orderItemRepository, never()).findByOrderId(any(UUID.class));
    }

    @Test
    void orderHistoryLoadsAllAfterSaleSummariesForThePageInOneBatch() {
        UUID userId = UUID.randomUUID();
        UUID firstOrderId = UUID.randomUUID();
        UUID secondOrderId = UUID.randomUUID();
        Order first = order(firstOrderId, userId);
        Order second = order(secondOrderId, userId);
        first.setStatus(OrderStatus.AFTER_SALE);
        second.setStatus(OrderStatus.AFTER_SALE);
        when(orderRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2));
        when(orderItemRepository.findByOrderIdIn(List.of(firstOrderId, secondOrderId)))
                .thenReturn(List.of());
        Map<String, Object> firstSummary = Map.of("id", UUID.randomUUID(),
                "order_id", firstOrderId, "status", "PENDING", "waiting_for", "ADMIN");
        Map<String, Object> secondSummary = Map.of("id", UUID.randomUUID(),
                "order_id", secondOrderId, "status", "PENDING", "waiting_for", "CUSTOMER");
        when(afterSaleService.buildOrderSummaries(List.of(firstOrderId, secondOrderId)))
                .thenReturn(Map.of(firstOrderId, firstSummary, secondOrderId, secondSummary));

        PageResult<?> result = new UserServiceImpl(userRepository, orderRepository, orderItemRepository,
                passwordEncoder, manualStockService, userLevelRepository, afterSaleService)
                .getOrders(userId, null, 1, 20);

        assertEquals(firstSummary, ((Map<?, ?>) result.getList().get(0)).get("after_sale"));
        assertEquals(secondSummary, ((Map<?, ?>) result.getList().get(1)).get("after_sale"));
        verify(afterSaleService).buildOrderSummaries(List.of(firstOrderId, secondOrderId));
    }

    private Order order(UUID orderId, UUID userId) {
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setOrderNo(1000000001L);
        order.setActualAmount(new java.math.BigDecimal("10.00"));
        order.setTotalAmount(new java.math.BigDecimal("10.00"));
        order.setStatus(OrderStatus.DELIVERED);
        return order;
    }
}
