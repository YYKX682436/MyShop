package com.myshop.repository;

import com.myshop.constant.CardKeyStatus;
import com.myshop.entity.CardKey;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CardKeyRepository extends JpaRepository<CardKey, UUID> {

    /**
     * Serializes duplicate-checked imports for one product/spec pool for the
     * lifetime of the surrounding PostgreSQL transaction. The hashed key is
     * stable and the extremely unlikely hash collision only causes harmless
     * serialization between pools.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(" +
            "CAST(:productId AS text) || ':' || COALESCE(CAST(:specId AS text), '<default>'), 0))",
            nativeQuery = true)
    void lockImportPool(@Param("productId") UUID productId, @Param("specId") UUID specId);

    @Query(value = "SELECT * FROM card_keys WHERE product_id = :productId " +
            "AND ((:specId IS NULL AND spec_id IS NULL) OR spec_id = CAST(:specId AS uuid)) " +
            "AND status = 'AVAILABLE' ORDER BY created_at ASC LIMIT :count " +
            "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<CardKey> findAndLockAvailable(@Param("productId") UUID productId,
                                       @Param("specId") UUID specId,
                                       @Param("count") int count);

    /** Locks exactly the requested CardKey for the administrator single-pickup path. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ck FROM CardKey ck WHERE ck.id = :id")
    java.util.Optional<CardKey> findByIdForUpdate(@Param("id") UUID id);

    /** Locks every key currently bound to an order while its order is held. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ck FROM CardKey ck WHERE ck.orderId = :orderId ORDER BY ck.createdAt ASC")
    List<CardKey> findByOrderIdForUpdate(@Param("orderId") UUID orderId);

    long countByProductIdAndStatus(UUID productId, CardKeyStatus status);

    long countByStatus(CardKeyStatus status);

    @Query(value = "SELECT COUNT(ck.id) FROM card_keys ck "
            + "JOIN products p ON p.id = ck.product_id "
            + "WHERE ck.status = 'AVAILABLE' AND p.stock_mode = 'CARD_KEY' "
            + "AND p.is_deleted = 0", nativeQuery = true)
    long countAvailableForActiveCardKeyProducts();

    long countByProductIdAndSpecIdAndStatus(UUID productId, UUID specId, CardKeyStatus status);

    long countByProductIdAndSpecIdIsNullAndStatus(UUID productId, CardKeyStatus status);

    @Query(value = "SELECT product_id, spec_id, COUNT(*) "
            + "FROM card_keys WHERE product_id IN (:productIds) AND status = 'AVAILABLE' "
            + "GROUP BY product_id, spec_id", nativeQuery = true)
    List<Object[]> countAvailableByProductIds(@Param("productIds") List<UUID> productIds);

    List<CardKey> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    @Query(value = "SELECT content_fingerprint FROM card_keys WHERE product_id = :productId "
            + "AND ((:specId IS NULL AND spec_id IS NULL) OR spec_id = CAST(:specId AS uuid)) "
            + "AND content_fingerprint IN (:fingerprints)", nativeQuery = true)
    List<String> findExistingFingerprints(@Param("productId") UUID productId,
                                          @Param("specId") UUID specId,
                                          @Param("fingerprints") List<String> fingerprints);

    @Query("SELECT COUNT(ck) FROM CardKey ck WHERE ck.productId = :productId " +
            "AND ((:specId IS NULL AND ck.specId IS NULL) OR ck.specId = :specId) " +
            "AND ck.status <> :excludeStatus")
    long countByProductIdAndSpecIdExcludingStatus(@Param("productId") UUID productId,
                                                   @Param("specId") UUID specId,
                                                   @Param("excludeStatus") CardKeyStatus excludeStatus);

    @Query("SELECT ck FROM CardKey ck WHERE ck.productId = :productId " +
            "AND ((:specId IS NULL AND ck.specId IS NULL) OR ck.specId = :specId) " +
            "ORDER BY ck.createdAt DESC")
    Page<CardKey> findByProductIdAndOptionalSpecId(@Param("productId") UUID productId,
                                                    @Param("specId") UUID specId,
                                                    Pageable pageable);

    @Query("SELECT ck.status, COUNT(ck) FROM CardKey ck " +
            "WHERE ck.productId = :productId AND ((:specId IS NULL AND ck.specId IS NULL) OR ck.specId = :specId) " +
            "GROUP BY ck.status")
    List<Object[]> countByProductIdAndSpecIdGroupByStatus(@Param("productId") UUID productId,
                                                          @Param("specId") UUID specId);

    @Modifying
    @Query("UPDATE CardKey ck SET ck.status = :newStatus " +
            "WHERE ck.productId = :productId " +
            "AND ((:specId IS NULL AND ck.specId IS NULL) OR ck.specId = :specId) " +
            "AND ck.status = :oldStatus")
    int updateStatusByProductIdAndSpecId(@Param("productId") UUID productId,
                                         @Param("specId") UUID specId,
                                         @Param("oldStatus") CardKeyStatus oldStatus,
                                         @Param("newStatus") CardKeyStatus newStatus);
}
