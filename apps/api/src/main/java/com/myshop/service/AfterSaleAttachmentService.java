package com.myshop.service;

import com.myshop.entity.AfterSaleAttachment;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The single validation and persistence boundary for private after-sale
 * attachments. Both the initial application and conversation replies use it.
 */
public interface AfterSaleAttachmentService {

    int MAX_ATTACHMENTS = 5;
    long MAX_ATTACHMENT_SIZE = 5L * 1024 * 1024;

    List<ValidatedAttachment> validate(List<MultipartFile> files);

    StoredAttachments store(UUID requestId, UUID messageId,
                            List<ValidatedAttachment> files);

    void cleanup(Collection<String> storageKeys);

    record ValidatedAttachment(MultipartFile file, String extension,
                               String contentType, String originalFilename) {
    }

    record StoredAttachments(List<AfterSaleAttachment> attachments,
                             List<String> storageKeys) {
    }
}
