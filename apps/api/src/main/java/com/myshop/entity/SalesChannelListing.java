package com.myshop.entity;

import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelConvergenceCutoverState;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "management_mode", nullable = false, length = 32)
    @Setter
    private SalesChannelListingManagementMode managementMode = SalesChannelListingManagementMode.MANAGED;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    @Column(name = "auto_fulfillment_enabled", nullable = false)
    private boolean autoFulfillmentEnabled;

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

    @Column(name = "convergence_adopted_external_item_id", length = 128)
    @Setter
    private String convergenceAdoptedExternalItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "convergence_cutover_state", nullable = false, length = 16)
    private SalesChannelConvergenceCutoverState convergenceCutoverState = SalesChannelConvergenceCutoverState.UNCERTIFIED;

    @Column(name = "convergence_certification_id")
    private UUID convergenceCertificationId;

    @Column(name = "convergence_activated_at")
    private LocalDateTime convergenceActivatedAt;

    @Column(name = "convergence_paused_at")
    private LocalDateTime convergencePausedAt;

    @Column(name = "convergence_adopted_at")
    @Setter
    private LocalDateTime convergenceAdoptedAt;

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

    public void setAutoFulfillmentEnabled(boolean enabled) {
        this.autoFulfillmentEnabled = enabled;
    }

    public String getDesiredFingerprint() {
        return desiredFingerprint;
    }

    public SalesChannelConvergenceCutoverState getConvergenceCutoverState() {
        return convergenceCutoverState;
    }

    public UUID getConvergenceCertificationId() {
        return convergenceCertificationId;
    }

    public LocalDateTime getConvergenceActivatedAt() {
        return convergenceActivatedAt;
    }

    public LocalDateTime getConvergencePausedAt() {
        return convergencePausedAt;
    }

    public void certifyConvergence(UUID certificationId) {
        this.convergenceCertificationId = certificationId;
        this.convergenceCutoverState = SalesChannelConvergenceCutoverState.CERTIFIED;
        this.convergenceActivatedAt = null;
        this.convergencePausedAt = null;
    }

    public void activateConvergence(LocalDateTime activatedAt) {
        this.convergenceCutoverState = SalesChannelConvergenceCutoverState.ACTIVE;
        this.convergenceActivatedAt = activatedAt;
        this.convergencePausedAt = null;
    }

    public void pauseConvergence(LocalDateTime pausedAt) {
        this.convergenceCutoverState = SalesChannelConvergenceCutoverState.PAUSED;
        this.convergencePausedAt = pausedAt;
    }

    public void invalidateConvergenceCertification() {
        this.convergenceCutoverState = SalesChannelConvergenceCutoverState.UNCERTIFIED;
        this.convergenceCertificationId = null;
        this.convergenceActivatedAt = null;
        this.convergencePausedAt = null;
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

}
