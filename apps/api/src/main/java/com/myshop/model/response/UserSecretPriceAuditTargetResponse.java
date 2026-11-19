package com.myshop.model.response;

import java.util.UUID;

/** Non-price identifiers returned after deletion for audit correlation. */
public record UserSecretPriceAuditTargetResponse(UUID id, UUID productId, UUID specId) {
}
