package com.myshop.service;

import org.springframework.core.io.Resource;

import java.util.UUID;

/** Database-authorized access to one private after-sale attachment. */
public interface AfterSaleAttachmentAccessService {

    AttachmentDownload open(UUID attachmentId);

    record AttachmentDownload(Resource resource, String contentType, long size, String filename) {
    }
}
