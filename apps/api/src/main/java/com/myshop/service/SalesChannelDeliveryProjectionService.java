package com.myshop.service;

import com.myshop.constant.SalesChannelDeliveryOperationStatus;
import com.myshop.constant.SalesChannelDeliveryOperationType;
import com.myshop.constant.SalesChannelOrderDeliveryStatus;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Rebuilds the channel delivery projection from durable operation facts.
 *
 * <p>Operation rows are the source of truth. In particular, a retryable
 * reconciliation failure does not resolve an UNKNOWN side effect, so this
 * service must keep MESSAGE_UNKNOWN/CONFIRM_UNKNOWN projected until the
 * provider proves a final target outcome.</p>
 */
@Service
@RequiredArgsConstructor
public class SalesChannelDeliveryProjectionService {

    private final SalesChannelDeliveryOperationRepository operationRepository;
    private final SalesChannelOrderRepository channelOrderRepository;

    @Transactional
    public SalesChannelOrderDeliveryStatus refreshDeliveryProjection(UUID channelOrderId) {
        SalesChannelOrder order = channelOrderRepository.findByIdForUpdate(channelOrderId).orElse(null);
        if (order == null) return null;
        List<SalesChannelDeliveryOperation> operations = operations(channelOrderId);
        SalesChannelOrderDeliveryStatus projected = project(order.getDeliveryStatus(), operations);
        if (projected != order.getDeliveryStatus()) {
            order.setDeliveryStatus(projected);
            channelOrderRepository.save(order);
        }
        return projected;
    }

    @Transactional(readOnly = true)
    public boolean hasUnresolvedMessageUnknown(UUID channelOrderId) {
        return hasUnresolvedUnknown(operations(channelOrderId),
                SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationType.RECONCILE_MESSAGE);
    }

    @Transactional(readOnly = true)
    public boolean hasUnresolvedConfirmUnknown(UUID channelOrderId) {
        return hasUnresolvedUnknown(operations(channelOrderId),
                SalesChannelDeliveryOperationType.CONFIRM_DELIVERY,
                SalesChannelDeliveryOperationType.RECONCILE_CONFIRM);
    }

    @Transactional
    public void markReviewRequired(UUID channelOrderId, String code, String message) {
        SalesChannelOrder order = channelOrderRepository.findByIdForUpdate(channelOrderId).orElse(null);
        if (order == null) return;
        order.setDeliveryStatus(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED);
        order.setLastErrorCode(code);
        order.setLastErrorMessage(message);
        channelOrderRepository.save(order);
    }

    /**
     * Applies the projection priority without writing the channel row. This is
     * package-visible for focused state-transition tests and keeps the rules in
     * one place instead of duplicating them in planner/worker branches.
     */
    SalesChannelOrderDeliveryStatus project(SalesChannelOrderDeliveryStatus current,
                                            List<SalesChannelDeliveryOperation> operations) {
        if (current == SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED) {
            return SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED;
        }
        if (hasPermanentReconcileFailure(operations)) {
            return SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED;
        }
        if (hasUnresolvedUnknown(operations, SalesChannelDeliveryOperationType.CONFIRM_DELIVERY,
                SalesChannelDeliveryOperationType.RECONCILE_CONFIRM)) {
            return SalesChannelOrderDeliveryStatus.CONFIRM_UNKNOWN;
        }
        if (hasUnresolvedUnknown(operations, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationType.RECONCILE_MESSAGE)) {
            return SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN;
        }
        if (hasPermanentSideEffectFailure(operations)) {
            return SalesChannelOrderDeliveryStatus.FAILED;
        }
        if (hasSucceeded(operations, SalesChannelDeliveryOperationType.CONFIRM_DELIVERY)) {
            return SalesChannelOrderDeliveryStatus.SUCCEEDED;
        }
        if (hasPendingOrRunning(operations, SalesChannelDeliveryOperationType.CONFIRM_DELIVERY,
                SalesChannelDeliveryOperationType.RECONCILE_CONFIRM)) {
            return SalesChannelOrderDeliveryStatus.CONFIRM_PENDING;
        }
        if (hasPendingOrRunning(operations, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationType.RECONCILE_MESSAGE)) {
            return hasRunning(operations, SalesChannelDeliveryOperationType.SEND_MESSAGE)
                    ? SalesChannelOrderDeliveryStatus.MESSAGE_SENDING
                    : SalesChannelOrderDeliveryStatus.MESSAGE_PENDING;
        }
        if (hasSucceeded(operations, SalesChannelDeliveryOperationType.SEND_MESSAGE)) {
            return SalesChannelOrderDeliveryStatus.FULFILLED;
        }
        return current;
    }

    private List<SalesChannelDeliveryOperation> operations(UUID channelOrderId) {
        return operationRepository.findBySalesChannelOrderIdOrderByCreatedAtAsc(channelOrderId);
    }

    private boolean hasUnresolvedUnknown(List<SalesChannelDeliveryOperation> operations,
                                         SalesChannelDeliveryOperationType targetType,
                                         SalesChannelDeliveryOperationType reconcileType) {
        return operations.stream()
                .filter(operation -> operation.getOperationType() == targetType
                        && operation.getStatus() == SalesChannelDeliveryOperationStatus.UNKNOWN)
                .anyMatch(target -> !isResolvedByReconciliation(target, operations, reconcileType));
    }

    private boolean isResolvedByReconciliation(SalesChannelDeliveryOperation target,
                                               List<SalesChannelDeliveryOperation> operations,
                                               SalesChannelDeliveryOperationType reconcileType) {
        return operations.stream()
                .filter(operation -> operation.getOperationType() == reconcileType
                        && Objects.equals(operation.getUnitIndex(), target.getUnitIndex()))
                .anyMatch(reconcile -> {
                    if (reconcile.getStatus() != SalesChannelDeliveryOperationStatus.SUCCEEDED) return false;
                    String outcome = value(reconcile.getResultJson(), "target_outcome");
                    return ("SUCCEEDED".equals(outcome)
                            && target.getStatus() == SalesChannelDeliveryOperationStatus.SUCCEEDED)
                            || ("FAILED".equals(outcome)
                            && target.getStatus() == SalesChannelDeliveryOperationStatus.FAILED);
                });
    }

    private boolean hasPermanentReconcileFailure(List<SalesChannelDeliveryOperation> operations) {
        return operations.stream()
                .filter(operation -> operation.getOperationType() == SalesChannelDeliveryOperationType.RECONCILE_MESSAGE
                        || operation.getOperationType() == SalesChannelDeliveryOperationType.RECONCILE_CONFIRM)
                .anyMatch(operation -> operation.getStatus() == SalesChannelDeliveryOperationStatus.FAILED
                        && operation.getNextAttemptAt() == null);
    }

    private boolean hasPermanentSideEffectFailure(List<SalesChannelDeliveryOperation> operations) {
        return operations.stream()
                .filter(operation -> operation.getOperationType() == SalesChannelDeliveryOperationType.SEND_MESSAGE
                        || operation.getOperationType() == SalesChannelDeliveryOperationType.CONFIRM_DELIVERY)
                .anyMatch(operation -> operation.getStatus() == SalesChannelDeliveryOperationStatus.FAILED
                        && operation.getNextAttemptAt() == null);
    }

    private boolean hasSucceeded(List<SalesChannelDeliveryOperation> operations,
                                 SalesChannelDeliveryOperationType type) {
        return operations.stream().anyMatch(operation -> operation.getOperationType() == type
                && operation.getStatus() == SalesChannelDeliveryOperationStatus.SUCCEEDED);
    }

    private boolean hasPendingOrRunning(List<SalesChannelDeliveryOperation> operations,
                                        SalesChannelDeliveryOperationType... types) {
        return operations.stream().anyMatch(operation -> isType(operation, types)
                && (operation.getStatus() == SalesChannelDeliveryOperationStatus.PENDING
                || operation.getStatus() == SalesChannelDeliveryOperationStatus.RUNNING
                || isRetryableFailure(operation)));
    }

    private boolean hasRunning(List<SalesChannelDeliveryOperation> operations,
                               SalesChannelDeliveryOperationType type) {
        return operations.stream().anyMatch(operation -> operation.getOperationType() == type
                && operation.getStatus() == SalesChannelDeliveryOperationStatus.RUNNING);
    }

    private boolean isRetryableFailure(SalesChannelDeliveryOperation operation) {
        return operation.getStatus() == SalesChannelDeliveryOperationStatus.FAILED
                && operation.getNextAttemptAt() != null;
    }

    private boolean isType(SalesChannelDeliveryOperation operation,
                           SalesChannelDeliveryOperationType... types) {
        for (SalesChannelDeliveryOperationType type : types) {
            if (operation.getOperationType() == type) return true;
        }
        return false;
    }

    private String value(Map<String, Object> values, String key) {
        if (values == null) return null;
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value).toUpperCase(java.util.Locale.ROOT);
    }
}
