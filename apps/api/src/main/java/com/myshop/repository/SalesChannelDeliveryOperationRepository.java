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

    Optional<SalesChannelDeliveryOperation> findBySalesChannelOrderIdAndOperationTypeAndUnitIndex(
            UUID orderId, SalesChannelDeliveryOperationType type, Integer unitIndex);

    boolean existsBySalesChannelOrderIdAndOperationTypeAndUnitIndex(
            UUID orderId, SalesChannelDeliveryOperationType type, Integer unitIndex);

    List<SalesChannelDeliveryOperation> findBySalesChannelOrderIdOrderByCreatedAtAsc(UUID orderId);

    List<SalesChannelDeliveryOperation> findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(
            UUID orderId, SalesChannelDeliveryOperationType type);

    @Query("SELECT operation FROM SalesChannelDeliveryOperation operation "
            + "WHERE operation.status IN :statuses "
            + "AND (operation.nextAttemptAt IS NULL OR operation.nextAttemptAt <= :now) "
            + "ORDER BY operation.createdAt ASC")
    List<SalesChannelDeliveryOperation> findDue(@Param("statuses") Collection<SalesChannelDeliveryOperationStatus> statuses,
                                                @Param("now") LocalDateTime now,
                                                Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT operation FROM SalesChannelDeliveryOperation operation WHERE operation.id = :id")
    Optional<SalesChannelDeliveryOperation> findByIdForUpdate(@Param("id") UUID id);
}
