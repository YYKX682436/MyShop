package com.myshop.config;

import com.myshop.context.RequestContext;
import com.myshop.entity.User;
import com.myshop.repository.UserRepository;
import com.myshop.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * JWT is only a signed locator. Every request with a token re-reads the user
 * row so deletion, role changes, password resets, and status changes take
 * effect without waiting for token expiry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (StringUtils.hasText(token)) {
                // A presented token is authoritative for this request. Do
                // not let an earlier filter's authentication survive a bad
                // or stale cookie.
                SecurityContextHolder.clearContext();
                authenticateFromDatabase(token);
            }
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    private void authenticateFromDatabase(String token) {
        Claims claims = jwtUtils.parseTokenSafe(token);
        if (claims == null) return;

        UUID userId;
        String tokenRole = claims.get("role", String.class);
        Integer tokenAuthVersion = authVersion(claims.get("authVersion"));
        try {
            userId = UUID.fromString(claims.getSubject());
        } catch (Exception exception) {
            return;
        }
        if (!StringUtils.hasText(tokenRole) || tokenAuthVersion == null) return;

        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getIsDeleted() != 0
                    || !user.getRole().name().equals(tokenRole)
                    || user.getAuthVersion() != tokenAuthVersion) {
                log.debug("JWT rejected by current user authority");
                return;
            }

            String databaseRole = user.getRole().name();
            RequestContext.set(new RequestContext.UserInfo(user.getId(), user.getUsername(), databaseRole));
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + databaseRole));
            var authentication = new UsernamePasswordAuthenticationToken(user.getId(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (RuntimeException exception) {
            // Authentication must fail closed when the authority lookup is not
            // usable, while public endpoints can still render their response.
            log.warn("JWT database authority lookup failed", exception);
        }
    }

    private Integer authVersion(Object value) {
        if (!(value instanceof Number number)) return null;
        long candidate = number.longValue();
        return candidate < Integer.MIN_VALUE || candidate > Integer.MAX_VALUE ? null : (int) candidate;
    }

    private String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if ("myshop_access_token".equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
