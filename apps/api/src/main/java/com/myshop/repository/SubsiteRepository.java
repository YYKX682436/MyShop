package com.myshop.repository;

import com.myshop.entity.Subsite;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SubsiteRepository extends JpaRepository<Subsite, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Subsite s where s.id = :id")
    Optional<Subsite> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    Optional<Subsite> findByPrefix(String prefix);
    Optional<Subsite> findByOwnerId(UUID ownerId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Subsite s where s.ownerId = :ownerId")
    Optional<Subsite> findByOwnerIdForUpdate(@org.springframework.data.repository.query.Param("ownerId") UUID ownerId);
    boolean existsByOwnerId(UUID ownerId);
    boolean existsByPrefix(String prefix);
}
