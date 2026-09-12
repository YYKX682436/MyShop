package com.myshop.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthCookieServiceTest {

    @Test
    void clearExpiresBothAuthenticationCookiesWithTheirOriginalContract() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AuthCookieService(false).clearAuthCookies(response);

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertEquals(2, cookies.size());
        assertTrue(cookies.stream().anyMatch(cookie -> cookie.startsWith("myshop_access_token=;")
                && cookie.contains("HttpOnly")
                && cookie.contains("Max-Age=0")
                && cookie.contains("Path=/")));
        assertTrue(cookies.stream().anyMatch(cookie -> cookie.startsWith("myshop_csrf=;")
                && cookie.contains("Max-Age=0")
                && cookie.contains("Path=/")));
    }

    @Test
    void writingKeepsAccessCookieHttpOnlyAndCreatesCsrfCookieSeparately() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AuthCookieService(true).writeAuthCookies(response, "token-value");

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertEquals(2, cookies.size());
        assertTrue(cookies.stream().anyMatch(cookie -> cookie.startsWith("myshop_access_token=token-value;")
                && cookie.contains("HttpOnly")
                && cookie.contains("Secure")));
        assertTrue(cookies.stream().anyMatch(cookie -> cookie.startsWith("myshop_csrf=")
                && !cookie.contains("HttpOnly")
                && cookie.contains("Secure")));
    }
}
