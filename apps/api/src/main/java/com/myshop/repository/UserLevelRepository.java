package com.myshop.repository;

import com.myshop.entity.UserLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserLevelRepository extends JpaRepository<UserLevel, UUID> {

    @Query("SELECT level FROM UserLevel level ORDER BY level.sortOrder ASC, level.createdAt ASC, level.id ASC")
    List<UserLevel> findAllOrdered();

    @Query("SELECT level FROM UserLevel level WHERE lower(trim(level.name)) = lower(trim(:name))")
    Optional<UserLevel> findByNameIgnoreCase(@Param("name") String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT level FROM UserLevel level WHERE level.id = :id")
    Optional<UserLevel> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT level FROM UserLevel level WHERE level.isDefault = true AND level.enabled = true")
    Optional<UserLevel> findActiveDefault();
}
