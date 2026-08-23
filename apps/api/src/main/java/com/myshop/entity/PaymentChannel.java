package com.myshop.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment_channels")
public class PaymentChannel extends BaseEntity {

    @Column(nullable = false)
    private String channelCode;

    @Column(nullable = false)
    private String channelName;

    /** Registered provider id. Phase 1 supports epay only. */
    @Column(nullable = false, columnDefinition = "varchar(255) not null default 'epay'")
    private String providerType = "epay";

    /** Payment type exposed in the first-party UI, e.g. alipay or wechat. */
    @Column(nullable = false, length = 32)
    private String paymentType = "alipay";

    @Column(columnDefinition = "TEXT")
    private String configData;

    @Column(name = "is_enabled")
    private boolean enabled = true;

    private int sortOrder = 0;

    private int isDeleted = 0;
}
