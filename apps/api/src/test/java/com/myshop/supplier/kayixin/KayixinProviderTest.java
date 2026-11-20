package com.myshop.supplier.kayixin;

import com.myshop.supplier.SupplierException;
import com.myshop.supplier.SupplierModels;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class KayixinProviderTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void publishedSigningExampleMatches() {
        // Public example from the supplied protocol document; not account credentials.
        String body="{\"orderNumber\":\"20251016165825069\",\"outerNumber\":\"\"}";
        assertEquals("6a414e658265ff3ca665cee3b161c4e9", KayixinProvider.signature("10000",
                "652fbdd2f637606a2afbb2fe0ca72419", "3.0", "1760767397", body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void readProtocolPreservesBytesAndParsesAllGoodsAndSkuModes() throws Exception {
        AtomicBoolean signed = new AtomicBoolean(true);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            byte[] body=exchange.getRequestBody().readAllBytes();
            String timestamp=exchange.getRequestHeaders().getFirst("X-Timestamp");
            signed.set(signed.get() && KayixinProvider.signature("demo-id","demo-secret","3.0",timestamp,body)
                    .equals(exchange.getRequestHeaders().getFirst("X-Signature")));
            String path=exchange.getRequestURI().getPath();
            String text=new String(body,StandardCharsets.UTF_8);
            String data;
            if (path.endsWith("getAccount")) data="{\"balance\":12.50}";
            else if (path.endsWith("getDirs")) data="[{\"id\":7,\"name\":\"分类\",\"children\":[]}]";
            else if (path.endsWith("getList")) {
                if (!text.contains("商品")) signed.set(false);
                data="{\"allCount\":1,\"allPage\":50,\"items\":[{\"goodsId\":11,\"name\":\"商品\",\"goodsType\":1,\"status\":1,\"skuType\":0,\"salesPrice\":0.01234567}]}";
            } else if (text.contains("11")) data=detail("11",1,0, "\"stockCount\":5");
            else if (text.contains("12")) data=detail("12",3,1,"\"goodsSku\":{\"details\":[{\"sku\":\"SKU-A\",\"names\":[{\"value\":\"蓝\"}],\"money\":0.12345678,\"count\":8,\"status\":1}]}");
            else data=detail("13",1,2,"\"goodsSpec\":[{\"name\":\"区域\",\"items\":[{\"sku\":\"REGION-A\",\"title\":\"一区\",\"money\":1.23,\"count\":2,\"status\":1}]},{\"name\":\"面值\",\"items\":[{\"sku\":\"FACE-B\",\"title\":\"一元\",\"money\":2.34,\"count\":3,\"status\":0}]}]");
            byte[] response=("{\"code\":1000,\"msg\":\"success\",\"data\":"+data+"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        var provider=new KayixinProvider(HttpClient.newHttpClient());
        var connection=new SupplierModels.Connection("http://127.0.0.1:"+server.getAddress().getPort(),"demo-id","demo-secret");
        assertEquals("12.5",provider.account(connection).balance().stripTrailingZeros().toPlainString());
        assertEquals("分类",provider.categories(connection).getFirst().name());
        assertEquals("0.01234567",provider.list(connection,"商品","",1).items().getFirst().cost().toPlainString());
        var single=provider.detail(connection,"11");
        var multi=provider.detail(connection,"12");
        var groups=provider.detail(connection,"13");
        assertEquals(1,single.goodsType()); assertEquals(0,single.skuType()); assertEquals(5,single.skus().getFirst().stock());
        assertEquals(3,multi.goodsType()); assertEquals("SKU-A",multi.skus().getFirst().id());
        assertEquals("0.12345678",multi.skus().getFirst().cost().toPlainString());
        assertEquals(2,groups.skus().size()); assertEquals("区域",groups.skus().getFirst().group());
        assertEquals("FACE-B",groups.skus().get(1).id());
        assertTrue(multi.snapshot().path("rechargeTemplates").get(0).path("options").get(0).path("children").isArray());
        assertTrue(signed.get());
    }
    private static String detail(String id,int goodsType,int skuType,String extra) {
        return "{\"goodsId\":"+id+",\"name\":\"测试商品\",\"goodsType\":"+goodsType+",\"status\":1,\"skuType\":"+skuType+
                ",\"multiple\":1,\"minQuantity\":1,\"maxQuantity\":10,\"salesPrice\":0.01,\"rechargeTemplates\":[{\"type\":16,\"required\":1,\"regex\":\".*\",\"options\":[{\"name\":\"a\",\"value\":\"v\",\"children\":[],\"expand\":[]}]}],"+extra+"}";
    }

    @Test void distinguishesHttpBusinessDataAndConnectionFailures() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/user/getAccount", exchange -> {
            String app=exchange.getRequestHeaders().getFirst("X-App-Id");
            int status=app.equals("http") ? 403 : 200;
            byte[] body=(app.equals("business") ? "{\"code\":1001,\"msg\":\"no\"}" : "invalid").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var p=new KayixinProvider(HttpClient.newHttpClient());
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        assertEquals("HTTP",assertThrows(SupplierException.class,()->p.account(new SupplierModels.Connection(base,"http","secret"))).kind());
        assertEquals("BUSINESS",assertThrows(SupplierException.class,()->p.account(new SupplierModels.Connection(base,"business","secret"))).kind());
        assertEquals("DATA",assertThrows(SupplierException.class,()->p.account(new SupplierModels.Connection(base,"data","secret"))).kind());
        server.stop(0); server=null;
        assertEquals("CONNECTION",assertThrows(SupplierException.class,()->p.account(new SupplierModels.Connection(base,"id","secret"))).kind());
    }

    @Test void purchaseIsSentOnceEvenWhenResponseIsLostOrServerFails() throws Exception {
        AtomicInteger count=new AtomicInteger(); AtomicBoolean signed=new AtomicBoolean(true);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/order/create", exchange -> {
            int n=count.incrementAndGet(); byte[] body=exchange.getRequestBody().readAllBytes();
            String timestamp=exchange.getRequestHeaders().getFirst("X-Timestamp");
            String text=new String(body,StandardCharsets.UTF_8);
            signed.set(signed.get() && text.contains("\"safePrice\":0.06") && text.contains("\"count\":2")
                    && text.contains("\"sku\":\"sku-1\"")
                    && KayixinProvider.signature("test-id","test-secret","3.0",timestamp,body)
                        .equals(exchange.getRequestHeaders().getFirst("X-Signature")));
            if (n==1) { exchange.close(); return; }
            exchange.sendResponseHeaders(503,-1); exchange.close();
        });
        server.start();
        var p=new KayixinProvider(HttpClient.newHttpClient());
        var c=new SupplierModels.Connection("http://127.0.0.1:"+server.getAddress().getPort(),"test-id","test-secret");
        var req=new SupplierModels.PurchaseRequest("12",2,"https://shop.invalid/callback","fixed-outer",new BigDecimal("0.06"),"sku-1",List.of());
        assertThrows(SupplierException.class,()->p.createPurchase(c,req));
        assertEquals(1,count.get());
        assertThrows(SupplierException.class,()->p.createPurchase(c,req));
        assertEquals(2,count.get()); assertTrue(signed.get());
    }

    @Test void purchaseDetailPreservesPartialRefundAndCardWithOnlyOneContentPart() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/order/getDetail", exchange -> {
            byte[] data=("{\"code\":1000,\"data\":{\"orderNumber\":\"up-1\",\"outerNumber\":\"outer-1\",\"status\":5,\"count\":1,\"money\":0.06,\"retMoney\":0.02,\"result\":\"\",\"cards\":[{\"cardId\":\"c1\",\"cardNo\":\"\",\"cardPwd\":\"test-card\",\"status\":0,\"cancelRequest\":1}]}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data); exchange.close();
        }); server.start();
        var p=new KayixinProvider(HttpClient.newHttpClient());
        var c=new SupplierModels.Connection("http://127.0.0.1:"+server.getAddress().getPort(),"id","secret");
        var detail=p.purchaseDetail(c,"outer-1");
        assertEquals(5,detail.status()); assertEquals(0,new BigDecimal("0.02").compareTo(detail.refunded()));
        assertEquals("",detail.cards().getFirst().number()); assertEquals("test-card",detail.cards().getFirst().password());
        assertFalse(detail.toString().contains("test-card"));
    }
    @Test void localServerCanCreateThenLoseResponseAndBeQueriedByOriginalOuterNumber() throws Exception {
        AtomicInteger creates=new AtomicInteger(); AtomicBoolean originalOuter=new AtomicBoolean();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v3/order/create", exchange -> {
            creates.incrementAndGet(); exchange.getRequestBody().readAllBytes(); exchange.close();
        });
        server.createContext("/api/v3/order/getDetail", exchange -> {
            originalOuter.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8).contains("fixed-outer"));
            byte[] result="{\"code\":1000,\"data\":{\"orderNumber\":\"up-1\",\"outerNumber\":\"fixed-outer\",\"status\":3,\"count\":1,\"money\":0.01,\"retMoney\":0,\"result\":\"ok\",\"cards\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,result.length); exchange.getResponseBody().write(result); exchange.close();
        }); server.start();
        var p=new KayixinProvider(HttpClient.newHttpClient());
        var c=new SupplierModels.Connection("http://127.0.0.1:"+server.getAddress().getPort(),"id","secret");
        var req=new SupplierModels.PurchaseRequest("12",1,"https://shop.invalid/callback","fixed-outer",new BigDecimal("0.01"),"",List.of());
        assertThrows(SupplierException.class,()->p.createPurchase(c,req));
        assertEquals(3,p.purchaseDetail(c,"fixed-outer").status());
        assertEquals(1,creates.get()); assertTrue(originalOuter.get());
    }
}
