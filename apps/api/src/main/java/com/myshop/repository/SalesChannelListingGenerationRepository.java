package com.myshop.repository;

import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.constant.SalesChannelGenerationState;
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

    Optional<SalesChannelListingGeneration> findBySalesChannelListingIdAndGenerationState(
            UUID listingId, SalesChannelGenerationState state);

    default Optional<SalesChannelListingGeneration> findCurrentByListingId(UUID listingId) {
        return findBySalesChannelListingIdAndGenerationState(listingId, SalesChannelGenerationState.CURRENT);
    }

    Optional<SalesChannelListingGeneration> findBySalesChannelAccountIdAndExternalItemId(
            UUID accountId, String externalItemId);

    List<SalesChannelListingGeneration> findAllBySalesChannelAccountIdAndExternalItemIdIn(
            UUID accountId, Collection<String> externalItemIds);

    List<SalesChannelListingGeneration> findAllBySalesChannelListingIdOrderByGenerationNoAsc(UUID listingId);

    List<SalesChannelListingGeneration> findAllByGenerationStateOrderByCreatedAtAsc(
            SalesChannelGenerationState state);

    boolean existsBySalesChannelListingIdAndGenerationState(UUID listingId, SalesChannelGenerationState state);

    default Optional<SalesChannelListingGeneration> findCandidateByListingId(UUID listingId) {
        return findBySalesChannelListingIdAndGenerationState(listingId, SalesChannelGenerationState.CANDIDATE);
    }

    default boolean existsCandidateByListingId(UUID listingId) {
        return existsBySalesChannelListingIdAndGenerationState(listingId, SalesChannelGenerationState.CANDIDATE);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT generation FROM SalesChannelListingGeneration generation "
            + "WHERE generation.salesChannelListingId = :listingId "
            + "AND generation.generationState = com.myshop.constant.SalesChannelGenerationState.CURRENT")
    Optional<SalesChannelListingGeneration> findCurrentByListingIdForUpdate(@Param("listingId") UUID listingId);

    default Optional<SalesChannelListingGeneration> lockCurrentGeneration(UUID listingId) {
        return findCurrentByListingIdForUpdate(listingId);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT generation FROM SalesChannelListingGeneration generation "
            + "WHERE generation.salesChannelListingId = :listingId "
            + "AND generation.generationState = com.myshop.constant.SalesChannelGenerationState.CANDIDATE")
    Optional<SalesChannelListingGeneration> lockCandidateByListingId(@Param("listingId") UUID listingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT generation FROM SalesChannelListingGeneration generation "
            + "WHERE generation.id = :id")
    Optional<SalesChannelListingGeneration> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT COALESCE(MAX(generation.generationNo), 0) + 1 "
            + "FROM SalesChannelListingGeneration generation "
            + "WHERE generation.salesChannelListingId = :listingId")
    long nextGenerationNumber(@Param("listingId") UUID listingId);
}
