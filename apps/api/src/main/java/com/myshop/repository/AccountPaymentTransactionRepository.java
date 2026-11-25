package com.myshop.repository;

import com.myshop.constant.AccountPaymentStatus;
import com.myshop.constant.AccountPaymentType;
import com.myshop.entity.AccountPaymentTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountPaymentTransactionRepository extends JpaRepository<AccountPaymentTransaction, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AccountPaymentTransaction t where t.id = :id")
    Optional<AccountPaymentTransaction> findByIdForUpdate(@Param("id") UUID id);

    Optional<AccountPaymentTransaction> findByIdAndUserId(UUID id, UUID userId);

    Page<AccountPaymentTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<AccountPaymentTransaction> findByUserIdAndTypeAndStatusOrderByCreatedAtDesc(
            UUID userId, AccountPaymentType type, AccountPaymentStatus status);
}
