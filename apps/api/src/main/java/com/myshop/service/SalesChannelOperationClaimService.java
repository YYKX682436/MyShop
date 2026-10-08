package com.myshop.service;

import com.myshop.entity.SalesChannelOperation;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.repository.SalesChannelRecoveryPermitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Selects candidate ids under row locks and releases the transaction before any provider HTTP call. */
@Service
@RequiredArgsConstructor
public class SalesChannelOperationClaimService {

    private final SalesChannelOperationRepository operationRepository;
    private final SalesChannelRecoveryPermitRepository recoveryPermitRepository;

    @Transactional
    public List<UUID> selectReadyIds(LocalDateTime now, int batchSize) {
        List<UUID> scopedRecovery = recoveryPermitRepository.findGoReadyOperationIdsForUpdate(now, batchSize);
        if (!scopedRecovery.isEmpty()) return scopedRecovery;
        if (recoveryPermitRepository.existsByStateIn(SalesChannelRecoveryPermitRepository.SCOPED_STATES)) {
            // Preserve replay for already-attempted UNKNOWN commands, while
            // suppressing every unattempted queue candidate in scoped mode.
            return operationRepository.findAttemptedRecoveryPendingForUpdate(now, batchSize).stream()
                    .map(SalesChannelOperation::getId).toList();
        }
        return operationRepository.findReadyPendingForUpdate(now, batchSize).stream()
                .map(operation -> operation.getId()).toList();
    }
}
