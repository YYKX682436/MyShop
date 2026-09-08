package com.myshop.repository;

import com.myshop.entity.PaymentAttempt;
import com.myshop.payment.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findFirstByOrderIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID orderId, PaymentStatus status, LocalDateTime now);

    List<PaymentAttempt> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentAttempt p WHERE p.orderId = :orderId AND p.status = :status ORDER BY p.createdAt ASC")
    List<PaymentAttempt> findByOrderIdAndStatusForUpdate(@Param("orderId") UUID orderId,
                                                          @Param("status") PaymentStatus status);

    Optional<PaymentAttempt> findByMerchantTradeNo(String merchantTradeNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentAttempt p WHERE p.id = :id")
    Optional<PaymentAttempt> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByMerchantTradeNo(String merchantTradeNo);
}
