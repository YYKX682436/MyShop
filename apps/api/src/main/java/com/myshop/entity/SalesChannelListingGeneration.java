package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Column(name = "origin", nullable = false, length = 32)
    private String origin;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    public SalesChannelListingGeneration(UUID listingId, UUID accountId, long generationNo,
                                         String externalItemId, boolean current, String origin) {
        this.salesChannelListingId = listingId;
        this.salesChannelAccountId = accountId;
        this.generationNo = generationNo;
        this.externalItemId = externalItemId;
        this.current = current;
        this.origin = origin;
    }
}
