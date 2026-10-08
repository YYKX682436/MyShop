package com.myshop.repository;

import com.myshop.entity.SalesChannelRecoveryPermit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalesChannelRecoveryPermitRepository extends JpaRepository<SalesChannelRecoveryPermit, UUID> {

    List<String> SCOPED_STATES = List.of(
            "PREPARED", "ARMED", "GO_PENDING", "ACTIVE", "EXECUTING", "RECOVERY_REQUIRED",
            "TERMINAL", "CANCEL_PENDING");

    Optional<SalesChannelRecoveryPermit> findByMyshopOperationId(UUID operationId);

    Optional<SalesChannelRecoveryPermit> findByRequestId(String requestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT permit FROM SalesChannelRecoveryPermit permit WHERE permit.id = :id")
    Optional<SalesChannelRecoveryPermit> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT permit FROM SalesChannelRecoveryPermit permit WHERE permit.myshopOperationId = :operationId")
    Optional<SalesChannelRecoveryPermit> findByOperationIdForUpdate(@Param("operationId") UUID operationId);

    @Query("SELECT permit FROM SalesChannelRecoveryPermit permit WHERE permit.state IN :states "
            + "ORDER BY permit.createdAt, permit.id")
    List<SalesChannelRecoveryPermit> findByStateInOrderByCreatedAt(@Param("states") List<String> states);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT permit FROM SalesChannelRecoveryPermit permit WHERE permit.state IN :states "
            + "ORDER BY permit.createdAt, permit.id")
    List<SalesChannelRecoveryPermit> findByStateInForUpdate(@Param("states") List<String> states);

    boolean existsByStateIn(List<String> states);

    @Query(value = "SELECT operation.id FROM sales_channel_operations operation "
            + "JOIN sales_channel_recovery_permits permit "
            + "  ON permit.myshop_operation_id = operation.id "
            + "WHERE permit.state IN ('ACTIVE', 'RECOVERY_REQUIRED') "
            + "AND operation.status = 'PENDING' "
            + "AND operation.operation_type = 'ENSURE_LISTING' "
            + "AND (operation.next_attempt_at IS NULL OR operation.next_attempt_at <= :now) "
            + "ORDER BY permit.created_at, operation.created_at, operation.id "
            + "LIMIT :limit FOR UPDATE OF operation SKIP LOCKED", nativeQuery = true)
    List<UUID> findGoReadyOperationIdsForUpdate(@Param("now") java.time.LocalDateTime now,
                                                @Param("limit") int limit);
}
