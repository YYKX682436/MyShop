package com.myshop.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name="supplier_purchases") @Getter @Setter @NoArgsConstructor
public class SupplierPurchase extends BaseEntity {
    @Column(nullable=false) private UUID orderId;
    @Column(nullable=false) private UUID orderItemId;
    @Column(nullable=false) private UUID accountId;
    @Column(nullable=false,length=120) private String remoteGoodsId;
    @Column(nullable=false,length=255) private String remoteSku;
    @Column(nullable=false) private int goodsType;
    @Column(nullable=false,length=100) private String outerNumber;
    @Column(length=120) private String upstreamOrderNumber;
    @Column(nullable=false) private int buyerQuantity;
    @Column(nullable=false) private int multiple;
    @Column(nullable=false) private int actualQuantity;
    @Column(nullable=false,precision=24,scale=8) private BigDecimal unitCost;
    @Column(nullable=false,precision=24,scale=8) private BigDecimal safePrice;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private JsonNode attach;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") private JsonNode rechargeTemplate;
    @Column(nullable=false,length=32) private String status;
    private Integer upstreamStatus;
    @Column(precision=24,scale=8) private BigDecimal actualCost;
    @Column(precision=24,scale=8) private BigDecimal refundedAmount;
    @Column(columnDefinition="text") private String result;
    @Column(columnDefinition="text") private String startCount;
    @Column(columnDefinition="text") private String nowCount;
    @Column(columnDefinition="text") private String endCount;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime submitStartedAt;
    @Column(length=64) private String errorCode;
}
