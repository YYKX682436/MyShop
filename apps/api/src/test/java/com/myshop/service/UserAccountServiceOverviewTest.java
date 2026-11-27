package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.myshop.constant.PricingType;
import com.myshop.constant.UserRole;
import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Subsite;
import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.myshop.repository.AccountPaymentTransactionRepository;
import com.myshop.repository.BalanceTransactionRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.SubsiteRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAccountServiceOverviewTest {
    @Test
    void higherPurchasableLevelIsNotHighestAndRemainsAvailable() {
        var current = level("Member", 0, true, false, new BigDecimal("0.00"));
        var higher = level("Partner", 100, true, true, new BigDecimal("30.00"));
        var fixture = fixture(current, higher);

        var overview = fixture.service.overview(fixture.user.getId());

        assertTrue(overview.hasHigherLevel());
        assertFalse(overview.isHighestLevel());
        assertTrue(overview.upgradeAvailable());
        assertEquals(List.of(higher.getId()), overview.upgrades().stream()
                .map(UserAccountService.UpgradeOption::levelId).toList());
    }

    @Test
    void higherEnabledLevelWithoutSelfUpgradeDoesNotCountAsHighest() {
        var current = level("Member", 0, true, false, new BigDecimal("0.00"));
        var higher = level("Partner", 100, true, false, null);
        var fixture = fixture(current, higher);

        var overview = fixture.service.overview(fixture.user.getId());

        assertTrue(overview.hasHigherLevel());
        assertFalse(overview.isHighestLevel());
        assertFalse(overview.upgradeAvailable());
    }

    @Test
    void validLevelWithNoHigherEnabledLevelIsHighest() {
        var current = level("Partner", 100, true, false, null);
        var fixture = fixture(current);

        var overview = fixture.service.overview(fixture.user.getId());

        assertFalse(overview.hasHigherLevel());
        assertTrue(overview.isHighestLevel());
        assertFalse(overview.upgradeAvailable());
        assertTrue(new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .valueToTree(overview).has("is_highest_level"));
    }

    @Test
    void administratorAndUserWithDisabledLevelAreNeverHighest() {
        var current = level("Disabled", 0, false, false, null);
        current.setEnabled(false);
        var fixture = fixture(current);

        var ordinaryOverview = fixture.service.overview(fixture.user.getId());
        assertNull(ordinaryOverview.currentLevelName());
        assertFalse(ordinaryOverview.isHighestLevel());

        var admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setRole(UserRole.SUPER_ADMIN);
        admin.setIsDeleted(0);
        when(fixture.users.findById(admin.getId())).thenReturn(Optional.of(admin));

        var adminOverview = fixture.service.overview(admin.getId());
        assertTrue(adminOverview.admin());
        assertFalse(adminOverview.isHighestLevel());
    }

    @Test
    void subsiteOwnerIsNotHighestInTheirOwnStorefront() {
        var current = level("Member", 0, true, false, new BigDecimal("0.00"));
        var fixture = fixture(current);
        var site = new Subsite();
        site.setId(UUID.randomUUID());
        site.setOwnerId(fixture.user.getId());

        SiteRequestContext.set(site);
        try {
            var overview = fixture.service.overview(fixture.user.getId());
            assertFalse(overview.hasHigherLevel());
            assertFalse(overview.isHighestLevel());
            assertFalse(overview.upgradeAvailable());
        } finally {
            SiteRequestContext.clear();
        }
    }

    private Fixture fixture(UserLevel current, UserLevel... higherLevels) {
        var users = mock(UserRepository.class);
        var levels = mock(UserLevelRepository.class);
        var configs = mock(SiteConfigRepository.class);
        var upgradePolicy = mock(AccountUpgradePolicy.class);
        var user = new User();
        user.setId(UUID.randomUUID());
        user.setRole(UserRole.USER);
        user.setIsDeleted(0);
        user.setUserLevelId(current.getId());
        user.setBalance(new BigDecimal("0.00"));

        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(levels.findById(current.getId())).thenReturn(Optional.of(current));
        when(levels.findAllOrdered()).thenReturn(List.of(higherLevels).isEmpty()
                ? List.of(current)
                : java.util.stream.Stream.concat(java.util.stream.Stream.of(current), List.of(higherLevels).stream()).toList());
        when(configs.findByConfigKey(anyString())).thenReturn(Optional.empty());
        when(upgradePolicy.price(null, current)).thenReturn(current.getUpgradePrice());
        for (UserLevel higher : higherLevels) {
            when(upgradePolicy.selfUpgradeEnabled(null, higher)).thenReturn(higher.isSelfUpgradeEnabled());
            when(upgradePolicy.price(null, higher)).thenReturn(higher.getUpgradePrice());
            when(upgradePolicy.publicPriceEnabled(null, higher)).thenReturn(higher.isPublicPriceEnabled());
        }

        var service = new UserAccountService(
                users,
                levels,
                mock(AccountPaymentTransactionRepository.class),
                mock(PaymentAttemptRepository.class),
                mock(BalanceTransactionRepository.class),
                mock(ProductRepository.class),
                mock(ProductSpecRepository.class),
                configs,
                mock(SubsiteRepository.class),
                mock(PricingService.class),
                mock(SubsitePricingService.class),
                upgradePolicy,
                mock(AccountPaymentCompletionService.class),
                mock(PaymentService.class),
                new ObjectMapper());
        return new Fixture(service, users, user);
    }

    private UserLevel level(String name, int rank, boolean enabled, boolean selfUpgrade, BigDecimal price) {
        var level = new UserLevel();
        level.setId(UUID.randomUUID());
        level.setName(name);
        level.setEnabled(enabled);
        level.setUpgradeRank(rank);
        level.setPricingType(PricingType.RETAIL);
        level.setSelfUpgradeEnabled(selfUpgrade);
        level.setUpgradePrice(price);
        level.setPublicPriceEnabled(selfUpgrade && price != null);
        return level;
    }

    private record Fixture(UserAccountService service, UserRepository users, User user) { }
}
