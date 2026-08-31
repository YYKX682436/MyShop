package com.myshop.model.response;

import java.util.List;

public record AdminPricingPreviewResponse(
        List<AdminPricingMatrixResponse> pricingMatrix,
        List<String> warnings
) {
}
