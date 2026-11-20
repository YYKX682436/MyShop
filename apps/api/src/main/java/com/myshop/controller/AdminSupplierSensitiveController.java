package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.supplier.SupplierSensitiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/admin/suppliers/{accountId}") @RequiredArgsConstructor
public class AdminSupplierSensitiveController {
    private final SupplierSensitiveService service;
    @GetMapping("/sensitive-capabilities") public ApiResponse<?> capabilities() {
        return ApiResponse.success(service.capabilities());
    }
    @GetMapping("/transfers") public ApiResponse<?> transfers(@PathVariable UUID accountId) {
        return ApiResponse.success(service.list(accountId,"TRANSFER"));
    }
    @PostMapping("/transfers") public ApiResponse<?> transfer(@PathVariable UUID accountId,@RequestBody SupplierSensitiveService.TransferInput input) {
        return ApiResponse.success(service.transfer(accountId,input));
    }
    @PostMapping("/transfers/{id}/review") public ApiResponse<?> reviewTransfer(@PathVariable UUID accountId,@PathVariable UUID id,@RequestBody Review input) {
        return ApiResponse.success(service.review(accountId,id,"TRANSFER",input.outcome()));
    }
    @PostMapping("/transfers/{id}/cancel") public ApiResponse<?> cancelTransfer(@PathVariable UUID accountId,@PathVariable UUID id) {
        return ApiResponse.success(service.cancel(accountId,id,"TRANSFER"));
    }
    @GetMapping("/cdkeys") public ApiResponse<?> cdkeys(@PathVariable UUID accountId) {
        return ApiResponse.success(service.list(accountId,"CDKEY"));
    }
    @PostMapping("/cdkeys") public ApiResponse<?> cdkeys(@PathVariable UUID accountId,@RequestBody SupplierSensitiveService.CdkeyInput input) {
        return ApiResponse.success(service.createCdkeys(accountId,input));
    }
    @GetMapping("/cdkeys/{id}/result") public ResponseEntity<ApiResponse<?>> result(@PathVariable UUID accountId,@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.result(accountId,id)));
    }
    @PostMapping("/cdkeys/{id}/review") public ApiResponse<?> reviewCdkey(@PathVariable UUID accountId,@PathVariable UUID id,@RequestBody Review input) {
        return ApiResponse.success(service.review(accountId,id,"CDKEY",input.outcome()));
    }
    @PostMapping("/cdkeys/{id}/cancel") public ApiResponse<?> cancelCdkey(@PathVariable UUID accountId,@PathVariable UUID id) {
        return ApiResponse.success(service.cancel(accountId,id,"CDKEY"));
    }
    public record Review(String outcome) {}
}
