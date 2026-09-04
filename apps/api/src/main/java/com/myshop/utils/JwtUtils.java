package com.myshop.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtils {

    private final SecretKey key;
    private final long expiration;

    public JwtUtils(@Value("${jwt.secret}") String secret,
                    @Value("${jwt.expiration}") long expiration) {
        validateSecret(secret);
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    private void validateSecret(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 64
                || secret.chars().distinct().count() < 16) {
            throw new IllegalStateException(
                    "JWT_SECRET must contain at least 64 bytes and sufficient randomness");
        }
    }

    public String generateToken(UUID userId, String username, String role, int authVersion) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role)
                .claim("authVersion", authVersion)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    public String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }

    public String getRole(String token) {
        return parseToken(token).get("role", String.class);
    }

    public int getAuthVersion(String token) {
        Object value = parseToken(token).get("authVersion");
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("JWT authVersion claim is missing or invalid");
        }
        return number.intValue();
    }

    /**
     * Parse token safely, returning null if invalid instead of throwing.
     */
    public Claims parseTokenSafe(String token) {
        try {
            return parseToken(token);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValid(String token) {
        return parseTokenSafe(token) != null;
    }
}
