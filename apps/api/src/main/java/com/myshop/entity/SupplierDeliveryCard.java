package com.myshop.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;
import java.time.LocalDateTime;

@Entity @Table(name="supplier_delivery_cards") @Getter @Setter @NoArgsConstructor
public class SupplierDeliveryCard extends BaseEntity {
    @Column(nullable=false) private UUID purchaseId;
    @Column(nullable=false) private UUID accountId;
    @Column(nullable=false,length=120) private String remoteCardId;
    @Column(columnDefinition="text") private String cardNo;
    @Column(columnDefinition="text") private String cardPwd;
    @Column(nullable=false) private int cardStatus;
    @Column(nullable=false) private int cancelRequest;
    private LocalDateTime lastCheckedAt;
    @Column(columnDefinition="text") private String supplierResult;
}
