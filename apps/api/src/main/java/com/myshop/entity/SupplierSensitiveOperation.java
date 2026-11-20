package com.myshop.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name="supplier_sensitive_operations") @Getter @Setter
public class SupplierSensitiveOperation extends BaseEntity {
    @Column(nullable=false) private UUID accountId;
    @Column(nullable=false,length=120) private String accountNameSnapshot;
    @Column(nullable=false,length=32) private String providerTypeSnapshot;
    @Column(nullable=false,length=500) private String baseUrlSnapshot;
    @Column(nullable=false,length=120) private String appIdSnapshot;
    @Column(nullable=false,length=16) private String kind;
    @Column(nullable=false) private UUID idempotencyKey;
    @Column(nullable=false) private UUID operatorId;
    @Column(nullable=false,length=16) private String status;
    @Column(length=100) private String outerNumber;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private JsonNode parameters;
    @Column(columnDefinition="text") private String encryptedResult;
    @Column(length=64) private String errorCode;
    private LocalDateTime submittedAt;
    @Column(length=40) private String reviewOutcome;
    private UUID reviewedBy;
    private LocalDateTime reviewedAt;
}
