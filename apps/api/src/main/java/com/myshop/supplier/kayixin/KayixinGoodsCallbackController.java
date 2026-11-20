package com.myshop.supplier.kayixin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.supplier.SupplierGoodsNotifications;
import com.myshop.supplier.SupplierService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

@RestController
@RequestMapping("/supplier-callback/goods")
public class KayixinGoodsCallbackController {
    private final SupplierService suppliers;
    private final SupplierGoodsNotifications events;
    private final KayixinCallbackCodec codec;
    private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public KayixinGoodsCallbackController(SupplierService suppliers,SupplierGoodsNotifications events,KayixinCallbackCodec codec) {
        this.suppliers=suppliers; this.events=events; this.codec=codec;
    }
    @PostMapping(value="/{accountId}",produces=MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receive(@PathVariable UUID accountId,
            @RequestHeader(value="X-App-Id",required=false) String appId,
            @RequestHeader(value="X-Version",required=false) String version,
            @RequestHeader(value="X-Timestamp",required=false) String timestamp,
            @RequestHeader(value="X-Signature",required=false) String signature,@RequestBody byte[] raw) {
        try {
            var account=suppliers.runtime(accountId,false);
            if (!"KAYIXIN".equals(account.provider().type())) return failed();
            codec.verify(account.connection(),appId,version,timestamp,signature,raw,Clock.systemUTC());
            JsonNode body=json.readTree(raw);
            JsonNode goods=body==null ? null : body.get("goodsId");
            if (!body.isObject() || goods==null || !goods.isValueNode() || !goods.asText().matches("[0-9]{1,18}")) return failed();
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update(timestamp.getBytes(StandardCharsets.UTF_8));
            String hash=HexFormat.of().formatHex(digest.digest(raw));
            UUID id=events.accept(accountId,goods.asText(),hash);
            events.enqueue(id);
            return ResponseEntity.ok("ok");
        } catch (Exception ignored) { return failed(); }
    }
    private ResponseEntity<String> failed() { return ResponseEntity.badRequest().body("fail"); }
}
