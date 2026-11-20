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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public static String signature(String appId, String secret, String version, String timestamp, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update((appId + secret + version + timestamp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest(body));
        } catch (Exception ignored) { throw new IllegalStateException("MD5 unavailable"); }
    }

    private JsonNode call(SupplierModels.Connection connection, String path, Map<String, Object> payload) {
        final byte[] body;
        try { body = payload == null ? new byte[0] : json.writeValueAsBytes(payload); }
        catch (Exception ignored) { throw new SupplierException("DATA", "供应商请求数据无效"); }
        for (int attempt = 0; attempt < 2; attempt++) {
            String timestamp = Long.toString(System.currentTimeMillis() / 1000);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(connection.baseUrl() + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("X-App-Id", connection.appId())
                    .header("X-Version", VERSION)
                    .header("X-Timestamp", timestamp)
                    .header("X-Signature", signature(connection.appId(), connection.secret(), VERSION, timestamp, body));
            if (payload != null) builder.header("Content-Type", "application/json; charset=utf-8");
            try {
                HttpResponse<byte[]> response = client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    if ((status == 429 || status >= 500) && attempt == 0) continue;
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
                if (!root.hasNonNull("data")) throw new SupplierException("DATA", "供应商响应缺少数据");
                return root.get("data");
            } catch (SupplierException e) { throw e; }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SupplierException("CONNECTION", "供应商连接已中断");
            } catch (java.io.IOException e) {
                if (attempt == 0) continue;
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
}
