package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.StockMode;
import com.myshop.constant.UserRole;
import com.myshop.context.RequestContext;
import com.myshop.entity.CardKey;
import com.myshop.entity.AfterSaleRequest;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.constant.AfterSaleRequestStatus;
import com.myshop.constant.AfterSaleWaitingFor;
import com.myshop.model.response.AdminOrderRefundResponse;
import com.myshop.repository.AfterSaleRequestRepository;
import com.myshop.payment.PaymentStatus;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.BalanceTransactionService;
import com.myshop.service.CardKeyReclaimService;
import com.myshop.service.ManualStockService;
import com.myshop.service.OperationLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderRefundServiceImplTest {

    private static final UUID OPERATOR_ID = UUID.randomUUID();

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private com.myshop.repository.CardKeyRepository cardKeyRepository;
    @Mock private PaymentAttemptRepository paymentAttemptRepository;
    @Mock private UserRepository userRepository;
    @Mock private AfterSaleRequestRepository afterSaleRequestRepository;
    @Mock private ManualStockService manualStockService;
    @Mock private BalanceTransactionService balanceTransactionService;
    @Mock private OperationLogService operationLogService;
    @Mock private CardKeyReclaimService cardKeyReclaimService;

    private OrderRefundServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderRefundServiceImpl(
                orderRepository, orderItemRepository, cardKeyRepository, paymentAttemptRepository,
                userRepository, afterSaleRequestRepository, manualStockService, balanceTransactionService,
                operationLogService, cardKeyReclaimService);
        RequestContext.set(new RequestContext.UserInfo(OPERATOR_ID, "refund-admin", "ADMIN"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void paidRegisteredOrderCreditsTheExactPaidAmountAndCancelsUnfinishedFulfillment() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();
        Order order = order(orderId, userId, OrderStatus.PAID, "12.34");
        User user = user(userId, "5.66");
        OrderItem item = item(orderId, productId, specId, StockMode.MANUAL,
                FulfillmentStatus.WAITING_MANUAL, 3, null);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING))
                .thenReturn(List.of());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));
        when(balanceTransactionService.recordRefund(
                eq(userId), eq(new BigDecimal("12.34")), eq(new BigDecimal("5.66")),
                eq(new BigDecimal("18.00")), eq(orderId), eq("客户申请退款"), eq(OPERATOR_ID)))
                .thenReturn(null);

        AdminOrderRefundResponse response = service.refund(orderId, "  客户申请退款  ", true, false);

        assertEquals(OrderStatus.REFUNDED, order.getStatus());
        assertNotNull(order.getRefundedAt());
        assertEquals(new BigDecimal("18.00"), user.getBalance());
        assertEquals(FulfillmentStatus.CANCELLED, item.getFulfillmentStatus());
        assertEquals(0, item.getManualStockReserved());
        assertEquals(new BigDecimal("12.34"), response.refundAmount());
        assertEquals("BALANCE", response.refundDestination());
        assertEquals(new BigDecimal("5.66"), response.balanceBefore());
        assertEquals(new BigDecimal("18.00"), response.balanceAfter());
        verify(manualStockService).release(productId, specId, 3);
        verify(balanceTransactionService).recordRefund(
                userId, new BigDecimal("12.34"), new BigDecimal("5.66"),
                new BigDecimal("18.00"), orderId, "客户申请退款", OPERATOR_ID);
        org.mockito.ArgumentCaptor<String> auditDetail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(operationLogService).logCurrentRequest(
                eq("order.refund"), eq("ORDER"), eq(orderId.toString()), auditDetail.capture());
        assertTrue(auditDetail.getValue().contains("原因=客户申请退款"));
        var locks = inOrder(orderRepository, userRepository, paymentAttemptRepository);
        locks.verify(orderRepository).findByIdForUpdate(orderId);
        locks.verify(userRepository).findByIdForUpdate(userId);
        locks.verify(paymentAttemptRepository)
                .findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING);
    }

    @Test
    void afterSaleApprovalUsesTheFormalRefundCoreAndCompletesThePendingRequestExactlyOnce() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order order = order(orderId, userId, OrderStatus.AFTER_SALE, "12.34");
        AfterSaleRequest request = new AfterSaleRequest();
        request.setId(UUID.randomUUID());
        request.setOrderId(orderId);
        request.setUserId(userId);
        request.setStatus(AfterSaleRequestStatus.PENDING);
        request.setSourceOrderStatus(OrderStatus.DELIVERED);
        request.setReason("交付内容存在问题");
        request.setSubmittedAt(LocalDateTime.now().minusMinutes(5));
        request.setWaitingFor(AfterSaleWaitingFor.ADMIN);
        request.setLastActivityAt(request.getSubmittedAt());
        User user = user(userId, "5.66");

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(afterSaleRequestRepository.findFirstByOrderIdAndStatusForUpdate(
                orderId, AfterSaleRequestStatus.PENDING)).thenReturn(Optional.of(request));
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(paymentAttemptRepository.findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING))
                .thenReturn(List.of());
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());

        AdminOrderRefundResponse response = service.approveAfterSale(orderId, true, false);

        assertEquals(OrderStatus.REFUNDED, order.getStatus());
        assertEquals(AfterSaleRequestStatus.REFUNDED, request.getStatus());
        assertEquals(null, request.getWaitingFor());
        assertEquals(OPERATOR_ID, request.getHandledBy());
        assertNotNull(request.getHandledAt());
        assertEquals(new BigDecimal("18.00"), user.getBalance());
        assertEquals(new BigDecimal("12.34"), response.refundAmount());
        verify(balanceTransactionService).recordRefund(
                userId, new BigDecimal("12.34"), new BigDecimal("5.66"),
                new BigDecimal("18.00"), orderId, "售后申请退款", OPERATOR_ID);
        verify(operationLogService).logCurrentRequest(
                eq("order.after_sale.approve"), eq("ORDER"), eq(orderId.toString()), any(String.class));
        verify(afterSaleRequestRepository).save(request);
    }

    @Test
    void ordinaryRefundCannotBypassAnAfterSaleRequest() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(orderId))
                .thenReturn(Optional.of(order(orderId, userId, OrderStatus.AFTER_SALE, "1.00")));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refund(orderId, "绕过售后", true, false));

        assertEquals(ErrorCode.ORDER_REFUND_NOT_ALLOWED, exception.getCode());
        verifyNoInteractions(afterSaleRequestRepository, userRepository, balanceTransactionService,
                operationLogService, paymentAttemptRepository, orderItemRepository);
    }

    @Test
    void deliveredContentRemainsDeliveredAndIsNeverRecovered() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order order = order(orderId, userId, OrderStatus.DELIVERED, "8.00");
        User user = user(userId, "2.00");
        OrderItem delivered = item(orderId, UUID.randomUUID(), null, StockMode.MANUAL,
                FulfillmentStatus.DELIVERED, 3, "already-delivered-card-or-content");
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING))
                .thenReturn(List.of());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(delivered));

        service.refund(orderId, "已发货订单退款", true, false);

        assertEquals(OrderStatus.REFUNDED, order.getStatus());
        assertEquals(FulfillmentStatus.DELIVERED, delivered.getFulfillmentStatus());
        assertEquals(3, delivered.getManualStockReserved());
        assertEquals("already-delivered-card-or-content", delivered.getCustomerDeliveryContent());
        verifyNoInteractions(manualStockService);
        verify(orderItemRepository, never()).save(delivered);
    }

    @Test
    void guestOrderIsRejectedBeforeAnyUserOrLedgerMutation() {
        UUID orderId = UUID.randomUUID();
        Order order = order(orderId, null, OrderStatus.PAID, "1.00");
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refund(orderId, "游客订单退款", true, false));

        assertEquals(ErrorCode.ORDER_REFUND_NOT_ALLOWED, exception.getCode());
        verifyNoInteractions(paymentAttemptRepository, userRepository, balanceTransactionService,
                operationLogService, orderItemRepository);
    }

    @Test
    void pendingExpiredAndAlreadyRefundedOrdersAreRejected() {
        for (OrderStatus status : List.of(OrderStatus.PENDING, OrderStatus.EXPIRED, OrderStatus.REFUNDED)) {
            UUID orderId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            when(orderRepository.findByIdForUpdate(orderId))
                    .thenReturn(Optional.of(order(orderId, userId, status, "1.00")));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.refund(orderId, "状态校验", true, false));

            assertEquals(status == OrderStatus.REFUNDED
                    ? ErrorCode.ORDER_ALREADY_REFUNDED : ErrorCode.ORDER_REFUND_NOT_ALLOWED,
                    exception.getCode());
        }
        verifyNoInteractions(paymentAttemptRepository, userRepository, balanceTransactionService,
                operationLogService, orderItemRepository);
    }

    @Test
    void balanceOverflowFailsBeforeChangingOrderUserItemsOrLedger() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order order = order(orderId, userId, OrderStatus.PAID, "0.01");
        User user = user(userId, "9999999999.99");
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING))
                .thenReturn(List.of());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refund(orderId, "余额上限校验", true, false));

        assertEquals(ErrorCode.ORDER_REFUND_BALANCE_LIMIT, exception.getCode());
        assertEquals(OrderStatus.PAID, order.getStatus());
        assertEquals(new BigDecimal("9999999999.99"), user.getBalance());
        verify(orderRepository, never()).save(any(Order.class));
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(orderItemRepository, manualStockService, balanceTransactionService,
                operationLogService);
    }

    @Test
    void confirmationIsRequiredButRefundReasonMayBeBlank() {
        UUID orderId = UUID.randomUUID();

        BusinessException unconfirmed = assertThrows(BusinessException.class,
                () -> service.refund(orderId, "有效原因", false, false));

        assertEquals(ErrorCode.BAD_REQUEST, unconfirmed.getCode());
        verifyNoInteractions(orderRepository, paymentAttemptRepository, userRepository,
                balanceTransactionService, operationLogService, cardKeyRepository,
                cardKeyReclaimService);
    }

    @Test
    void missingRefundReasonsAreNormalizedToNullInLedgerAndAudit() {
        for (String reason : new String[]{null, "", "   "}) {
            UUID orderId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            Order order = order(orderId, userId, OrderStatus.PAID, "1.00");
            User user = user(userId, "0.00");
            stubBasicRefund(order, user, List.of());

            service.refund(orderId, reason, true, false);
        }

        verify(balanceTransactionService, times(3)).recordRefund(
                any(UUID.class), eq(new BigDecimal("1.00")), eq(new BigDecimal("0.00")),
                eq(new BigDecimal("1.00")), any(UUID.class), isNull(String.class), eq(OPERATOR_ID));
        org.mockito.ArgumentCaptor<String> auditDetails =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(operationLogService, times(3)).logCurrentRequest(
                eq("order.refund"), eq("ORDER"), any(String.class), auditDetails.capture());
        assertTrue(auditDetails.getAllValues().stream()
                .allMatch(detail -> detail.contains("原因=未填写") && !detail.contains("原因=null")));
    }

    @Test
    void oneCharacterRefundReasonIsTrimmedAndAccepted() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubBasicRefund(order(orderId, userId, OrderStatus.PAID, "1.00"), user(userId, "0.00"), List.of());

        service.refund(orderId, "  好  ", true, false);

        verify(balanceTransactionService).recordRefund(
                userId, new BigDecimal("1.00"), new BigDecimal("0.00"),
                new BigDecimal("1.00"), orderId, "好", OPERATOR_ID);
    }

    @Test
    void refundReasonOverFiveHundredCharactersIsRejectedBeforeLocking() {
        String tooLong = "字".repeat(501);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refund(UUID.randomUUID(), tooLong, true, false));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        verifyNoInteractions(orderRepository, paymentAttemptRepository, userRepository,
                balanceTransactionService, operationLogService, cardKeyRepository,
                cardKeyReclaimService);
    }

    @Test
    void reclaimReturnsOnlyDeliveredAutomaticKeysAndPreservesDeliveryHistory() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Order order = order(orderId, userId, OrderStatus.DELIVERED, "2.00");
        User user = user(userId, "0.00");
        OrderItem item = item(orderId, productId, null, StockMode.CARD_KEY,
                FulfillmentStatus.DELIVERED, 0, null);
        item.setQuantity(2);
        LocalDateTime deliveredAt = LocalDateTime.now().minusMinutes(5);
        LocalDateTime notifiedAt = LocalDateTime.now().minusMinutes(4);
        item.setDeliveredAt(deliveredAt);
        item.setDeliveryNotifiedAt(notifiedAt);
        CardKey first = soldKey(orderId, item.getId(), productId, "card-1");
        CardKey second = soldKey(orderId, item.getId(), productId, "card-2");
        stubBasicRefund(order, user, List.of(item));
        when(cardKeyRepository.findByOrderIdForUpdate(orderId)).thenReturn(List.of(first, second));

        AdminOrderRefundResponse response = service.refund(
                orderId, "  确认卡密未使用  ", true, true);

        assertEquals(2, response.reclaimedCardKeyCount());
        assertEquals(FulfillmentStatus.CANCELLED, item.getFulfillmentStatus());
        assertEquals(deliveredAt, item.getDeliveredAt());
        assertEquals(notifiedAt, item.getDeliveryNotifiedAt());
        assertEquals(CardKeyStatus.AVAILABLE, first.getStatus());
        assertEquals(CardKeyStatus.AVAILABLE, second.getStatus());
        assertEquals(null, first.getOrderId());
        assertEquals(null, first.getOrderItemId());
        assertEquals(null, first.getSoldAt());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.myshop.entity.CardKeyReclaim>> reclaims =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(cardKeyReclaimService).recordAll(reclaims.capture());
        assertEquals(2, reclaims.getValue().size());
        assertTrue(reclaims.getValue().stream().allMatch(reclaim ->
                orderId.equals(reclaim.getOrderId())
                        && item.getId().equals(reclaim.getOrderItemId())
                        && OPERATOR_ID.equals(reclaim.getOperatorId())));
        verify(cardKeyRepository).saveAll(List.of(first, second));
        org.mockito.ArgumentCaptor<String> auditDetail =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(operationLogService).logCurrentRequest(
                eq("order.refund"), eq("ORDER"), eq(orderId.toString()), auditDetail.capture());
        assertTrue(auditDetail.getValue().contains("回收卡密=是"));
        assertTrue(auditDetail.getValue().contains("回收数量=2"));
        assertTrue(auditDetail.getValue().contains("原因=确认卡密未使用"));
    }

    @Test
    void invalidReclaimCandidateFailsBeforeAnyInventoryOrAccountingMutation() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Order order = order(orderId, userId, OrderStatus.DELIVERED, "1.00");
        User user = user(userId, "0.00");
        OrderItem item = item(orderId, productId, null, StockMode.CARD_KEY,
                FulfillmentStatus.DELIVERED, 0, null);
        CardKey available = soldKey(orderId, item.getId(), productId, "invalid-card");
        available.setStatus(com.myshop.constant.CardKeyStatus.AVAILABLE);
        stubBasicRefund(order, user, List.of(item));
        when(cardKeyRepository.findByOrderIdForUpdate(orderId)).thenReturn(List.of(available));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refund(orderId, "异常回收", true, true));

        assertEquals(ErrorCode.ORDER_REFUND_NOT_ALLOWED, exception.getCode());
        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        assertEquals(FulfillmentStatus.DELIVERED, item.getFulfillmentStatus());
        assertEquals(com.myshop.constant.CardKeyStatus.AVAILABLE, available.getStatus());
        verify(cardKeyReclaimService, never()).recordAll(any());
        verify(cardKeyRepository, never()).saveAll(any());
        verify(userRepository, never()).save(any(User.class));
        verify(orderRepository, never()).save(any(Order.class));
        verifyNoInteractions(manualStockService, balanceTransactionService, operationLogService);
    }

    @Test
    void nonAdministratorContextCannotIssueARefund() {
        RequestContext.set(new RequestContext.UserInfo(OPERATOR_ID, "buyer", "USER"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refund(UUID.randomUUID(), "越权退款", true, false));

        assertEquals(ErrorCode.FORBIDDEN, exception.getCode());
        verifyNoInteractions(orderRepository, paymentAttemptRepository, userRepository,
                balanceTransactionService, operationLogService, cardKeyRepository,
                cardKeyReclaimService);
    }

    @Test
    void repeatedRequestSeesRefundedStatusAndDoesNotCreditTwice() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order order = order(orderId, userId, OrderStatus.PAID, "4.00");
        User user = user(userId, "0.00");
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING))
                .thenReturn(List.of());
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());

        service.refund(orderId, "第一次退款", true, false);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refund(orderId, "第二次退款", true, false));

        assertEquals(ErrorCode.ORDER_ALREADY_REFUNDED, exception.getCode());
        verify(balanceTransactionService).recordRefund(
                eq(userId), eq(new BigDecimal("4.00")), eq(new BigDecimal("0.00")),
                eq(new BigDecimal("4.00")), eq(orderId), eq("第一次退款"), eq(OPERATOR_ID));
    }

    @Test
    void stalePendingAttemptsAreClosedInsideTheRefundTransaction() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order order = order(orderId, userId, OrderStatus.PAID, "2.00");
        User user = user(userId, "0.00");
        com.myshop.entity.PaymentAttempt stale = new com.myshop.entity.PaymentAttempt();
        stale.setStatus(PaymentStatus.PENDING);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByOrderIdAndStatusForUpdate(orderId, PaymentStatus.PENDING))
                .thenReturn(List.of(stale));
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());

        service.refund(orderId, "关闭遗留支付", true, false);

        assertEquals(PaymentStatus.CLOSED, stale.getStatus());
        verify(paymentAttemptRepository).save(stale);
    }

    private Order order(UUID id, UUID userId, OrderStatus status, String amount) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNo(1000000001L);
        order.setUserId(userId);
        order.setTotalAmount(new BigDecimal(amount));
        order.setActualAmount(new BigDecimal(amount));
        order.setStatus(status);
        order.setOrderType(OrderType.NORMAL);
        order.setPaymentMethod(OrderPaymentMethod.ONLINE);
        order.setPaymentChannelCode("alipay");
        order.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return order;
    }

    private void stubBasicRefund(Order order, User user, List<OrderItem> items) {
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(paymentAttemptRepository.findByOrderIdAndStatusForUpdate(
                order.getId(), PaymentStatus.PENDING)).thenReturn(List.of());
        when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(items);
    }

    private CardKey soldKey(UUID orderId, UUID orderItemId, UUID productId, String fingerprint) {
        CardKey key = new CardKey();
        key.setId(UUID.randomUUID());
        key.setProductId(productId);
        key.setContentFingerprint(fingerprint);
        key.setContentParts(List.of(fingerprint));
        key.setStatus(com.myshop.constant.CardKeyStatus.SOLD);
        key.setOrderId(orderId);
        key.setOrderItemId(orderItemId);
        key.setSoldAt(LocalDateTime.now().minusMinutes(10));
        return key;
    }

    private User user(UUID id, String balance) {
        User user = new User();
        user.setId(id);
        user.setUsername("buyer");
        user.setRole(UserRole.USER);
        user.setBalance(new BigDecimal(balance));
        user.setIsDeleted(0);
        return user;
    }

    private OrderItem item(UUID orderId, UUID productId, UUID specId, StockMode stockMode,
                           FulfillmentStatus fulfillmentStatus, int reserved, String content) {
        OrderItem item = new OrderItem();
        item.setId(UUID.randomUUID());
        item.setOrderId(orderId);
        item.setProductId(productId);
        item.setSpecId(specId);
        item.setProductNo(1L);
        item.setProductTitle("退款测试商品");
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("12.34"));
        item.setCostPriceSnapshot(new BigDecimal("1.00"));
        item.setRetailPriceSnapshot(new BigDecimal("12.34"));
        item.setSubtotal(new BigDecimal("12.34"));
        item.setDeliveryType(stockMode == StockMode.MANUAL ? "MANUAL" : "AUTO");
        item.setStockMode(stockMode);
        item.setFulfillmentStatus(fulfillmentStatus);
        item.setManualStockReserved(reserved);
        item.setCustomerDeliveryContent(content);
        return item;
    }
}
