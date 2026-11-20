package com.myshop.repository;

import com.myshop.entity.SupplierAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface SupplierAccountRepository extends JpaRepository<SupplierAccount, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SupplierAccount a where a.id = :id")
    Optional<SupplierAccount> findForUpdate(@Param("id") UUID id);
}
