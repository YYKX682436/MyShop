package com.myshop.repository;

import com.myshop.constant.AfterSaleRequestStatus;
import com.myshop.entity.AfterSaleRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AfterSaleRequestRepository extends JpaRepository<AfterSaleRequest, UUID> {

    Optional<AfterSaleRequest> findFirstByOrderIdAndStatusOrderBySubmittedAtDesc(
            UUID orderId, AfterSaleRequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM AfterSaleRequest r WHERE r.orderId = :orderId AND r.status = :status "
            + "ORDER BY r.submittedAt DESC")
    Optional<AfterSaleRequest> findFirstByOrderIdAndStatusForUpdate(
            @Param("orderId") UUID orderId, @Param("status") AfterSaleRequestStatus status);

    List<AfterSaleRequest> findByOrderIdOrderBySubmittedAtDesc(UUID orderId);

    @Query("SELECT r FROM AfterSaleRequest r WHERE r.orderId IN :orderIds "
            + "AND r.status = :status ORDER BY r.lastActivityAt DESC, r.id DESC")
    List<AfterSaleRequest> findByOrderIdInAndStatusOrderByLastActivityAtDescIdDesc(
            @Param("orderIds") List<UUID> orderIds,
            @Param("status") AfterSaleRequestStatus status);

    long countByStatusAndWaitingFor(AfterSaleRequestStatus status,
                                    com.myshop.constant.AfterSaleWaitingFor waitingFor);
}
