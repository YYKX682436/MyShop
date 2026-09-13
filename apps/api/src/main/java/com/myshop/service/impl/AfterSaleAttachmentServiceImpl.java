package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.entity.AfterSaleAttachment;
import com.myshop.exception.BusinessException;
import com.myshop.repository.AfterSaleAttachmentRepository;
import com.myshop.service.AfterSaleAttachmentService;
import com.myshop.service.PrivateUploadStorage;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AfterSaleAttachmentServiceImpl implements AfterSaleAttachmentService {

    private final AfterSaleAttachmentRepository attachmentRepository;
    private final PrivateUploadStorage privateUploadStorage;

    public AfterSaleAttachmentServiceImpl(AfterSaleAttachmentRepository attachmentRepository,
                                          PrivateUploadStorage privateUploadStorage) {
        this.attachmentRepository = attachmentRepository;
        this.privateUploadStorage = privateUploadStorage;
    }

    @Override
    public List<ValidatedAttachment> validate(List<MultipartFile> files) {
        if (files == null) {
            return List.of();
        }
        List<ValidatedAttachment> validated = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            if (file == null) {
                continue;
            }
            if (validated.size() >= MAX_ATTACHMENTS) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "最多上传 5 张售后凭证");
            }
            if (file.isEmpty() || file.getSize() <= 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "售后凭证不能为空");
            }
            if (file.getSize() > MAX_ATTACHMENT_SIZE) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "每张售后凭证不能超过 5MB");
            }
            String extension = extensionOf(file.getOriginalFilename());
            String contentType = normalizedContentType(file);
            String expectedType = switch (extension) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "png" -> "image/png";
                case "webp" -> "image/webp";
                default -> null;
            };
            if (expectedType == null || !expectedType.equals(contentType)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "售后凭证仅支持 JPG、PNG、WebP，且扩展名与 MIME 必须匹配");
            }
            validateMagic(file, extension);
            validated.add(new ValidatedAttachment(file, extension, contentType,
                    safeOriginalFilename(file.getOriginalFilename(), extension)));
        }
        return List.copyOf(validated);
    }

    @Override
    public StoredAttachments store(UUID requestId, UUID messageId,
                                   List<ValidatedAttachment> files) {
        if (requestId == null) {
            throw new IllegalArgumentException("after-sale request id is required");
        }
        if (files == null || files.isEmpty()) {
            return new StoredAttachments(List.of(), List.of());
        }
        List<AfterSaleAttachment> saved = new ArrayList<>(files.size());
        List<String> storageKeys = new ArrayList<>(files.size());
        try {
            for (ValidatedAttachment validated : files) {
                String storageKey = "after-sales/" + requestId + "/"
                        + UUID.randomUUID() + "." + validated.extension();
                privateUploadStorage.write(storageKey, validated.file());
                storageKeys.add(storageKey);

                AfterSaleAttachment attachment = new AfterSaleAttachment();
                attachment.setId(UUID.randomUUID());
                attachment.setRequestId(requestId);
                attachment.setMessageId(messageId);
                attachment.setStorageKey(storageKey);
                attachment.setOriginalFilename(validated.originalFilename());
                attachment.setContentType(validated.contentType());
                attachment.setFileSize(validated.file().getSize());
                saved.add(attachment);
            }
            attachmentRepository.saveAllAndFlush(saved);
            return new StoredAttachments(List.copyOf(saved), List.copyOf(storageKeys));
        } catch (RuntimeException exception) {
            cleanup(storageKeys);
            throw exception;
        }
    }

    @Override
    public void cleanup(Collection<String> storageKeys) {
        if (storageKeys == null) {
            return;
        }
        for (String storageKey : storageKeys) {
            privateUploadStorage.deleteManagedFile(storageKey);
        }
    }

    private void validateMagic(MultipartFile file, String extension) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(12);
            if (!magicMatches(extension, header)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "售后凭证文件内容与声明格式不一致");
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售后凭证读取失败");
        }
    }

    private boolean magicMatches(String extension, byte[] header) {
        return switch (extension) {
            case "jpg", "jpeg" -> header.length >= 3
                    && (header[0] & 0xff) == 0xff
                    && (header[1] & 0xff) == 0xd8
                    && (header[2] & 0xff) == 0xff;
            case "png" -> header.length >= 8
                    && startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47,
                    0x0d, 0x0a, 0x1a, 0x0a});
            case "webp" -> header.length >= 12
                    && startsWith(header, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && startsWith(header, 8, "WEBP".getBytes(StandardCharsets.US_ASCII));
            default -> false;
        };
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        return startsWith(value, 0, prefix);
    }

    private boolean startsWith(byte[] value, int offset, byte[] prefix) {
        if (value.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private String normalizedContentType(MultipartFile file) {
        return file.getContentType() == null
                ? ""
                : file.getContentType().trim().toLowerCase(Locale.ROOT);
    }

    private String extensionOf(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = name.lastIndexOf('.');
        if (dot < 1 || dot == name.length() - 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "售后凭证必须带有 JPG、PNG 或 WebP 扩展名");
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String safeOriginalFilename(String originalFilename, String extension) {
        String name = originalFilename == null ? "凭证." + extension
                : originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.strip().replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank()) {
            name = "凭证." + extension;
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
