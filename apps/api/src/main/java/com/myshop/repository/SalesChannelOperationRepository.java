package com.myshop.repository;

import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.entity.SalesChannelOperation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface SalesChannelOperationRepository extends JpaRepository<SalesChannelOperation, UUID> {

    Optional<SalesChannelOperation> findByIdempotencyKey(String idempotencyKey);

    List<SalesChannelOperation> findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(
            UUID listingId, long desiredRevision);

    List<SalesChannelOperation> findBySalesChannelListingIdOrderByCreatedAtAsc(UUID listingId);

    boolean existsBySalesChannelListingId(UUID listingId);

    @Query("SELECT DISTINCT operation.salesChannelListingId FROM SalesChannelOperation operation "
            + "WHERE operation.salesChannelListingId IN :listingIds")
    Set<UUID> findListingIdsWithOperations(@Param("listingIds") Collection<UUID> listingIds);

    boolean existsBySalesChannelListingIdAndStatus(UUID listingId, SalesChannelOperationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT operation FROM SalesChannelOperation operation WHERE operation.id = :id")
    Optional<SalesChannelOperation> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Selects and temporarily reserves queue rows. Commit this selection before
     * calling SalesChannelOperationServiceImpl.markRunning: that service locks
     * listing rows before operation rows, so retaining these operation locks
     * while acquiring a listing lock would invert the domain lock order.
     */
    @Query(value = "SELECT operation.* FROM sales_channel_operations operation "
            + "WHERE operation.status = 'PENDING' "
            + "AND operation.operation_type = 'ENSURE_LISTING' "
            + "AND (operation.next_attempt_at IS NULL OR operation.next_attempt_at <= :now) "
            + "AND NOT EXISTS (SELECT 1 FROM sales_channel_operations running_operation "
            + "  WHERE running_operation.sales_channel_listing_id = operation.sales_channel_listing_id "
            + "  AND running_operation.status = 'RUNNING') "
            + "AND NOT EXISTS ("
            + "  SELECT 1 FROM sales_channel_operations legacy_unknown "
            + "  WHERE legacy_unknown.sales_channel_listing_id = operation.sales_channel_listing_id "
            + "  AND legacy_unknown.operation_type <> 'ENSURE_LISTING' AND legacy_unknown.status = 'UNKNOWN') "
            + "AND NOT EXISTS (SELECT 1 FROM sales_channel_operations older "
            + "  WHERE older.sales_channel_listing_id = operation.sales_channel_listing_id "
            + "  AND older.operation_type = 'ENSURE_LISTING' "
            + "  AND older.desired_revision < operation.desired_revision "
            + "  AND older.status IN ('PENDING', 'RUNNING', 'UNKNOWN')) "
            + "ORDER BY COALESCE(operation.next_attempt_at, operation.created_at), operation.created_at, operation.id "
            + "LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<SalesChannelOperation> findReadyPendingForUpdate(@Param("now") LocalDateTime now,
                                                           @Param("limit") int limit);

    @Query(value = "SELECT operation.* FROM sales_channel_operations operation "
            + "WHERE operation.operation_type = 'ENSURE_LISTING' "
            + "AND operation.status = 'RUNNING' AND operation.started_at IS NOT NULL "
            + "AND operation.started_at < :cutoff "
            + "ORDER BY operation.started_at, operation.id LIMIT :limit", nativeQuery = true)
    List<SalesChannelOperation> findStaleEnsureRunningBefore(@Param("cutoff") LocalDateTime cutoff,
                                                              @Param("limit") int limit);

}
