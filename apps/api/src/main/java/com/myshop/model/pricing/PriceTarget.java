package com.myshop.model.pricing;

import java.util.UUID;

/** Identifies either a product without specs or one concrete product spec. */
public record PriceTarget(UUID productId, UUID specId) {
}
