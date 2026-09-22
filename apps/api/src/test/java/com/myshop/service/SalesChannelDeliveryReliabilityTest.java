package com.myshop.service;

import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelDeliveryOperationStatus;
import com.myshop.constant.SalesChannelDeliveryOperationType;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.model.SalesChannelDtos.DeliveryReconcileData;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelDeliveryReliabilityTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Mock private SalesChannelDeliveryPlanner planner;
    @Mock private SalesChannelDeliveryOperationRepository operationRepository;
    @Mock private SalesChannelDeliveryClaimService claimService;
    @Mock private SalesChannelDeliveryRecoveryService recoveryService;
    @Mock private SalesChannelDeliveryOperationStateService stateService;
    @Mock private SalesChannelOrderRepository channelOrderRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private SalesChannelAccountRepository accountRepository;
    @Mock private CardKeyRepository cardKeyRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private SalesChannelProviderClient providerClient;

    @InjectMocks private SalesChannelDeliveryWorker worker;
    @InjectMocks private SalesChannelDeliveryClaimService claimServiceUnderTest;

    @Test
    void unknownMessageReconcileIsRescheduledWithSameOperationAndNeverSendsAgain() {
        UUID orderId = UUID.randomUUID();
        UUID reconcileId = UUID.randomUUID();
        String targetRequestId = "XIANYU_DELIVERY:" + orderId + ":0";
        SalesChannelOrder channelOrder = channelOrder(orderId);
        SalesChannelAccount account = account();
        SalesChannelDeliveryOperation reconcile = operation(reconcileId,
                SalesChannelDeliveryOperationType.RECONCILE_MESSAGE, "XIANYU_RECONCILE_MESSAGE:" + orderId + ":0");
        reconcile.setUnitIndex(0);
        reconcile.setSalesChannelOrderId(orderId);
        reconcile.setResultJson(new LinkedHashMap<>(Map.of("target_request_id", targetRequestId)));
        SalesChannelDeliveryOperation target = operation(UUID.randomUUID(),
                SalesChannelDeliveryOperationType.SEND_MESSAGE, targetRequestId);
        target.setSalesChannelOrderId(orderId);
        target.setStatus(SalesChannelDeliveryOperationStatus.UNKNOWN);

        when(channelOrderRepository.findById(orderId)).thenReturn(Optional.of(channelOrder));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(operationRepository.findByRequestId(targetRequestId)).thenReturn(Optional.of(target));
        when(providerClient.reconcileDeliveryUnit(any())).thenReturn(
                reconcileResponse(targetRequestId, Outcome.UNKNOWN),
                reconcileResponse(targetRequestId, Outcome.SUCCEEDED));

        worker.process(reconcile);
        worker.process(reconcile);

        verify(providerClient, times(2)).reconcileDeliveryUnit(any());
        verify(providerClient, never()).sendDeliveryUnit(any());
        verify(stateService, times(1)).failed(eq(reconcileId), eq("MESSAGE_UNKNOWN"), anyString(), eq(true));
        verify(stateService, times(1)).succeeded(eq(reconcileId), any());
        verify(stateService, times(1)).succeeded(eq(target.getId()), any());
    }

    @Test
    void unknownConfirmReconcileIsRescheduledWithSameOperationAndNeverConfirmsAgain() {
        UUID orderId = UUID.randomUUID();
        UUID reconcileId = UUID.randomUUID();
        String targetRequestId = "XIANYU_CONFIRM:" + orderId;
        SalesChannelOrder channelOrder = channelOrder(orderId);
        SalesChannelAccount account = account();
        SalesChannelDeliveryOperation reconcile = operation(reconcileId,
                SalesChannelDeliveryOperationType.RECONCILE_CONFIRM, "XIANYU_RECONCILE_CONFIRM:" + orderId);
        reconcile.setSalesChannelOrderId(orderId);
        reconcile.setResultJson(new LinkedHashMap<>(Map.of("target_request_id", targetRequestId)));
        SalesChannelDeliveryOperation target = operation(UUID.randomUUID(),
                SalesChannelDeliveryOperationType.CONFIRM_DELIVERY, targetRequestId);
        target.setSalesChannelOrderId(orderId);
        target.setStatus(SalesChannelDeliveryOperationStatus.UNKNOWN);

        when(channelOrderRepository.findById(orderId)).thenReturn(Optional.of(channelOrder));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(operationRepository.findByRequestId(targetRequestId)).thenReturn(Optional.of(target));
        when(providerClient.reconcileConfirmDelivery(any())).thenReturn(
                reconcileResponse(targetRequestId, Outcome.UNKNOWN),
                reconcileResponse(targetRequestId, Outcome.SUCCEEDED));

        worker.process(reconcile);
        worker.process(reconcile);

        verify(providerClient, times(2)).reconcileConfirmDelivery(any());
        verify(providerClient, never()).confirmDelivery(any());
        verify(stateService, times(1)).failed(eq(reconcileId), eq("CONFIRM_UNKNOWN"), anyString(), eq(true));
        verify(stateService, times(1)).succeeded(eq(reconcileId), any());
        verify(stateService, times(1)).succeeded(eq(target.getId()), any());
    }

    @Test
    void failedOperationAtAttemptLimitCannotBeClaimedForANinthProviderCall() {
        SalesChannelDeliveryOperation operation = operation(UUID.randomUUID(),
                SalesChannelDeliveryOperationType.SEND_MESSAGE, "XIANYU_DELIVERY:attempt-limit");
        operation.setStatus(SalesChannelDeliveryOperationStatus.FAILED);
        operation.setAttemptCount(8);
        operation.setNextAttemptAt(java.time.LocalDateTime.now().minusMinutes(1));
        when(operationRepository.findByIdForUpdate(operation.getId())).thenReturn(Optional.of(operation));

        assertEquals(Optional.empty(), claimServiceUnderTest.claim(operation.getId()));
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, operation.getStatus());
        assertEquals("DELIVERY_MAX_ATTEMPTS_EXCEEDED", operation.getLastErrorCode());
        verify(operationRepository).save(operation);
    }

    private IntegrationEnvelope<DeliveryReconcileData> reconcileResponse(String targetRequestId,
                                                                           Outcome targetOutcome) {
        return new IntegrationEnvelope<>("reconcile-request", Outcome.SUCCEEDED,
                new DeliveryReconcileData(targetRequestId, targetOutcome, "DELIVERY", false, Map.of()), null);
    }

    private SalesChannelDeliveryOperation operation(UUID id, SalesChannelDeliveryOperationType type,
                                                     String requestId) {
        SalesChannelDeliveryOperation operation = new SalesChannelDeliveryOperation();
        operation.setId(id);
        operation.setSalesChannelOrderId(id);
        operation.setOperationType(type);
        operation.setRequestId(requestId);
        operation.setPayloadFingerprint("a".repeat(64));
        operation.setResultJson(new LinkedHashMap<>());
        operation.setStatus(SalesChannelDeliveryOperationStatus.RUNNING);
        return operation;
    }

    private SalesChannelOrder channelOrder(UUID orderId) {
        SalesChannelOrder row = new SalesChannelOrder();
        row.setId(UUID.randomUUID());
        row.setOrderId(orderId);
        row.setSalesChannelAccountId(ACCOUNT_ID);
        row.setExternalOrderNo("external-order");
        return row;
    }

    private SalesChannelAccount account() {
        SalesChannelAccount account = new SalesChannelAccount();
        account.setId(ACCOUNT_ID);
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setProviderAccountId("agent-account");
        account.setStatus(SalesChannelAccountStatus.CONNECTED);
        return account;
    }
}
