package com.myshop.repository;

import com.myshop.entity.SalesChannelCutoverCertification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SalesChannelCutoverCertificationRepository
        extends JpaRepository<SalesChannelCutoverCertification, UUID> {

    List<SalesChannelCutoverCertification> findBySalesChannelListingIdOrderByCreatedAtAsc(UUID listingId);
}
