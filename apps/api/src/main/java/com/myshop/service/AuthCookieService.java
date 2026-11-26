package com.myshop.service;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Owns the browser authentication cookie contract for every authentication
 * entry point. Keeping creation and deletion here prevents password changes
 * from accidentally leaving an unusable HttpOnly session behind.
 */
@Service
public class AuthCookieService {

    private static final String ACCESS_COOKIE = "myshop_access_token";
    private static final String CSRF_COOKIE = "myshop_csrf";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final boolean cookieSecure;

    public AuthCookieService(@Value("${security.cookie-secure:false}") boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public void writeAuthCookies(HttpServletResponse response, String token, Duration lifetime) {
        writeAuthCookies(response, token, newCsrfToken(), lifetime);
    }

    /** Refresh the persistent cookies when the server-side session is renewed. */
    public void refreshAuthCookies(HttpServletResponse response, String token, HttpServletRequest request,
                                   Duration lifetime) {
        String csrf = cookieValue(request, CSRF_COOKIE);
        writeAuthCookies(response, token, csrf == null || csrf.isBlank() ? newCsrfToken() : csrf, lifetime);
    }

    private void writeAuthCookies(HttpServletResponse response, String token, String csrf, Duration lifetime) {
        response.addHeader("Set-Cookie", ResponseCookie.from(ACCESS_COOKIE, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(lifetime)
                .build()
                .toString());
        response.addHeader("Set-Cookie", ResponseCookie.from(CSRF_COOKIE, csrf)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(lifetime)
                .build()
                .toString());
    }

    public String getAccessToken(HttpServletRequest request) {
        return cookieValue(request, ACCESS_COOKIE);
    }

    public String getCsrfToken(HttpServletRequest request) {
        return cookieValue(request, CSRF_COOKIE);
    }

    public static String accessCookieName() { return ACCESS_COOKIE; }

    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private String newCsrfToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public void clearAuthCookies(HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(ACCESS_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build()
                .toString());
        response.addHeader("Set-Cookie", ResponseCookie.from(CSRF_COOKIE, "")
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build()
                .toString());
    }
}
