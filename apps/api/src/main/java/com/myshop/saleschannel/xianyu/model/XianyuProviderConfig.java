package com.myshop.saleschannel.xianyu.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Server-validated, deliberately small configuration surface for Xianyu listings. */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record XianyuProviderConfig(
        @Size(max = 200) String titleOverride,
        @Size(max = 1500) String descriptionOverride,
        @Size(max = 9) List<@Size(max = 2048) String> imageUrls,
        PriceMode priceMode,
        @DecimalMin("0.01") @DecimalMax("99999999.99") BigDecimal fixedPrice,
        @DecimalMin("0.00") @DecimalMax("1000.00") BigDecimal markupPercent,
        @Size(max = 200) String address,
        @Size(max = 200) String addressExpectedText,
        @Size(max = 64) String platformCategoryId,
        @Size(max = 100) String platformCategoryName,
        @Size(max = 64) String platformChannelCategoryId,
        @Size(max = 100) String platformChannelCategoryName,
        @Size(max = 64) String platformLeafId,
        @Size(max = 64) String platformTbCategoryId,
        @Size(max = 30) List<@Valid PlatformAttributeConfig> platformAttributes,
        @Pattern(regexp = "express|pickup") String deliveryMethod,
        @Pattern(regexp = "free|distance|fixed|template|none") String shippingMethod,
        Boolean supportPickup,
        @DecimalMin("0.00") @DecimalMax("1000.00") BigDecimal postage,
        @Min(0) @Max(999999) Integer unlimitedStockQuantity) {

    public enum PriceMode {
        PRODUCT_RETAIL,
        FIXED,
        MARKUP_PERCENT
    }

    public record PlatformAttributeConfig(
            @Size(max = 64) String propertyId,
            @Size(max = 100) String propertyName,
            @Size(max = 64) String valueId,
            @Size(max = 200) String valueName,
            @Size(max = 200) String text,
            @Size(max = 500) Map<String, Object> properties) { }

    public XianyuProviderConfig {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        platformAttributes = platformAttributes == null ? List.of() : List.copyOf(platformAttributes);
        priceMode = priceMode == null ? PriceMode.PRODUCT_RETAIL : priceMode;
        deliveryMethod = deliveryMethod == null || deliveryMethod.isBlank() ? "express" : deliveryMethod;
        shippingMethod = shippingMethod == null || shippingMethod.isBlank() ? "free" : shippingMethod;
        supportPickup = supportPickup == null ? false : supportPickup;
        postage = postage == null ? BigDecimal.ZERO : postage;
        unlimitedStockQuantity = unlimitedStockQuantity == null ? 999 : unlimitedStockQuantity;
    }

    public void validateForSave() {
        if (priceMode == PriceMode.FIXED && fixedPrice == null) {
            throw new IllegalArgumentException("fixedPrice is required for FIXED price mode");
        }
        if (priceMode == PriceMode.MARKUP_PERCENT && markupPercent == null) {
            throw new IllegalArgumentException("markupPercent is required for MARKUP_PERCENT price mode");
        }
    }
}
