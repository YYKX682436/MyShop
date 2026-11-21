package com.myshop.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.UUID;

@Getter @Setter @Entity @Table(name = "subsite_settings")
public class SubsiteSettings {
    @Id private Integer id = 1;
    @Column(name = "self_service_enabled", nullable = false) private boolean selfServiceEnabled;
    @Column(name = "opening_price", nullable = false, precision = 12, scale = 2) private BigDecimal openingPrice = BigDecimal.ZERO;
    @Column(name = "supply_level_id") private UUID supplyLevelId;
}
