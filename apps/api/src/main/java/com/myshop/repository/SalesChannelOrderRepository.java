package com.myshop.repository;

import com.myshop.constant.SalesChannelOrderDeliveryStatus;
import com.myshop.constant.SalesChannelOrderIngestStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalesChannelOrderRepository extends JpaRepository<SalesChannelOrder, UUID> {
    Optional<SalesChannelOrder> findByProviderAndSalesChannelAccountIdAndExternalOrderNo(
            SalesChannelProvider provider, UUID accountId, String externalOrderNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT row FROM SalesChannelOrder row WHERE row.id = :id")
    Optional<SalesChannelOrder> findByIdForUpdate(@Param("id") UUID id);

    List<SalesChannelOrder> findByDeliveryStatusIn(Collection<SalesChannelOrderDeliveryStatus> statuses);

    List<SalesChannelOrder> findByIngestStatusIn(Collection<SalesChannelOrderIngestStatus> statuses);
}
