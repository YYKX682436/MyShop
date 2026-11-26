package com.myshop.service;

import com.myshop.constant.UserRole;
import com.myshop.context.SiteRequestContext;
import com.myshop.entity.Subsite;
import com.myshop.entity.User;
import com.myshop.repository.UserRepository;
import com.myshop.service.impl.IntegrationPostgres;
import com.myshop.utils.JwtUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIf(value = "postgresAvailable", disabledReason = "Disposable PostgreSQL required")
@SpringBootTest
class AuthSessionPostgresIntegrationTest {
    private static final String SECRET = "auth-session-postgres-test-secret-with-at-least-64-bytes-for-hmac-key-2026";
    private static final IntegrationPostgres POSTGRES = new IntegrationPostgres();
    private static final java.nio.file.Path ROOT = java.nio.file.Path.of(
            System.getProperty("java.io.tmpdir"), "myshop-auth-session-test-" + UUID.randomUUID());

    static boolean postgresAvailable() {
        return POSTGRES.available();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("jwt.secret", () -> SECRET);
        registry.add("upload.path", () -> ROOT.resolve("public").toString());
        registry.add("private-upload.path", () -> ROOT.resolve("private").toString());
        registry.add("mail.enabled", () -> false);
        registry.add("supplier.sync-initial-delay-ms", () -> "3600000");
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
        if (java.nio.file.Files.exists(ROOT)) try (var paths = java.nio.file.Files.walk(ROOT)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                java.nio.file.Files.deleteIfExists(path);
        }
    }

    @AfterEach
    void clearScope() {
        SiteRequestContext.clear();
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private AuthSessionService sessions;

    @BeforeEach
    void resetSettings() {
        jdbc.update("UPDATE site_configs SET config_value='30' WHERE config_key='login_duration_days'");
    }

    @Test
    void storesOnlyTheCredentialDigestAndSurvivesAServiceRecreationAndBrowserRestart() throws Exception {
        User user = createUser(UserRole.USER);
        AuthSessionService service = sessions;
        AuthSessionService.IssuedCredential issued = service.create(user);

        assertEquals(43, issued.value().length());
        String digest = sha256(issued.value());
        assertEquals(64,digest.length());
        assertTrue(digest.matches("[0-9a-f]{64}"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE token_hash=?", Integer.class, digest));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE token_hash=?", Integer.class, issued.value()));

        MockHttpServletRequest request = requestWith(issued.value());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthSessionService recreatedService = service(new JwtUtils(SECRET, 86_400_000L));
        AuthSessionService.Resolution resolved = recreatedService.resolve(issued.value(), request, response);

        assertTrue(resolved.authenticated());
        assertEquals(user.getId(), resolved.user().getId());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
    }

    @Test
    void renewsAtMostOncePerDayAndUsesTheCurrentSiteDuration() {
        User user = createUser(UserRole.USER);
        AuthSessionService service = sessions;
        AuthSessionService.IssuedCredential issued = service.create(user);
        String digest = sha256(issued.value());
        jdbc.update("UPDATE auth_sessions SET renewed_at=CURRENT_TIMESTAMP-INTERVAL '25 hours', expires_at=CURRENT_TIMESTAMP+INTERVAL '1 day' WHERE token_hash=?", digest);
        jdbc.update("UPDATE site_configs SET config_value='365' WHERE config_key='login_duration_days'");

        MockHttpServletResponse renewalResponse = new MockHttpServletResponse();
        AuthSessionService.Resolution resolved = service.resolve(issued.value(), requestWith(issued.value()), renewalResponse);

        assertTrue(resolved.authenticated());
        assertEquals(1, jdbc.queryForObject("SELECT (expires_at>CURRENT_TIMESTAMP+INTERVAL '364 days')::int FROM auth_sessions WHERE token_hash=?", Integer.class, digest));
        assertTrue(renewalResponse.getHeaders("Set-Cookie").stream().anyMatch(value -> value.contains("Max-Age=31536000")));
        assertTrue(renewalResponse.getHeaders("Set-Cookie").stream().anyMatch(value -> value.contains("HttpOnly")));

        var renewedAt = jdbc.queryForObject("SELECT renewed_at FROM auth_sessions WHERE token_hash=?", java.time.LocalDateTime.class, digest);
        MockHttpServletResponse steadyResponse = new MockHttpServletResponse();
        service.resolve(issued.value(), requestWith(issued.value()), steadyResponse);
        assertTrue(steadyResponse.getHeaders("Set-Cookie").isEmpty());
        assertEquals(renewedAt, jdbc.queryForObject("SELECT renewed_at FROM auth_sessions WHERE token_hash=?", java.time.LocalDateTime.class, digest));
    }

    @Test
    void serverRenderedRequestsLeaveCookieRenewalToTheBrowserRequest() {
        User user = createUser(UserRole.USER);
        AuthSessionService.IssuedCredential issued = sessions.create(user);
        String digest = sha256(issued.value());
        jdbc.update("UPDATE auth_sessions SET renewed_at=CURRENT_TIMESTAMP-INTERVAL '25 hours', expires_at=CURRENT_TIMESTAMP+INTERVAL '1 day' WHERE token_hash=?",digest);
        var staleRenewal = jdbc.queryForObject("SELECT renewed_at FROM auth_sessions WHERE token_hash=?",java.time.LocalDateTime.class,digest);

        MockHttpServletRequest renderRequest=requestWith(issued.value());
        renderRequest.addHeader("X-MyShop-SSR","true");
        MockHttpServletResponse renderResponse=new MockHttpServletResponse();
        assertTrue(sessions.resolve(issued.value(),renderRequest,renderResponse).authenticated());
        assertTrue(renderResponse.getHeaders("Set-Cookie").isEmpty());
        assertEquals(staleRenewal,jdbc.queryForObject("SELECT renewed_at FROM auth_sessions WHERE token_hash=?",java.time.LocalDateTime.class,digest));

        MockHttpServletResponse browserResponse=new MockHttpServletResponse();
        assertTrue(sessions.resolve(issued.value(),requestWith(issued.value()),browserResponse).authenticated());
        assertEquals(2,browserResponse.getHeaders("Set-Cookie").size());
    }

    @Test
    void concurrentRequestsShareOneRollingRenewalWindow() throws Exception {
        User user = createUser(UserRole.USER);
        AuthSessionService.IssuedCredential issued = sessions.create(user);
        String digest = sha256(issued.value());
        jdbc.update("UPDATE auth_sessions SET renewed_at=CURRENT_TIMESTAMP-INTERVAL '25 hours' WHERE token_hash=?",digest);

        int requests = 8;
        var executor = java.util.concurrent.Executors.newFixedThreadPool(requests);
        var ready = new java.util.concurrent.CountDownLatch(requests);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index=0; index<requests; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10,java.util.concurrent.TimeUnit.SECONDS)) return false;
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    AuthSessionService.Resolution resolution = sessions.resolve(
                            issued.value(),requestWith(issued.value()),response);
                    return resolution.authenticated() && response.getHeaders("Set-Cookie").size()==2;
                }));
            }
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            long cookieRefreshes = 0;
            for (var future : futures) if (future.get(15,java.util.concurrent.TimeUnit.SECONDS)) cookieRefreshes++;
            assertEquals(1L,cookieRefreshes);
            assertEquals(1,jdbc.queryForObject(
                    "SELECT (renewed_at>CURRENT_TIMESTAMP-INTERVAL '1 minute' AND expires_at>CURRENT_TIMESTAMP+INTERVAL '29 days')::int FROM auth_sessions WHERE token_hash=?",
                    Integer.class,digest));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void administratorsRemainAtThirtyDaysAndPasswordOrPermissionVersionChangesRevokeOldSessions() {
        jdbc.update("UPDATE site_configs SET config_value='365' WHERE config_key='login_duration_days'");
        User admin = createUser(UserRole.ADMIN);
        AuthSessionService service = sessions;
        AuthSessionService.IssuedCredential issued = service.create(admin);
        assertEquals(30, issued.lifetimeDays());

        admin.setAuthVersion(admin.getAuthVersion() + 1);
        users.saveAndFlush(admin);
        AuthSessionService.Resolution resolution = service.resolve(issued.value(), requestWith(issued.value()), new MockHttpServletResponse());

        assertFalse(resolution.authenticated());
        assertEquals("AUTHORITY_CHANGED", jdbc.queryForObject(
                "SELECT revoked_reason FROM auth_sessions WHERE token_hash=?", String.class, sha256(issued.value())));
    }

    @Test
    void currentStationIsStillCheckedAndLogoutRevokesThePresentedSession() {
        User owner = createUser(UserRole.USER);
        UUID stationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO subsites(id,owner_id,prefix,name,enabled)
                VALUES (?,?,?,'Session Test',true)
                """, stationId, owner.getId(), "session-" + stationId.toString().substring(0, 8));
        User buyer = createUser(UserRole.USER);
        buyer.setSubsiteId(stationId);
        buyer = users.saveAndFlush(buyer);
        AuthSessionService service = sessions;
        AuthSessionService.IssuedCredential issued = service.create(buyer);

        assertTrue(service.resolve(issued.value(), requestWith(issued.value()), new MockHttpServletResponse()).forbidden());
        Subsite site = new Subsite();
        site.setId(stationId);
        site.setOwnerId(UUID.randomUUID());
        SiteRequestContext.set(site);
        assertTrue(service.resolve(issued.value(), requestWith(issued.value()), new MockHttpServletResponse()).authenticated());

        MockHttpServletRequest logout = requestWith(issued.value());
        service.revokeCurrent(logout);
        assertFalse(service.resolve(issued.value(), requestWith(issued.value()), new MockHttpServletResponse()).authenticated());
        assertEquals("LOGOUT", jdbc.queryForObject(
                "SELECT revoked_reason FROM auth_sessions WHERE token_hash=?", String.class, sha256(issued.value())));
    }

    @Test
    void onlyAnUnexpiredLegacyJwtIsUpgradedToANewOpaqueSession() {
        User user = createUser(UserRole.USER);
        JwtUtils jwt = new JwtUtils(SECRET, 86_400_000L);
        String legacy = jwt.generateToken(user.getId(), user.getUsername(), user.getRole().name(), user.getAuthVersion());
        AuthSessionService service = sessions;
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(service.resolve(legacy, requestWith(legacy), response).authenticated());
        String upgraded = response.getHeaders("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("myshop_access_token="))
                .map(cookie -> cookie.substring("myshop_access_token=".length(), cookie.indexOf(';')))
                .findFirst().orElseThrow();
        assertNotEquals(legacy, upgraded);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE token_hash=?", Integer.class, sha256(upgraded)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE token_hash=?", Integer.class, sha256(legacy)));

        JwtUtils expiredJwt = new JwtUtils(SECRET, -1L);
        String expired = expiredJwt.generateToken(user.getId(), user.getUsername(), user.getRole().name(), user.getAuthVersion());
        assertFalse(service(expiredJwt).resolve(expired, requestWith(expired), new MockHttpServletResponse()).authenticated());
    }

    @Test
    void serverRenderedLegacyRequestsDoNotCreateUnreachableSessions() {
        User user = createUser(UserRole.USER);
        JwtUtils jwt = new JwtUtils(SECRET, 86_400_000L);
        String legacy = jwt.generateToken(user.getId(),user.getUsername(),user.getRole().name(),user.getAuthVersion());
        MockHttpServletRequest request = requestWith(legacy);
        request.addHeader("X-MyShop-SSR","true");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(sessions.resolve(legacy,request,response).authenticated());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE user_id=?",Integer.class,user.getId()));
    }

    private AuthSessionService service(JwtUtils jwt) {
        return new AuthSessionService(jdbc, users, jwt, new AuthCookieService(false));
    }

    private User createUser(UserRole role) {
        User user = new User();
        String suffix = UUID.randomUUID().toString();
        user.setUsername("session-" + suffix);
        user.setEmail("session-" + suffix + "@example.com");
        user.setPasswordHash("not-used-by-session-test");
        user.setRole(role);
        if (role == UserRole.USER) {
            long userNo = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
            user.setUserNo(Math.max(1, userNo));
            user.setUserLevelId(jdbc.queryForObject("SELECT id FROM user_levels WHERE is_default=true AND enabled=true", UUID.class));
        }
        return users.saveAndFlush(user);
    }

    private MockHttpServletRequest requestWith(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("myshop_access_token", token), new Cookie("myshop_csrf", "csrf-value"));
        return request;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

}
