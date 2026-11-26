package com.myshop.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.context.RequestContext;
import com.myshop.entity.User;
import com.myshop.service.AuthCookieService;
import com.myshop.service.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private AuthSessionService sessions;
    @Mock private CustomAccessDeniedHandler accessDeniedHandler;

    private AuthCookieService cookies;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        cookies = new AuthCookieService(false);
        filter = new JwtAuthenticationFilter(sessions, cookies, accessDeniedHandler, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContext.clear();
    }

    @Test
    void activeServerSessionBuildsCurrentUserAuthorityFromDatabaseResolution() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "database-user", "USER", 4, 0);
        when(sessions.resolve(eq("opaque-session"), any(), any()))
                .thenReturn(AuthSessionService.Resolution.authenticated(user));

        Observation observation = run("opaque-session");

        assertAuthenticated(observation, userId, "database-user", "USER");
        verify(sessions).resolve(eq("opaque-session"), any(), any());
    }

    @Test
    void rejectedCredentialClearsContextAndBrowserCookies() throws Exception {
        when(sessions.resolve(eq("revoked-session"), any(), any()))
                .thenReturn(AuthSessionService.Resolution.invalid());

        Observation observation = run("revoked-session");

        assertNull(observation.authentication());
        assertNull(observation.context());
        assertEquals(2, observation.response().getHeaders("Set-Cookie").size());
        verify(accessDeniedHandler, org.mockito.Mockito.never()).handle(any(), any(), any());
    }

    @Test
    void crossSiteCredentialUsesForbiddenHandlerWithoutClearingItsSession() throws Exception {
        when(sessions.resolve(eq("other-site-session"), any(), any()))
                .thenReturn(AuthSessionService.Resolution.forbiddenResult());

        Observation observation = run("other-site-session");

        assertNull(observation.authentication());
        assertNull(observation.context());
        verify(accessDeniedHandler).handle(any(), any(), any(AccessDeniedException.class));
        assertEquals(0, observation.response().getHeaders("Set-Cookie").size());
    }

    @Test
    void databaseFailureReturnsRetryableServiceUnavailableAndKeepsCredential() throws Exception {
        when(sessions.resolve(eq("temporarily-unavailable"), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        Observation observation = run("temporarily-unavailable");

        assertEquals(503, observation.response().getStatus());
        assertEquals(0, observation.response().getHeaders("Set-Cookie").size());
        assertNull(observation.authentication());
    }

    private Observation run(String token) throws Exception {
        SecurityContextHolder.clearContext();
        RequestContext.clear();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("myshop_access_token", token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> authentication = new AtomicReference<>();
        AtomicReference<RequestContext.UserInfo> context = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) -> {
            authentication.set(SecurityContextHolder.getContext().getAuthentication());
            context.set(RequestContext.get());
        };
        filter.doFilter(request, response, chain);
        return new Observation(authentication.get(), context.get(), response);
    }

    private void assertAuthenticated(Observation observation, UUID userId,
                                    String username, String role) {
        assertNotNull(observation.authentication());
        assertEquals(userId, observation.authentication().getPrincipal());
        assertEquals("ROLE_" + role,
                observation.authentication().getAuthorities().iterator().next().getAuthority());
        assertNotNull(observation.context());
        assertEquals(userId, observation.context().getUserId());
        assertEquals(username, observation.context().getUsername());
        assertEquals(role, observation.context().getRole());
    }

    private User user(UUID id, String username, String role, int authVersion, int isDeleted) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(com.myshop.constant.UserRole.valueOf(role));
        user.setAuthVersion(authVersion);
        user.setIsDeleted(isDeleted);
        return user;
    }

    private record Observation(Authentication authentication, RequestContext.UserInfo context,
                               MockHttpServletResponse response) { }
}
