package com.myshop.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** One-time guest order lookup credentials. Only the digest is persisted. */
public final class LookupTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private LookupTokens() {
    }

    public static Issued issue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Issued(plaintext, hash(plaintext));
    }

    public static String hash(String token) {
        if (token == null || token.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    public static boolean matches(String token, String expectedHash) {
        if (token == null || expectedHash == null || expectedHash.isBlank()) return false;
        return MessageDigest.isEqual(
                hash(token).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    public record Issued(String plaintext, String hash) {
    }
}
