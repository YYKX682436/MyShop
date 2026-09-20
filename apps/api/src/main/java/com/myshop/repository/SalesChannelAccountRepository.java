package com.myshop.repository;

import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface SalesChannelAccountRepository extends JpaRepository<SalesChannelAccount, UUID> {

    Optional<SalesChannelAccount> findByProviderAndProviderAccountId(
            SalesChannelProvider provider, String providerAccountId);

    @Modifying
    @Query(value = "INSERT INTO sales_channel_accounts(provider, provider_account_id, display_name, status) "
            + "VALUES (:provider, :providerAccountId, :displayName, :status) "
            + "ON CONFLICT (provider, provider_account_id) DO NOTHING", nativeQuery = true)
    int insertIdentityIfAbsent(@Param("provider") String provider,
                               @Param("providerAccountId") String providerAccountId,
                               @Param("displayName") String displayName,
                               @Param("status") String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT account FROM SalesChannelAccount account "
            + "WHERE account.provider = :provider AND account.providerAccountId = :providerAccountId")
    Optional<SalesChannelAccount> findByProviderAndProviderAccountIdForUpdate(
            @Param("provider") SalesChannelProvider provider,
            @Param("providerAccountId") String providerAccountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT account FROM SalesChannelAccount account WHERE account.id = :id")
    Optional<SalesChannelAccount> findByIdForUpdate(@Param("id") UUID id);
}
