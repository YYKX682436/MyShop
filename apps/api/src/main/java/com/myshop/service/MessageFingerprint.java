package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Hashes the complete normalized request, including attachment bytes, for safe retries. */
public final class MessageFingerprint {
    private MessageFingerprint() {}

    public static byte[] calculate(String content, List<AfterSaleAttachmentService.ValidatedAttachment> files) {
        return calculate(content, files, null);
    }

    /** Context-bound retries must not be replayed against another order, sale round, or notice. */
    public static byte[] calculate(String content, List<AfterSaleAttachmentService.ValidatedAttachment> files,
                                   String context) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                writeText(out, content);
                if (context != null) writeText(out, context);
                out.writeInt(files.size());
                for (var file : files) {
                    writeText(out, file.originalFilename());
                    writeText(out, file.contentType());
                    out.writeLong(file.file().getSize());
                    try (var input = file.file().getInputStream()) { input.transferTo(out); }
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无法读取消息附件");
        }
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }
}
