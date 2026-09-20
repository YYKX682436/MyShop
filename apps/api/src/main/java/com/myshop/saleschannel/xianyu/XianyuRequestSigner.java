package com.myshop.saleschannel.xianyu;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** HMAC-SHA256 signing contract shared with the Xianyu Agent integration API. */
final class XianyuRequestSigner {

    private XianyuRequestSigner() { }

    static String canonicalString(String method, String path, String timestamp, String nonce, byte[] rawBody) {
        return method.toUpperCase(java.util.Locale.ROOT) + "\n"
                + path + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + sha256Hex(rawBody);
    }

    static String signature(String secret, String canonicalString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonicalString.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
