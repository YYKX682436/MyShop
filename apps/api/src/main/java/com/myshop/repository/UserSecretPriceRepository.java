package com.myshop.repository;

import com.myshop.entity.UserSecretPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSecretPriceRepository extends JpaRepository<UserSecretPrice, UUID> {
    List<UserSecretPrice> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<UserSecretPrice> findByUserIdAndProductIdIn(UUID userId, List<UUID> productIds);
    List<UserSecretPrice> findByProductId(UUID productId);
    Optional<UserSecretPrice> findByUserIdAndProductIdAndSpecId(UUID userId, UUID productId, UUID specId);
    Optional<UserSecretPrice> findByIdAndUserId(UUID id, UUID userId);
}
