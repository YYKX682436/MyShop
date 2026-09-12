package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** Immutable audit fact for a deliberately reclaimed sold card key. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "card_key_reclaims", uniqueConstraints = @UniqueConstraint(
        name = "uq_card_key_reclaims_order_card_key",
        columnNames = {"order_id", "card_key_id"}))
public class CardKeyReclaim extends BaseEntity {

    @Column(name = "card_key_id", nullable = false)
    private UUID cardKeyId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "order_item_id")
    private UUID orderItemId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "spec_id")
    private UUID specId;

    @Column(name = "operator_id", nullable = false)
    private UUID operatorId;

    @Column(name = "original_sold_at")
    private LocalDateTime originalSoldAt;

    @Column(name = "reclaimed_at", nullable = false)
    private LocalDateTime reclaimedAt;
}
