package com.myshop.service.impl;

import com.myshop.constant.BalanceTransactionType;
import com.myshop.constant.ErrorCode;
import com.myshop.entity.BalanceTransaction;
import com.myshop.exception.BusinessException;
import com.myshop.repository.BalanceTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceTransactionServiceImplTest {

    @Mock
    private BalanceTransactionRepository repository;

    private BalanceTransactionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BalanceTransactionServiceImpl(repository);
    }

    @Test
    void recordsCanonicalAdminAdjustmentLedgerValues() {
        when(repository.save(any(BalanceTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UUID userId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();

        BalanceTransaction result = service.recordAdminAdjustment(
                userId,
                new BigDecimal("2.5"),
                new BigDecimal("-2.5"),
                new BigDecimal("10.00"),
                new BigDecimal("7.50"),
                "  人工扣减  ",
                operatorId);

        assertEquals(userId, result.getUserId());
        assertEquals(BalanceTransactionType.ADMIN_ADJUSTMENT, result.getType());
        assertEquals(new BigDecimal("2.50"), result.getAmount());
        assertEquals(new BigDecimal("-2.50"), result.getDelta());
        assertEquals(new BigDecimal("10.00"), result.getBalanceBefore());
        assertEquals(new BigDecimal("7.50"), result.getBalanceAfter());
        assertEquals("人工扣减", result.getReason());
        assertEquals(operatorId, result.getOperatorId());
    }

    @Test
    void rejectsLedgerWhenDeltaAndAmountDoNotMatch() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.recordAdminAdjustment(
                        UUID.randomUUID(),
                        new BigDecimal("2.00"),
                        new BigDecimal("-1.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("9.00"),
                        "错误流水",
                        UUID.randomUUID()));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        verify(repository, never()).save(any(BalanceTransaction.class));
    }

    @Test
    void storesNullForMissingOrBlankReason() {
        when(repository.save(any(BalanceTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UUID userId = UUID.randomUUID();
        UUID operatorId = UUID.randomUUID();

        BalanceTransaction missing = service.recordAdminAdjustment(
                userId, new BigDecimal("1.00"), new BigDecimal("1.00"),
                new BigDecimal("0.00"), new BigDecimal("1.00"), null, operatorId);
        BalanceTransaction blank = service.recordAdminAdjustment(
                userId, new BigDecimal("1.00"), new BigDecimal("1.00"),
                new BigDecimal("1.00"), new BigDecimal("2.00"), "  ", operatorId);

        assertNull(missing.getReason());
        assertNull(blank.getReason());
    }
}
