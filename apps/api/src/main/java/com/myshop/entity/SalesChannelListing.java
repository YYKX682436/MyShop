package com.myshop.entity;

import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "sales_channel_listings")
@Getter
@NoArgsConstructor
public class SalesChannelListing extends BaseEntity {

    @Column(name = "sales_channel_account_id", nullable = false)
    @Setter
    private UUID salesChannelAccountId;

    @Column(name = "product_id", nullable = false)
    @Setter
    private UUID productId;

    @Column(name = "external_item_id", length = 128)
    private String externalItemId;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "desired_state", nullable = false, length = 16)
    @Setter
    private SalesChannelListingDesiredState desiredState = SalesChannelListingDesiredState.OFFLINE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Setter
    private SalesChannelListingStatus status = SalesChannelListingStatus.DRAFT;

    @Column(name = "desired_revision", nullable = false)
    private long desiredRevision;

    @Column(name = "applied_revision", nullable = false)
    private long appliedRevision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> providerConfig = new LinkedHashMap<>();

    @Column(name = "applied_fingerprint", length = 64)
    @Setter
    private String appliedFingerprint;

    @Column(name = "desired_fingerprint", length = 64)
    private String desiredFingerprint;

    @Column(name = "last_synced_at")
    @Setter
    private LocalDateTime lastSyncedAt;

    @Column(name = "last_error_code", length = 128)
    @Setter
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    @Setter
    private String lastErrorMessage;

    public Map<String, Object> getProviderConfig() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(providerConfig));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDesiredFingerprint() {
        return desiredFingerprint;
    }

    public void setDesiredFingerprint(String desiredFingerprint) {
        this.desiredFingerprint = desiredFingerprint;
    }

    public void setProviderConfig(Map<String, Object> providerConfig) {
        this.providerConfig = providerConfig == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(providerConfig);
    }

    public void incrementDesiredRevision() {
        desiredRevision = Math.addExact(desiredRevision, 1);
    }

    public void advanceAppliedRevision(long revision) {
        if (revision > desiredRevision) {
            throw new IllegalArgumentException("Applied revision cannot exceed desired revision");
        }
        if (revision > appliedRevision) {
            appliedRevision = revision;
        }
    }

    /** Binds the first provider identity and prevents silent replacement by another remote item. */
    public ExternalItemBindingResult bindExternalItemId(String itemId) {
        Objects.requireNonNull(itemId, "External item id is required");
        if (itemId.isBlank()) throw new IllegalArgumentException("External item id must not be blank");
        if (externalItemId == null) {
            externalItemId = itemId;
            return ExternalItemBindingResult.BOUND;
        }
        if (externalItemId.equals(itemId)) return ExternalItemBindingResult.ALREADY_BOUND;

        status = SalesChannelListingStatus.UNKNOWN;
        lastErrorCode = "EXTERNAL_ITEM_ID_CONFLICT";
        lastErrorMessage = "Provider returned a different item id than the listing's bound external item";
        return ExternalItemBindingResult.CONFLICT;
    }

    public enum ExternalItemBindingResult {
        BOUND,
        ALREADY_BOUND,
        CONFLICT
    }
}
