package com.myshop.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

@Entity @Table(name="supplier_callback_events") @Getter @Setter @NoArgsConstructor
public class SupplierCallbackEvent extends BaseEntity {
    @Column(nullable=false) private UUID accountId;
    @Column(nullable=false) private UUID purchaseId;
    @Column(nullable=false,length=64) private String eventHash;
    @Column(nullable=false) private int upstreamStatus;
    @Column(columnDefinition="text") private String encryptedCards;
    @Column(nullable=false) private boolean processed;
}
