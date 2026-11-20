package com.myshop.supplier.kayixin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.supplier.SupplierComplaintNotifications;
import com.myshop.supplier.SupplierService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

@RestController @RequestMapping("/supplier-callback/complaint")
public class KayixinComplaintCallbackController {
    private final SupplierService suppliers;
    private final SupplierComplaintNotifications notifications;
    private final KayixinCallbackCodec codec;
    private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public KayixinComplaintCallbackController(SupplierService suppliers,SupplierComplaintNotifications notifications,KayixinCallbackCodec codec) {
        this.suppliers=suppliers; this.notifications=notifications; this.codec=codec;
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
            codec.verify(account.connection(),appId,version,timestamp,signature,raw,Clock.systemUTC(),14_500_000);
            JsonNode body=json.readTree(raw);
            if (body==null || !body.isObject() || !body.path("reqId").isIntegralNumber()
                    || !body.path("reqId").canConvertToLong() || body.path("reqId").longValue()<1
                    || !body.path("status").isInt() || !body.path("replay").isTextual()
                    || !body.path("orderNumber").isTextual()) return failed();
            String order=body.path("orderNumber").asText(),reply=body.path("replay").asText();
            if (order.isBlank() || order.length()>120) return failed();
            JsonNode outerNode=body.path("outerNumber");
            if (!outerNode.isMissingNode() && !outerNode.isNull() && !outerNode.isTextual()) return failed();
            String outer=outerNode.isTextual() ? outerNode.asText() : null;
            if (outer!=null && outer.length()>100) return failed();
            JsonNode imageNode=body.path("base64Image"); List<String> images=new ArrayList<>();
            if (!imageNode.isMissingNode() && !imageNode.isNull()) {
                if (!imageNode.isArray() || imageNode.size()>5) return failed();
                for (JsonNode item:imageNode) { if (!item.isTextual()) return failed(); images.add(item.asText()); }
            }
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            notifications.accept(accountId,body.path("reqId").longValue(),order,outer,
                    body.path("status").intValue(),reply,hash,images);
            return ResponseEntity.ok("ok");
        } catch (Exception ignored) { return failed(); }
    }
    private ResponseEntity<String> failed() { return ResponseEntity.badRequest().body("fail"); }
}
