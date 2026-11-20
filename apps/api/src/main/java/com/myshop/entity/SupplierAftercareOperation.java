package com.myshop.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name="supplier_aftercare_operations") @Getter @Setter
public class SupplierAftercareOperation extends BaseEntity {
    @Column(nullable=false) private UUID accountId;
    @Column(nullable=false) private UUID purchaseId;
    @Column(nullable=false,length=16) private String kind;
    @Column(nullable=false) private UUID idempotencyKey;
    private UUID cardId;
    private UUID afterSaleId;
    @Column(nullable=false) private UUID operatorId;
    @Column(nullable=false,length=16) private String status;
    @Column(length=200) private String topic;
    @Column(columnDefinition="text") private String content;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private JsonNode attachmentIds;
    private Long supplierReqId;
    private Integer upstreamStatus;
    @Column(columnDefinition="text") private String result;
    @Column(length=64) private String errorCode;
    private LocalDateTime submittedAt;
    private LocalDateTime lastCheckedAt;
}
