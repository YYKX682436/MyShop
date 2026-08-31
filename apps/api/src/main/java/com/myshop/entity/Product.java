package com.myshop.entity;

import com.myshop.constant.StockMode;
import com.myshop.model.CustomerInputField;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String detailMd;

    private String coverUrl;

    @Column(name = "retail_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal retailPrice;

    @Column(name = "cost_price", precision = 10, scale = 2)
    private BigDecimal costPrice;

    @Column(nullable = false)
    private UUID categoryId;

    @Column(nullable = false, length = 10)
    private String deliveryType = "AUTO";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "customer_input_fields", nullable = false, columnDefinition = "jsonb")
    private List<CustomerInputField> customerInputFields = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_mode", nullable = false, length = 32)
    private StockMode stockMode = StockMode.CARD_KEY;

    private int lowStockThreshold = 10;

    @Column(name = "spec_enabled", columnDefinition = "boolean not null default false")
    private boolean specEnabled = false;

    @Column(name = "is_enabled")
    private boolean enabled = true;

    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private int initialSales = 0;

    private int sortOrder = 0;

    private int isDeleted = 0;
}
