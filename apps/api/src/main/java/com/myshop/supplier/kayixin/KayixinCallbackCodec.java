package com.myshop.supplier.kayixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.supplier.SupplierException;
import com.myshop.supplier.SupplierModels;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** Protocol-only validation; the caller must persist and correlate the event. */
@Component
public class KayixinCallbackCodec {
    private final KayixinProvider provider;
    private final ObjectMapper json;
    public KayixinCallbackCodec(KayixinProvider provider, ObjectMapper json) { this.provider=provider; this.json=json; }

    public void verify(SupplierModels.Connection connection, String appId, String version,
                       String timestamp, String signature, byte[] rawBody, Clock clock) {
        verify(connection,appId,version,timestamp,signature,rawBody,clock,1_000_000);
    }
    public void verify(SupplierModels.Connection connection, String appId, String version,
                       String timestamp, String signature, byte[] rawBody, Clock clock,int maxBytes) {
        if (!connection.appId().equals(appId) || !"3.0".equals(version)
                || timestamp == null || !timestamp.matches("[0-9]{10}")
                || signature == null || !signature.matches("[0-9a-fA-F]{32}")
                || rawBody == null || rawBody.length > maxBytes) throw invalid();
        long seconds;
        try { seconds=Long.parseLong(timestamp); }
        catch (NumberFormatException e) { throw invalid(); }
        if (Math.abs(clock.instant().getEpochSecond()-seconds)>300) throw invalid();
        byte[] supplied;
        try { supplied=HexFormat.of().parseHex(signature); }
        catch (IllegalArgumentException e) { throw invalid(); }
        byte[] expected=HexFormat.of().parseHex(KayixinProvider.signature(appId,connection.secret(),version,timestamp,rawBody));
        if (!MessageDigest.isEqual(expected,supplied)) throw invalid();
    }

    public List<SupplierModels.Card> decryptCards(String encrypted, String secret) {
        try {
            byte[] key=secret.getBytes(StandardCharsets.UTF_8);
            if (key.length!=32 || encrypted==null || encrypted.length()>1_000_000) throw invalid();
            Cipher cipher=Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"));
            byte[] clear=cipher.doFinal(Base64.getDecoder().decode(encrypted));
            JsonNode cards=json.readTree(clear);
            return provider.cards(cards,true);
        } catch (Exception ignored) { throw invalid(); }
    }

    private SupplierException invalid() { return new SupplierException("CALLBACK", "供应商回调验证失败"); }
}
