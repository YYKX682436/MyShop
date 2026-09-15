package com.myshop.service.impl;

import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderType;
import com.myshop.constant.PricingSource;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.PaymentAttempt;
import com.myshop.entity.User;
import com.myshop.entity.BalanceTransaction;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AdminManualFulfillRequest;
import com.myshop.model.request.AdminOrderSearchRequest;
import com.myshop.model.response.AdminOrderRefundResponse;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.BalanceTransactionRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.FulfillmentService;
import com.myshop.service.CardKeyReclaimService;
import com.myshop.service.AfterSaleService;
import com.myshop.service.OperationLogService;
import com.myshop.service.OrderPaymentCompletionService;
import com.myshop.service.OrderRefundService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AdminOrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private FulfillmentService fulfillmentService;
    @Mock private PaymentAttemptRepository paymentAttemptRepository;
    @Mock private OrderPaymentCompletionService orderPaymentCompletionService;
    @Mock private OperationLogService operationLogService;
    @Mock private OrderRefundService orderRefundService;
    @Mock private BalanceTransactionRepository balanceTransactionRepository;
    @Mock private CardKeyReclaimService cardKeyReclaimService;
    @Mock private AfterSaleService afterSaleService;

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

        Map<String, Object> response = (Map<String, Object>) fullService().getOrderDetail(orderId);

        assertEquals(orderId, response.get("id"));
        assertEquals(1000000001L, response.get("order_no"));
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

        Map<String, Object> response = (Map<String, Object>) fullService().getOrderDetail(orderId);

        assertEquals(null, response.get("user_level_id_snapshot"));
        assertEquals(1000000001L, response.get("order_no"));
        assertEquals(null, response.get("user_level_name_snapshot"));
        Map<?, ?> itemResponse = (Map<?, ?>) ((List<?>) response.get("items")).get(0);
        assertEquals(new BigDecimal("20.00"), itemResponse.get("gross_profit"));
        assertEquals(new BigDecimal("20.00"), itemResponse.get("gross_margin"));
        assertEquals(PricingSource.RETAIL, itemResponse.get("pricing_source"));
    }

    @Test
    void refundedOrderDetailHidesAnUnprovidedReasonAndShowsTheReclaimCount() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order order = order(orderId, userId);
        order.setStatus(OrderStatus.REFUNDED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));
        BalanceTransaction refund = new BalanceTransaction();
        refund.setReason(null);
        when(balanceTransactionRepository.findFirstByOrderIdAndTypeOrderByCreatedAtDesc(
                orderId, com.myshop.constant.BalanceTransactionType.REFUND))
                .thenReturn(Optional.of(refund));
        when(cardKeyReclaimService.countByOrderId(orderId)).thenReturn(2L);

        Map<String, Object> response = (Map<String, Object>) fullService().getOrderDetail(orderId);

        assertFalse(response.containsKey("refund_reason"));
        assertEquals(2, response.get("reclaimed_card_key_count"));
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

        var result = fullService().searchOrders(
                new AdminOrderSearchRequest(1, 20, null, null, null, null, null));

        assertEquals(2L, result.getPagination().getTotal());
        Map<?, ?> registered = (Map<?, ?>) result.getList().get(0);
        assertEquals(registeredOrderId, registered.get("id"));
        assertEquals(1000000001L, registered.get("order_no"));
        assertEquals("REGISTERED", registered.get("customer_type"));
        assertEquals("registered-buyer", registered.get("username"));
        assertEquals(12345L, registered.get("user_no"));
        org.junit.jupiter.api.Assertions.assertFalse(registered.containsKey("email"));
        org.junit.jupiter.api.Assertions.assertFalse(registered.containsKey("order_email"));
        Map<?, ?> guest = (Map<?, ?>) result.getList().get(1);
        assertEquals(guestOrderId, guest.get("id"));
        assertEquals(1000000001L, guest.get("order_no"));
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

    @Test
    void searchAcceptsAfterSaleStatusAndPassesItToTheRepository() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, UUID.randomUUID());
        order.setStatus(OrderStatus.AFTER_SALE);
        when(orderRepository.searchAdminOrders(eq("AFTER_SALE"), isNull(), isNull(), isNull(),
                eq("desc"), any()))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1));
        when(orderItemRepository.findByOrderIdIn(List.of(orderId))).thenReturn(List.of());
        when(userRepository.findAllById(anyList())).thenReturn(List.of());
        Map<String, Object> summary = Map.of("id", UUID.randomUUID(),
                "order_id", orderId, "status", "PENDING", "waiting_for", "ADMIN");
        when(afterSaleService.buildOrderSummaries(List.of(orderId)))
                .thenReturn(Map.of(orderId, summary));

        var result = fullService().searchOrders(
                new AdminOrderSearchRequest(1, 20, null, "AFTER_SALE", null, null, null));

        assertEquals(1L, result.getPagination().getTotal());
        assertEquals(OrderStatus.AFTER_SALE.name(), ((Map<?, ?>) result.getList().get(0)).get("status"));
        assertEquals(summary, ((Map<?, ?>) result.getList().get(0)).get("after_sale"));
        verify(afterSaleService).buildOrderSummaries(List.of(orderId));
        verify(orderRepository).searchAdminOrders(eq("AFTER_SALE"), isNull(), isNull(), isNull(),
                eq("desc"), any());
    }

    @Test
    void adminSearchPassesBusinessOrderNumberAsTheIdentityKeyword() {
        when(orderRepository.searchAdminOrders(isNull(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.eq("%1000000001%"),
                org.mockito.ArgumentMatchers.eq("desc"), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        var result = fullService().searchOrders(
                new AdminOrderSearchRequest(1, 20, "1000000001", null, null, null, null));

        assertEquals(0L, result.getPagination().getTotal());
        verify(orderRepository).searchAdminOrders(isNull(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.eq("%1000000001%"),
                org.mockito.ArgumentMatchers.eq("desc"),
                org.mockito.ArgumentMatchers.eq(PageRequest.of(0, 20)));
    }

    @Test
    void manualFulfillmentRequiresExplicitConfirmationAndRejectsDuplicateItems() {
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        var service = fullService();

        assertThrows(BusinessException.class, () -> service.completeManualItems(orderId,
                new AdminManualFulfillRequest(false, List.of(
                        new AdminManualFulfillRequest.Item(itemId, "content")))));
        assertThrows(BusinessException.class, () -> service.completeManualItems(orderId,
                new AdminManualFulfillRequest(null, List.of(
                        new AdminManualFulfillRequest.Item(itemId, "content")))));
        assertThrows(BusinessException.class, () -> service.completeManualItems(orderId,
                new AdminManualFulfillRequest(true, List.of(
                        new AdminManualFulfillRequest.Item(itemId, "content"),
                        new AdminManualFulfillRequest.Item(itemId, "content")))));

        verifyNoInteractions(fulfillmentService);
    }

    @Test
    void manualFulfillmentAuditDetailContainsMetadataButNeverCustomerContent() {
        UUID firstItemId = UUID.randomUUID();
        UUID secondItemId = UUID.randomUUID();
        String sensitiveContent = "账号 buyer@example.com 密码 super-secret";
        var request = new AdminManualFulfillRequest(true, List.of(
                new AdminManualFulfillRequest.Item(firstItemId, sensitiveContent),
                new AdminManualFulfillRequest.Item(secondItemId, null)));

        String detail = request.auditDetail();

        assertTrue(detail.contains(firstItemId.toString()));
        assertTrue(detail.contains(secondItemId.toString()));
        assertTrue(detail.contains("填写发货内容=1/2"));
        assertFalse(detail.contains(sensitiveContent));
        assertFalse(detail.contains("super-secret"));
    }

    @Test
    void manualFulfillmentForwardsEachItemContentToTheLockedFulfillmentService() {
        UUID orderId = UUID.randomUUID();
        UUID firstItemId = UUID.randomUUID();
        UUID secondItemId = UUID.randomUUID();
        when(fulfillmentService.completeManualItems(eq(orderId), anyList()))
                .thenReturn(Map.of("status", "DELIVERED"));

        var result = fullService().completeManualItems(orderId, new AdminManualFulfillRequest(true, List.of(
                        new AdminManualFulfillRequest.Item(firstItemId, "first"),
                        new AdminManualFulfillRequest.Item(secondItemId, null))));

        assertEquals("DELIVERED", result.get("status"));
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(fulfillmentService).completeManualItems(eq(orderId), captor.capture());
        List<?> forwarded = captor.getValue();
        assertEquals(new FulfillmentService.ManualFulfillmentItem(firstItemId, "first"), forwarded.get(0));
        assertEquals(new FulfillmentService.ManualFulfillmentItem(secondItemId, null), forwarded.get(1));
    }

    @Test
    void manualPaymentConfirmationPaysLatestAttemptClosesOlderAttemptsAndUsesUnifiedCompletion() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, UUID.randomUUID());
        order.setStatus(OrderStatus.PENDING);
        order.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(10));
        PaymentAttempt oldAttempt = pendingAttempt(orderId, java.time.LocalDateTime.now().plusMinutes(5));
        PaymentAttempt currentAttempt = pendingAttempt(orderId, java.time.LocalDateTime.now().plusMinutes(5));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING))
                .thenReturn(List.of(oldAttempt, currentAttempt));
        when(orderPaymentCompletionService.completeFirstPayment(order)).thenAnswer(invocation -> {
            order.setStatus(OrderStatus.PAID);
            order.setPaidAt(java.time.LocalDateTime.now());
            return true;
        });

        fullService().markPaid(orderId, "  线下订单凭证已核验  ", true);

        assertEquals(OrderStatus.PAID, order.getStatus());
        assertEquals(PaymentStatus.CLOSED, oldAttempt.getStatus());
        assertEquals(PaymentStatus.PAID, currentAttempt.getStatus());
        verify(paymentAttemptRepository).save(oldAttempt);
        verify(paymentAttemptRepository).save(currentAttempt);
        verify(orderPaymentCompletionService).completeFirstPayment(order);
        org.mockito.ArgumentCaptor<String> detail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(operationLogService).logCurrentRequest(
                eq("order.mark_paid"), eq("ORDER"), eq(orderId.toString()), detail.capture());
        assertTrue(detail.getValue().contains("原因=线下订单凭证已核验"));
    }

    @Test
    void manualPaymentConfirmationRejectsBalanceExpiredAndMissingAttemptOrders() {
        UUID balanceOrderId = UUID.randomUUID();
        Order balanceOrder = order(balanceOrderId, UUID.randomUUID());
        balanceOrder.setStatus(OrderStatus.PENDING);
        balanceOrder.setPaymentMethod(OrderPaymentMethod.BALANCE);
        balanceOrder.setPaymentChannelCode(null);
        balanceOrder.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(10));
        when(orderRepository.findByIdForUpdate(balanceOrderId)).thenReturn(Optional.of(balanceOrder));

        BusinessException balance = assertThrows(BusinessException.class,
                () -> fullService().markPaid(balanceOrderId, "余额订单禁止确认", true));
        assertEquals(com.myshop.constant.ErrorCode.BAD_REQUEST, balance.getCode());

        UUID expiredOrderId = UUID.randomUUID();
        Order expiredOrder = order(expiredOrderId, UUID.randomUUID());
        expiredOrder.setStatus(OrderStatus.EXPIRED);
        when(orderRepository.findByIdForUpdate(expiredOrderId)).thenReturn(Optional.of(expiredOrder));
        BusinessException expired = assertThrows(BusinessException.class,
                () -> fullService().markPaid(expiredOrderId, "过期订单禁止恢复", true));
        assertEquals(com.myshop.constant.ErrorCode.ORDER_EXPIRED, expired.getCode());

        UUID noAttemptOrderId = UUID.randomUUID();
        Order noAttemptOrder = order(noAttemptOrderId, UUID.randomUUID());
        noAttemptOrder.setStatus(OrderStatus.PENDING);
        noAttemptOrder.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(10));
        when(orderRepository.findByIdForUpdate(noAttemptOrderId)).thenReturn(Optional.of(noAttemptOrder));
        when(paymentAttemptRepository.findByOrderIdAndStatusForUpdate(noAttemptOrderId, PaymentStatus.PENDING))
                .thenReturn(List.of());
        BusinessException noAttempt = assertThrows(BusinessException.class,
                () -> fullService().markPaid(noAttemptOrderId, "没有支付尝试", true));
        assertEquals(com.myshop.constant.ErrorCode.BAD_REQUEST, noAttempt.getCode());
        verifyNoInteractions(orderPaymentCompletionService, operationLogService);
    }

    @Test
    void manualPaymentConfirmationRejectsExpiredCurrentAttemptBeforeMutation() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, UUID.randomUUID());
        order.setStatus(OrderStatus.PENDING);
        order.setExpiresAt(java.time.LocalDateTime.now().plusMinutes(10));
        PaymentAttempt expiredAttempt = pendingAttempt(orderId, java.time.LocalDateTime.now().minusSeconds(1));
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING))
                .thenReturn(List.of(expiredAttempt));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> fullService().markPaid(orderId, "当前支付尝试已过期", true));

        assertEquals(com.myshop.constant.ErrorCode.ORDER_EXPIRED, exception.getCode());
        assertEquals(PaymentStatus.PENDING, expiredAttempt.getStatus());
        verify(paymentAttemptRepository, never()).save(any(PaymentAttempt.class));
        verifyNoInteractions(orderPaymentCompletionService, operationLogService);
    }

    @Test
    void afterSaleActionsAreDelegatedToTheDedicatedLifecycleService() {
        UUID orderId = UUID.randomUUID();
        AdminOrderRefundResponse response = new AdminOrderRefundResponse(
                orderId, 1000000001L, OrderStatus.REFUNDED, new BigDecimal("60.00"),
                java.time.LocalDateTime.now(), "BALANCE", BigDecimal.ZERO,
                new BigDecimal("60.00"), 0);
        when(afterSaleService.approve(orderId, true, false)).thenReturn(response);

        fullService().rejectAfterSale(orderId, "请补充凭证");
        AdminOrderRefundResponse approved = fullService().approveAfterSale(orderId, true, false);

        assertEquals(response, approved);
        verify(afterSaleService).reject(orderId, "请补充凭证");
        verify(afterSaleService).approve(orderId, true, false);
    }

    private AdminOrderServiceImpl fullService() {
        return new AdminOrderServiceImpl(orderRepository, orderItemRepository, userRepository,
                fulfillmentService, paymentAttemptRepository, orderPaymentCompletionService,
                operationLogService, orderRefundService, balanceTransactionRepository,
                cardKeyReclaimService, afterSaleService);
    }

    private PaymentAttempt pendingAttempt(UUID orderId, java.time.LocalDateTime expiresAt) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setOrderId(orderId);
        attempt.setStatus(PaymentStatus.PENDING);
        attempt.setExpiresAt(expiresAt);
        return attempt;
    }

    private Order order(UUID id, UUID userId) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNo(1000000001L);
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
