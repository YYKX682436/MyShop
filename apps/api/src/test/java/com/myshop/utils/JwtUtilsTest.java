package com.myshop.utils;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilsTest {

    @Test
    void weakSecretFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> new JwtUtils("short-secret", 86_400_000L));
    }

    @Test
    void tokenCarriesTheDatabaseAuthorityVersion() {
        JwtUtils jwtUtils = new JwtUtils(
                "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_=+",
                86_400_000L);
        UUID userId = UUID.randomUUID();

        String token = jwtUtils.generateToken(userId, "buyer", "USER", 7);

        assertEquals(userId, jwtUtils.getUserId(token));
        assertEquals("buyer", jwtUtils.getUsername(token));
        assertEquals("USER", jwtUtils.getRole(token));
        assertEquals(7, jwtUtils.getAuthVersion(token));
    }
}
