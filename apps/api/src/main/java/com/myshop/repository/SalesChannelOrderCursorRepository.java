package com.myshop.repository;

import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelOrderCursor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SalesChannelOrderCursorRepository extends JpaRepository<SalesChannelOrderCursor, UUID> {
    Optional<SalesChannelOrderCursor> findByProviderAndSalesChannelAccountId(
            SalesChannelProvider provider, UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT row FROM SalesChannelOrderCursor row "
            + "WHERE row.provider = :provider AND row.salesChannelAccountId = :accountId")
    Optional<SalesChannelOrderCursor> findForUpdate(@Param("provider") SalesChannelProvider provider,
                                                    @Param("accountId") UUID accountId);
}
