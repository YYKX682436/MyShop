package com.myshop.supplier.kayixin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.supplier.SupplierModels;
import com.myshop.supplier.SupplierPurchaseTransactions;
import com.myshop.supplier.SupplierPurchaseWorker;
import com.myshop.supplier.SupplierService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Raw-body callback verification and durable receipt; never logs payloads or cards. */
@RestController
@RequestMapping("/supplier-callback")
public class KayixinCallbackController {
    private final SupplierService suppliers;
    private final KayixinCallbackCodec codec;
    private final SupplierPurchaseTransactions transactions;
    private final SupplierPurchaseWorker worker;
    private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public KayixinCallbackController(SupplierService suppliers,KayixinCallbackCodec codec,
                                     SupplierPurchaseTransactions transactions,SupplierPurchaseWorker worker) {
        this.suppliers=suppliers; this.codec=codec; this.transactions=transactions; this.worker=worker;
    }
    @PostMapping(value="/{accountId}",produces=MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receive(@PathVariable UUID accountId,
                                          @RequestHeader(value="X-App-Id",required=false) String appId,
                                          @RequestHeader(value="X-Version",required=false) String version,
                                          @RequestHeader(value="X-Timestamp",required=false) String timestamp,
                                          @RequestHeader(value="X-Signature",required=false) String signature,
                                          @RequestBody byte[] raw) {
        try {
            var account=suppliers.runtime(accountId,false);
            if (!"KAYIXIN".equals(account.provider().type())) return failed();
            codec.verify(account.connection(),appId,version,timestamp,signature,raw,Clock.systemUTC());
            JsonNode data=json.readTree(raw);
            if (data==null || !data.isObject() || !data.path("status").isInt()) return failed();
            int status=data.path("status").asInt();
            if (!Set.of(0,1,2,3,4,5,7,8,9).contains(status)) return failed();
            String outer=required(data,"outerNumber",100), order=required(data,"orderNumber",120);
            JsonNode rawCards=data.path("cards");
            if (!rawCards.isMissingNode() && !rawCards.isNull() && !rawCards.isTextual()) return failed();
            String encrypted=rawCards.isTextual() && !rawCards.asText().isBlank() ? rawCards.asText() : null;
            List<SupplierModels.Card> cards=encrypted==null ? List.of() : codec.decryptCards(encrypted,account.connection().secret());
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            UUID purchase=transactions.acceptCallback(accountId,outer,order,status,hash,encrypted,cards);
            worker.enqueueRefresh(purchase);
            return ResponseEntity.ok("ok");
        } catch (Exception ignored) { return failed(); }
    }
    private String required(JsonNode data,String key,int max) {
        JsonNode value=data.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length()>max) throw new IllegalArgumentException();
        return value.asText();
    }
    private ResponseEntity<String> failed() { return ResponseEntity.badRequest().body("fail"); }
}
