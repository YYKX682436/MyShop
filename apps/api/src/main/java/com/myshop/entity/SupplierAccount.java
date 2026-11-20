package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "supplier_accounts")
@Getter @Setter @NoArgsConstructor
public class SupplierAccount extends BaseEntity {
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "provider_type", nullable = false, length = 32) private String providerType;
    @Column(name = "base_url", nullable = false, length = 500) private String baseUrl;
    @Column(name = "app_id", nullable = false, length = 120) private String appId;
    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "text") private String encryptedSecret;
    @Column(nullable = false) private boolean enabled;
}
