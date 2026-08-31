package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "product_level_price_overrides")
public class ProductLevelPriceOverride extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** Null means the override applies to the product without a spec. */
    @Column(name = "spec_id")
    private UUID specId;

    @Column(name = "user_level_id", nullable = false)
    private UUID userLevelId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
}
