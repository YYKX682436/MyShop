package com.myshop.service;

import com.myshop.repository.SalesChannelOperationRepository;
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

    @Transactional
    public List<UUID> selectReadyIds(LocalDateTime now, int batchSize) {
        return operationRepository.findReadyPendingForUpdate(now, batchSize).stream()
                .map(operation -> operation.getId()).toList();
    }
}
