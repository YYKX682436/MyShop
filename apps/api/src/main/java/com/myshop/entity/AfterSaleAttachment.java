package com.myshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** Metadata for one private, authenticated after-sale image. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "after_sale_attachments")
public class AfterSaleAttachment extends AssignedUuidEntity {

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;
}
