package com.myshop.entity;

import com.myshop.constant.SalesChannelProvider;
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

@Entity
@Table(name = "sales_channel_order_cursors")
@Getter
@Setter
@NoArgsConstructor
public class SalesChannelOrderCursor extends BaseEntity {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SalesChannelProvider provider;

    @Column(name = "sales_channel_account_id", nullable = false)
    private UUID salesChannelAccountId;

    @Column(name = "cursor_updated_at")
    private LocalDateTime cursorUpdatedAt;

    @Column(name = "cursor_id")
    private Long cursorId;

    @Column(name = "initialized_at", nullable = false)
    private LocalDateTime initializedAt;
}
