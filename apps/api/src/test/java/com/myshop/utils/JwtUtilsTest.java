package com.myshop.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilsTest {

    @Test
    void weakSecretFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> new JwtUtils("short-secret", 86_400_000L));
    }
}
