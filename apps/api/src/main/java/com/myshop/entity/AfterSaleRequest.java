package com.myshop.entity;

import com.myshop.constant.AfterSaleRequestStatus;
import com.myshop.constant.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** A historical customer after-sale request linked to one order snapshot. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "after_sale_requests")
public class AfterSaleRequest extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AfterSaleRequestStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_order_status", nullable = false, length = 16)
    private OrderStatus sourceOrderStatus;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "admin_reply", length = 500)
    private String adminReply;

    @Column(name = "handled_by")
    private UUID handledBy;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;
}
