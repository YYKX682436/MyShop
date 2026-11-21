package com.myshop.service.impl;

import com.myshop.constant.BalanceTransactionType;
import com.myshop.entity.BalanceTransaction;
import com.myshop.entity.Order;
import com.myshop.entity.User;
import com.myshop.exception.BusinessException;
import com.myshop.repository.BalanceTransactionRepository;
import com.myshop.repository.UserRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.constant.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubsiteSettlementServiceTest {
    @Mock UserRepository users;
    @Mock BalanceTransactionRepository ledger;
    @Mock OrderRepository orders;

    @Test void deliveryCreditsOnceAndRefundReversesOnce() {
        SubsiteSettlementService service = new SubsiteSettlementService(users, ledger, orders);
        UUID ownerId = UUID.randomUUID();
        Order order = order(ownerId, "5.00");
        User owner = new User(); owner.setId(ownerId); owner.setBalance(new BigDecimal("10.00"));
        when(users.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        service.settle(order);
        service.settle(order);
        assertEquals("PENDING", order.getSubsiteProfitState());
        order.setStatus(OrderStatus.DELIVERED); order.setDeliveredAt(java.time.LocalDateTime.now());
        service.creditLocked(order);
        service.creditLocked(order);
        assertEquals(new BigDecimal("15.00"), owner.getBalance());
        service.reverse(order, owner);
        service.reverse(order, owner);
        assertEquals(new BigDecimal("10.00"), owner.getBalance());
        ArgumentCaptor<BalanceTransaction> entries = ArgumentCaptor.forClass(BalanceTransaction.class);
        verify(ledger, times(2)).save(entries.capture());
        assertEquals(BalanceTransactionType.SUBSITE_PROFIT, entries.getAllValues().get(0).getType());
        assertEquals(new BigDecimal("5.00"), entries.getAllValues().get(0).getDelta());
        assertEquals(BalanceTransactionType.SUBSITE_PROFIT_REVERSAL, entries.getAllValues().get(1).getType());
        assertEquals(new BigDecimal("-5.00"), entries.getAllValues().get(1).getDelta());
    }

    @Test void insufficientOwnerBalanceLeavesMoneyAndOrderUntouched() {
        SubsiteSettlementService service = new SubsiteSettlementService(users, ledger, orders);
        UUID ownerId = UUID.randomUUID();
        Order order = order(ownerId, "5.00");
        order.setSubsiteProfitState("SETTLED");
        order.setSubsiteProfitSettledAt(java.time.LocalDateTime.now());
        User owner = new User(); owner.setId(ownerId); owner.setBalance(new BigDecimal("1.00"));
        BusinessException error = assertThrows(BusinessException.class, () -> service.reverse(order, owner));
        assertTrue(error.getMessage().contains("4.00"));
        assertEquals(new BigDecimal("1.00"), owner.getBalance());
        assertNull(order.getSubsiteProfitReversedAt());
        verify(ledger, never()).save(any());
        verify(users, never()).save(any());
    }

    @Test void fullBalanceKeepsEarnedProfitPendingUntilSpaceIsAvailable() {
        SubsiteSettlementService service = new SubsiteSettlementService(users, ledger, orders);
        UUID ownerId = UUID.randomUUID();
        Order order = order(ownerId, "5.00");
        User owner = new User(); owner.setId(ownerId); owner.setBalance(new BigDecimal("9999999999.99"));
        when(users.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        service.settle(order);
        order.setStatus(OrderStatus.DELIVERED); order.setDeliveredAt(java.time.LocalDateTime.now());
        service.creditLocked(order);
        assertEquals("PENDING", order.getSubsiteProfitState());
        verify(ledger, never()).save(any());
        owner.setBalance(new BigDecimal("9999999994.99"));
        service.creditLocked(order);
        service.creditLocked(order);
        assertEquals("SETTLED", order.getSubsiteProfitState());
        assertEquals(new BigDecimal("9999999999.99"), owner.getBalance());
        verify(ledger, times(1)).save(any());
    }

    @Test void pendingRefundCancelsWithoutTouchingOwnerBalance() {
        SubsiteSettlementService service = new SubsiteSettlementService(users, ledger, orders);
        Order order = order(UUID.randomUUID(), "5.00");
        service.settle(order);
        service.reverse(order, null);
        service.reverse(order, null);
        order.setStatus(OrderStatus.DELIVERED); order.setDeliveredAt(java.time.LocalDateTime.now());
        service.creditLocked(order);
        assertEquals("CANCELED", order.getSubsiteProfitState());
        assertNotNull(order.getSubsiteProfitCanceledAt());
        verifyNoInteractions(users, ledger);
    }

    @Test void ownerPurchaseCannotCreateProfit() {
        SubsiteSettlementService service = new SubsiteSettlementService(users, ledger, orders);
        UUID owner = UUID.randomUUID();
        Order order = order(owner, "5.00"); order.setUserId(owner);
        assertThrows(BusinessException.class, () -> service.settle(order));
        verifyNoInteractions(users, ledger);
    }

    private Order order(UUID owner, String profit) {
        Order order = new Order(); order.setId(UUID.randomUUID()); order.setSubsiteId(UUID.randomUUID());
        order.setSubsiteOwnerIdSnapshot(owner); order.setSubsiteProfit(new BigDecimal(profit));
        return order;
    }
}
