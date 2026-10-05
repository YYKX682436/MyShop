package com.myshop.saleschannel;

import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.service.SalesChannelDesiredStatePlanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.myshop.constant.SalesChannelConvergenceCutoverState;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sales-channel.worker", name = "enabled", havingValue = "true")
public class SalesChannelDriftScanner {

    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelDesiredStatePlanner planner;

    @Scheduled(fixedDelayString = "${sales-channel.sync.scan-interval-ms:15000}")
    public void scanEnabledListings() {
        for (var listing : listingRepository.findByEnabledTrueAndConvergenceCutoverStateOrderByUpdatedAtAsc(
                SalesChannelConvergenceCutoverState.ACTIVE)) {
            try {
                planner.refresh(listing.getId());
            } catch (RuntimeException exception) {
                log.warn("Sales channel drift scan could not plan listing {}: {}",
                        listing.getId(), safeMessage(exception));
            }
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName()
                : message.replaceAll("(?i)(secret|token|cookie|password)=[^\\s,;]+", "$1=[redacted]");
    }
}
