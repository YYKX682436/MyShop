package com.myshop.repository;

import com.myshop.entity.SalesChannelListingSpec;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SalesChannelListingSpecRepository extends JpaRepository<SalesChannelListingSpec, UUID> {

    List<SalesChannelListingSpec> findBySalesChannelListingIdOrderByCreatedAtAsc(UUID listingId);

    boolean existsBySalesChannelListingIdAndProductSpecId(UUID listingId, UUID productSpecId);
}
