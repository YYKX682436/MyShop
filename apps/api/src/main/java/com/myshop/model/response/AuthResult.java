package com.myshop.model.response;

/** Internal result carrying the token only as far as AuthController. */
public record AuthResult(String token, UserProfileResponse user) {
}
