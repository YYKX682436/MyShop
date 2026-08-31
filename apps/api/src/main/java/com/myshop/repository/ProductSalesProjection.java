package com.myshop.repository;

import java.util.UUID;

public interface ProductSalesProjection {
    UUID getProductId();
    Long getQuantity();
}
