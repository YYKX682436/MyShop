package com.myshop.entity;

import com.myshop.constant.CustomerInputType;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.StockMode;
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

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "delivery_type", nullable = false, length = 10)
    private String deliveryType = "AUTO";

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_input_type", nullable = false, length = 32)
    private CustomerInputType customerInputType = CustomerInputType.NONE;

    @Column(name = "customer_input_value", length = 255)
    private String customerInputValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "stock_mode", nullable = false, length = 32)
    private StockMode stockMode = StockMode.CARD_KEY;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false, length = 32)
    private FulfillmentStatus fulfillmentStatus = FulfillmentStatus.PENDING;

    @Column(name = "delivered_at")
    private java.time.LocalDateTime deliveredAt;

    @Column(name = "manual_stock_reserved", nullable = false)
    private int manualStockReserved = 0;
}
