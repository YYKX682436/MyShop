package com.myshop.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.SiteConfig;
import com.myshop.repository.SiteConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TurnstileFilterTest {

    @Test
    void incompleteTurnstileKeysNeverBlockLogin() throws Exception {
        SiteConfigRepository repository = mock(SiteConfigRepository.class);
        when(repository.findByConfigKey("turnstile_enabled")).thenReturn(Optional.of(config("true")));
        TurnstileFilter filter = new TurnstileFilter(repository, new ObjectMapper());
        ReflectionTestUtils.setField(filter, "secretKey", "server-secret");
        ReflectionTestUtils.setField(filter, "siteKey", "");
        AtomicBoolean continued = new AtomicBoolean();

        MockHttpServletResponse response = invoke(filter, continued);

        assertEquals(200, response.getStatus());
        assertTrue(continued.get());
    }

    @Test
    void completeTurnstileKeysEnforceTheOptionalChallenge() throws Exception {
        SiteConfigRepository repository = mock(SiteConfigRepository.class);
        when(repository.findByConfigKey("turnstile_enabled")).thenReturn(Optional.of(config("true")));
        TurnstileFilter filter = new TurnstileFilter(repository, new ObjectMapper());
        ReflectionTestUtils.setField(filter, "secretKey", "server-secret");
        ReflectionTestUtils.setField(filter, "siteKey", "public-site-key");
        AtomicBoolean continued = new AtomicBoolean();

        MockHttpServletResponse response = invoke(filter, continued);

        assertEquals(400, response.getStatus());
        assertTrue(!continued.get());
    }

    private MockHttpServletResponse invoke(TurnstileFilter filter, AtomicBoolean continued) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRequestURI("/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> continued.set(true));
        return response;
    }

    private static SiteConfig config(String value) {
        SiteConfig config = new SiteConfig();
        config.setConfigValue(value);
        return config;
    }
}
