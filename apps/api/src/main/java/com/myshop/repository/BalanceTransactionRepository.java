package com.myshop.repository;

import com.myshop.entity.BalanceTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BalanceTransactionRepository extends JpaRepository<BalanceTransaction, UUID> {

    Page<BalanceTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
