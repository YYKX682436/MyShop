package com.myshop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.AccountSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.ListingSummary;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountCapabilities;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountsData;
import com.myshop.saleschannel.model.SalesChannelDtos.CapabilityData;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.xianyu.model.XianyuProviderConfig;
import com.myshop.service.impl.SalesChannelAccountServiceImpl;
import com.myshop.service.impl.SalesChannelListingServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin-only orchestration. Agent credentials and session data never cross this API boundary. */
@Service
@RequiredArgsConstructor
public class SalesChannelAdminService {

    private final SalesChannelProviderClient providerClient;
    private final SalesChannelAccountServiceImpl accountService;
    private final SalesChannelListingServiceImpl listingService;
    private final SalesChannelDesiredStatePlanner planner;
    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelListingRepository listingRepository;
    private final ObjectMapper objectMapper;

    public List<AccountSummary> accounts() {
        return accountRepository.findAll().stream().filter(account -> account.getProvider() == SalesChannelProvider.XIANYU)
                .map(this::accountSummary).toList();
    }

    public List<AccountSummary> syncXianyuAccounts() {
        IntegrationEnvelope<AccountsData> response = providerClient.listAccounts();
        requireSuccess(response.outcome(), response.error() == null ? null : response.error().code());
        List<com.myshop.saleschannel.model.SalesChannelDtos.SalesChannelAccount> remote =
                response.data() == null || response.data().accounts() == null
                        ? List.of() : response.data().accounts();
        for (var account : remote) {
            if (blank(account.providerAccountId())) continue;
            Map<String, Object> capabilities = account.capabilities() == null
                    ? Map.of() : objectMapper.convertValue(account.capabilities(), new TypeReference<>() { });
            SalesChannelAccountStatus status = mapStatus(account.status(), account.enabled());
            accountService.upsert(SalesChannelProvider.XIANYU, account.providerAccountId(),
                    account.displayName(), status, capabilities, null);
        }
        return accounts();
    }

    public AccountSummary verifyAccount(UUID accountId) {
        SalesChannelAccount account = findAccount(accountId);
        IntegrationEnvelope<CapabilityData> response = providerClient.verifyAccount(account.getProviderAccountId());
        requireSuccess(response.outcome(), response.error() == null ? null : response.error().code());
        if (response.data() == null || response.data().capabilities() == null) {
            throw new IllegalStateException("Xianyu Agent returned no verified account capabilities");
        }
        Map<String, Object> capabilities = objectMapper.convertValue(response.data().capabilities(),
                new TypeReference<>() { });
        SalesChannelAccount updated = accountService.upsert(account.getProvider(), account.getProviderAccountId(),
                account.getDisplayName(), SalesChannelAccountStatus.CONNECTED, capabilities, LocalDateTime.now());
        return accountSummary(updated);
    }

    public List<ListingSummary> productListings(UUID productId) {
        return listingRepository.findByProductIdOrderByCreatedAtAsc(productId).stream()
                .map(this::listingSummary).toList();
    }

    public ListingSummary updateXianyuListing(UUID productId, UUID accountId, XianyuProviderConfig config) {
        SalesChannelAccount account = findAccount(accountId);
        if (account.getProvider() != SalesChannelProvider.XIANYU) {
            throw new IllegalArgumentException("The selected account is not a Xianyu account");
        }
        SalesChannelListing listing = listingService.ensureDraft(accountId, productId);
        planner.saveConfig(listing.getId(), config);
        return listingSummary(planner.refresh(listing.getId()));
    }

    public ListingSummary enableListing(UUID listingId) {
        return listingSummary(planner.setEnabled(listingId, true));
    }

    public ListingSummary disableListing(UUID listingId) {
        return listingSummary(planner.setEnabled(listingId, false));
    }

    public ListingSummary retryListing(UUID listingId) {
        planner.retry(listingId);
        SalesChannelListing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel listing does not exist"));
        return listingSummary(listing);
    }

    private AccountSummary accountSummary(SalesChannelAccount account) {
        return new AccountSummary(account.getId().toString(), account.getProviderAccountId(),
                account.getDisplayName(), accountType(account), account.getStatus().name(), account.isEnabled(),
                account.getCapabilities(), account.getLastVerifiedAt());
    }

    private ListingSummary listingSummary(SalesChannelListing listing) {
        SalesChannelAccount account = findAccount(listing.getSalesChannelAccountId());
        XianyuProviderConfig config = objectMapper.convertValue(listing.getProviderConfig(), XianyuProviderConfig.class);
        return new ListingSummary(listing.getId().toString(), account.getProviderAccountId(),
                account.getDisplayName(), accountType(account), listing.isEnabled(), listing.getStatus().name(),
                listing.getExternalItemId(), listing.getDesiredRevision(), listing.getLastSyncedAt(),
                listing.getLastErrorCode(), listing.getLastErrorMessage(), config);
    }

    private SalesChannelAccount findAccount(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel account does not exist"));
    }

    private String accountType(SalesChannelAccount account) {
        Object type = account.getCapabilities().get("account_type");
        return type == null ? "UNKNOWN" : type.toString().toUpperCase();
    }

    private SalesChannelAccountStatus mapStatus(String status, Boolean enabled) {
        if (Boolean.FALSE.equals(enabled)) return SalesChannelAccountStatus.DISABLED;
        String normalized = status == null ? "" : status.trim().toLowerCase();
        return switch (normalized) {
            case "active", "connected", "online", "ready", "verified" -> SalesChannelAccountStatus.CONNECTED;
            case "auth_required", "login_required", "expired", "logged_out" -> SalesChannelAccountStatus.AUTH_REQUIRED;
            case "suspended", "blocked", "banned" -> SalesChannelAccountStatus.SUSPENDED;
            case "disabled" -> SalesChannelAccountStatus.DISABLED;
            default -> SalesChannelAccountStatus.ERROR;
        };
    }

    private void requireSuccess(Outcome outcome, String code) {
        if (outcome != Outcome.SUCCEEDED) {
            throw new IllegalStateException("Xianyu Agent admin request failed" + (blank(code) ? "" : ": " + code));
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
