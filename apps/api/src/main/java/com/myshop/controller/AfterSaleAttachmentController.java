package com.myshop.controller;

import com.myshop.service.AfterSaleAttachmentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Protected attachment stream; there is intentionally no static file route. */
@RestController
@RequestMapping("/after-sale-attachments")
@RequiredArgsConstructor
public class AfterSaleAttachmentController {

    private final AfterSaleAttachmentAccessService accessService;

    @GetMapping("/{attachmentId}")
    public ResponseEntity<org.springframework.core.io.Resource> get(@PathVariable UUID attachmentId) {
        AfterSaleAttachmentAccessService.AttachmentDownload download = accessService.open(attachmentId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.contentType());
        } catch (IllegalArgumentException exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        String filename = download.filename() == null || download.filename().isBlank()
                ? "attachment" : download.filename().replaceAll("[\\r\\n\"]", "_");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(download.size())
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(download.resource());
    }
}
