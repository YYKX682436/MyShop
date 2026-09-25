package com.myshop.service;

import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelOrderCursor;
import com.myshop.exception.UnmanagedSalesChannelItemException;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelOrderCursorRepository;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.model.SalesChannelDtos.AgentOrder;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.OrderChangesCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.OrderChangesData;
import com.myshop.saleschannel.model.SalesChannelDtos.OrderCursor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Disabled-by-default durable order changes consumer. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sales-channel.order-sync", name = "enabled", havingValue = "true")
public class SalesChannelOrderSyncWorker {

    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelOrderCursorRepository cursorRepository;
    private final SalesChannelProviderClient providerClient;
    private final SalesChannelOrderImportService importService;

    @Value("${sales-channel.order-sync.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${sales-channel.order-sync.interval-ms:5000}")
    public void poll() {
        for (SalesChannelAccount account : accountRepository
                .findByProviderAndEnabledTrueAndOrderSyncEnabledTrue(SalesChannelProvider.XIANYU)) {
            try {
                syncAccount(account);
            } catch (RuntimeException exception) {
                // A read-only transport failure is safe to retry on the next
                // tick. Never move a cursor without a complete successful page.
                log.warn("Sales-channel order sync deferred for account {}", account.getId());
            }
        }
    }

    private void syncAccount(SalesChannelAccount account) {
        SalesChannelOrderCursor cursor = cursorRepository
                .findByProviderAndSalesChannelAccountId(account.getProvider(), account.getId())
                .orElse(null);
        if (cursor == null || cursor.getCursorUpdatedAt() == null || cursor.getCursorId() == null) {
            return;
        }
        OrderCursor current = new OrderCursor(
                cursor.getCursorUpdatedAt().atOffset(ZoneOffset.UTC).toString(), cursor.getCursorId());
        boolean hasMore;
        int pages = 0;
        do {
            String requestId = "XIANYU_ORDER_CHANGES:" + account.getId() + ":" + current.updatedAt() + ":" + current.id();
            IntegrationEnvelope<OrderChangesData> response = providerClient.getOrderChanges(
                    new OrderChangesCommand(requestId, List.of(account.getProviderAccountId()), current, Math.max(1, Math.min(batchSize, 100))));
            if (response == null || response.outcome() != com.myshop.saleschannel.model.SalesChannelDtos.Outcome.SUCCEEDED
                    || response.data() == null) {
                return;
            }
            OrderChangesData data = response.data();
            for (AgentOrder order : data.orders() == null ? List.<AgentOrder>of() : data.orders()) {
                try {
                    importService.importOrder(account, order);
                } catch (UnmanagedSalesChannelItemException ignored) {
                    log.info("sales_channel_order_ignored_unmanaged_item accountId={} externalItemId={} externalOrderNo={} reason=LINKED_ONLY",
                            ignored.getAccountId(), ignored.getExternalItemId(), ignored.getExternalOrderNo());
                } catch (RuntimeException exception) {
                    importService.persistImportFailure(account, order, "IMPORT_ERROR", "External order could not be persisted safely");
                }
            }
            OrderCursor next = data.nextCursor();
            hasMore = Boolean.TRUE.equals(data.hasMore()) && next != null;
            if (next == null) return;
            advance(account, next);
            current = next;
            pages++;
        } while (hasMore && pages < 10);
    }

    protected void advance(SalesChannelAccount account, OrderCursor next) {
        SalesChannelOrderCursor cursor = cursorRepository
                .findByProviderAndSalesChannelAccountId(account.getProvider(), account.getId()).orElse(null);
        if (cursor == null) return;
        cursor.setCursorUpdatedAt(parseTime(next.updatedAt()));
        cursor.setCursorId(next.id());
        cursorRepository.save(cursor);
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        return OffsetDateTime.parse(value.replace("Z", "+00:00")).toLocalDateTime();
    }
}
