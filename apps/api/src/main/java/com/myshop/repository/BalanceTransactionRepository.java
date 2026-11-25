package com.myshop.repository;

import com.myshop.entity.BalanceTransaction;
import com.myshop.constant.BalanceTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;

public interface BalanceTransactionRepository extends JpaRepository<BalanceTransaction, UUID> {

    Page<BalanceTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<BalanceTransaction> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);

    @Query("select count(t) from BalanceTransaction t where t.userId=:userId and "
            + "(t.createdAt>:createdAt or (t.createdAt=:createdAt and t.id>:id))")
    long countNewerThanForUser(@Param("userId") UUID userId, @Param("createdAt") java.time.LocalDateTime createdAt,
                               @Param("id") UUID id);

    Optional<BalanceTransaction> findFirstByOrderIdAndTypeOrderByCreatedAtDesc(
            UUID orderId, BalanceTransactionType type);
}
