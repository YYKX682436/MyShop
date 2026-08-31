package com.myshop.repository;

import com.myshop.entity.UserLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserLevelRepository extends JpaRepository<UserLevel, UUID> {

    @Query("SELECT level FROM UserLevel level WHERE level.isDefault = true AND level.enabled = true")
    Optional<UserLevel> findActiveDefault();
}
