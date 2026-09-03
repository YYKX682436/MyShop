package com.myshop.model.response;

import java.util.List;

public record AdminPricingPreviewResponse(
        List<AdminPricingPreviewRowResponse> pricingMatrix,
        List<String> warnings
) {
}
