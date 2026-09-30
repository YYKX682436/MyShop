package com.myshop.repository;

import com.myshop.entity.SalesChannelListingGenerationSpec;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SalesChannelListingGenerationSpecRepository
        extends JpaRepository<SalesChannelListingGenerationSpec, UUID> {

    List<SalesChannelListingGenerationSpec>
    findBySalesChannelListingGenerationIdOrderByCreatedAtAsc(UUID generationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT mapping FROM SalesChannelListingGenerationSpec mapping "
            + "WHERE mapping.salesChannelListingGenerationId = :generationId "
            + "ORDER BY mapping.createdAt ASC")
    List<SalesChannelListingGenerationSpec>
    findBySalesChannelListingGenerationIdForUpdate(@Param("generationId") UUID generationId);

    boolean existsBySalesChannelListingGenerationIdAndProductSpecId(UUID generationId, UUID productSpecId);
}
