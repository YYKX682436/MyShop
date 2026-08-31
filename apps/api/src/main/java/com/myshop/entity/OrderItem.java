package com.myshop.entity;

import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.PricingSource;
import com.myshop.constant.StockMode;
import com.myshop.model.CustomerInputValue;
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
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private String productTitle;

    private UUID specId;

    private String specName;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "cost_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "retail_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal retailPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_source", nullable = false, length = 32)
    private PricingSource pricingSource = PricingSource.RETAIL;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "delivery_type", nullable = false, length = 10)
    private String deliveryType = "AUTO";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "customer_inputs", nullable = false, columnDefinition = "jsonb")
    private List<CustomerInputValue> customerInputs = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_mode", nullable = false, length = 32)
    private StockMode stockMode = StockMode.CARD_KEY;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false, length = 32)
    private FulfillmentStatus fulfillmentStatus = FulfillmentStatus.PENDING;

    @Column(name = "delivered_at")
    private java.time.LocalDateTime deliveredAt;

    @Column(name = "delivery_notified_at")
    private java.time.LocalDateTime deliveryNotifiedAt;

    @Column(name = "manual_stock_reserved", nullable = false)
    private int manualStockReserved = 0;
}
