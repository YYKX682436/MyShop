package com.myshop.repository;

import com.myshop.entity.BalanceTransaction;
import com.myshop.constant.BalanceTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;

public interface BalanceTransactionRepository extends JpaRepository<BalanceTransaction, UUID> {

    Page<BalanceTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<BalanceTransaction> findFirstByOrderIdAndTypeOrderByCreatedAtDesc(
            UUID orderId, BalanceTransactionType type);
}
