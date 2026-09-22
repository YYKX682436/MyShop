package com.myshop.repository;

import com.myshop.constant.SalesChannelDeliveryOperationStatus;
import com.myshop.constant.SalesChannelDeliveryOperationType;
import com.myshop.entity.SalesChannelDeliveryOperation;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalesChannelDeliveryOperationRepository extends JpaRepository<SalesChannelDeliveryOperation, UUID> {
    Optional<SalesChannelDeliveryOperation> findByRequestId(String requestId);

    @Query("SELECT operation FROM SalesChannelDeliveryOperation operation "
            + "WHERE operation.salesChannelOrderId = :orderId "
            + "AND operation.operationType = :type "
            + "AND ((:unitIndex IS NULL AND operation.unitIndex IS NULL) "
            + "OR operation.unitIndex = :unitIndex)")
    Optional<SalesChannelDeliveryOperation> findBySalesChannelOrderIdAndOperationTypeAndUnitIndex(
            @Param("orderId") UUID orderId,
            @Param("type") SalesChannelDeliveryOperationType type,
            @Param("unitIndex") Integer unitIndex);

    boolean existsBySalesChannelOrderIdAndOperationTypeAndUnitIndex(
            UUID orderId, SalesChannelDeliveryOperationType type, Integer unitIndex);

    List<SalesChannelDeliveryOperation> findBySalesChannelOrderIdOrderByCreatedAtAsc(UUID orderId);

    List<SalesChannelDeliveryOperation> findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(
            UUID orderId, SalesChannelDeliveryOperationType type);

    @Query("SELECT operation FROM SalesChannelDeliveryOperation operation "
            + "WHERE operation.status IN :statuses "
            + "AND (operation.status = com.myshop.constant.SalesChannelDeliveryOperationStatus.PENDING "
            + "OR (operation.status = com.myshop.constant.SalesChannelDeliveryOperationStatus.FAILED "
            + "AND operation.nextAttemptAt IS NOT NULL AND operation.nextAttemptAt <= :now)) "
            + "ORDER BY operation.createdAt ASC")
    List<SalesChannelDeliveryOperation> findDue(@Param("statuses") Collection<SalesChannelDeliveryOperationStatus> statuses,
                                                @Param("now") LocalDateTime now,
                                                Pageable pageable);

    @Query("SELECT operation FROM SalesChannelDeliveryOperation operation "
            + "WHERE operation.status = com.myshop.constant.SalesChannelDeliveryOperationStatus.RUNNING "
            + "AND operation.startedAt IS NOT NULL AND operation.startedAt < :cutoff "
            + "ORDER BY operation.startedAt ASC")
    List<SalesChannelDeliveryOperation> findStaleRunning(@Param("cutoff") LocalDateTime cutoff,
                                                         Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT operation FROM SalesChannelDeliveryOperation operation WHERE operation.id = :id")
    Optional<SalesChannelDeliveryOperation> findByIdForUpdate(@Param("id") UUID id);
}
