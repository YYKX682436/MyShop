package com.myshop.repository;

import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.entity.SalesChannelOperation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalesChannelOperationRepository extends JpaRepository<SalesChannelOperation, UUID> {

    Optional<SalesChannelOperation> findByIdempotencyKey(String idempotencyKey);

    List<SalesChannelOperation> findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(
            UUID listingId, long desiredRevision);

    List<SalesChannelOperation> findBySalesChannelListingIdOrderByCreatedAtAsc(UUID listingId);

    boolean existsBySalesChannelListingIdAndStatus(UUID listingId, SalesChannelOperationStatus status);

    boolean existsBySalesChannelListingIdAndOperationTypeAndStatus(
            UUID listingId, com.myshop.constant.SalesChannelOperationType operationType,
            SalesChannelOperationStatus status);

    @Query(value = "SELECT operation.* FROM sales_channel_operations operation "
            + "WHERE operation.status = 'PENDING' "
            + "AND (operation.next_attempt_at IS NULL OR operation.next_attempt_at <= :now) "
            + "AND NOT EXISTS (SELECT 1 FROM sales_channel_operations running_operation "
            + "  WHERE running_operation.sales_channel_listing_id = operation.sales_channel_listing_id "
            + "  AND running_operation.status = 'RUNNING') "
            + "AND (operation.operation_type = 'RECONCILE' OR NOT EXISTS ("
            + "  SELECT 1 FROM sales_channel_operations unknown_operation "
            + "  WHERE unknown_operation.sales_channel_listing_id = operation.sales_channel_listing_id "
            + "  AND unknown_operation.status = 'UNKNOWN' "
            + "  AND NOT EXISTS (SELECT 1 FROM sales_channel_operations reconciliation "
            + "    WHERE reconciliation.sales_channel_listing_id = unknown_operation.sales_channel_listing_id "
            + "    AND reconciliation.operation_type = 'RECONCILE' AND reconciliation.status = 'SUCCEEDED' "
            + "    AND reconciliation.desired_revision >= unknown_operation.desired_revision "
            + "    AND reconciliation.started_at > unknown_operation.finished_at)"
            + ")) "
            + "ORDER BY COALESCE(operation.next_attempt_at, operation.created_at), operation.created_at, operation.id "
            + "LIMIT :limit", nativeQuery = true)
    List<SalesChannelOperation> findReadyPendingAt(@Param("now") LocalDateTime now,
                                                   @Param("limit") int limit);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM sales_channel_operations unknown_operation "
            + "WHERE unknown_operation.sales_channel_listing_id = :listingId "
            + "AND unknown_operation.status = 'UNKNOWN' "
            + "AND NOT EXISTS (SELECT 1 FROM sales_channel_operations reconciliation "
            + "  WHERE reconciliation.sales_channel_listing_id = unknown_operation.sales_channel_listing_id "
            + "  AND reconciliation.operation_type = 'RECONCILE' AND reconciliation.status = 'SUCCEEDED' "
            + "  AND reconciliation.desired_revision >= unknown_operation.desired_revision "
            + "  AND reconciliation.started_at > unknown_operation.finished_at))",
            nativeQuery = true)
    boolean hasUnresolvedUnknown(@Param("listingId") UUID listingId);

    @Query(value = "SELECT MAX(unknown_operation.finished_at) FROM sales_channel_operations unknown_operation "
            + "WHERE unknown_operation.sales_channel_listing_id = :listingId "
            + "AND unknown_operation.status = 'UNKNOWN' "
            + "AND unknown_operation.desired_revision <= :revision", nativeQuery = true)
    Optional<LocalDateTime> findLatestUnknownFinishedAt(@Param("listingId") UUID listingId,
                                                        @Param("revision") long revision);

    @Query(value = "SELECT unknown_operation.* FROM sales_channel_operations unknown_operation "
            + "WHERE unknown_operation.sales_channel_listing_id = :listingId "
            + "AND unknown_operation.status = 'UNKNOWN' "
            + "AND NOT EXISTS (SELECT 1 FROM sales_channel_operations reconciliation "
            + "  WHERE reconciliation.sales_channel_listing_id = unknown_operation.sales_channel_listing_id "
            + "  AND reconciliation.operation_type = 'RECONCILE' AND reconciliation.status = 'SUCCEEDED' "
            + "  AND reconciliation.desired_revision >= unknown_operation.desired_revision "
            + "  AND reconciliation.started_at > unknown_operation.finished_at) "
            + "ORDER BY unknown_operation.finished_at DESC, unknown_operation.created_at DESC, unknown_operation.id DESC "
            + "LIMIT 1", nativeQuery = true)
    Optional<SalesChannelOperation> findLatestUnresolvedUnknown(@Param("listingId") UUID listingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT operation FROM SalesChannelOperation operation WHERE operation.id = :id")
    Optional<SalesChannelOperation> findByIdForUpdate(@Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SalesChannelOperation operation "
            + "SET operation.status = :superseded, operation.finishedAt = :now, operation.updatedAt = :now "
            + "WHERE operation.salesChannelListingId = :listingId "
            + "AND operation.desiredRevision < :revision "
            + "AND operation.status = :pending "
            + "AND operation.operationType <> com.myshop.constant.SalesChannelOperationType.RECONCILE")
    int supersedePendingBeforeRevision(@Param("listingId") UUID listingId,
                                       @Param("revision") long revision,
                                       @Param("pending") SalesChannelOperationStatus pending,
                                       @Param("superseded") SalesChannelOperationStatus superseded,
                                       @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SalesChannelOperation operation "
            + "SET operation.status = :superseded, operation.finishedAt = :now, operation.updatedAt = :now "
            + "WHERE operation.salesChannelListingId = :listingId "
            + "AND operation.desiredRevision = :revision "
            + "AND operation.operationType = com.myshop.constant.SalesChannelOperationType.SYNC "
            + "AND operation.status = :pending")
    int supersedePendingSyncAtRevision(@Param("listingId") UUID listingId,
                                       @Param("revision") long revision,
                                       @Param("pending") SalesChannelOperationStatus pending,
                                       @Param("superseded") SalesChannelOperationStatus superseded,
                                       @Param("now") LocalDateTime now);

    /**
     * Selects and temporarily reserves queue rows. Commit this selection before
     * calling SalesChannelOperationServiceImpl.markRunning: that service locks
     * listing rows before operation rows, so retaining these operation locks
     * while acquiring a listing lock would invert the domain lock order.
     */
    @Query(value = "SELECT operation.* FROM sales_channel_operations operation "
            + "WHERE operation.status = 'PENDING' "
            + "AND (operation.next_attempt_at IS NULL OR operation.next_attempt_at <= :now) "
            + "AND NOT EXISTS (SELECT 1 FROM sales_channel_operations running_operation "
            + "  WHERE running_operation.sales_channel_listing_id = operation.sales_channel_listing_id "
            + "  AND running_operation.status = 'RUNNING') "
            + "AND (operation.operation_type = 'RECONCILE' OR NOT EXISTS ("
            + "  SELECT 1 FROM sales_channel_operations unknown_operation "
            + "  WHERE unknown_operation.sales_channel_listing_id = operation.sales_channel_listing_id "
            + "  AND unknown_operation.status = 'UNKNOWN' "
            + "  AND NOT EXISTS (SELECT 1 FROM sales_channel_operations reconciliation "
            + "    WHERE reconciliation.sales_channel_listing_id = unknown_operation.sales_channel_listing_id "
            + "    AND reconciliation.operation_type = 'RECONCILE' AND reconciliation.status = 'SUCCEEDED' "
            + "    AND reconciliation.desired_revision >= unknown_operation.desired_revision "
            + "    AND reconciliation.started_at > unknown_operation.finished_at)"
            + ")) "
            + "ORDER BY COALESCE(operation.next_attempt_at, operation.created_at), operation.created_at, operation.id "
            + "LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<SalesChannelOperation> findReadyPendingForUpdate(@Param("now") LocalDateTime now,
                                                           @Param("limit") int limit);

    @Query(value = "SELECT operation.* FROM sales_channel_operations operation "
            + "WHERE operation.status = 'RUNNING' AND operation.started_at IS NOT NULL "
            + "AND operation.started_at < :cutoff "
            + "ORDER BY operation.started_at, operation.id LIMIT :limit", nativeQuery = true)
    List<SalesChannelOperation> findStaleRunningBefore(@Param("cutoff") LocalDateTime cutoff,
                                                        @Param("limit") int limit);

}
