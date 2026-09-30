package com.myshop.repository;

import com.myshop.entity.SalesChannelListing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.util.Collection;
import java.util.UUID;

public interface SalesChannelListingRepository extends JpaRepository<SalesChannelListing, UUID> {

    Optional<SalesChannelListing> findBySalesChannelAccountIdAndProductId(UUID accountId, UUID productId);

    List<SalesChannelListing> findBySalesChannelAccountIdAndProductIdIn(
            UUID accountId, Collection<UUID> productIds);

    List<SalesChannelListing> findByEnabledTrueOrderByUpdatedAtAsc();

    List<SalesChannelListing> findByProductIdOrderByCreatedAtAsc(UUID productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT listing FROM SalesChannelListing listing WHERE listing.id = :id")
    Optional<SalesChannelListing> findByIdForUpdate(@Param("id") UUID id);
}
