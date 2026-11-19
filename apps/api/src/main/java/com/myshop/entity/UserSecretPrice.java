package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "user_secret_prices")
public class UserSecretPrice extends BaseEntity {
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false) private UUID productId;
    @Column private UUID specId;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal price;
}
