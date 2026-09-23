package com.myshop.entity;

import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "sales_channel_accounts")
@Getter
@NoArgsConstructor
public class SalesChannelAccount extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Setter
    private SalesChannelProvider provider;

    @Column(name = "provider_account_id", nullable = false, length = 128)
    @Setter
    private String providerAccountId;

    @Column(name = "display_name", length = 255)
    @Setter
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Setter
    private SalesChannelAccountStatus status;

    @Column(name = "is_enabled", nullable = false)
    @Setter
    private boolean enabled = true;

    @Column(name = "order_sync_enabled", nullable = false)
    @Setter
    private boolean orderSyncEnabled = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> capabilities = new LinkedHashMap<>();

    @Column(name = "last_verified_at")
    @Setter
    private LocalDateTime lastVerifiedAt;

    @Column(name = "last_login_at")
    @Setter
    private LocalDateTime lastLoginAt;

    public void setCapabilities(Map<String, Object> capabilities) {
        this.capabilities = capabilities == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(capabilities);
    }
}
