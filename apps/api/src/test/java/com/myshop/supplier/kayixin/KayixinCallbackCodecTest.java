package com.myshop.supplier.kayixin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.supplier.SupplierException;
import com.myshop.supplier.SupplierModels;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;

class KayixinCallbackCodecTest {
    private final KayixinCallbackCodec codec=new KayixinCallbackCodec(new KayixinProvider(),new ObjectMapper());
    @Test void decryptsPublishedExampleAndRejectsMalformedCiphertext() {
        // Public protocol example; these are not account credentials or live cards.
        var cards=codec.decryptCards("C8bR0FfVx+zMUZf/AJS9eyw3rKn+adBuHYqqnSFk09railKZVUvJV0fWO8w6q7OVOEw5RO04TfllBP1+8sjiMyj57JHp/ABJEtnygPhyYjr/6upkYGi0lAVTSfrxuJaf", "5fb9600dd400b5e0853caed93ebbfb4e");
        assertEquals(1,cards.size()); assertEquals("764",cards.getFirst().id());
        assertEquals("123456789",cards.getFirst().number()); assertEquals("",cards.getFirst().password());
        assertThrows(SupplierException.class,()->codec.decryptCards("broken", "5fb9600dd400b5e0853caed93ebbfb4e"));
    }
    @Test void verifiesExactBodyAccountAndTimeWindow() {
        var c=new SupplierModels.Connection("https://example.invalid","test-id","test-secret");
        byte[] body="{\"状态\":3}".getBytes(StandardCharsets.UTF_8);
        String ts="1760767397", sig=KayixinProvider.signature(c.appId(),c.secret(),"3.0",ts,body);
        Clock now=Clock.fixed(Instant.ofEpochSecond(Long.parseLong(ts)),ZoneOffset.UTC);
        codec.verify(c,c.appId(),"3.0",ts,sig,body,now);
        assertThrows(SupplierException.class,()->codec.verify(c,c.appId(),"3.0",ts,sig,"{}".getBytes(StandardCharsets.UTF_8),now));
        assertThrows(SupplierException.class,()->codec.verify(c,"other","3.0",ts,sig,body,now));
        assertThrows(SupplierException.class,()->codec.verify(c,c.appId(),"3.0",ts,sig,body,Clock.offset(now,java.time.Duration.ofMinutes(6))));
    }
}
