package com.myshop.repository;

import com.myshop.entity.ManualStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ManualStockRepository extends JpaRepository<ManualStock, UUID> {

    @Query("SELECT m FROM ManualStock m WHERE m.productId = :productId "
            + "AND ((:specId IS NULL AND m.specId IS NULL) OR m.specId = :specId)")
    Optional<ManualStock> findPool(@Param("productId") UUID productId, @Param("specId") UUID specId);

    /** PostgreSQL upsert is idempotent and also closes the first-use race. */
    @Modifying
    @Query(value = "INSERT INTO manual_stocks "
            + "(id, created_at, updated_at, product_id, spec_id, available_quantity, reserved_quantity) "
            + "VALUES (:id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, :productId, :specId, 0, 0) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int ensurePool(@Param("id") UUID id, @Param("productId") UUID productId, @Param("specId") UUID specId);

    @Modifying
    @Query(value = "UPDATE manual_stocks SET available_quantity = available_quantity - :quantity, "
            + "reserved_quantity = reserved_quantity + :quantity, updated_at = CURRENT_TIMESTAMP "
            + "WHERE product_id = :productId "
            + "AND ((:specId IS NULL AND spec_id IS NULL) OR spec_id = :specId) "
            + "AND available_quantity >= :quantity", nativeQuery = true)
    int reserve(@Param("productId") UUID productId, @Param("specId") UUID specId,
                @Param("quantity") int quantity);

    @Modifying
    @Query(value = "UPDATE manual_stocks SET available_quantity = available_quantity + :quantity, "
            + "reserved_quantity = reserved_quantity - :quantity, updated_at = CURRENT_TIMESTAMP "
            + "WHERE product_id = :productId "
            + "AND ((:specId IS NULL AND spec_id IS NULL) OR spec_id = :specId) "
            + "AND reserved_quantity >= :quantity", nativeQuery = true)
    int release(@Param("productId") UUID productId, @Param("specId") UUID specId,
                @Param("quantity") int quantity);

    @Modifying
    @Query(value = "UPDATE manual_stocks SET reserved_quantity = reserved_quantity - :quantity, "
            + "updated_at = CURRENT_TIMESTAMP WHERE product_id = :productId "
            + "AND ((:specId IS NULL AND spec_id IS NULL) OR spec_id = :specId) "
            + "AND reserved_quantity >= :quantity", nativeQuery = true)
    int consumeReservation(@Param("productId") UUID productId, @Param("specId") UUID specId,
                           @Param("quantity") int quantity);

    @Modifying
    @Query(value = "UPDATE manual_stocks SET available_quantity = :availableQuantity, "
            + "updated_at = CURRENT_TIMESTAMP WHERE product_id = :productId "
            + "AND ((:specId IS NULL AND spec_id IS NULL) OR spec_id = :specId)", nativeQuery = true)
    int setAvailable(@Param("productId") UUID productId, @Param("specId") UUID specId,
                     @Param("availableQuantity") int availableQuantity);
}
