package com.myshop.model.request;

import java.math.BigDecimal;
import java.util.List;

/**
 * Price-only draft used by the live pricing preview.
 *
 * <p>This contract intentionally contains no publishable-product fields. The
 * complete aggregate validation remains the responsibility of the product
 * upsert request.</p>
 */
public record AdminPricingPreviewRequest(
        BigDecimal costPrice,
        BigDecimal retailPrice,
        Boolean specEnabled,
        List<AdminPricingPreviewSpecRequest> specs,
        List<AdminPricingPreviewOverrideRequest> overrides
) {
}
