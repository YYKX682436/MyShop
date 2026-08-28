package com.myshop.repository;

import com.myshop.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByUserId(UUID userId);

    List<CartItem> findBySessionToken(String sessionToken);

    /** Pool candidates are compared in Java after structured normalization. */
    List<CartItem> findByUserIdAndProductId(UUID userId, UUID productId);

    List<CartItem> findBySessionTokenAndProductId(String sessionToken, UUID productId);

    @Transactional
    void deleteBySessionToken(String sessionToken);
}
