package com.myshop.model.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthResponseTest {

    @Test
    void publicAuthJsonContainsProfileButNeverJwt() throws Exception {
        UserProfileResponse profile = new UserProfileResponse();
        profile.setUsername("buyer");
        String json = new ObjectMapper().writeValueAsString(new AuthResponse(profile));

        assertTrue(json.contains("user"));
        assertFalse(json.contains("token"));
    }
}
