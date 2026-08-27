package com.myshop.repository;

import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.List;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByUsernameOrEmail(String username, String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    long countByRoleAndIsDeleted(UserRole role, int isDeleted);

    long countByRoleAndIsDeletedAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            UserRole role, int isDeleted, LocalDateTime start, LocalDateTime end);

    List<User> findTop5ByRoleAndIsDeletedOrderByCreatedAtDesc(UserRole role, int isDeleted);

    Page<User> findByUsernameContainingOrEmailContaining(String username, String email, Pageable pageable);
}
