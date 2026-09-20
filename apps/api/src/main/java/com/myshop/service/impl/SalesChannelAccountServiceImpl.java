package com.myshop.service.impl;

import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.repository.SalesChannelAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SalesChannelAccountServiceImpl {

    private final SalesChannelAccountRepository accountRepository;

    @Transactional
    public SalesChannelAccount upsert(SalesChannelProvider provider, String providerAccountId,
                                      String displayName, SalesChannelAccountStatus status,
                                      Map<String, Object> capabilities, LocalDateTime lastVerifiedAt) {
        Objects.requireNonNull(provider, "provider is required");
        Objects.requireNonNull(status, "status is required");
        if (providerAccountId == null || providerAccountId.isBlank() || providerAccountId.length() > 128) {
            throw new IllegalArgumentException("providerAccountId must contain 1 to 128 characters");
        }

        accountRepository.insertIdentityIfAbsent(
                provider.name(), providerAccountId, displayName, status.name());
        SalesChannelAccount account = accountRepository
                .findByProviderAndProviderAccountIdForUpdate(provider, providerAccountId)
                .orElseThrow(() -> new IllegalStateException("Sales channel account upsert did not produce a row"));
        account.setProvider(provider);
        account.setProviderAccountId(providerAccountId);
        account.setDisplayName(displayName);
        account.setStatus(status);
        account.setCapabilities(capabilities);
        account.setLastVerifiedAt(lastVerifiedAt);
        return accountRepository.save(account);
    }

    @Transactional
    public SalesChannelAccount setEnabled(UUID accountId, boolean enabled) {
        SalesChannelAccount account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel account does not exist"));
        account.setEnabled(enabled);
        return account;
    }
}
