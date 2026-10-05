package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable evidence captured by one successful read-only cutover certification. */
@Entity
@Immutable
@Table(name = "sales_channel_cutover_certifications")
@Getter
@NoArgsConstructor
public class SalesChannelCutoverCertification {

    @Id
    private UUID id;

    @Column(name = "sales_channel_listing_id", nullable = false)
    private UUID salesChannelListingId;

    @Column(name = "desired_revision", nullable = false)
    private long desiredRevision;

    @Column(name = "desired_state", nullable = false, length = 16)
    private String desiredState;

    @Column(name = "desired_fingerprint", nullable = false, length = 64)
    private String desiredFingerprint;

    @Column(name = "current_external_item_id", length = 128)
    private String currentExternalItemId;

    @Column(name = "agent_state_digest", nullable = false, length = 64)
    private String agentStateDigest;

    @Column(name = "requires_adoption", nullable = false)
    private boolean requiresAdoption;

    @Column(name = "bootstrap_operation_id")
    private UUID bootstrapOperationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> evidence = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public SalesChannelCutoverCertification(UUID id, UUID listingId, long desiredRevision,
                                             String desiredState, String desiredFingerprint,
                                             String currentExternalItemId, String agentStateDigest,
                                             boolean requiresAdoption, UUID bootstrapOperationId,
                                             Map<String, Object> evidence, LocalDateTime createdAt) {
        this.id = id;
        this.salesChannelListingId = listingId;
        this.desiredRevision = desiredRevision;
        this.desiredState = desiredState;
        this.desiredFingerprint = desiredFingerprint;
        this.currentExternalItemId = currentExternalItemId;
        this.agentStateDigest = agentStateDigest;
        this.requiresAdoption = requiresAdoption;
        this.bootstrapOperationId = bootstrapOperationId;
        this.evidence = evidence == null ? new LinkedHashMap<>() : new LinkedHashMap<>(evidence);
        this.createdAt = createdAt;
    }

    public Map<String, Object> getEvidence() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }
}
