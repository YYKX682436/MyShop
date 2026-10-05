package com.myshop.entity;

import com.myshop.constant.SalesChannelGenerationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A provider item generation belonging to a logical sales-channel listing.
 *
 * <p>A listing is the durable MyShop identity.  An external item is only one
 * generation of that identity and may be retired when the provider creates a
 * replacement in a later phase.</p>
 */
@Entity
@Table(name = "sales_channel_listing_generations")
@Getter
@Setter
@NoArgsConstructor
public class SalesChannelListingGeneration extends BaseEntity {

    @Column(name = "sales_channel_listing_id", nullable = false)
    private UUID salesChannelListingId;

    @Column(name = "sales_channel_account_id", nullable = false)
    private UUID salesChannelAccountId;

    @Column(name = "generation_no", nullable = false)
    private long generationNo;

    @Column(name = "external_item_id", nullable = false, length = 128)
    private String externalItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_state", nullable = false, length = 16)
    private SalesChannelGenerationState generationState;

    @Column(name = "replaces_generation_id")
    private UUID replacesGenerationId;

    @Column(name = "origin", nullable = false, length = 32)
    private String origin;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    public void makeCurrent(LocalDateTime at) {
        this.generationState = SalesChannelGenerationState.CURRENT;
        this.activatedAt = at;
        this.retiredAt = null;
    }

    public void retire(LocalDateTime at) {
        this.generationState = SalesChannelGenerationState.RETIRED;
        this.retiredAt = at;
    }

    public SalesChannelListingGeneration(UUID listingId, UUID accountId, long generationNo,
                                         String externalItemId, SalesChannelGenerationState state, String origin) {
        this.salesChannelListingId = listingId;
        this.salesChannelAccountId = accountId;
        this.generationNo = generationNo;
        this.externalItemId = externalItemId;
        this.generationState = state;
        this.origin = origin;
    }

}
