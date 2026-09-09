package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.UserRole;
import com.myshop.entity.Order;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.repository.UserRepository;
import com.myshop.service.BalanceTransactionService;
import com.myshop.service.OrderPaymentCompletionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceOrderPaymentServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private BalanceTransactionService balanceTransactionService;
    @Mock private OrderPaymentCompletionService orderPaymentCompletionService;

    @Test
    void debitsTheLockedUserAndRecordsTheExactLedgerTransition() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Order order = balanceOrder(orderId, userId, "12.34");
        User user = user(userId, "20.00", UserRole.USER);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        BalanceOrderPaymentServiceImpl service = service();
        service.pay(order, userId);

        assertEquals(new BigDecimal("7.66"), user.getBalance());
        verify(balanceTransactionService).recordOrderPayment(
                userId, new BigDecimal("12.34"), new BigDecimal("20.00"),
                new BigDecimal("7.66"), orderId, userId);
        verify(orderPaymentCompletionService).completeFirstPayment(order);
    }

    @Test
    void allowsTheBalanceToEqualTheOrderAmountWithoutGoingNegative() {
        UUID userId = UUID.randomUUID();
        Order order = balanceOrder(UUID.randomUUID(), userId, "20.00");
        User user = user(userId, "20.00", UserRole.USER);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        service().pay(order, userId);

        assertEquals(new BigDecimal("0.00"), user.getBalance());
    }

    @Test
    void insufficientBalanceFailsBeforeAnyDebitOrLedgerWrite() {
        UUID userId = UUID.randomUUID();
        Order order = balanceOrder(UUID.randomUUID(), userId, "20.01");
        User user = user(userId, "20.00", UserRole.USER);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service().pay(order, userId));

        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, exception.getCode());
        assertEquals(new BigDecimal("20.00"), user.getBalance());
        verify(userRepository, never()).save(user);
        verify(balanceTransactionService, never()).recordOrderPayment(
                eq(userId), eq(new BigDecimal("20.01")), eq(new BigDecimal("20.00")),
                eq(new BigDecimal("-0.01")), eq(order.getId()), eq(userId));
        verify(orderPaymentCompletionService, never()).completeFirstPayment(order);
    }

    @Test
    void guestAndAdministratorAccountsCannotUseBalancePayment() {
        Order guestOrder = balanceOrder(UUID.randomUUID(), null, "10.00");
        BusinessException guest = assertThrows(BusinessException.class,
                () -> service().pay(guestOrder, null));
        assertEquals(ErrorCode.BALANCE_PAYMENT_REQUIRES_LOGIN, guest.getCode());

        UUID adminId = UUID.randomUUID();
        Order adminOrder = balanceOrder(UUID.randomUUID(), adminId, "10.00");
        when(userRepository.findByIdForUpdate(adminId))
                .thenReturn(Optional.of(user(adminId, "100.00", UserRole.ADMIN)));
        BusinessException admin = assertThrows(BusinessException.class,
                () -> service().pay(adminOrder, adminId));
        assertEquals(ErrorCode.BALANCE_PAYMENT_REQUIRES_LOGIN, admin.getCode());
    }

    private BalanceOrderPaymentServiceImpl service() {
        return new BalanceOrderPaymentServiceImpl(
                userRepository, balanceTransactionService, orderPaymentCompletionService);
    }

    private Order balanceOrder(UUID orderId, UUID userId, String amount) {
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setOrderType(OrderType.NORMAL);
        order.setPaymentMethod(OrderPaymentMethod.BALANCE);
        order.setPaymentChannelCode(null);
        order.setStatus(OrderStatus.PENDING);
        order.setActualAmount(new BigDecimal(amount));
        return order;
    }

    private User user(UUID id, String balance, UserRole role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setIsDeleted(0);
        user.setBalance(new BigDecimal(balance));
        return user;
    }
}
