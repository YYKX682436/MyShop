package com.myshop.service;

import com.myshop.common.PageResult;
import com.myshop.model.response.PublicProductResponse;

import java.math.BigDecimal;
import java.util.UUID;

public interface PublicProductService {
    PageResult<PublicProductResponse> listProducts(UUID categoryId, String keyword,
                                                   int page, int pageSize, UUID requestUserId);

    PageResult<PublicProductResponse> listProducts(UUID categoryId, String keyword,
                                                   int page, int pageSize, UUID requestUserId,
                                                   String sort, boolean inStock,
                                                   BigDecimal priceMin, BigDecimal priceMax);

    PublicProductResponse getProduct(UUID id, UUID requestUserId);
}
