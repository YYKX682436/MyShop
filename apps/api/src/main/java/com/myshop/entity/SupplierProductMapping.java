package com.myshop.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "supplier_product_mappings")
@Getter @Setter @NoArgsConstructor
public class SupplierProductMapping extends BaseEntity {
    @Column(nullable = false) private UUID accountId;
    @Column(nullable = false, length = 120) private String remoteGoodsId;
    @Column(nullable = false, length = 255) private String remoteSku = "";
    @Column(nullable = false) private UUID productId;
    private UUID specId;
    @Column(nullable = false) private int goodsType;
    @Column(nullable = false) private int skuType;
    @Column(nullable = false, precision = 24, scale = 8) private BigDecimal cost;
    @Column(nullable = false) private long stock;
    @Column(nullable = false) private int goodsStatus;
    @Column(nullable = false) private int skuStatus;
    @Column(nullable = false) private int multiple;
    @Column(nullable = false) private int minQuantity;
    @Column(nullable = false) private int maxQuantity;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private JsonNode snapshot;
    @Column(nullable = false) private LocalDateTime lastSyncedAt;
    @Column(nullable = false) private LocalDateTime lastAttemptAt;
    @Column(length = 200) private String syncError;
}
