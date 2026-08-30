package com.myshop.repository;

import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.List;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByRoleAndUserNo(UserRole role, Long userNo);

    long countByRole(UserRole role);

    @Query("SELECT MAX(u.userNo) FROM User u WHERE u.role = :role")
    Long findMaxUserNoByRole(@Param("role") UserRole role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    long countByRoleAndIsDeleted(UserRole role, int isDeleted);

    long countByRoleAndIsDeletedAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            UserRole role, int isDeleted, LocalDateTime start, LocalDateTime end);

    List<User> findTop5ByRoleAndIsDeletedOrderByCreatedAtDesc(UserRole role, int isDeleted);

    @Query(value = """
            SELECT u.*
            FROM users u
            WHERE u.role = 'USER'
              AND (CAST(:isDeleted AS integer) IS NULL OR u.is_deleted = CAST(:isDeleted AS integer))
              AND (
                  CAST(:keyword AS text) IS NULL
                  OR u.username ILIKE '%' || CAST(:keyword AS text) || '%'
                  OR u.email ILIKE '%' || CAST(:keyword AS text) || '%'
                  OR CAST(u.user_no AS text) ILIKE '%' || CAST(:keyword AS text) || '%'
              )
            ORDER BY u.created_at DESC, u.id DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM users u
            WHERE u.role = 'USER'
              AND (CAST(:isDeleted AS integer) IS NULL OR u.is_deleted = CAST(:isDeleted AS integer))
              AND (
                  CAST(:keyword AS text) IS NULL
                  OR u.username ILIKE '%' || CAST(:keyword AS text) || '%'
                  OR u.email ILIKE '%' || CAST(:keyword AS text) || '%'
                  OR CAST(u.user_no AS text) ILIKE '%' || CAST(:keyword AS text) || '%'
              )
            """,
            nativeQuery = true)
    Page<User> findAdminUsers(@Param("keyword") String keyword,
                               @Param("isDeleted") Integer isDeleted,
                               Pageable pageable);

    @Query(value = """
            SELECT u.*
            FROM users u
            WHERE u.role IN ('ADMIN', 'SUPER_ADMIN')
              AND (CAST(:isDeleted AS integer) IS NULL OR u.is_deleted = CAST(:isDeleted AS integer))
              AND (
                  CAST(:keyword AS text) IS NULL
                  OR u.username ILIKE '%' || CAST(:keyword AS text) || '%'
                  OR u.email ILIKE '%' || CAST(:keyword AS text) || '%'
                  OR u.phone ILIKE '%' || CAST(:keyword AS text) || '%'
              )
            ORDER BY u.created_at DESC, u.id DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM users u
            WHERE u.role IN ('ADMIN', 'SUPER_ADMIN')
              AND (CAST(:isDeleted AS integer) IS NULL OR u.is_deleted = CAST(:isDeleted AS integer))
              AND (
                  CAST(:keyword AS text) IS NULL
                  OR u.username ILIKE '%' || CAST(:keyword AS text) || '%'
                  OR u.email ILIKE '%' || CAST(:keyword AS text) || '%'
                  OR u.phone ILIKE '%' || CAST(:keyword AS text) || '%'
              )
            """,
            nativeQuery = true)
    Page<User> findAdministrators(@Param("keyword") String keyword,
                                   @Param("isDeleted") Integer isDeleted,
                                   Pageable pageable);
}
