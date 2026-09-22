package com.myshop.service;

import com.myshop.constant.SalesChannelDeliveryOperationStatus;
import com.myshop.constant.SalesChannelDeliveryOperationType;
import com.myshop.constant.SalesChannelOrderDeliveryStatus;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final OrderRepository orderRepository;
    private final SalesChannelOrderRepository channelOrderRepository;

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
    }

    @Transactional
    public void failed(UUID operationId, String code, String message, boolean retryable) {
        SalesChannelDeliveryOperation operation = operationRepository.findByIdForUpdate(operationId).orElseThrow();
        operation.setStatus(SalesChannelDeliveryOperationStatus.FAILED);
        operation.setLastErrorCode(code);
        operation.setLastErrorMessage(message == null ? null : message.substring(0, Math.min(1000, message.length())));
        operation.setFinishedAt(LocalDateTime.now());
        operation.setNextAttemptAt(retryable ? LocalDateTime.now().plusSeconds(backoff(operation.getAttemptCount())) : null);
        operationRepository.save(operation);
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
    }

    @Transactional
    public void createReconcile(UUID orderId, SalesChannelDeliveryOperationType type,
                                Integer unitIndex, String targetRequestId) {
        if (operationRepository.findBySalesChannelOrderIdAndOperationTypeAndUnitIndex(
                orderId, type, unitIndex).isPresent()) return;
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
        channelOrderRepository.findByIdForUpdate(orderId).ifPresent(order -> {
            order.setDeliveryStatus(type == SalesChannelDeliveryOperationType.RECONCILE_MESSAGE
                    ? SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN
                    : SalesChannelOrderDeliveryStatus.CONFIRM_UNKNOWN);
            channelOrderRepository.save(order);
        });
    }

    @Transactional
    public void ensureConfirmOperation(UUID orderId, String requestId, String externalOrderNo) {
        SalesChannelOrder order = channelOrderRepository.findByIdForUpdate(orderId).orElseThrow();
        List<SalesChannelDeliveryOperation> sends = operationRepository
                .findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(
                        orderId, SalesChannelDeliveryOperationType.SEND_MESSAGE);
        if (sends.isEmpty() || sends.stream().anyMatch(item -> item.getStatus() != SalesChannelDeliveryOperationStatus.SUCCEEDED)) return;
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
        order.setDeliveryStatus(SalesChannelOrderDeliveryStatus.CONFIRM_PENDING);
        channelOrderRepository.save(order);
    }

    @Transactional
    public void setChannelStatus(UUID channelOrderId, SalesChannelOrderDeliveryStatus status,
                                 String code, String message) {
        SalesChannelOrder order = channelOrderRepository.findById(channelOrderId).orElseThrow();
        order.setDeliveryStatus(status);
        order.setLastErrorCode(code);
        order.setLastErrorMessage(message);
        channelOrderRepository.save(order);
    }

    private long backoff(int attempts) { return Math.min(300, Math.max(1, 1L << Math.min(8, attempts))); }

    private String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}
