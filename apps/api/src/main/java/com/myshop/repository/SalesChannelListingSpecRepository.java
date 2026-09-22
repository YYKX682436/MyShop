package com.myshop.repository;

import com.myshop.entity.SalesChannelListingSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.UUID;

public interface SalesChannelListingSpecRepository extends JpaRepository<SalesChannelListingSpec, UUID> {

    List<SalesChannelListingSpec> findBySalesChannelListingIdOrderByCreatedAtAsc(UUID listingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT mapping FROM SalesChannelListingSpec mapping "
            + "WHERE mapping.salesChannelListingId = :listingId ORDER BY mapping.createdAt ASC")
    List<SalesChannelListingSpec> findBySalesChannelListingIdForUpdate(@Param("listingId") UUID listingId);

    boolean existsBySalesChannelListingIdAndProductSpecId(UUID listingId, UUID productSpecId);
}
