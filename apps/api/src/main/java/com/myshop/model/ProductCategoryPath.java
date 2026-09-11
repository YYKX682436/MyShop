package com.myshop.model;

import com.myshop.entity.ProductCategory;

import java.util.Map;
import java.util.UUID;

/** Explicit primary/secondary category information for product responses. */
public record ProductCategoryPath(
        UUID primaryCategoryId,
        String primaryCategoryName,
        UUID secondaryCategoryId,
        String secondaryCategoryName
) {

    public static ProductCategoryPath from(ProductCategory leaf, Map<UUID, ProductCategory> categories) {
        if (leaf == null) return new ProductCategoryPath(null, null, null, null);
        if (leaf.getParentId() == null) {
            return new ProductCategoryPath(leaf.getId(), leaf.getName(), null, null);
        }
        ProductCategory primary = categories.get(leaf.getParentId());
        return new ProductCategoryPath(leaf.getParentId(), primary == null ? null : primary.getName(),
                leaf.getId(), leaf.getName());
    }
}
