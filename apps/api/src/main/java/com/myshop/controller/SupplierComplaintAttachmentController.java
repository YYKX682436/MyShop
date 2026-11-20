package com.myshop.controller;

import com.myshop.supplier.SupplierComplaintNotifications;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/supplier-complaint-attachments") @RequiredArgsConstructor
public class SupplierComplaintAttachmentController {
    private final SupplierComplaintNotifications notifications;
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var file=notifications.download(id);
        String extension=switch (file.contentType()) {
            case "image/png" -> "png"; case "image/jpeg" -> "jpg"; case "image/gif" -> "gif";
            case "image/webp" -> "webp"; case "image/svg+xml" -> "svg"; default -> "bin";
        };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"supplier-evidence."+extension+"\"")
                .header("X-Content-Type-Options","nosniff").body(file.bytes());
    }
}
