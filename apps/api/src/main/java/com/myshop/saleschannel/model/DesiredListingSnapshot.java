package com.myshop.saleschannel.model;

import com.myshop.saleschannel.model.SalesChannelDtos.PlatformAttribute;
import com.myshop.saleschannel.model.SalesChannelDtos.SkuRow;
import com.myshop.saleschannel.model.SalesChannelDtos.Specification;

import java.math.BigDecimal;
import java.util.List;

/** Immutable, revision-bound MyShop target state persisted into each operation payload. */
public record DesiredListingSnapshot(
        String providerAccountId,
        String accountType,
        String desiredState,
        String title,
        String description,
        BigDecimal price,
        BigDecimal originalPrice,
        List<String> sourceImageUrls,
        String platformCategoryId,
        String platformCategoryName,
        String platformChannelCategoryId,
        String platformChannelCategoryName,
        String platformLeafId,
        String platformTbCategoryId,
        List<PlatformAttribute> platformAttributes,
        Integer quantity,
        List<Specification> specifications,
        List<SkuRow> skuRows,
        String address,
        String addressExpectedText,
        String deliveryMethod,
        String shippingMethod,
        Boolean supportPickup,
        BigDecimal postage) {

    public DesiredListingSnapshot {
        sourceImageUrls = sourceImageUrls == null ? List.of() : List.copyOf(sourceImageUrls);
        platformAttributes = platformAttributes == null ? List.of() : List.copyOf(platformAttributes);
        specifications = specifications == null ? List.of() : List.copyOf(specifications);
        skuRows = skuRows == null ? List.of() : List.copyOf(skuRows);
    }
}
