package com.myshop.service;

import com.myshop.constant.SalesChannelDeliveryOperationStatus;
import com.myshop.constant.SalesChannelDeliveryOperationType;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Recovers delivery worker claims after a process crash without replaying a
 * provider side effect. Only read-only reconciliation is made runnable again.
 */
@Service
@RequiredArgsConstructor
public class SalesChannelDeliveryRecoveryService {

    private final SalesChannelDeliveryOperationRepository operationRepository;
    private final SalesChannelDeliveryOperationStateService stateService;

    @Transactional
    public int recoverStaleRunning(LocalDateTime cutoff, int batchSize) {
        int recovered = 0;
        for (SalesChannelDeliveryOperation candidate : operationRepository.findStaleRunning(
                cutoff, PageRequest.of(0, Math.max(1, batchSize)))) {
            SalesChannelDeliveryOperation operation = operationRepository.findByIdForUpdate(candidate.getId())
                    .orElse(null);
            if (operation == null || operation.getStatus() != SalesChannelDeliveryOperationStatus.RUNNING
                    || operation.getStartedAt() == null || !operation.getStartedAt().isBefore(cutoff)) {
                continue;
            }

            if (operation.getOperationType() == SalesChannelDeliveryOperationType.SEND_MESSAGE) {
                stateService.unknown(operation.getId(), "WORKER_ATTEMPT_STALE",
                        "The worker stopped after the provider call may have started");
                stateService.createReconcile(operation.getSalesChannelOrderId(),
                        SalesChannelDeliveryOperationType.RECONCILE_MESSAGE,
                        operation.getUnitIndex(), operation.getRequestId());
            } else if (operation.getOperationType() == SalesChannelDeliveryOperationType.CONFIRM_DELIVERY) {
                stateService.unknown(operation.getId(), "WORKER_ATTEMPT_STALE",
                        "The worker stopped after the provider call may have started");
                stateService.createReconcile(operation.getSalesChannelOrderId(),
                        SalesChannelDeliveryOperationType.RECONCILE_CONFIRM,
                        null, operation.getRequestId());
            } else {
                // Reconciliation has no side effect, so the same durable
                // operation/request can safely be attempted again.
                operation.setStatus(SalesChannelDeliveryOperationStatus.PENDING);
                operation.setStartedAt(null);
                operation.setFinishedAt(null);
                operation.setNextAttemptAt(LocalDateTime.now());
                operation.setLastErrorCode("WORKER_ATTEMPT_STALE");
                operation.setLastErrorMessage("Read-only reconciliation was recovered after a worker restart");
                operationRepository.save(operation);
            }
            recovered++;
        }
        return recovered;
    }
}
