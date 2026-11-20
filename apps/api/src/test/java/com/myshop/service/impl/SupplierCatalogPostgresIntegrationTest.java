package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.StockMode;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AdminProductStatusRequest;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.SupplierAccountRepository;
import com.myshop.repository.SupplierProductMappingRepository;
import com.myshop.service.AdminProductService;
import com.myshop.service.OrderService;
import com.myshop.service.ProductInventoryBatchService;
import com.myshop.supplier.SupplierException;
import com.myshop.supplier.SupplierModels;
import com.myshop.supplier.SupplierProvider;
import com.myshop.supplier.SupplierSecretCipher;
import com.myshop.supplier.SupplierService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIf(value="postgresAvailable", disabledReason="Disposable PostgreSQL required")
@SpringBootTest(classes={com.myshop.MyShopApplication.class,SupplierCatalogPostgresIntegrationTest.FixtureConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class SupplierCatalogPostgresIntegrationTest {
    static final IntegrationPostgres POSTGRES=new IntegrationPostgres();
    static boolean postgresAvailable() { return POSTGRES.available(); }
    @AfterAll static void close() { POSTGRES.close(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword); r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.flyway.enabled",()->true); r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("SUPPLIER_MASTER_KEY",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("supplier.sync-initial-delay-ms",()->"3600000");
        r.add("jwt.secret",()->"integration-test-secret-with-at-least-64-bytes-and-random-looking-value-2026");
        r.add("mail.enabled",()->false);
    }
    @TestConfiguration static class FixtureConfiguration {
        @Bean FixtureProvider fixtureProvider() { return new FixtureProvider(); }
    }
    static class FixtureProvider implements SupplierProvider {
        final Map<String,SupplierModels.Product> goods=new ConcurrentHashMap<>();
        final java.util.Set<String> failedGoods=ConcurrentHashMap.newKeySet();
        volatile boolean offline;
        volatile String blockedGoods;
        volatile java.util.concurrent.CountDownLatch entered;
        volatile java.util.concurrent.CountDownLatch release;
        volatile String workerName;
        @Override public String type() { return "FIXTURE"; }
        @Override public SupplierModels.Account account(SupplierModels.Connection c) {
            return new SupplierModels.Account(new BigDecimal(c.secret().equals("fake-secret-a") ? "10.00":"20.00"));
        }
        @Override public List<SupplierModels.Category> categories(SupplierModels.Connection c) { return List.of(); }
        @Override public SupplierModels.Page list(SupplierModels.Connection c,String k,String category,int page) { return new SupplierModels.Page(List.of(),0,0); }
        @Override public List<com.myshop.model.CustomerInputField> customerFields(com.fasterxml.jackson.databind.JsonNode snapshot) { return List.of(); }
        @Override public SupplierModels.CreatedPurchase createPurchase(SupplierModels.Connection c,SupplierModels.PurchaseRequest r) { throw new UnsupportedOperationException(); }
        @Override public SupplierModels.PurchaseDetail purchaseDetail(SupplierModels.Connection c,String outerNumber) { throw new UnsupportedOperationException(); }
        @Override public SupplierModels.Product detail(SupplierModels.Connection c,String id) {
            if (offline || failedGoods.contains(id)) throw new SupplierException("CONNECTION","无法连接供应商或请求超时");
            if (id.equals(blockedGoods)) {
                workerName=Thread.currentThread().getName(); entered.countDown();
                try { if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            }
            var p=goods.get(id); if (p==null) throw new SupplierException("DATA","测试商品不存在"); return p;
        }
    }
    @Autowired SupplierService service;
    @Autowired SupplierAccountRepository accounts;
    @Autowired SupplierProductMappingRepository mappings;
    @Autowired ProductRepository products;
    @Autowired AdminProductService adminProducts;
    @Autowired OrderService orders;
    @Autowired ProductInventoryBatchService inventory;
    @Autowired FixtureProvider fixture;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper json;
    @Autowired com.myshop.controller.AdminSupplierController controller;
    @Autowired WebApplicationContext webContext;
    @Autowired FilterChainProxy security;
    @Autowired com.myshop.utils.JwtUtils jwt;
    UUID category;

    @BeforeEach void setup() {
        fixture.offline=false; fixture.goods.clear(); fixture.failedGoods.clear(); fixture.blockedGoods=null;
        jdbc.update("delete from supplier_product_mappings");
        jdbc.update("delete from supplier_accounts");
        category=UUID.randomUUID();
        jdbc.update("insert into product_categories(id,name,sort_order,is_deleted) values (?,?,0,0)",category,"测试分类-"+category);
    }
    private SupplierService.AccountView account(String name,String secret) {
        return service.saveAccount(null,new SupplierService.AccountRequest(name,"FIXTURE","https://supplier.example","account-"+name,secret,true));
    }
    private SupplierModels.Product product(String id,int goodsType,int skuType,String cost,long stock) throws Exception {
        List<SupplierModels.Sku> skus=skuType==0 ? List.of(new SupplierModels.Sku("","","",new BigDecimal(cost),stock,1)) :
                List.of(new SupplierModels.Sku("A","第一项","组一",new BigDecimal(cost),stock,1),new SupplierModels.Sku("B","第二项","组二",new BigDecimal(cost),stock+1,0));
        return new SupplierModels.Product(id,"上游商品"+id,goodsType,1,skuType,2,1,20,skus,
                json.readTree("{\"rechargeTemplates\":[{\"type\":0},{\"type\":1},{\"type\":13},{\"type\":14},{\"type\":15},{\"type\":16,\"required\":1,\"regex\":\".*\",\"options\":[{\"value\":\"x\",\"children\":[{\"value\":\"y\"}],\"expand\":[{\"type\":0}]}]}],\"isRefOrder\":1,\"warrantyTime\":30}"));
    }

    @Test void encryptsCredentialsKeepsEditSecretAndSeparatesSitesWithSameGoodsId() throws Exception {
        var a=account("a","fake-secret-a"); var b=account("b","fake-secret-b");
        assertEquals("10.00",service.test(a.id()).balance().toPlainString());
        assertEquals("20.00",service.test(b.id()).balance().toPlainString());
        String stored=accounts.findById(a.id()).orElseThrow().getEncryptedSecret();
        assertFalse(stored.contains("fake-secret-a")); assertTrue(stored.startsWith("v1:"));
        assertFalse(json.writeValueAsString(service.listAccounts()).contains("fake-secret"));
        assertFalse(new SupplierService.AccountRequest("x","FIXTURE","https://x","x","fake-secret-a",true).toString().contains("fake-secret"));
        service.saveAccount(a.id(),new SupplierService.AccountRequest("renamed","FIXTURE","https://supplier.example","account-a","",true));
        assertEquals(stored,accounts.findById(a.id()).orElseThrow().getEncryptedSecret());
        assertEquals("10.00",service.test(a.id()).balance().toPlainString());
        fixture.goods.put("11",product("11",1,0,"0.01234567",5));
        var first=service.importProduct(a.id(),"11",category);
        var repeat=service.importProduct(a.id(),"11",category);
        var other=service.importProduct(b.id(),"11",category);
        assertTrue(first.created()); assertFalse(repeat.created()); assertEquals(first.productId(),repeat.productId());
        assertNotEquals(first.productId(),other.productId());
        assertThrows(SupplierException.class,()->service.saveAccount(a.id(),new SupplierService.AccountRequest("renamed","FIXTURE","https://another.example","account-a","",true)));
        assertEquals(1,service.mappings(a.id()).size()); assertEquals(1,service.mappings(b.id()).size());
        assertThrows(SupplierException.class,()->new SupplierSecretCipher("").encrypt("demo"));
    }

    @Test void accountApiAndLogsNeverReturnPlaintextSecret(CapturedOutput output) throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(webContext).addFilters(security).build();
        String body="{\"name\":\"api-fixture\",\"provider_type\":\"FIXTURE\",\"base_url\":\"https://supplier.example\",\"app_id\":\"fixture-id\",\"secret\":\"fixture-secret-never-in-output\",\"enabled\":true}";
        UUID adminId=UUID.randomUUID(); String username="supplier-admin-"+adminId;
        jdbc.update("insert into users (id,username,email,password_hash,role,is_deleted,user_no) values (?,?,?,'fixture-hash','ADMIN',0,null)",adminId,username,adminId+"@example.com");
        String token=jwt.generateToken(adminId,username,"ADMIN",0);
        var auth=new jakarta.servlet.http.Cookie("myshop_access_token",token);
        var csrf=new jakarta.servlet.http.Cookie("myshop_csrf","test-csrf");
        mvc.perform(get("/api/admin/suppliers").contextPath("/api")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/suppliers").contextPath("/api").with(user("buyer").roles("USER"))).andExpect(status().isForbidden());
        String result=mvc.perform(post("/api/admin/suppliers").contextPath("/api").cookie(auth,csrf).header("X-CSRF-Token","test-csrf").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(result.contains("provider_type")); assertTrue(result.contains("secret_mask"));
        assertFalse(result.contains("fixture-secret-never-in-output"));
        String accountId=json.readTree(result).path("data").path("id").asText();
        fixture.goods.put("60",product("60",3,2,"0.01234567",6));
        String detail=mvc.perform(get("/api/admin/suppliers/"+accountId+"/goods/60").contextPath("/api").cookie(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(detail.contains("goods_type")); assertTrue(detail.contains("sku_type"));
        String imported=mvc.perform(post("/api/admin/suppliers/"+accountId+"/goods/60/import").contextPath("/api")
                        .cookie(auth,csrf).header("X-CSRF-Token","test-csrf").contentType("application/json").content("{\"category_id\":\""+category+"\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(imported.contains("product_id"));
        String mappingResponse=mvc.perform(get("/api/admin/suppliers/"+accountId+"/mappings").contextPath("/api").cookie(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(mappingResponse.contains("remote_goods_id")); assertTrue(mappingResponse.contains("0.01234567"));
        assertTrue(json.readTree(mappingResponse).path("data").get(0).path("cost").isTextual());
        assertFalse(detail.contains("fixture-secret-never-in-output")); assertFalse(mappingResponse.contains("fixture-secret-never-in-output"));
        assertEquals(2,jdbc.queryForObject("select count(*) from operation_logs where user_id=? and action in ('supplier.create','supplier.import')",Integer.class,adminId));
        mvc.perform(post("/api/admin/suppliers/"+accountId+"/status").contextPath("/api").cookie(auth,csrf).header("X-CSRF-Token","test-csrf")
                .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());
        String bypass="{\"name\":\"api-fixture\",\"provider_type\":\"FIXTURE\",\"base_url\":\"https://supplier.example\",\"app_id\":\"fixture-id\",\"enabled\":false}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/suppliers/"+accountId).contextPath("/api").cookie(auth,csrf).header("X-CSRF-Token","test-csrf")
                .contentType("application/json").content(bypass)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/suppliers/"+accountId+"/status").contextPath("/api").cookie(auth,csrf).header("X-CSRF-Token","test-csrf")
                .contentType("application/json").content("{\"enabled\":false,\"confirmed\":true}"))
                .andExpect(status().isOk());
        assertEquals(0,jdbc.queryForObject("select count(*) from operation_logs where detail like '%fixture-secret-never-in-output%'",Integer.class));
        assertFalse(output.getAll().contains("fixture-secret-never-in-output"));
    }

    @Test void concurrentImportsCreateOnlyOneProduct() throws Exception {
        var a=account("concurrent","fake-secret-a"); fixture.goods.put("51",product("51",1,0,"0.01",5));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var one=executor.submit(() -> { start.await(); return service.importProduct(a.id(),"51",category); });
            var two=executor.submit(() -> { start.await(); return service.importProduct(a.id(),"51",category); });
            start.countDown();
            var left=one.get(10,java.util.concurrent.TimeUnit.SECONDS); var right=two.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(left.productId(),right.productId()); assertNotEquals(left.created(),right.created());
            assertEquals(1,service.mappings(a.id()).size());
        } finally { executor.shutdownNow(); }
    }

    @Test void singleSpecUsesEmptySkuAndDatabaseRejectsDuplicateRemoteIdentity() throws Exception {
        var a=account("empty-sku","fake-secret-a");
        fixture.goods.put("52",product("52",1,0,"0.01",5));
        var imported=service.importProduct(a.id(),"52",category);
        var mapping=service.mappings(a.id()).getFirst();
        assertEquals("",mapping.remoteSku());
        assertNull(mapping.specId());
        assertEquals(imported.productId(),mapping.productId());
        UUID anotherProduct=UUID.randomUUID();
        jdbc.update("insert into products (id,title,retail_price,cost_price,category_id,stock_mode) values (?,'other',1.00,1.00,?,'SUPPLIER')",anotherProduct,category);
        var remoteConflict=assertThrows(org.springframework.dao.DataIntegrityViolationException.class,() -> jdbc.update("""
                insert into supplier_product_mappings
                (id,account_id,remote_goods_id,remote_sku,product_id,goods_type,sku_type,cost,stock,
                 goods_status,sku_status,multiple,min_quantity,max_quantity,snapshot,last_synced_at,last_attempt_at)
                select gen_random_uuid(),account_id,remote_goods_id,remote_sku,?,goods_type,sku_type,cost,stock,
                 goods_status,sku_status,multiple,min_quantity,max_quantity,snapshot,last_synced_at,last_attempt_at
                from supplier_product_mappings where id=?
                """,anotherProduct,mapping.id()));
        assertTrue(remoteConflict.getMostSpecificCause().getMessage().contains("uq_supplier_mapping_remote"));
        var localConflict=assertThrows(org.springframework.dao.DataIntegrityViolationException.class,() -> jdbc.update("""
                insert into supplier_product_mappings
                (id,account_id,remote_goods_id,remote_sku,product_id,goods_type,sku_type,cost,stock,
                 goods_status,sku_status,multiple,min_quantity,max_quantity,snapshot,last_synced_at,last_attempt_at)
                select gen_random_uuid(),account_id,'different-goods',remote_sku,product_id,goods_type,sku_type,cost,stock,
                 goods_status,sku_status,multiple,min_quantity,max_quantity,snapshot,last_synced_at,last_attempt_at
                from supplier_product_mappings where id=?
                """,mapping.id()));
        assertTrue(localConflict.getMostSpecificCause().getMessage().contains("uq_supplier_mapping_local"));
    }

    @Test void credentialReplacementAndDisabledAccountAreIsolated() throws Exception {
        var a=account("edit-secret","fake-secret-a");
        String before=accounts.findById(a.id()).orElseThrow().getEncryptedSecret();
        service.saveAccount(a.id(),new SupplierService.AccountRequest("edit-secret","FIXTURE","https://supplier.example","account-edit-secret","fake-secret-b",true));
        String after=accounts.findById(a.id()).orElseThrow().getEncryptedSecret();
        assertNotEquals(before,after);
        assertEquals("20.00",service.test(a.id()).balance().toPlainString());
        assertThrows(SupplierException.class,()->new SupplierSecretCipher("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=").decrypt(after));
        service.setEnabled(a.id(),false);
        assertThrows(SupplierException.class,()->service.test(a.id()));
        fixture.goods.put("53",product("53",1,0,"0.01",5));
        assertThrows(SupplierException.class,()->service.importProduct(a.id(),"53",category));
        assertEquals(0,service.mappings(a.id()).size());
    }

    @Test void scheduledSyncEventuallyReachesProductsBeyondFirstBatch() throws Exception {
        var a=account("fairness","fake-secret-a");
        for (int index=0;index<25;index++) {
            String id=Integer.toString(1000+index);
            fixture.goods.put(id,product(id,1,0,"0.01",5));
            service.importProduct(a.id(),id,category);
            fixture.goods.put(id,product(id,1,0,"0.01",15));
        }
        jdbc.update("update supplier_product_mappings set last_attempt_at='2000-01-01' where account_id=?",a.id());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime()<deadline) {
            service.enqueueScheduledSync();
            if (service.mappings(a.id()).stream().filter(m->m.stock()==15).count()==25) break;
            Thread.sleep(30);
        }
        assertEquals(25,service.mappings(a.id()).stream().filter(m->m.stock()==15).count());
    }

    @Test void importsThreeModesAndSyncsWithoutOverwritingLocalEdits() throws Exception {
        var a=account("sync","fake-secret-a");
        for (int mode=0;mode<=2;mode++) {
            String id=Integer.toString(20+mode); fixture.goods.put(id,product(id,mode==1?3:1,mode,"0.01234567",5));
            var imported=service.importProduct(a.id(),id,category);
            var local=products.findById(imported.productId()).orElseThrow();
            assertFalse(local.isEnabled()); assertEquals(StockMode.SUPPLIER,local.getStockMode());
            var rows=mappings.findByAccountIdAndRemoteGoodsIdOrderByRemoteSku(a.id(),id);
            assertEquals(mode==0 ? 1:2,rows.size()); assertEquals(new BigDecimal("0.01234567"),rows.getFirst().getCost());
            var templates=rows.getFirst().getSnapshot().path("rechargeTemplates");
            assertEquals(List.of(0,1,13,14,15,16),java.util.stream.StreamSupport.stream(templates.spliterator(),false).map(n->n.path("type").asInt()).toList());
            assertEquals("y",templates.get(5).path("options").get(0).path("children").get(0).path("value").asText());
            assertTrue(templates.get(5).path("options").get(0).path("expand").isArray());
            assertEquals(2,rows.getFirst().getMultiple());
        }
        var row=service.mappings(a.id()).stream().filter(m->m.remoteGoodsId().equals("20")).findFirst().orElseThrow();
        jdbc.update("update products set title='本地名称',retail_price=9.99,description='本地描述' where id=?",row.productId());
        fixture.goods.put("20",product("20",1,0,"0.07543210",7));
        service.sync(a.id(),"20");
        var refreshed=service.mappings(a.id()).stream().filter(m->m.remoteGoodsId().equals("20")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("0.07543210"),refreshed.cost()); assertEquals(7,refreshed.stock()); assertNull(refreshed.syncError());
        var edited=products.findById(row.productId()).orElseThrow();
        assertEquals("本地名称",edited.getTitle()); assertEquals("本地描述",edited.getDescription());
        assertEquals(new BigDecimal("9.99"),edited.getRetailPrice()); assertEquals(new BigDecimal("0.16"),edited.getCostPrice());
        fixture.offline=true;
        assertThrows(SupplierException.class,()->service.sync(a.id(),"20"));
        var failed=service.mappings(a.id()).stream().filter(m->m.remoteGoodsId().equals("20")).findFirst().orElseThrow();
        assertEquals("CONNECTION",failed.syncError()); assertEquals(7,failed.stock()); assertEquals(refreshed.lastSyncedAt(),failed.lastSyncedAt());
        var stock=inventory.load(List.of(edited),Map.of()).values().iterator().next(); assertEquals(0,stock.available());
    }

    @Test void syncUpdatesSkuStatusAndContinuesPastAnOfflineProduct() throws Exception {
        var a=account("statuses","fake-secret-a");
        fixture.goods.put("40",product("40",3,1,"0.03",5));
        fixture.goods.put("41",product("41",1,0,"0.05",5));
        var imported=service.importProduct(a.id(),"40",category);
        service.importProduct(a.id(),"41",category);
        var old=fixture.goods.get("40");
        var updated=new SupplierModels.Product(old.id(),old.name(),old.goodsType(),2,old.skuType(),old.multiple(),old.minQuantity(),old.maxQuantity(),
                List.of(new SupplierModels.Sku("A","第一项","组一",new BigDecimal("0.09000000"),2,0),
                        new SupplierModels.Sku("B","第二项","组二",new BigDecimal("0.04000000"),8,1)),old.snapshot());
        fixture.goods.put("40",updated); fixture.failedGoods.add("41");
        service.enqueueScheduledSync();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime()<deadline && service.mappings(a.id()).stream().noneMatch(m->m.remoteGoodsId().equals("41") && "CONNECTION".equals(m.syncError()))) Thread.sleep(20);
        var rows=service.mappings(a.id());
        var first=rows.stream().filter(x->x.remoteGoodsId().equals("40") && x.remoteSku().equals("A")).findFirst().orElseThrow();
        assertEquals(2,first.goodsStatus()); assertEquals(0,first.skuStatus()); assertEquals(2,first.stock());
        assertEquals(new BigDecimal("0.09000000"),first.cost());
        assertEquals("CONNECTION",rows.stream().filter(x->x.remoteGoodsId().equals("41")).findFirst().orElseThrow().syncError());
        assertFalse(products.findById(imported.productId()).orElseThrow().isEnabled());
    }

    @Test void scheduledNetworkWorkDoesNotOccupyTheCallingSchedulerThread() throws Exception {
        var a=account("worker","fake-secret-a"); fixture.goods.put("70",product("70",1,0,"0.01",5));
        service.importProduct(a.id(),"70",category);
        fixture.goods.put("71",product("71",1,0,"0.01",5));
        service.importProduct(a.id(),"71",category);
        fixture.goods.put("71",product("71",1,0,"0.01",15));
        var before=service.mappings(a.id()).stream().filter(m->m.remoteGoodsId().equals("70")).findFirst().orElseThrow().lastSyncedAt();
        fixture.entered=new java.util.concurrent.CountDownLatch(1); fixture.release=new java.util.concurrent.CountDownLatch(1);
        fixture.blockedGoods="70";
        long start=System.nanoTime(); service.enqueueScheduledSync();
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);
        try {
            assertTrue(fixture.entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("supplier-sync",fixture.workerName);
            assertNotNull(products.findById(service.mappings(a.id()).getFirst().productId()).orElseThrow());
            long otherDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime()<otherDeadline && service.mappings(a.id()).stream().filter(m->m.remoteGoodsId().equals("71")).findFirst().orElseThrow().stock()!=15) Thread.sleep(20);
            assertEquals(15,service.mappings(a.id()).stream().filter(m->m.remoteGoodsId().equals("71")).findFirst().orElseThrow().stock());
        } finally { fixture.release.countDown(); }
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime()<deadline && !service.mappings(a.id()).getFirst().lastSyncedAt().isAfter(before)) Thread.sleep(20);
        assertTrue(service.mappings(a.id()).getFirst().lastSyncedAt().isAfter(before));
    }

    @Test void externalProductCannotBeEnabledOrOrderedEvenIfDatabaseStatusIsForced() throws Exception {
        var a=account("guard","fake-secret-a"); fixture.goods.put("30",product("30",1,0,"0.01",9));
        UUID id=service.importProduct(a.id(),"30",category).productId();
        assertThrows(BusinessException.class,()->adminProducts.setStatus(id,new AdminProductStatusRequest(true)));
        jdbc.update("update products set is_enabled=true where id=?",id);
        long before=jdbc.queryForObject("select count(*) from orders",Long.class);
        var req=Map.<String,Object>of("product_id",id.toString(),"quantity",1,"payment_method","BALANCE");
        BusinessException ex=assertThrows(BusinessException.class,()->orders.createDirectOrder(req,null,"127.0.0.1","guest-session"));
        assertTrue(ex.getMessage().contains("暂未开放"));
        assertEquals(before,jdbc.queryForObject("select count(*) from orders",Long.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from card_keys where product_id=?",Integer.class,id));
    }

    @Test void externalProductCanEditLocalPresentationAndRetailButCannotChangeItsSource() throws Exception {
        var a=account("editor","fake-secret-a"); fixture.goods.put("80",product("80",1,0,"0.01234567",9));
        UUID id=service.importProduct(a.id(),"80",category).productId();
        var edited=new com.myshop.model.request.AdminProductUpsertRequest("本地标题","本地介绍","本地详情",null,
                new BigDecimal("99.00"),new BigDecimal("8.00"),category,10,false,false,0,0,
                "AUTO",List.of(),StockMode.SUPPLIER,null,List.of(),List.of());
        adminProducts.updateProduct(id,edited);
        var product=products.findById(id).orElseThrow();
        assertEquals("本地标题",product.getTitle()); assertEquals(new BigDecimal("8.00"),product.getRetailPrice());
        assertEquals(new BigDecimal("0.03"),product.getCostPrice());
        var changeSource=new com.myshop.model.request.AdminProductUpsertRequest("本地标题",null,null,null,
                new BigDecimal("0.02"),new BigDecimal("8.00"),category,10,false,false,0,0,
                "AUTO",List.of(),StockMode.CARD_KEY,null,List.of(),List.of());
        assertThrows(BusinessException.class,()->adminProducts.updateProduct(id,changeSource));
    }
}
