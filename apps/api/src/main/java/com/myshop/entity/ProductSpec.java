package com.myshop.entity;

import jakarta.persistence.*;
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
@Table(name = "product_specs")
public class ProductSpec extends AssignedUuidEntity {

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private String name;

    @Column(name = "retail_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal retailPrice;

    @Column(name = "cost_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "is_visible")
    private boolean visible = true;

    private int sortOrder = 0;

    private int isDeleted = 0;
}
