package com.myshop.repository;

import com.myshop.entity.SalesChannelListingGeneration;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface SalesChannelListingGenerationRepository
        extends JpaRepository<SalesChannelListingGeneration, UUID> {

    Optional<SalesChannelListingGeneration> findBySalesChannelListingIdAndCurrentTrue(UUID listingId);

    default Optional<SalesChannelListingGeneration> findCurrentByListingId(UUID listingId) {
        return findBySalesChannelListingIdAndCurrentTrue(listingId);
    }

    Optional<SalesChannelListingGeneration> findBySalesChannelAccountIdAndExternalItemId(
            UUID accountId, String externalItemId);

    List<SalesChannelListingGeneration> findAllBySalesChannelAccountIdAndExternalItemIdIn(
            UUID accountId, Collection<String> externalItemIds);

    List<SalesChannelListingGeneration> findAllBySalesChannelListingIdOrderByGenerationNoAsc(UUID listingId);

    boolean existsBySalesChannelListingIdAndCurrentTrue(UUID listingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT generation FROM SalesChannelListingGeneration generation "
            + "WHERE generation.salesChannelListingId = :listingId AND generation.current = true")
    Optional<SalesChannelListingGeneration> findCurrentByListingIdForUpdate(@Param("listingId") UUID listingId);

    default Optional<SalesChannelListingGeneration> lockCurrentGeneration(UUID listingId) {
        return findCurrentByListingIdForUpdate(listingId);
    }

    @Query("SELECT COALESCE(MAX(generation.generationNo), 0) + 1 "
            + "FROM SalesChannelListingGeneration generation "
            + "WHERE generation.salesChannelListingId = :listingId")
    long nextGenerationNumber(@Param("listingId") UUID listingId);
}
