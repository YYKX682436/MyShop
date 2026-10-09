package com.myshop.repository;

import com.myshop.entity.SalesChannelRecoveryPermitEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.UUID;

public interface SalesChannelRecoveryPermitEventRepository
        extends JpaRepository<SalesChannelRecoveryPermitEvent, Long> {

    boolean existsByPermitIdAndGenerationAndEventTypeIn(
            UUID permitId, long generation, Collection<String> eventTypes);
}
