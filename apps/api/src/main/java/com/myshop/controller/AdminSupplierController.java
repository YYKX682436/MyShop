package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.annotation.LogOperation;
import com.myshop.supplier.SupplierService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/admin/suppliers")
@RequiredArgsConstructor
public class AdminSupplierController {
    private final SupplierService suppliers;
    @GetMapping public ApiResponse<?> list() { return ApiResponse.success(suppliers.listAccounts()); }
    @LogOperation(action="supplier.create", targetType="SUPPLIER", detail="'添加货源'")
    @PostMapping public ApiResponse<?> create(@RequestBody SupplierService.AccountRequest request) { return ApiResponse.success(suppliers.saveAccount(null,request)); }
    @LogOperation(action="supplier.update", targetType="SUPPLIER", targetId="#id", detail="'编辑货源'")
    @PutMapping("/{id}") public ApiResponse<?> update(@PathVariable UUID id,@RequestBody SupplierService.AccountRequest request) { return ApiResponse.success(suppliers.saveAccount(id,request)); }
    @LogOperation(action="supplier.status", targetType="SUPPLIER", targetId="#id", detail="'修改货源状态'")
    @PostMapping("/{id}/status") public ApiResponse<?> status(@PathVariable UUID id,@RequestBody Status request) {
        if (request.enabled()==null) throw new com.myshop.supplier.SupplierException("CONFIG","请选择账号状态");
        suppliers.setEnabled(id,request.enabled()); return ApiResponse.success();
    }
    @PostMapping("/{id}/test") public ApiResponse<?> test(@PathVariable UUID id) { return ApiResponse.success(suppliers.test(id)); }
    @GetMapping("/{id}/categories") public ApiResponse<?> categories(@PathVariable UUID id) { return ApiResponse.success(suppliers.categories(id)); }
    @GetMapping("/{id}/goods") public ApiResponse<?> goods(@PathVariable UUID id,@RequestParam(required=false) String keyword,
            @RequestParam(value="category_id",required=false) String categoryId,@RequestParam(defaultValue="1") int page) {
        return ApiResponse.success(suppliers.list(id,keyword,categoryId,page));
    }
    @GetMapping("/{id}/goods/{goodsId}") public ApiResponse<?> detail(@PathVariable UUID id,@PathVariable String goodsId) { return ApiResponse.success(suppliers.detail(id,goodsId)); }
    @LogOperation(action="supplier.import", targetType="SUPPLIER", targetId="#id", detail="'导入货源商品'")
    @PostMapping("/{id}/goods/{goodsId}/import") public ApiResponse<?> importProduct(@PathVariable UUID id,@PathVariable String goodsId,@RequestBody ImportRequest request) {
        return ApiResponse.success(suppliers.importProduct(id,goodsId,request.categoryId()));
    }
    @GetMapping("/{id}/mappings") public ApiResponse<?> mappings(@PathVariable UUID id) { return ApiResponse.success(suppliers.mappings(id)); }
    @LogOperation(action="supplier.sync", targetType="SUPPLIER", targetId="#id", detail="'同步货源商品'")
    @PostMapping("/{id}/goods/{goodsId}/sync") public ApiResponse<?> sync(@PathVariable UUID id,@PathVariable String goodsId) {
        suppliers.sync(id,goodsId); return ApiResponse.success();
    }
    public record Status(Boolean enabled) {}
    public record ImportRequest(UUID categoryId) {}
}
