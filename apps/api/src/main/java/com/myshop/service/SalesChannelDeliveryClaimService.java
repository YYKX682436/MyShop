package com.myshop.service;

import com.myshop.constant.SalesChannelDeliveryOperationStatus;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SalesChannelDeliveryClaimService {
    private final SalesChannelDeliveryOperationRepository repository;

    @Transactional
    public Optional<SalesChannelDeliveryOperation> claim(UUID operationId) {
        SalesChannelDeliveryOperation operation = repository.findByIdForUpdate(operationId).orElse(null);
        if (operation == null) return Optional.empty();
        if (operation.getStatus() != SalesChannelDeliveryOperationStatus.PENDING
                && operation.getStatus() != SalesChannelDeliveryOperationStatus.FAILED) {
            return Optional.empty();
        }
        if (operation.getNextAttemptAt() != null && operation.getNextAttemptAt().isAfter(LocalDateTime.now())) {
            return Optional.empty();
        }
        operation.setStatus(SalesChannelDeliveryOperationStatus.RUNNING);
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setStartedAt(LocalDateTime.now());
        operation.setFinishedAt(null);
        return Optional.of(repository.save(operation));
    }
}
