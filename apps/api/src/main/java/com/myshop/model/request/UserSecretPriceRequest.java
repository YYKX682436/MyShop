package com.myshop.model.request;

import java.math.BigDecimal;
import java.util.UUID;

public record UserSecretPriceRequest(UUID productId, UUID specId, BigDecimal price) {}
