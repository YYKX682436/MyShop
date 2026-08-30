package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.SiteConfig;
import com.myshop.entity.UserNumberSequence;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.UserNumberConfigUpdateRequest;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.UserNumberSequenceRepository;
import com.myshop.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        service = new SiteConfigServiceImpl(repository, new ObjectMapper(), userNumberSequenceRepository, userRepository);
    }

    @Test
    void acceptsZeroContactItemsAndStoresCanonicalJson() {
        saveContactItems("[]");
    }

    @Test
    void acceptsOneContactItemAndNormalizesText() {
        saveContactItems("[{\"name\":\"  邮箱  \",\"url\":\" https://example.com/support \"}]",
                "[{\"name\":\"邮箱\",\"url\":\"https://example.com/support\"}]");
    }

    @Test
    void acceptsThreeContactItemsInSubmittedOrder() {
        saveContactItems("[{\"name\":\"A\",\"url\":\"https://a.example\"},"
                        + "{\"name\":\"B\",\"qr_url\":\"/api/uploads/b.png\"},"
                        + "{\"name\":\"C\",\"url\":\"http://c.example\",\"qr_url\":\"https://c.example/qr.png\"}]");
    }

    @ParameterizedTest
    @MethodSource("invalidContactItems")
    void rejectsInvalidContactItemsWithBusinessError(String raw, String messageFragment) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateConfigs(List.of(config("contact_items", raw))));

        assertEquals(10001, exception.getCode());
        assertTrue(exception.getMessage().contains(messageFragment), exception.getMessage());
    }

    @Test
    void exposesContactItemsAsAnArrayRatherThanStoredJsonText() {
        when(repository.findByConfigKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return "contact_items".equals(key)
                    ? Optional.of(configValue("[{\"name\":\"客服\",\"url\":\"https://example.com\"}]"))
                    : Optional.empty();
        });

        Object value = service.getPublicConfig().get("contact_items");

        assertNotNull(value);
        assertInstanceOf(List.class, value);
        assertEquals(List.of(Map.of("name", "客服", "url", "https://example.com")), value);
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

    private void saveContactItems(String raw) {
        saveContactItems(raw, raw);
    }

    private void saveContactItems(String raw, String expected) {
        when(repository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(SiteConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service.updateConfigs(List.of(config("contact_items", raw)));

        ArgumentCaptor<SiteConfig> captor = ArgumentCaptor.forClass(SiteConfig.class);
        verify(repository).save(captor.capture());
        assertEquals(expected, captor.getValue().getConfigValue());
    }

    private static Stream<Arguments> invalidContactItems() {
        return Stream.of(
                Arguments.of("[{\"name\":\"客服\",\"url\":\"https://a.example\"},"
                        + "{\"name\":\"客服2\",\"url\":\"https://b.example\"},"
                        + "{\"name\":\"客服3\",\"url\":\"https://c.example\"},"
                        + "{\"name\":\"客服4\",\"url\":\"https://d.example\"}]", "最多"),
                Arguments.of("[{\"name\":\"   \",\"url\":\"https://example.com\"}]", "不能为空"),
                Arguments.of("[{\"name\":\"客服\"}]", "链接或二维码"),
                Arguments.of("[{\"name\":\"客服\",\"url\":\"javascript:alert(1)\"}]", "http/https"),
                Arguments.of("[{\"name\":\"客服\",\"qr_url\":\"data:image/png;base64,abc\"}]", "二维码地址"),
                Arguments.of("[{\"name\":\"客服\",\"url\":\"https://example.com\",\"extra\":\"x\"}]", "不支持的字段")
        );
    }

    private Map<String, String> config(String key, String value) {
        return Map.of("config_key", key, "config_value", value);
    }

    private SiteConfig configValue(String value) {
        SiteConfig config = new SiteConfig();
        config.setConfigKey("contact_items");
        config.setConfigValue(value);
        return config;
    }
}
