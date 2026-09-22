package com.myshop.service;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.SalesChannelDeliveryOperationStatus;
import com.myshop.constant.SalesChannelDeliveryOperationType;
import com.myshop.constant.SalesChannelOrderDeliveryStatus;
import com.myshop.entity.CardKey;
import com.myshop.entity.OrderItem;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.model.SalesChannelDtos.ConfirmDeliveryCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.DeliveryData;
import com.myshop.saleschannel.model.SalesChannelDtos.DeliveryReconcileCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.DeliveryReconcileData;
import com.myshop.saleschannel.model.SalesChannelDtos.DeliverySendCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Disabled-by-default provider delivery worker; HTTP is outside DB claims. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sales-channel.delivery", name = "worker-enabled", havingValue = "true")
public class SalesChannelDeliveryWorker {
    private final SalesChannelDeliveryPlanner planner;
    private final SalesChannelDeliveryOperationRepository operationRepository;
    private final SalesChannelDeliveryClaimService claimService;
    private final SalesChannelDeliveryOperationStateService stateService;
    private final com.myshop.repository.SalesChannelOrderRepository channelOrderRepository;
    private final OrderRepository orderRepository;
    private final SalesChannelAccountRepository accountRepository;
    private final CardKeyRepository cardKeyRepository;
    private final OrderItemRepository orderItemRepository;
    private final SalesChannelProviderClient providerClient;

    @Value("${sales-channel.worker.batch-size:10}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${sales-channel.delivery.interval-ms:1000}")
    public void poll() {
        planner.planPending();
        operationRepository.findDue(
                        List.of(SalesChannelDeliveryOperationStatus.PENDING,
                                SalesChannelDeliveryOperationStatus.FAILED),
                        java.time.LocalDateTime.now(), PageRequest.of(0, Math.max(1, batchSize)))
                .forEach(operation -> claimService.claim(operation.getId()).ifPresent(this::process));
    }

    private void process(SalesChannelDeliveryOperation operation) {
        try {
            switch (operation.getOperationType()) {
                case SEND_MESSAGE -> processSend(operation);
                case RECONCILE_MESSAGE -> processReconcileMessage(operation);
                case CONFIRM_DELIVERY -> processConfirm(operation);
                case RECONCILE_CONFIRM -> processReconcileConfirm(operation);
            }
        } catch (SalesChannelProviderClientException exception) {
            if (exception.getDisposition() == SalesChannelProviderClientException.Disposition.UNKNOWN) {
                stateService.unknown(operation.getId(), exception.getErrorCode(), "Provider outcome is unknown");
                if (operation.getOperationType() == SalesChannelDeliveryOperationType.SEND_MESSAGE) {
                    stateService.createReconcile(operation.getSalesChannelOrderId(), SalesChannelDeliveryOperationType.RECONCILE_MESSAGE,
                            operation.getUnitIndex(), operation.getRequestId());
                } else if (operation.getOperationType() == SalesChannelDeliveryOperationType.CONFIRM_DELIVERY) {
                    stateService.createReconcile(operation.getSalesChannelOrderId(), SalesChannelDeliveryOperationType.RECONCILE_CONFIRM,
                            null, operation.getRequestId());
                }
            } else {
                stateService.failed(operation.getId(), exception.getErrorCode(), "Provider request was rejected before an uncertain side effect",
                        exception.getDisposition() == SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE);
            }
        } catch (RuntimeException exception) {
            stateService.failed(operation.getId(), "WORKER_ERROR", "Delivery operation failed before a provider outcome was proven", true);
        }
    }

    private void processSend(SalesChannelDeliveryOperation operation) {
        SalesChannelOrder channelOrder = channelOrderRepository.findById(operation.getSalesChannelOrderId()).orElse(null);
        if (channelOrder == null || operation.getCardKeyId() == null) {
            stateService.failed(operation.getId(), "DATA_INTEGRITY_ERROR", "Delivery operation has no channel order or CardKey", false);
            return;
        }
        CardKey key = cardKeyRepository.findById(operation.getCardKeyId()).orElse(null);
        if (key == null || key.getStatus() != CardKeyStatus.SOLD
                || !channelOrder.getOrderId().equals(key.getOrderId())) {
            stateService.failed(operation.getId(), "DATA_INTEGRITY_ERROR", "CardKey is not SOLD and bound to the current order", false);
            stateService.setChannelStatus(channelOrder.getId(), SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED,
                    "DATA_INTEGRITY_ERROR", "CardKey binding is inconsistent");
            return;
        }
        List<OrderItem> items = orderItemRepository.findByOrderId(channelOrder.getOrderId());
        Set<UUID> itemIds = new HashSet<>(items.stream().map(OrderItem::getId).toList());
        if (key.getOrderItemId() == null || !itemIds.contains(key.getOrderItemId())) {
            stateService.failed(operation.getId(), "DATA_INTEGRITY_ERROR", "CardKey order item binding is inconsistent", false);
            return;
        }
        SalesChannelAccount account = accountRepository.findById(channelOrder.getSalesChannelAccountId()).orElse(null);
        if (account == null) {
            stateService.failed(operation.getId(), "ACCOUNT_NOT_FOUND", "Sales-channel account was not found", false);
            return;
        }
        IntegrationEnvelope<DeliveryData> response = providerClient.sendDeliveryUnit(new DeliverySendCommand(
                operation.getRequestId(), account.getProviderAccountId(), channelOrder.getExternalOrderNo(),
                operation.getUnitIndex(), List.copyOf(key.getContentParts())));
        if (response.outcome() == Outcome.SUCCEEDED && response.data() != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", response.data().status());
            if (response.data().messageId() != null) result.put("message_id", response.data().messageId());
            stateService.succeeded(operation.getId(), result);
            stateService.ensureConfirmOperation(channelOrder.getId(),
                    "XIANYU_CONFIRM:" + channelOrder.getId(), channelOrder.getExternalOrderNo());
        } else if (response.outcome() == Outcome.UNKNOWN) {
            stateService.unknown(operation.getId(), "MESSAGE_UNKNOWN", "Message send outcome is unknown");
            stateService.createReconcile(channelOrder.getId(), SalesChannelDeliveryOperationType.RECONCILE_MESSAGE,
                    operation.getUnitIndex(), operation.getRequestId());
        } else {
            boolean retryable = response.error() != null && Boolean.TRUE.equals(response.error().retryable());
            stateService.failed(operation.getId(), response.error() == null ? "DELIVERY_FAILED" : response.error().code(),
                    "Agent rejected the message delivery", retryable);
            if (!retryable) stateService.setChannelStatus(channelOrder.getId(), SalesChannelOrderDeliveryStatus.FAILED,
                    "DELIVERY_FAILED", "Agent rejected the message delivery");
        }
    }

    private void processReconcileMessage(SalesChannelDeliveryOperation operation) {
        SalesChannelOrder channelOrder = channelOrderRepository.findById(operation.getSalesChannelOrderId()).orElse(null);
        SalesChannelAccount account = channelOrder == null ? null
                : accountRepository.findById(channelOrder.getSalesChannelAccountId()).orElse(null);
        String target = targetRequest(operation);
        if (channelOrder == null || account == null || target == null) {
            stateService.failed(operation.getId(), "DATA_INTEGRITY_ERROR", "Reconcile operation is incomplete", false);
            return;
        }
        IntegrationEnvelope<DeliveryReconcileData> response = providerClient.reconcileDeliveryUnit(
                new DeliveryReconcileCommand(operation.getRequestId(), account.getProviderAccountId(), target,
                        channelOrder.getExternalOrderNo(), operation.getUnitIndex()));
        if (response.outcome() != Outcome.SUCCEEDED || response.data() == null) {
            stateService.failed(operation.getId(), "RECONCILE_RETRYABLE", "Read-only message reconciliation did not complete", true);
            return;
        }
        DeliveryReconcileData data = response.data();
        SalesChannelDeliveryOperation targetOperation = operationRepository.findByRequestId(target).orElse(null);
        if (targetOperation == null) {
            stateService.failed(operation.getId(), "TARGET_OPERATION_NOT_FOUND", "Target message operation is missing", false);
            return;
        }
        if (data.targetOutcome() == Outcome.SUCCEEDED) {
            stateService.succeeded(operation.getId(), Map.of("target_outcome", "SUCCEEDED"));
            stateService.succeeded(targetOperation.getId(), data.result() == null ? Map.of("status", "sent") : data.result());
            stateService.ensureConfirmOperation(channelOrder.getId(), "XIANYU_CONFIRM:" + channelOrder.getId(), channelOrder.getExternalOrderNo());
        } else if (data.targetOutcome() == Outcome.FAILED) {
            stateService.succeeded(operation.getId(), Map.of("target_outcome", "FAILED"));
            stateService.failed(targetOperation.getId(), "DELIVERY_FAILED", "Agent reconciliation proved delivery failed", Boolean.TRUE.equals(data.targetRetryable()));
        } else {
            stateService.succeeded(operation.getId(), Map.of("target_outcome", "UNKNOWN"));
            stateService.setChannelStatus(channelOrder.getId(), SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN,
                    "MESSAGE_UNKNOWN", "Reconciliation could not prove whether the message was sent");
        }
    }

    private void processConfirm(SalesChannelDeliveryOperation operation) {
        SalesChannelOrder channelOrder = channelOrderRepository.findById(operation.getSalesChannelOrderId()).orElse(null);
        SalesChannelAccount account = channelOrder == null ? null
                : accountRepository.findById(channelOrder.getSalesChannelAccountId()).orElse(null);
        if (channelOrder == null || account == null || channelOrder.getOrderId() == null) {
            stateService.failed(operation.getId(), "DATA_INTEGRITY_ERROR", "Confirm operation is incomplete", false);
            return;
        }
        var order = orderRepository.findById(channelOrder.getOrderId()).orElse(null);
        var items = order == null ? List.<OrderItem>of() : orderItemRepository.findByOrderId(order.getId());
        var first = items.stream().findFirst().orElse(null);
        if (order == null || first == null) {
            stateService.failed(operation.getId(), "DATA_INTEGRITY_ERROR", "Confirm order snapshot is incomplete", false);
            return;
        }
        IntegrationEnvelope<DeliveryData> response = providerClient.confirmDelivery(new ConfirmDeliveryCommand(
                operation.getRequestId(), account.getProviderAccountId(), channelOrder.getExternalOrderNo()));
        if (response.outcome() == Outcome.SUCCEEDED) {
            stateService.succeeded(operation.getId(), Map.of("status", "shipped"));
            stateService.setChannelStatus(channelOrder.getId(), SalesChannelOrderDeliveryStatus.SUCCEEDED, null, null);
        } else if (response.outcome() == Outcome.UNKNOWN) {
            stateService.unknown(operation.getId(), "CONFIRM_UNKNOWN", "Confirm delivery outcome is unknown");
            stateService.createReconcile(channelOrder.getId(), SalesChannelDeliveryOperationType.RECONCILE_CONFIRM,
                    null, operation.getRequestId());
        } else {
            boolean retryable = response.error() != null && Boolean.TRUE.equals(response.error().retryable());
            stateService.failed(operation.getId(), response.error() == null ? "CONFIRM_FAILED" : response.error().code(),
                    "Agent rejected confirm delivery", retryable);
            if (!retryable) stateService.setChannelStatus(channelOrder.getId(), SalesChannelOrderDeliveryStatus.FAILED,
                    "CONFIRM_FAILED", "Agent rejected confirm delivery");
        }
    }

    private void processReconcileConfirm(SalesChannelDeliveryOperation operation) {
        SalesChannelOrder channelOrder = channelOrderRepository.findById(operation.getSalesChannelOrderId()).orElse(null);
        SalesChannelAccount account = channelOrder == null ? null
                : accountRepository.findById(channelOrder.getSalesChannelAccountId()).orElse(null);
        String target = targetRequest(operation);
        if (channelOrder == null || account == null || target == null) {
            stateService.failed(operation.getId(), "DATA_INTEGRITY_ERROR", "Confirm reconcile operation is incomplete", false);
            return;
        }
        IntegrationEnvelope<DeliveryReconcileData> response = providerClient.reconcileConfirmDelivery(
                new DeliveryReconcileCommand(operation.getRequestId(), account.getProviderAccountId(), target,
                        channelOrder.getExternalOrderNo(), null));
        if (response.outcome() != Outcome.SUCCEEDED || response.data() == null) {
            stateService.failed(operation.getId(), "RECONCILE_RETRYABLE", "Read-only confirm reconciliation did not complete", true);
            return;
        }
        SalesChannelDeliveryOperation targetOperation = operationRepository.findByRequestId(target).orElse(null);
        if (targetOperation == null) {
            stateService.failed(operation.getId(), "TARGET_OPERATION_NOT_FOUND", "Target confirm operation is missing", false);
            return;
        }
        DeliveryReconcileData data = response.data();
        if (data.targetOutcome() == Outcome.SUCCEEDED) {
            stateService.succeeded(operation.getId(), Map.of("target_outcome", "SUCCEEDED"));
            stateService.succeeded(targetOperation.getId(), Map.of("status", "shipped"));
            stateService.setChannelStatus(channelOrder.getId(), SalesChannelOrderDeliveryStatus.SUCCEEDED, null, null);
        } else if (data.targetOutcome() == Outcome.FAILED) {
            stateService.succeeded(operation.getId(), Map.of("target_outcome", "FAILED"));
            stateService.failed(targetOperation.getId(), "CONFIRM_FAILED", "Reconciliation proved confirm delivery failed", Boolean.TRUE.equals(data.targetRetryable()));
        } else {
            stateService.succeeded(operation.getId(), Map.of("target_outcome", "UNKNOWN"));
            stateService.setChannelStatus(channelOrder.getId(), SalesChannelOrderDeliveryStatus.CONFIRM_UNKNOWN,
                    "CONFIRM_UNKNOWN", "Reconciliation could not prove whether confirm delivery applied");
        }
    }

    private String targetRequest(SalesChannelDeliveryOperation operation) {
        Object value = operation.getResultJson() == null ? null : operation.getResultJson().get("target_request_id");
        return value == null ? null : String.valueOf(value);
    }
}
