package com.myshop.entity;

import com.myshop.constant.PricingType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_levels")
public class UserLevel extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_type", nullable = false, length = 32)
    private PricingType pricingType = PricingType.RETAIL;

    /** Percentage points: 10.0000 means a 10% markup. */
    @Column(name = "markup_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal markupRate = BigDecimal.ZERO.setScale(4);

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;
}
