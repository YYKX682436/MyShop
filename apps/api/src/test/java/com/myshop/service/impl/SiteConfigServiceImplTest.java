package com.myshop.service.impl;

import com.myshop.entity.SiteConfig;
import com.myshop.entity.UserNumberSequence;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.UserNumberConfigUpdateRequest;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserNumberSequenceRepository;
import com.myshop.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteConfigServiceImplTest {

    @Mock
    private SiteConfigRepository repository;

    @Mock
    private UserNumberSequenceRepository userNumberSequenceRepository;

    @Mock
    private UserRepository userRepository;

    private SiteConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SiteConfigServiceImpl(repository, userNumberSequenceRepository, userRepository);
    }

    @Test
    void siteBrandUsesOnlyTheCurrentSiteAndNeverInheritsMainFavicon() {
        when(repository.findByConfigKey(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "site_name" -> Optional.of(configValue("主站"));
            case "favicon_url" -> Optional.of(configValue("/api/uploads/main.png"));
            default -> Optional.empty();
        });
        assertEquals("主站", service.getPublicConfig().get("site_name"));
        assertEquals("/api/uploads/main.png", service.getPublicConfig().get("favicon_url"));
        var site = new com.myshop.entity.Subsite();
        site.setName("分站");
        com.myshop.context.SiteRequestContext.set(site);
        try {
            assertEquals("分站", service.getPublicConfig().get("site_name"));
            assertEquals(null, service.getPublicConfig().get("favicon_url"));
            site.setFaviconUrl("/api/uploads/subsite.png");
            assertEquals("/api/uploads/subsite.png", service.getPublicConfig().get("favicon_url"));
            var other = new com.myshop.entity.Subsite();
            other.setName("另一个分站"); other.setFaviconUrl("/api/uploads/other.png");
            com.myshop.context.SiteRequestContext.set(other);
            assertEquals("另一个分站", service.getPublicConfig().get("site_name"));
            assertEquals("/api/uploads/other.png", service.getPublicConfig().get("favicon_url"));
            assertFalse(service.getPublicConfig().containsKey("main_site_has_contact"));
        } finally { com.myshop.context.SiteRequestContext.clear(); }
    }

    @Test
    void retiredBrandAndContactKeysCannotBeUpdated() {
        service.updateConfigs(List.of(config("logo_url", "/old.png"), config("contact_items", "[]"), config("site_slogan", "old")));
        verify(repository, never()).save(any(SiteConfig.class));
    }

    @Test
    void generalSiteSettingsRejectInvalidSharedRiskValues() {
        assertThrows(BusinessException.class,
                () -> service.updateConfigs(List.of(config("rate_limit_per_second", "0"))));
        assertThrows(BusinessException.class,
                () -> service.updateConfigs(List.of(config("order_expire_minutes", "-1"))));
        assertThrows(BusinessException.class,
                () -> service.updateConfigs(List.of(config("order_expire_minutes", "many"))));
        verify(repository, never()).save(any(SiteConfig.class));
    }

    @Test
    void loginDurationOnlyAcceptsTheFourSupportedSlidingIntervals() {
        when(repository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(SiteConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        for (String days : List.of("15", "30", "90", "365")) {
            service.updateConfigs(List.of(config("login_duration_days", days)));
            verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(saved ->
                    "login_duration_days".equals(saved.getConfigKey()) && days.equals(saved.getConfigValue())));
        }

        assertThrows(BusinessException.class,
                () -> service.updateConfigs(List.of(config("login_duration_days", "14"))));
        assertThrows(BusinessException.class,
                () -> service.updateConfigs(List.of(config("login_duration_days", "forever"))));
    }

    @Test
    void lowStockReminderSettingOnlyAcceptsBooleanValues() {
        when(repository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(SiteConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateConfigs(List.of(config("low_stock_attention_enabled", "true")));
        verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(saved ->
                "low_stock_attention_enabled".equals(saved.getConfigKey()) && "true".equals(saved.getConfigValue())));
        assertThrows(BusinessException.class,
                () -> service.updateConfigs(List.of(config("low_stock_attention_enabled", "yes"))));
    }

    @Test
    void publicTurnstileStateStaysDisabledWhenKeysAreIncomplete() {
        org.mockito.Mockito.lenient().when(repository.findByConfigKey("turnstile_enabled"))
                .thenReturn(Optional.of(configValue("true")));

        var result = service.getPublicConfig();

        assertEquals(false, result.get("turnstile_enabled"));
        assertFalse(result.containsKey("turnstile_site_key"));
    }

    @Test
    void publicConfigNeverExposesAnnouncementContentOrRetiredChannels() {
        when(repository.findByConfigKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return switch (key) {
                default -> Optional.empty();
            };
        });
        var site = new com.myshop.entity.Subsite();
        site.setId(java.util.UUID.randomUUID()); site.setName("分站");
        com.myshop.context.SiteRequestContext.set(site);
        try {
            var result = service.getPublicConfig();
            assertFalse(result.containsKey("popup_enabled"));
            assertFalse(result.containsKey("popup_content"));
            assertFalse(result.containsKey("announcement"));
            assertFalse(result.containsKey("announcement_enabled"));
            assertEquals("分站", result.get("site_name"));
            assertEquals("http://localhost:3000", result.get("main_site_url"));
        } finally { com.myshop.context.SiteRequestContext.clear(); }
    }

    @Test
    void readsDurableDefaultUserNumberConfig() {
        when(userNumberSequenceRepository.findById(1))
                .thenReturn(Optional.of(new UserNumberSequence(1, 10000L, 10000L)));

        var result = service.getUserNumberConfig();

        assertEquals(10000L, result.startUserNo());
        assertEquals(10000L, result.nextUserNo());
        assertFalse(result.hasExistingUsers());
    }

    @Test
    void updatesNextCounterWithoutRewritingExistingStartAfterUsersExist() {
        UserNumberSequence sequence = new UserNumberSequence(1, 10000L, 10000L);
        when(userNumberSequenceRepository.findByIdForUpdate(1)).thenReturn(sequence);
        when(userRepository.countByRole(com.myshop.constant.UserRole.USER)).thenReturn(1L);
        when(userRepository.findMaxUserNoByRole(com.myshop.constant.UserRole.USER)).thenReturn(10000L);
        when(userNumberSequenceRepository.save(any(UserNumberSequence.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserNumberConfigUpdateRequest request = new UserNumberConfigUpdateRequest();
        request.setStartUserNo(10000L);
        request.setNextUserNo(20000L);

        service.updateUserNumberConfig(request);

        assertEquals(10000L, sequence.getStartUserNo());
        assertEquals(20000L, sequence.getNextUserNo());
        verify(userNumberSequenceRepository).save(sequence);
    }

    @Test
    void rejectsStartChangeAfterOrdinaryUsersExist() {
        UserNumberSequence sequence = new UserNumberSequence(1, 10000L, 10001L);
        when(userNumberSequenceRepository.findByIdForUpdate(1)).thenReturn(sequence);
        when(userRepository.countByRole(com.myshop.constant.UserRole.USER)).thenReturn(1L);
        when(userRepository.findMaxUserNoByRole(com.myshop.constant.UserRole.USER)).thenReturn(10000L);

        UserNumberConfigUpdateRequest request = new UserNumberConfigUpdateRequest();
        request.setStartUserNo(20000L);
        request.setNextUserNo(20001L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateUserNumberConfig(request));

        assertTrue(exception.getMessage().contains("只读"));
        verify(userNumberSequenceRepository, never()).save(any(UserNumberSequence.class));
    }

    @Test
    void rejectsNextValueThatIsNotStrictlyAboveExistingMaximum() {
        UserNumberSequence sequence = new UserNumberSequence(1, 10000L, 10001L);
        when(userNumberSequenceRepository.findByIdForUpdate(1)).thenReturn(sequence);
        when(userRepository.countByRole(com.myshop.constant.UserRole.USER)).thenReturn(1L);
        when(userRepository.findMaxUserNoByRole(com.myshop.constant.UserRole.USER)).thenReturn(10000L);

        UserNumberConfigUpdateRequest request = new UserNumberConfigUpdateRequest();
        request.setStartUserNo(10000L);
        request.setNextUserNo(10000L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateUserNumberConfig(request));

        assertTrue(exception.getMessage().contains("严格大于"));
        verify(userNumberSequenceRepository, never()).save(any(UserNumberSequence.class));
    }

    @Test
    void allowsSettingTheFirstNumberOnlyWhenStartAndNextMatchWithoutUsers() {
        UserNumberSequence sequence = new UserNumberSequence(1, 10000L, 10000L);
        when(userNumberSequenceRepository.findByIdForUpdate(1)).thenReturn(sequence);
        when(userRepository.countByRole(com.myshop.constant.UserRole.USER)).thenReturn(0L);
        when(userRepository.findMaxUserNoByRole(com.myshop.constant.UserRole.USER)).thenReturn(null);
        when(userNumberSequenceRepository.save(any(UserNumberSequence.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserNumberConfigUpdateRequest request = new UserNumberConfigUpdateRequest();
        request.setStartUserNo(20000L);
        request.setNextUserNo(20000L);

        service.updateUserNumberConfig(request);

        assertEquals(20000L, sequence.getStartUserNo());
        assertEquals(20000L, sequence.getNextUserNo());
    }

    private Map<String, String> config(String key, String value) {
        return Map.of("config_key", key, "config_value", value);
    }

    private SiteConfig configValue(String value) {
        SiteConfig config = new SiteConfig();
        config.setConfigKey("test_key");
        config.setConfigValue(value);
        return config;
    }
}
