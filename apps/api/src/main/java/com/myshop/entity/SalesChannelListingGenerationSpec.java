package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Provider SKU/spec mapping scoped to one immutable item generation. */
@Entity
@Table(name = "sales_channel_listing_generation_specs")
@Getter
@Setter
@NoArgsConstructor
public class SalesChannelListingGenerationSpec extends BaseEntity {

    @Column(name = "sales_channel_listing_generation_id", nullable = false)
    private UUID salesChannelListingGenerationId;

    @Column(name = "product_spec_id", nullable = false)
    private UUID productSpecId;

    @Column(name = "external_sku_id", length = 128)
    private String externalSkuId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_mapping", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> providerMapping = new LinkedHashMap<>();

    public void setProviderMapping(Map<String, Object> providerMapping) {
        this.providerMapping = providerMapping == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(providerMapping);
    }

}
