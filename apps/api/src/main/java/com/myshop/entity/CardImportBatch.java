package com.myshop.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "card_import_batches")
public class CardImportBatch extends BaseEntity {

    @Column(nullable = false)
    private UUID productId;

    private UUID specId;

    @Column(nullable = false)
    private UUID importedBy;

    private int totalCount;

    private int successCount;

    private int duplicateCount;

    private int failCount;

    private int columnCount = 1;

    @Column(nullable = false)
    private boolean checkDuplicate = true;

    @Column(columnDefinition = "TEXT")
    private String failDetail;
}
