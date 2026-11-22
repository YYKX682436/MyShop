package com.myshop.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Getter @Setter @Entity @Table(name = "subsites")
public class Subsite extends BaseEntity {
    @Column(name = "owner_id", nullable = false, unique = true) private UUID ownerId;
    @Column(nullable = false, unique = true, length = 63, updatable = false) private String prefix;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "favicon_url", length = 500) private String faviconUrl;
    @Column(columnDefinition = "text") private String announcement;
}
