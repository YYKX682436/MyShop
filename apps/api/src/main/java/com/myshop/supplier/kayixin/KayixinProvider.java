package com.myshop.supplier.kayixin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.supplier.SupplierException;
import com.myshop.supplier.SupplierModels;
import com.myshop.supplier.SupplierProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class KayixinProvider implements SupplierProvider {
    private static final String VERSION = "3.0";
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final HttpClient client;

    @Autowired
    public KayixinProvider() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    KayixinProvider(HttpClient client) { this.client = client; }

    @Override public String type() { return "KAYIXIN"; }
    @Override public boolean supportsTransfer() { return true; }
    @Override public boolean supportsCdkeys() { return true; }
    @Override public List<com.myshop.model.CustomerInputField> customerFields(JsonNode snapshot) {
        return new KayixinRechargeTemplates().parse(snapshot);
    }

    public static String signature(String appId, String secret, String version, String timestamp, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update((appId + secret + version + timestamp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest(body));
        } catch (Exception ignored) { throw new IllegalStateException("MD5 unavailable"); }
    }

    private JsonNode call(SupplierModels.Connection connection, String path, Map<String, Object> payload) {
        return call(connection, path, payload, true);
    }
    private JsonNode call(SupplierModels.Connection connection, String path, Map<String, Object> payload, boolean retry) {
        return call(connection,path,payload,retry,true);
    }
    private JsonNode call(SupplierModels.Connection connection, String path, Map<String, Object> payload, boolean retry, boolean requireData) {
        final byte[] body;
        try { body = payload == null ? new byte[0] : json.writeValueAsBytes(payload); }
        catch (Exception ignored) { throw new SupplierException("DATA", "供应商请求数据无效"); }
        int attempts = retry ? 2 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            String timestamp = Long.toString(System.currentTimeMillis() / 1000);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(connection.baseUrl() + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("X-App-Id", connection.appId())
                    .header("X-Version", VERSION)
                    .header("X-Timestamp", timestamp)
                    .header("X-Signature", signature(connection.appId(), connection.secret(), VERSION, timestamp, body));
            if (payload != null) builder.header("Content-Type", "application/json; charset=utf-8");
            try {
                HttpRequest.BodyPublisher publisher=HttpRequest.BodyPublishers.ofByteArray(body);
                if (!retry) publisher=new SingleSubscriptionPublisher(publisher);
                HttpResponse<byte[]> response = client.send(builder.POST(publisher).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    if ((status == 429 || status >= 500) && attempt + 1 < attempts) continue;
                    throw new SupplierException("HTTP", "供应商 HTTP 请求失败（状态 " + status + "）");
                }
                if (response.body().length > 5_000_000) throw new SupplierException("DATA", "供应商响应过大");
                JsonNode root;
                try { root = json.readTree(response.body()); }
                catch (java.io.IOException ignored) { throw new SupplierException("DATA", "供应商响应格式无效"); }
                if (root == null || !root.isObject() || !root.path("code").isIntegralNumber())
                    throw new SupplierException("DATA", "供应商响应格式无效");
                if (root.path("code").asInt() != 1000)
                    throw new SupplierException("BUSINESS", "供应商业务请求失败（代码 " + root.path("code").asInt() + "）");
                if (!root.hasNonNull("data")) {
                    if (requireData) throw new SupplierException("DATA", "供应商响应缺少数据");
                    return com.fasterxml.jackson.databind.node.NullNode.instance;
                }
                return root.get("data");
            } catch (SupplierException e) { throw e; }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SupplierException("CONNECTION", "供应商连接已中断");
            } catch (java.io.IOException e) {
                if (attempt + 1 < attempts) continue;
                throw new SupplierException("CONNECTION", "无法连接供应商或请求超时");
            } catch (Exception e) {
                throw new SupplierException("DATA", "供应商响应格式无效");
            }
        }
        throw new SupplierException("CONNECTION", "无法连接供应商或请求超时");
    }

    @Override public SupplierModels.Account account(SupplierModels.Connection connection) {
        JsonNode data = call(connection, "/api/v3/user/getAccount", null);
        return new SupplierModels.Account(money(data, "balance"));
    }
    @Override public List<SupplierModels.Category> categories(SupplierModels.Connection connection) {
        return categoriesOf(call(connection, "/api/v3/goods/getDirs", null));
    }
    private List<SupplierModels.Category> categoriesOf(JsonNode nodes) {
        if (!nodes.isArray()) throw invalid();
        List<SupplierModels.Category> out = new ArrayList<>();
        for (JsonNode n : nodes) {
            JsonNode children = n.path("children");
            out.add(new SupplierModels.Category(text(n,"id"), text(n,"name"),
                    children.isMissingNode() || children.isNull() ? List.of() : categoriesOf(children)));
        }
        return List.copyOf(out);
    }
    @Override public SupplierModels.Page list(SupplierModels.Connection connection, String keyword, String categoryId, int page) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("page", page);
        if (keyword != null && !keyword.isBlank()) body.put("keyWord", keyword);
        if (categoryId != null && !categoryId.isBlank()) body.put("dirId", categoryId);
        JsonNode data = call(connection, "/api/v3/goods/getList", body);
        JsonNode items = data.path("items");
        if (!items.isArray()) throw invalid();
        List<SupplierModels.Summary> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode n : items) {
            String id = text(n,"goodsId");
            if (seen.add(id)) result.add(new SupplierModels.Summary(id,text(n,"name"), integer(n,"goodsType"),
                    integer(n,"status"), integer(n,"skuType"), money(n,"salesPrice")));
        }
        return new SupplierModels.Page(List.copyOf(result), optionalInteger(data,"allCount"), optionalInteger(data,"allPage"));
    }
    @Override public SupplierModels.Product detail(SupplierModels.Connection connection, String goodsId) {
        if (!goodsId.matches("[0-9]{1,18}")) throw new SupplierException("DATA", "供应商商品编号无效");
        JsonNode n = call(connection, "/api/v3/goods/getDetail", Map.of("goodsId", Long.parseLong(goodsId)));
        String id = text(n,"goodsId");
        if (!id.equals(goodsId)) throw invalid();
        int goodsType=integer(n,"goodsType"), skuType=integer(n,"skuType");
        if ((goodsType != 1 && goodsType != 3) || skuType < 0 || skuType > 2) throw invalid();
        int multiple=integer(n,"multiple"), min=integer(n,"minQuantity"), max=integer(n,"maxQuantity");
        if (multiple < 1 || min < 0 || max < 0) throw invalid();
        List<SupplierModels.Sku> skus = new ArrayList<>();
        if (skuType == 0) {
            skus.add(new SupplierModels.Sku("", "", "", money(n,"salesPrice"), number(n,"stockCount"), 1));
        } else if (skuType == 1) {
            JsonNode details = n.path("goodsSku").path("details");
            if (!details.isArray()) throw invalid();
            for (JsonNode s : details) {
                List<String> labels = new ArrayList<>();
                if (s.path("names").isArray()) for (JsonNode name : s.path("names")) labels.add(text(name,"value"));
                skus.add(sku(s, String.join(" / ", labels), ""));
            }
        } else {
            JsonNode groups = n.path("goodsSpec");
            if (!groups.isArray()) throw invalid();
            for (JsonNode group : groups) {
                String groupName = text(group,"name");
                if (!group.path("items").isArray()) throw invalid();
                for (JsonNode s : group.path("items")) skus.add(sku(s, text(s,"title"), groupName));
            }
        }
        if (skus.isEmpty()) throw invalid();
        Set<String> ids = new HashSet<>();
        for (var sku : skus) if (!ids.add(sku.id())) throw invalid();
        return new SupplierModels.Product(id,text(n,"name"),goodsType,integer(n,"status"),skuType,
                multiple,min,max,List.copyOf(skus),n.deepCopy());
    }
    @Override public SupplierModels.CreatedPurchase createPurchase(SupplierModels.Connection connection, SupplierModels.PurchaseRequest request) {
        if (!request.goodsId().matches("[0-9]{1,18}") || request.count() < 1 || request.safePrice().signum() < 0)
            throw invalid();
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("goodsId", Long.parseLong(request.goodsId()));
        body.put("count", request.count());
        body.put("notifyUrl", request.notifyUrl());
        body.put("outerNumber", request.outerNumber());
        body.put("safePrice", request.safePrice());
        body.put("sku", request.sku());
        body.put("attach", request.attach());
        // No automatic retry: even a transport error can follow a successful debit.
        JsonNode data = call(connection, "/api/v3/order/create", body, false);
        return new SupplierModels.CreatedPurchase(text(data,"orderNumber"), cards(data.path("cards"), false));
    }
    @Override public SupplierModels.PurchaseDetail purchaseDetail(SupplierModels.Connection connection, String outerNumber) {
        if (outerNumber == null || outerNumber.isBlank() || outerNumber.length() > 100) throw invalid();
        JsonNode n = call(connection, "/api/v3/order/getDetail", Map.of("outerNumber", outerNumber));
        int status = integer(n,"status");
        if (!Set.of(0,1,2,3,4,5,7,8,9).contains(status)) throw invalid();
        return new SupplierModels.PurchaseDetail(text(n,"orderNumber"), optionalText(n,"outerNumber"), status,
                integer(n,"count"), money(n,"money"), money(n,"retMoney"), optionalText(n,"result"),
                optionalText(n,"startCount"), optionalText(n,"nowCount"), optionalText(n,"endCount"), cards(n.path("cards"), true));
    }
    @Override public SupplierModels.RefundResult requestRefund(SupplierModels.Connection connection,String outerNumber) {
        if (outerNumber==null || outerNumber.isBlank() || outerNumber.length()>100) throw invalid();
        int status=integer(call(connection,"/api/v3/order/refunds",Map.of("outerNumber",outerNumber),false),"status");
        if (!Set.of(0,1,2,3,4,5,7,8,9).contains(status)) throw invalid();
        return new SupplierModels.RefundResult(status);
    }
    @Override public SupplierModels.ComplaintResult createComplaint(SupplierModels.Connection connection,SupplierModels.ComplaintRequest request) {
        if (request==null || request.orderNumber()==null || request.orderNumber().isBlank()
                || request.orderNumber().length()>120 || request.content()==null || request.content().isBlank()
                || request.content().codePointCount(0,request.content().length())>500
                || (request.topic()!=null && request.topic().codePointCount(0,request.topic().length())>50)
                || request.images()==null || request.images().size()>5) throw invalid();
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("orderNumber",request.orderNumber()); body.put("topic",request.topic());
        body.put("content",request.content()); body.put("notifyUrl",request.notifyUrl());
        body.put("base64Image",request.images());
        JsonNode data=call(connection,"/api/v3/complaint/create",body,false);
        JsonNode id=data.get("reqId");
        if (id==null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue()<1) throw invalid();
        return new SupplierModels.ComplaintResult(id.longValue());
    }
    @Override public SupplierModels.CardDetail cardDetail(SupplierModels.Connection connection,String cardId) {
        validateCardId(cardId);
        JsonNode data=call(connection,"/api/v3/card/getDetail",Map.of("cardId",cardId));
        String id=text(data,"cardId"); int status=cardStatus(data), cancel=integer(data,"cancelRequest");
        if (!cardId.equals(id) || (cancel!=0 && cancel!=1)) throw invalid();
        return new SupplierModels.CardDetail(id,status,cancel,optionalText(data,"result"));
    }
    @Override public SupplierModels.CardVoidResult voidCard(SupplierModels.Connection connection,String cardId) {
        validateCardId(cardId);
        JsonNode data=call(connection,"/api/v3/card/void",Map.of("cardId",cardId),false);
        return new SupplierModels.CardVoidResult(cardStatus(data),optionalText(data,"result"));
    }
    @Override public void transfer(SupplierModels.Connection connection,SupplierModels.TransferRequest request) {
        if (request==null || request.toUserNo()<1 || request.money()==null || request.money().signum()<=0
                || request.outerNumber()==null || request.outerNumber().isBlank() || request.outerNumber().length()>100
                || request.salePwd()!=null && request.salePwd().length()>128) throw invalid();
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("toUserNo",request.toUserNo()); body.put("money",request.money());
        body.put("outerNumber",request.outerNumber());
        if (request.salePwd()!=null && !request.salePwd().isEmpty()) body.put("salePwd",request.salePwd());
        call(connection,"/api/v3/user/transfer",body,false,false);
    }
    @Override public List<SupplierModels.Cdkey> createCdkeys(SupplierModels.Connection connection,SupplierModels.CdkeyRequest request) {
        if (request==null || request.goodsId()==null || !request.goodsId().matches("[0-9]{1,18}")
                || request.cardCount()<1 || request.cardCount()>1000 || request.count()<1
                || (request.shortStatus()!=0 && request.shortStatus()!=1) || request.cardLength()<5 || request.cardLength()>32
                || request.shortLength()<5 || request.shortLength()>32 || request.cardPrefix()==null || request.cardPrefix().length()>20
                || request.cardFaceValue()==null || request.cardFaceValue().signum()<0 || request.safePrice()==null
                || request.safePrice().signum()<=0 || request.cardDay()<-1
                || request.cardRemark()==null || request.cardRemark().length()>255) throw invalid();
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("goodsId",Long.parseLong(request.goodsId())); body.put("cardCount",request.cardCount());
        body.put("shortStatus",request.shortStatus()); body.put("cardPrefix",request.cardPrefix());
        body.put("cardLength",request.cardLength()); body.put("shortLength",request.shortLength());
        body.put("count",request.count()); body.put("cardFaceValue",request.cardFaceValue());
        body.put("safePrice",request.safePrice()); body.put("cardDay",request.cardDay()); body.put("cardRemark",request.cardRemark());
        JsonNode data=call(connection,"/api/v3/cdkey/create",body,false);
        if (!data.isArray() || data.size()!=request.cardCount()) throw invalid();
        List<SupplierModels.Cdkey> result=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for (JsonNode item:data) {
            String pwd=text(item,"pwd"), url=optionalText(item,"url");
            if (pwd.length()!=request.cardPrefix().length()+request.cardLength() || !pwd.startsWith(request.cardPrefix())
                    || pwd.chars().anyMatch(Character::isISOControl) || !seen.add(pwd)) throw invalid();
            if (url!=null && !url.isBlank()) {
                try {
                    URI uri=URI.create(url);
                    if (url.length()>2048 || uri.getHost()==null || uri.getUserInfo()!=null
                            || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) throw invalid();
                } catch (IllegalArgumentException e) { throw invalid(); }
            } else if (request.shortStatus()==1) throw invalid();
            result.add(new SupplierModels.Cdkey(pwd,url));
        }
        return List.copyOf(result);
    }
    private void validateCardId(String cardId) {
        if (cardId==null || cardId.isBlank() || cardId.length()>120) throw invalid();
    }
    private int cardStatus(JsonNode data) {
        int status=integer(data,"status");
        if (status<0 || status>4) throw invalid();
        return status;
    }
    public List<SupplierModels.Card> cards(JsonNode nodes, boolean requireStatus) {
        if (nodes.isMissingNode() || nodes.isNull()) return List.of();
        if (!nodes.isArray()) throw invalid();
        List<SupplierModels.Card> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode n : nodes) {
            String id=text(n,"cardId");
            String number=optionalText(n,"cardNo"), password=optionalText(n,"cardPwd");
            int status=requireStatus ? integer(n,"status") : (n.has("status") ? integer(n,"status") : 0);
            int cancel=integer(n,"cancelRequest");
            if (id.length()>120 || !ids.add(id) || status<0 || status>4 || (cancel!=0 && cancel!=1)
                    || ((number==null || number.isBlank()) && (password==null || password.isBlank()))) throw invalid();
            result.add(new SupplierModels.Card(id,number,password,status,cancel));
        }
        return List.copyOf(result);
    }
    private String optionalText(JsonNode n,String field) {
        JsonNode value=n.get(field);
        if (value==null || value.isNull()) return null;
        if (!value.isValueNode()) throw invalid();
        return value.asText();
    }
    private SupplierModels.Sku sku(JsonNode n, String name, String group) {
        String id = text(n,"sku");
        if (id.isBlank() || id.length() > 255) throw invalid();
        int status=integer(n,"status");
        if (status != 0 && status != 1) throw invalid();
        return new SupplierModels.Sku(id,name,group,money(n,"money"),number(n,"count"),status);
    }
    private String text(JsonNode n, String field) {
        JsonNode v=n.get(field);
        if (v == null || v.isNull() || !v.isValueNode() || v.asText().isBlank()) throw invalid();
        return v.asText();
    }
    private int integer(JsonNode n,String field) {
        JsonNode v=n.get(field);
        if (v == null || !v.canConvertToInt() || !v.isIntegralNumber()) throw invalid();
        return v.intValue();
    }
    private Integer optionalInteger(JsonNode n,String field) {
        JsonNode v=n.get(field); return v == null || !v.isIntegralNumber() || !v.canConvertToInt() ? null : v.intValue();
    }
    private long number(JsonNode n,String field) {
        JsonNode v=n.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue()<0) throw invalid();
        return v.longValue();
    }
    private BigDecimal money(JsonNode n,String field) {
        JsonNode v=n.get(field);
        if (v == null || !v.isNumber()) throw invalid();
        BigDecimal result=v.decimalValue();
        if (result.signum()<0 || result.scale()>8 || result.precision()-result.scale()>16) throw invalid();
        return result;
    }
    private SupplierException invalid() { return new SupplierException("DATA", "供应商商品数据不完整或格式无效"); }

    /** Even transport-level retry cannot replay the body of a money-changing request. */
    private static class SingleSubscriptionPublisher implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher delegate;
        private final AtomicBoolean subscribed=new AtomicBoolean();
        private SingleSubscriptionPublisher(HttpRequest.BodyPublisher delegate) { this.delegate=delegate; }
        @Override public long contentLength() { return delegate.contentLength(); }
        @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            if (subscribed.compareAndSet(false,true)) { delegate.subscribe(subscriber); return; }
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { }
            });
            subscriber.onError(new IllegalStateException("request body already sent"));
        }
    }
}
