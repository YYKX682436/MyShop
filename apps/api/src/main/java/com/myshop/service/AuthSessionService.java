package com.myshop.service;

import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.repository.UserRepository;
import com.myshop.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** PostgreSQL-backed opaque browser sessions with digest-only credentials. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSessionService {
    public static final String CURRENT_CREDENTIAL_ATTRIBUTE = AuthSessionService.class.getName() + ".credential";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_DURATION_DAYS = 30;
    private static final int[] ALLOWED_DURATION_DAYS = {15, 30, 90, 365};

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final JwtUtils legacyJwt;
    private final AuthCookieService cookies;

    public record IssuedCredential(String value, int lifetimeDays) { }

    public record Resolution(User user, boolean forbidden) {
        public static Resolution invalid() { return new Resolution(null, false); }
        public static Resolution forbiddenResult() { return new Resolution(null, true); }
        public static Resolution authenticated(User user) { return new Resolution(user, false); }
        public boolean authenticated() { return user != null; }
    }

    private record SessionRow(UUID userId, String role, int authVersion,
                              boolean active, boolean revoked, boolean renewalDue) { }

    @Transactional
    public IssuedCredential create(User user) {
        String value = newCredential();
        int days = lifetimeDays(user);
        jdbc.update("""
                INSERT INTO auth_sessions(token_hash,user_id,user_role,auth_version,expires_at)
                VALUES (?,?,?,?,CURRENT_TIMESTAMP + (? * INTERVAL '1 day'))
                """, digest(value), user.getId(), user.getRole().name(), user.getAuthVersion(), days);
        return new IssuedCredential(value, days);
    }

    /**
     * Resolves opaque sessions first. A still-valid legacy JWT is upgraded on
     * its first request; JWT parsing enforces its original exp claim, so an
     * expired token is never used to create a replacement session.
     */
    @Transactional
    public Resolution resolve(String presented, HttpServletRequest request, HttpServletResponse response) {
        if (presented == null || presented.isBlank()) return Resolution.invalid();
        boolean serverRendered = "true".equalsIgnoreCase(request.getHeader("X-MyShop-SSR"));
        String tokenHash = digest(presented);
        List<SessionRow> stored = jdbc.query("""
                SELECT user_id,user_role,auth_version,expires_at>CURRENT_TIMESTAMP,revoked_at IS NOT NULL,
                       renewed_at<=CURRENT_TIMESTAMP-INTERVAL '24 hours'
                FROM auth_sessions WHERE token_hash=?
                """, (rs, row) -> new SessionRow(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getInt(3), rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6)), tokenHash);

        if (!stored.isEmpty()) {
            SessionRow session = stored.getFirst();
            if (session.revoked() || !session.active()) {
                return Resolution.invalid();
            }
            User user = users.findById(session.userId()).orElse(null);
            if (user == null || user.getIsDeleted() != 0 || user.getAuthVersion() != session.authVersion()
                    || !user.getRole().name().equals(session.role())) {
                revokeHash(tokenHash, "AUTHORITY_CHANGED");
                return Resolution.invalid();
            }
            if (!SubsiteAccess.permits(user)) return Resolution.forbiddenResult();

            int days = lifetimeDays(user);
            if (!serverRendered && session.renewalDue() && renewIfDue(tokenHash, days) > 0) {
                cookies.refreshAuthCookies(response, presented, request, Duration.ofDays(days));
            }
            request.setAttribute(CURRENT_CREDENTIAL_ATTRIBUTE, presented);
            return Resolution.authenticated(user);
        }

        Claims claims = legacyJwt.parseTokenSafe(presented);
        if (claims == null) return Resolution.invalid();
        UUID userId;
        String role = claims.get("role", String.class);
        Integer authVersion = integerClaim(claims.get("authVersion"));
        try {
            userId = UUID.fromString(claims.getSubject());
        } catch (RuntimeException error) {
            return Resolution.invalid();
        }
        if (role == null || authVersion == null) return Resolution.invalid();

        User user = users.findById(userId).orElse(null);
        if (user == null || user.getIsDeleted() != 0 || user.getAuthVersion() != authVersion
                || !user.getRole().name().equals(role)) return Resolution.invalid();
        if (!SubsiteAccess.permits(user)) return Resolution.forbiddenResult();

        // Server Components can forward a legacy cookie for personalized GETs,
        // but cannot forward Set-Cookie back to the browser. Keep the legacy
        // token valid for this bounded request and let the browser's next API
        // request perform the one-time migration instead of creating an
        // unreachable database session on every server render.
        if (serverRendered)
            return Resolution.authenticated(user);

        IssuedCredential upgraded = create(user);
        cookies.refreshAuthCookies(response, upgraded.value(), request, Duration.ofDays(upgraded.lifetimeDays()));
        request.setAttribute(CURRENT_CREDENTIAL_ATTRIBUTE, upgraded.value());
        return Resolution.authenticated(user);
    }

    @Transactional
    public void revokeCurrent(HttpServletRequest request) {
        Object migrated = request.getAttribute(CURRENT_CREDENTIAL_ATTRIBUTE);
        String token = migrated instanceof String value ? value : cookies.getAccessToken(request);
        if (token != null && !token.isBlank()) revokeHash(digest(token), "LOGOUT");
    }

    /** Used by maintenance mode before the Spring Security chain is entered. */
    @Transactional(readOnly = true)
    public boolean isAdministratorCredential(String presented) {
        if (presented == null || presented.isBlank()) return false;
        List<SessionRow> stored = jdbc.query("""
                SELECT user_id,user_role,auth_version,expires_at>CURRENT_TIMESTAMP,revoked_at IS NOT NULL,
                       renewed_at<=CURRENT_TIMESTAMP-INTERVAL '24 hours'
                FROM auth_sessions WHERE token_hash=?
                """, (rs, row) -> new SessionRow(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getInt(3), rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6)), digest(presented));
        if (!stored.isEmpty()) {
            SessionRow session = stored.getFirst();
            if (session.revoked() || !session.active()
                    || !isAdminRole(session.role())) return false;
            return users.findById(session.userId()).filter(user -> user.getIsDeleted() == 0
                    && user.getAuthVersion() == session.authVersion()
                    && user.getRole().name().equals(session.role())
                    && SubsiteAccess.permits(user)).isPresent();
        }

        Claims claims = legacyJwt.parseTokenSafe(presented);
        if (claims == null || !isAdminRole(claims.get("role", String.class))) return false;
        Integer version = integerClaim(claims.get("authVersion"));
        if (version == null) return false;
        try {
            UUID id = UUID.fromString(claims.getSubject());
            return users.findById(id).filter(user -> user.getIsDeleted() == 0
                    && user.getAuthVersion() == version
                    && user.getRole().name().equals(claims.get("role", String.class))
                    && SubsiteAccess.permits(user)).isPresent();
        } catch (RuntimeException error) {
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${auth.session-cleanup-interval-ms:3600000}",
            initialDelayString = "${auth.session-cleanup-initial-delay-ms:60000}")
    @Transactional
    public int cleanupExpiredSessions() {
        int removed = jdbc.update("""
                DELETE FROM auth_sessions s
                WHERE s.expires_at <= CURRENT_TIMESTAMP
                   OR (s.revoked_at IS NOT NULL AND s.revoked_at < CURRENT_TIMESTAMP - INTERVAL '30 days')
                   OR NOT EXISTS (
                        SELECT 1 FROM users u WHERE u.id=s.user_id AND u.is_deleted=0 AND u.auth_version=s.auth_version
                           AND u.role::text=s.user_role)
                """);
        if (removed > 0) log.info("Removed {} expired or invalid authentication sessions", removed);
        return removed;
    }

    private int renewIfDue(String tokenHash, int days) {
        return jdbc.update("""
                UPDATE auth_sessions
                SET expires_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 day'),
                    renewed_at=CURRENT_TIMESTAMP,
                    last_seen_at=CURRENT_TIMESTAMP
                WHERE token_hash=? AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP
                  AND renewed_at<=CURRENT_TIMESTAMP - INTERVAL '24 hours'
                """, days, tokenHash);
    }

    private void revokeHash(String tokenHash, String reason) {
        jdbc.update("""
                UPDATE auth_sessions SET revoked_at=CURRENT_TIMESTAMP,revoked_reason=?
                WHERE token_hash=? AND revoked_at IS NULL
                """, reason, tokenHash);
    }

    private int lifetimeDays(User user) {
        if (user.getRole() == UserRole.ADMIN || user.getRole() == UserRole.SUPER_ADMIN)
            return DEFAULT_DURATION_DAYS;
        try {
            Integer configured = jdbc.query("SELECT config_value FROM site_configs WHERE config_key='login_duration_days'",
                    rs -> rs.next() ? Integer.valueOf(rs.getString(1)) : DEFAULT_DURATION_DAYS);
            if (configured != null && allowed(configured)) return configured;
        } catch (RuntimeException exception) {
            log.warn("Could not read login duration setting; using {} days", DEFAULT_DURATION_DAYS);
        }
        return DEFAULT_DURATION_DAYS;
    }

    private static boolean allowed(int days) {
        for (int option : ALLOWED_DURATION_DAYS) if (option == days) return true;
        return false;
    }

    private static boolean isAdminRole(String role) {
        return UserRole.ADMIN.name().equals(role) || UserRole.SUPER_ADMIN.name().equals(role);
    }

    private static Integer integerClaim(Object value) {
        if (!(value instanceof Number number)) return null;
        long candidate = number.longValue();
        return candidate < Integer.MIN_VALUE || candidate > Integer.MAX_VALUE ? null : (int) candidate;
    }

    private static String newCredential() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
