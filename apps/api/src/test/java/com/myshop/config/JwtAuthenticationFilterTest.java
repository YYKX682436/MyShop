package com.myshop.config;

import com.myshop.context.RequestContext;
import com.myshop.entity.User;
import com.myshop.repository.UserRepository;
import com.myshop.utils.JwtUtils;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String SECRET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_=+";

    @Mock
    private UserRepository userRepository;

    private JwtAuthenticationFilter filter;
    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils(SECRET, 86_400_000L);
        filter = new JwtAuthenticationFilter(jwtUtils, userRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContext.clear();
    }

    @Test
    void resetDisableAndRestoreInvalidateTheOriginalToken() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "database-user", "USER", 0, 0);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        String originalToken = jwtUtils.generateToken(userId, "stale-jwt-user", "USER", 0);

        assertAuthenticated(run(originalToken), userId, "database-user", "USER");

        user.setAuthVersion(1); // password reset
        assertRejected(run(originalToken));

        user.setIsDeleted(1);
        user.setAuthVersion(2); // disable
        assertRejected(run(originalToken));

        user.setIsDeleted(0);
        user.setAuthVersion(3); // restore; the old v0 token must stay invalid
        assertRejected(run(originalToken));

        String newToken = jwtUtils.generateToken(userId, "database-user", "USER", 3);
        assertAuthenticated(run(newToken), userId, "database-user", "USER");
    }

    @Test
    void databaseRoleMustMatchTheSignedRole() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = user(userId, "database-admin", "ADMIN", 0, 0);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        String token = jwtUtils.generateToken(userId, "stale-user", "USER", 0);

        assertRejected(run(token));
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
        return new Observation(authentication.get(), context.get());
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

    private void assertRejected(Observation observation) {
        assertNull(observation.authentication());
        assertNull(observation.context());
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

    private record Observation(Authentication authentication, RequestContext.UserInfo context) {
    }
}
