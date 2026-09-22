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
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SalesChannelDeliveryOperationStateService {
    private final SalesChannelDeliveryOperationRepository operationRepository;
    private final SalesChannelOrderRepository channelOrderRepository;
    private final SalesChannelDeliveryProjectionService projectionService;

    @Value("${sales-channel.delivery.max-attempts:8}")
    private int maxAttempts;

    @Transactional
    public void succeeded(UUID operationId, Map<String, Object> result) {
        SalesChannelDeliveryOperation operation = operationRepository.findByIdForUpdate(operationId).orElseThrow();
        operation.setStatus(SalesChannelDeliveryOperationStatus.SUCCEEDED);
        operation.setResultJson(result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result));
        operation.setLastErrorCode(null);
        operation.setLastErrorMessage(null);
        operation.setFinishedAt(LocalDateTime.now());
        operation.setNextAttemptAt(null);
        operationRepository.save(operation);
        projectionService.refreshDeliveryProjection(operation.getSalesChannelOrderId());
    }

    @Transactional
    public void failed(UUID operationId, String code, String message, boolean retryable) {
        SalesChannelDeliveryOperation operation = operationRepository.findByIdForUpdate(operationId).orElseThrow();
        boolean exhausted = retryable && operation.getAttemptCount() >= configuredMaxAttempts();
        if (exhausted) {
            exhaustLocked(operation,
                    isReconcile(operation)
                            ? "Read-only reconciliation exhausted its maximum attempt count"
                            : "Delivery operation reached its maximum attempt count");
            return;
        }
        operation.setStatus(SalesChannelDeliveryOperationStatus.FAILED);
        operation.setLastErrorCode(code);
        operation.setLastErrorMessage(limit(message));
        LocalDateTime now = LocalDateTime.now();
        operation.setFinishedAt(now);
        operation.setNextAttemptAt(retryable ? now.plusSeconds(backoff(operation.getAttemptCount())) : null);
        operationRepository.save(operation);
        if (!retryable && isReconcile(operation)) {
            projectionService.markReviewRequired(operation.getSalesChannelOrderId(), code, message);
        } else {
            projectionService.refreshDeliveryProjection(operation.getSalesChannelOrderId());
        }
    }

    @Transactional
    public void unknown(UUID operationId, String code, String message) {
        SalesChannelDeliveryOperation operation = operationRepository.findByIdForUpdate(operationId).orElseThrow();
        operation.setStatus(SalesChannelDeliveryOperationStatus.UNKNOWN);
        operation.setLastErrorCode(code);
        operation.setLastErrorMessage(message == null ? null : message.substring(0, Math.min(1000, message.length())));
        operation.setFinishedAt(LocalDateTime.now());
        operation.setNextAttemptAt(null);
        operationRepository.save(operation);
        projectionService.refreshDeliveryProjection(operation.getSalesChannelOrderId());
    }

    @Transactional
    public void createReconcile(UUID orderId, SalesChannelDeliveryOperationType type,
                                Integer unitIndex, String targetRequestId) {
        if (operationRepository.findBySalesChannelOrderIdAndOperationTypeAndUnitIndex(
                orderId, type, unitIndex).isPresent()) {
            projectionService.refreshDeliveryProjection(orderId);
            return;
        }
        SalesChannelDeliveryOperation operation = new SalesChannelDeliveryOperation();
        operation.setSalesChannelOrderId(orderId);
        operation.setOperationType(type);
        operation.setUnitIndex(unitIndex);
        operation.setRequestId(type == SalesChannelDeliveryOperationType.RECONCILE_MESSAGE
                ? "XIANYU_RECONCILE_MESSAGE:" + orderId + ":" + unitIndex
                : "XIANYU_RECONCILE_CONFIRM:" + orderId);
        operation.setPayloadFingerprint(digest(targetRequestId + ":" + orderId + ":" + unitIndex));
        operation.setResultJson(new LinkedHashMap<>(Map.of("target_request_id", targetRequestId)));
        operation.setStatus(SalesChannelDeliveryOperationStatus.PENDING);
        operationRepository.save(operation);
        projectionService.refreshDeliveryProjection(orderId);
    }

    @Transactional
    public void ensureConfirmOperation(UUID orderId, String requestId, String externalOrderNo) {
        SalesChannelOrder order = channelOrderRepository.findByIdForUpdate(orderId).orElseThrow();
        List<SalesChannelDeliveryOperation> sends = operationRepository
                .findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(
                        orderId, SalesChannelDeliveryOperationType.SEND_MESSAGE);
        if (sends.isEmpty() || sends.stream().anyMatch(item -> item.getStatus() != SalesChannelDeliveryOperationStatus.SUCCEEDED)) {
            projectionService.refreshDeliveryProjection(orderId);
            return;
        }
        boolean hasConfirm = operationRepository.findBySalesChannelOrderIdOrderByCreatedAtAsc(orderId).stream()
                .anyMatch(item -> item.getOperationType() == SalesChannelDeliveryOperationType.CONFIRM_DELIVERY
                        || item.getOperationType() == SalesChannelDeliveryOperationType.RECONCILE_CONFIRM);
        if (!hasConfirm) {
            SalesChannelDeliveryOperation operation = new SalesChannelDeliveryOperation();
            operation.setSalesChannelOrderId(orderId);
            operation.setOperationType(SalesChannelDeliveryOperationType.CONFIRM_DELIVERY);
            operation.setRequestId(requestId);
            operation.setPayloadFingerprint(digest(orderId + ":" + externalOrderNo));
            operation.setResultJson(new LinkedHashMap<>());
            operation.setStatus(SalesChannelDeliveryOperationStatus.PENDING);
            operationRepository.save(operation);
        }
        projectionService.refreshDeliveryProjection(orderId);
    }

    @Transactional
    public void setChannelStatus(UUID channelOrderId, SalesChannelOrderDeliveryStatus status,
                                 String code, String message) {
        SalesChannelOrder order = channelOrderRepository.findByIdForUpdate(channelOrderId).orElseThrow();
        if (status != SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED) {
            SalesChannelOrderDeliveryStatus projected = projectionService.refreshDeliveryProjection(channelOrderId);
            if (projected != status) return;
        }
        order.setDeliveryStatus(status);
        order.setLastErrorCode(code);
        order.setLastErrorMessage(message);
        channelOrderRepository.save(order);
    }

    /**
     * Shared max-attempt transition used by both claim and state paths. The
     * target side-effect operation is never changed to UNKNOWN/FAILED here;
     * reconciliation exhaustion only changes the reconciliation row and its
     * channel projection.
     */
    @Transactional
    public boolean exhaustIfAtLimit(UUID operationId) {
        SalesChannelDeliveryOperation operation = operationRepository.findByIdForUpdate(operationId).orElse(null);
        if (operation == null || operation.getAttemptCount() < configuredMaxAttempts()) return false;
        exhaustLocked(operation,
                isReconcile(operation)
                        ? "Read-only reconciliation exhausted its maximum attempt count"
                        : "Delivery operation reached its maximum attempt count");
        return true;
    }

    @Transactional
    public void exhaustOperation(UUID operationId, String message) {
        SalesChannelDeliveryOperation operation = operationRepository.findByIdForUpdate(operationId).orElseThrow();
        exhaustLocked(operation, message);
    }

    private void exhaustLocked(SalesChannelDeliveryOperation operation, String message) {
        operation.setStatus(SalesChannelDeliveryOperationStatus.FAILED);
        operation.setLastErrorCode("DELIVERY_MAX_ATTEMPTS_EXCEEDED");
        operation.setLastErrorMessage(limit(message));
        operation.setFinishedAt(LocalDateTime.now());
        operation.setNextAttemptAt(null);
        operationRepository.save(operation);
        if (isReconcile(operation)) {
            projectionService.markReviewRequired(operation.getSalesChannelOrderId(),
                    "DELIVERY_MAX_ATTEMPTS_EXCEEDED",
                    "Read-only reconciliation exhausted its maximum attempt count");
        } else {
            projectionService.refreshDeliveryProjection(operation.getSalesChannelOrderId());
        }
    }

    private boolean isReconcile(SalesChannelDeliveryOperation operation) {
        return operation.getOperationType() == SalesChannelDeliveryOperationType.RECONCILE_MESSAGE
                || operation.getOperationType() == SalesChannelDeliveryOperationType.RECONCILE_CONFIRM;
    }

    private int configuredMaxAttempts() { return Math.max(1, maxAttempts); }

    private String limit(String message) {
        return message == null ? null : message.substring(0, Math.min(1000, message.length()));
    }

    private long backoff(int attempts) { return Math.min(300, Math.max(1, 1L << Math.min(8, attempts))); }

    private String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}
