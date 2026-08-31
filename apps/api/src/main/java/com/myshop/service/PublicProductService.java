package com.myshop.service;

import com.myshop.common.PageResult;
import com.myshop.model.response.PublicProductResponse;

import java.util.UUID;

public interface PublicProductService {
    PageResult<PublicProductResponse> listProducts(UUID categoryId, String keyword,
                                                   int page, int pageSize, UUID requestUserId);

    PublicProductResponse getProduct(UUID id, UUID requestUserId);
}
