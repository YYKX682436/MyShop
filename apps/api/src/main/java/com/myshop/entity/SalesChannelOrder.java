package com.myshop.entity;

import com.myshop.constant.SalesChannelOrderDeliveryStatus;
import com.myshop.constant.SalesChannelOrderIngestStatus;
import com.myshop.constant.SalesChannelProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sales_channel_orders")
@Getter
@Setter
@NoArgsConstructor
public class SalesChannelOrder extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SalesChannelProvider provider;

    @Column(name = "sales_channel_account_id", nullable = false)
    private UUID salesChannelAccountId;

    @Column(name = "sales_channel_listing_id")
    private UUID salesChannelListingId;

    @Column(name = "order_id", unique = true)
    private UUID orderId;

    @Column(name = "external_order_no", nullable = false, length = 128)
    private String externalOrderNo;

    @Column(name = "external_item_id", nullable = false, length = 128)
    private String externalItemId;

    @Column(name = "external_sku_id", length = 128)
    private String externalSkuId;

    @Column(name = "external_spec_name", length = 128)
    private String externalSpecName;

    @Column(name = "external_spec_value", length = 128)
    private String externalSpecValue;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "external_status", nullable = false, length = 32)
    private String externalStatus;

    @Column(name = "source_updated_at", nullable = false)
    private LocalDateTime sourceUpdatedAt;

    @Column(name = "source_fingerprint", nullable = false, length = 64)
    private String sourceFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "ingest_status", nullable = false, length = 32)
    private SalesChannelOrderIngestStatus ingestStatus = SalesChannelOrderIngestStatus.DISCOVERED;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 32)
    private SalesChannelOrderDeliveryStatus deliveryStatus = SalesChannelOrderDeliveryStatus.NOT_READY;

    @Column(name = "last_error_code", length = 128)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "auto_fulfillment_requested", nullable = false)
    private boolean autoFulfillmentRequested;
}
