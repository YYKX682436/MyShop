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
import java.util.Objects;

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

    @Column(name = "product_no", nullable = false, updatable = false)
    private Long productNo;

    @Column(nullable = false)
    private String productTitle;

    /** Product presentation captured at checkout; this is not a live product relation. */
    @Column(name = "product_cover_url", columnDefinition = "TEXT", updatable = false)
    private String productCoverUrl;

    private UUID specId;

    private String specName;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "cost_price_snapshot", nullable = false, precision = 10, scale = 2)
    private BigDecimal costPriceSnapshot;

    @Column(name = "retail_price_snapshot", nullable = false, precision = 10, scale = 2)
    private BigDecimal retailPriceSnapshot;

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

    /**
     * Optional customer-facing content for manual fulfillment. This is kept
     * separate from admin notes and is exposed publicly only after the item
     * reaches DELIVERED.
     */
    @Column(name = "customer_delivery_content", columnDefinition = "TEXT")
    private String customerDeliveryContent;

    @Column(name = "manual_stock_reserved", nullable = false)
    private int manualStockReserved = 0;

    public BigDecimal getCostPriceSnapshot() { return costPriceSnapshot; }

    public void setCostPriceSnapshot(BigDecimal value) { this.costPriceSnapshot = value; }

    public BigDecimal getRetailPriceSnapshot() { return retailPriceSnapshot; }

    public void setRetailPriceSnapshot(BigDecimal value) { this.retailPriceSnapshot = value; }

    /** The order snapshot may be initialized once but cannot be changed later. */
    public void setProductNo(Long value) {
        if (productNo != null && !Objects.equals(productNo, value)) {
            throw new IllegalStateException("order item productNo is immutable");
        }
        productNo = value;
    }

    /** The order cover snapshot may be initialized once but cannot be changed later. */
    public void setProductCoverUrl(String value) {
        if (productCoverUrl != null && !Objects.equals(productCoverUrl, value)) {
            throw new IllegalStateException("order item productCoverUrl is immutable");
        }
        productCoverUrl = value;
    }
}
