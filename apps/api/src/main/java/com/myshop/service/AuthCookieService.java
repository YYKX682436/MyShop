package com.myshop.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Owns the browser authentication cookie contract for every authentication
 * entry point. Keeping creation and deletion here prevents password changes
 * from accidentally leaving an unusable HttpOnly session behind.
 */
@Service
public class AuthCookieService {

    private static final String ACCESS_COOKIE = "myshop_access_token";
    private static final String CSRF_COOKIE = "myshop_csrf";
    private static final Duration COOKIE_LIFETIME = Duration.ofHours(24);

    private final boolean cookieSecure;

    public AuthCookieService(@Value("${security.cookie-secure:false}") boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public void writeAuthCookies(HttpServletResponse response, String token) {
        response.addHeader("Set-Cookie", ResponseCookie.from(ACCESS_COOKIE, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(COOKIE_LIFETIME)
                .build()
                .toString());
        response.addHeader("Set-Cookie", ResponseCookie.from(CSRF_COOKIE, UUID.randomUUID().toString())
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(COOKIE_LIFETIME)
                .build()
                .toString());
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
