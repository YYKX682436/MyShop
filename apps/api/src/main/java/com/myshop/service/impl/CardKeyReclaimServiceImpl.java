package com.myshop.service.impl;

import com.myshop.entity.CardKeyReclaim;
import com.myshop.repository.CardKeyReclaimRepository;
import com.myshop.service.CardKeyReclaimService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CardKeyReclaimServiceImpl implements CardKeyReclaimService {

    private final CardKeyReclaimRepository repository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAll(List<CardKeyReclaim> reclaims) {
        if (reclaims == null || reclaims.isEmpty()) return;
        // Flush now so the immutable proof is persisted before the caller
        // clears the corresponding CardKey rows.
        repository.saveAllAndFlush(reclaims);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByOrderId(UUID orderId) {
        return repository.countByOrderId(orderId);
    }
}
