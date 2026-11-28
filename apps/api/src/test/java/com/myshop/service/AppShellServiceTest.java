package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.SiteConfig;
import com.myshop.entity.Subsite;
import com.myshop.model.request.AppShellConfigUpdateRequest;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.SubsiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppShellServiceTest {
    private final SiteConfigRepository configs = mock(SiteConfigRepository.class);
    private final SubsiteRepository subsites = mock(SubsiteRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SiteConfig> stored = new HashMap<>();
    private final AppShellService service = new AppShellService(configs, subsites, mapper);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "publicSiteUrl", "https://mc.mc520.top");
        store("site_name", "MyShop");
        store("favicon_url", "/favicon.ico");
        when(configs.findByConfigKey(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(configs.findByConfigKeyForUpdate(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(configs.saveAndFlush(any(SiteConfig.class))).thenAnswer(invocation -> {
            SiteConfig config = invocation.getArgument(0);
            stored.put(config.getConfigKey(), config);
            return config;
        });

        Subsite enabledSubsite = new Subsite();
        enabledSubsite.setPrefix("ready");
        enabledSubsite.setName("Ready Shop");
        enabledSubsite.setEnabled(true);
        enabledSubsite.setFaviconUrl("/ready.ico");
        when(subsites.findByPrefix("ready")).thenReturn(Optional.of(enabledSubsite));
    }

    @Test
    void savesReadsAndResolvesTheSerializedConfig() throws Exception {
        var rules = List.of(
                new AppShellConfigUpdateRequest.DomainRule("*.mc520.top", "MYSHOP", true, "default"),
                new AppShellConfigUpdateRequest.DomainRule("partner.example", "EXTERNAL", true, "external"),
                new AppShellConfigUpdateRequest.DomainRule("blocked.mc520.top", "MYSHOP", false, "disabled exact")
        );

        var saved = service.updateConfig(new AppShellConfigUpdateRequest(true, rules));
        String serialized = stored.get("app_shell_config").getConfigValue();
        assertNotNull(serialized);
        assertTrue(mapper.readTree(serialized).get("enabled").asBoolean());
        assertEquals(saved, service.getConfig());

        var main = service.resolve("https://mc.mc520.top");
        assertTrue(main.allowed());
        assertEquals("myshop", main.site_type());
        assertEquals("MyShop", main.brand().site_name());

        var subsite = service.resolve("https://ready.mc520.top");
        assertTrue(subsite.allowed());
        assertEquals("Ready Shop", subsite.brand().site_name());

        var external = service.resolve("https://partner.example");
        assertTrue(external.allowed());
        assertEquals("external", external.site_type());

        var disabledExact = service.resolve("https://blocked.mc520.top");
        assertFalse(disabledExact.allowed());
        assertEquals("not_authorized", disabledExact.reason());

        service.updateConfig(new AppShellConfigUpdateRequest(false, rules));
        assertFalse(service.getConfig().enabled());
        var disabledSwitch = service.resolve("https://partner.example");
        assertFalse(disabledSwitch.allowed());
        assertEquals("unavailable", disabledSwitch.reason());
    }

    private void store(String key, String value) {
        SiteConfig config = new SiteConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        stored.put(key, config);
    }
}
