package com.myshop.model.response;

import java.math.BigDecimal;
import java.util.UUID;

public record UserSecretPriceResponse(UUID id, UUID productId, String productTitle, UUID specId,
                                      String specName, BigDecimal price, BigDecimal levelPrice,
                                      BigDecimal retailPrice, BigDecimal costPrice, boolean belowCost) {}
