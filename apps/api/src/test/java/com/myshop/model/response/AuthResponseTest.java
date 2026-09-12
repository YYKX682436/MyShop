package com.myshop.model.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthResponseTest {

    @Test
    void profileMappingCarriesThePersistedAvatarUrl() {
        User user = new User();
        user.setUsername("buyer");
        user.setRole(UserRole.USER);
        user.setAvatarUrl("/api/uploads/avatars/avatar-test.png");

        assertEquals("/api/uploads/avatars/avatar-test.png",
                UserProfileResponse.from(user).getAvatarUrl());
    }

    @Test
    void publicAuthJsonContainsProfileButNeverJwt() throws Exception {
        UserProfileResponse profile = new UserProfileResponse();
        profile.setUsername("buyer");
        profile.setBalance(new BigDecimal("12.30"));
        profile.setAvatarUrl("/api/uploads/avatars/avatar-test.png");
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        String json = objectMapper.writeValueAsString(new AuthResponse(profile));
        var userJson = objectMapper.readTree(json).get("user");

        assertTrue(json.contains("user"));
        assertFalse(json.contains("token"));
        assertEquals(0, new BigDecimal("12.30").compareTo(userJson.get("balance").decimalValue()));
        assertEquals("/api/uploads/avatars/avatar-test.png", userJson.get("avatar_url").asText());
        assertFalse(userJson.has("points"));
    }
}
