package com.myshop.repository;

import com.myshop.entity.ProductLevelPriceOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProductLevelPriceOverrideRepository extends JpaRepository<ProductLevelPriceOverride, UUID> {

    List<ProductLevelPriceOverride> findByProductIdAndUserLevelId(UUID productId, UUID userLevelId);

    List<ProductLevelPriceOverride> findByProductIdInAndUserLevelId(List<UUID> productIds, UUID userLevelId);

    List<ProductLevelPriceOverride> findByProductId(UUID productId);

    long countByUserLevelId(UUID userLevelId);

    @Modifying
    @Query("DELETE FROM ProductLevelPriceOverride o WHERE o.userLevelId = :userLevelId")
    int deleteByUserLevelId(@Param("userLevelId") UUID userLevelId);

    @Modifying
    @Query("DELETE FROM ProductLevelPriceOverride o WHERE o.productId = :productId "
            + "AND o.specId IS NULL")
    int deleteProductLevelOverrides(@Param("productId") UUID productId);

    @Modifying
    @Query("DELETE FROM ProductLevelPriceOverride o WHERE o.productId = :productId "
            + "AND o.specId IS NOT NULL")
    int deleteSpecLevelOverrides(@Param("productId") UUID productId);

    @Modifying
    @Query("DELETE FROM ProductLevelPriceOverride o WHERE o.specId = :specId")
    int deleteBySpecId(@Param("specId") UUID specId);
}
