package com.myshop.supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SupplierSecretCipher {
    private final String masterKey;
    private final SecureRandom random = new SecureRandom();

    public SupplierSecretCipher(@Value("${SUPPLIER_MASTER_KEY:}") String masterKey) {
        this.masterKey = masterKey;
    }

    private byte[] key() {
        try {
            byte[] key = Base64.getDecoder().decode(masterKey);
            if (key.length == 32) return key;
        } catch (IllegalArgumentException ignored) { }
        throw new SupplierException("CONFIG", "未配置有效的货源加密主密钥（需要 32 字节 Base64）");
    }

    public String encrypt(String secret) {
        byte[] key = key();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception ignored) {
            throw new SupplierException("CONFIG", "货源密钥加密失败");
        } finally { java.util.Arrays.fill(key, (byte) 0); }
    }

    public String decrypt(String encrypted) {
        byte[] key = key();
        try {
            String[] parts = encrypted.split(":", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            throw new SupplierException("CONFIG", "无法解密货源密钥，请检查加密主密钥");
        } finally { java.util.Arrays.fill(key, (byte) 0); }
    }
}
