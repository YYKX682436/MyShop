package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.context.RequestContext;
import com.myshop.service.AfterSaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Registered-user after-sale submission endpoint. */
@RestController
@RequestMapping("/user/orders")
@RequiredArgsConstructor
public class AfterSaleController {

    private final AfterSaleService afterSaleService;

    @PostMapping("/{orderId}/after-sales")
    public ApiResponse<?> apply(@PathVariable UUID orderId,
                                @RequestParam(value = "reason", required = false) String reason,
                                @RequestParam(value = "attachments", required = false) MultipartFile[] attachments) {
        List<MultipartFile> files = attachments == null ? List.of() : Arrays.asList(attachments);
        return ApiResponse.success(afterSaleService.apply(
                orderId, RequestContext.getUserId(), reason, files));
    }

    @PostMapping("/{orderId}/after-sale/messages")
    public ApiResponse<?> reply(@PathVariable UUID orderId,
                                @RequestParam(value = "content", required = false) String content,
                                @RequestParam(value = "attachments", required = false) MultipartFile[] attachments) {
        List<MultipartFile> files = attachments == null ? List.of() : Arrays.asList(attachments);
        return ApiResponse.success(afterSaleService.replyFromCustomer(
                orderId, RequestContext.getUserId(), content, files));
    }
}
