package com.myshop.model.response;

/** Internal result carrying the token only as far as AuthController. */
public record AuthResult(String token, UserProfileResponse user, int lifetimeDays) {
    public AuthResult(String token, UserProfileResponse user) {
        this(token, user, 30);
    }
}
