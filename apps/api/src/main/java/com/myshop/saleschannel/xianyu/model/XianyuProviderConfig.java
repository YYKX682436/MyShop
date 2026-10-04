package com.myshop.saleschannel.xianyu.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Server-validated, deliberately small configuration surface for Xianyu listings. */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record XianyuProviderConfig(
        @JsonProperty("titleOverride") @Size(max = 200) String titleOverride,
        @JsonProperty("descriptionOverride") @Size(max = 1500) String descriptionOverride,
        @JsonProperty("imageUrls") @Size(max = 9) List<@Size(max = 2048) String> imageUrls,
        @JsonProperty("priceMode") PriceMode priceMode,
        @JsonProperty("fixedPrice") @DecimalMin("0.01") @DecimalMax("99999999.99") BigDecimal fixedPrice,
        @JsonProperty("markupPercent") @DecimalMin("0.00") @DecimalMax("1000.00") BigDecimal markupPercent,
        @JsonProperty("address") @Size(max = 200) String address,
        @JsonProperty("addressExpectedText") @Size(max = 200) String addressExpectedText,
        @JsonProperty("platformCategoryId") @Size(max = 64) String platformCategoryId,
        @JsonProperty("platformCategoryName") @Size(max = 100) String platformCategoryName,
        @JsonProperty("platformChannelCategoryId") @Size(max = 64) String platformChannelCategoryId,
        @JsonProperty("platformChannelCategoryName") @Size(max = 100) String platformChannelCategoryName,
        @JsonProperty("platformLeafId") @Size(max = 64) String platformLeafId,
        @JsonProperty("platformTbCategoryId") @Size(max = 64) String platformTbCategoryId,
        @JsonProperty("platformAttributes") @Size(max = 30) List<@Valid PlatformAttributeConfig> platformAttributes,
        @JsonProperty("deliveryMethod") @Pattern(regexp = "express|pickup") String deliveryMethod,
        @JsonProperty("shippingMethod") @Pattern(regexp = "free|distance|fixed|template|none") String shippingMethod,
        @JsonProperty("supportPickup") Boolean supportPickup,
        @JsonProperty("postage") @DecimalMin("0.00") @DecimalMax("1000.00") BigDecimal postage,
        @JsonProperty("unlimitedStockQuantity") @Min(0) @Max(999999) Integer unlimitedStockQuantity,
        @JsonProperty("stockCapPerSku") @Min(0) @Max(999999) Integer stockCapPerSku) {

    public enum PriceMode {
        PRODUCT_RETAIL,
        FIXED,
        MARKUP_PERCENT
    }

    public record PlatformAttributeConfig(
            @JsonProperty("propertyId") @Size(max = 64) String propertyId,
            @JsonProperty("propertyName") @Size(max = 100) String propertyName,
            @JsonProperty("valueId") @Size(max = 64) String valueId,
            @JsonProperty("valueName") @Size(max = 200) String valueName,
            @JsonProperty("text") @Size(max = 200) String text,
            @JsonProperty("properties") @Size(max = 500) Map<String, Object> properties) { }

    public XianyuProviderConfig {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        platformAttributes = platformAttributes == null ? List.of() : List.copyOf(platformAttributes);
        priceMode = priceMode == null ? PriceMode.PRODUCT_RETAIL : priceMode;
        deliveryMethod = deliveryMethod == null || deliveryMethod.isBlank() ? "express" : deliveryMethod;
        shippingMethod = shippingMethod == null || shippingMethod.isBlank() ? "free" : shippingMethod;
        supportPickup = supportPickup == null ? false : supportPickup;
        postage = postage == null && "fixed".equals(shippingMethod)
                ? null : postage == null ? BigDecimal.ZERO : postage;
        unlimitedStockQuantity = unlimitedStockQuantity == null ? 999 : unlimitedStockQuantity;
    }

    public void validateForSave() {
        if (stockCapPerSku != null && (stockCapPerSku < 0 || stockCapPerSku > 999999)) {
            throw new IllegalArgumentException("stockCapPerSku must be between 0 and 999999");
        }
        if (priceMode == PriceMode.FIXED && fixedPrice == null) {
            throw new IllegalArgumentException("fixedPrice is required for FIXED price mode");
        }
        if (priceMode == PriceMode.MARKUP_PERCENT && markupPercent == null) {
            throw new IllegalArgumentException("markupPercent is required for MARKUP_PERCENT price mode");
        }
        if ("fixed".equals(shippingMethod) && postage == null) {
            throw new IllegalArgumentException("postage is required for fixed shipping");
        }
    }
}
