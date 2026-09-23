package com.myshop.service.impl;

import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.repository.SalesChannelAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelAccountServiceImplTest {

    @Mock private SalesChannelAccountRepository accountRepository;
    @InjectMocks private SalesChannelAccountServiceImpl service;

    @Test
    void upsertUpdatesTheExistingAccountAndDoesNotReenableItOrPersistNullCapabilities() {
        UUID id = UUID.randomUUID();
        SalesChannelAccount existing = new SalesChannelAccount();
        existing.setId(id);
        existing.setEnabled(false);
        existing.setProvider(SalesChannelProvider.XIANYU);
        existing.setProviderAccountId("stable-account-id");
        when(accountRepository.findByProviderAndProviderAccountIdForUpdate(
                SalesChannelProvider.XIANYU, "stable-account-id")).thenReturn(Optional.of(existing));
        when(accountRepository.save(any(SalesChannelAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.insertIdentityIfAbsent("XIANYU", "stable-account-id", "renamed", "AUTH_REQUIRED"))
                .thenReturn(0);

        SalesChannelAccount result = service.upsert(SalesChannelProvider.XIANYU, "stable-account-id", "renamed",
                SalesChannelAccountStatus.AUTH_REQUIRED, null, null);

        assertEquals(id, result.getId());
        assertEquals("renamed", result.getDisplayName());
        assertEquals(SalesChannelAccountStatus.AUTH_REQUIRED, result.getStatus());
        assertFalse(result.isEnabled());
        assertEquals(Map.of(), result.getCapabilities());
        verify(accountRepository).save(existing);
    }

    @Test
    void upsertStoresProviderLoginTimeAndKeepsItWhenLaterSyncHasNoTimestamp() {
        SalesChannelAccount existing = new SalesChannelAccount();
        existing.setProvider(SalesChannelProvider.XIANYU);
        existing.setProviderAccountId("login-time-account");
        LocalDateTime loggedInAt = LocalDateTime.of(2026, 9, 23, 12, 30);
        when(accountRepository.findByProviderAndProviderAccountIdForUpdate(
                SalesChannelProvider.XIANYU, "login-time-account")).thenReturn(Optional.of(existing));
        when(accountRepository.insertIdentityIfAbsent("XIANYU", "login-time-account", "Shop", "CONNECTED"))
                .thenReturn(0);
        when(accountRepository.save(any(SalesChannelAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SalesChannelAccount first = service.upsert(SalesChannelProvider.XIANYU, "login-time-account", "Shop",
                SalesChannelAccountStatus.CONNECTED, Map.of(), loggedInAt, null);
        SalesChannelAccount second = service.upsert(SalesChannelProvider.XIANYU, "login-time-account", "Shop",
                SalesChannelAccountStatus.CONNECTED, Map.of(), null, null);

        assertEquals(loggedInAt, first.getLastLoginAt());
        assertEquals(loggedInAt, second.getLastLoginAt());
        assertNull(second.getLastVerifiedAt());
    }
}
