package com.myshop.repository;

import com.myshop.entity.ProductLevelPriceOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductLevelPriceOverrideRepository extends JpaRepository<ProductLevelPriceOverride, UUID> {

    List<ProductLevelPriceOverride> findByProductIdAndUserLevelId(UUID productId, UUID userLevelId);
}
