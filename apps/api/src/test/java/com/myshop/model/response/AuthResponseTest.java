package com.myshop.model.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthResponseTest {

    @Test
    void publicAuthJsonContainsProfileButNeverJwt() throws Exception {
        UserProfileResponse profile = new UserProfileResponse();
        profile.setUsername("buyer");
        profile.setBalance(new BigDecimal("12.30"));
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(new AuthResponse(profile));
        var userJson = objectMapper.readTree(json).get("user");

        assertTrue(json.contains("user"));
        assertFalse(json.contains("token"));
        assertEquals(0, new BigDecimal("12.30").compareTo(userJson.get("balance").decimalValue()));
        assertFalse(userJson.has("points"));
    }
}
