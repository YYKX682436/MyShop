package com.myshop.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Provider event identifiers may be reused by different merchants or gateways. */
public final class PaymentEventIdentity {
    private PaymentEventIdentity() { }

    public static String scoped(String provider, String merchantTradeNo, String providerEventId) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(provider.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            hash.update((byte) 0);
            hash.update(merchantTradeNo.getBytes(StandardCharsets.UTF_8));
            hash.update((byte) 0);
            hash.update(providerEventId.getBytes(StandardCharsets.UTF_8));
            return "payment:" + HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
