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
import com.myshop.supplier.SupplierCatalogTransaction;
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
    @Autowired com.myshop.supplier.SupplierCheckout checkout;
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

    private UUID localProduct(boolean specsEnabled) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into products (id,title,description,detail_md,retail_price,cost_price,category_id,stock_mode,spec_enabled,is_enabled) values (?,'本地名称','本地简介','本地详情',20,?,?, 'CARD_KEY',?,false)",
                id,specsEnabled ? null : BigDecimal.ONE,category,specsEnabled);
        return id;
    }
    private UUID localSpec(UUID product,String name,int price) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into product_specs (id,product_id,name,retail_price,cost_price) values (?,?,?,?,1)",id,product,name,price);
        return id;
    }
    private SupplierCatalogTransaction.BindingExpectation expected(SupplierService.MappingView target,SupplierService.MappingView source) {
        return new SupplierCatalogTransaction.BindingExpectation(target==null ? null : target.id(),source==null ? null : source.id(),
                source==null ? null : source.productId(),source==null ? null : source.specId());
    }
    private void bind(UUID account,String goods,UUID product,UUID spec,String sku,boolean move,
                      SupplierService.MappingView target,SupplierService.MappingView source) {
        service.bind(account,goods,product,spec,sku,true,move,expected(target,source));
    }
    private void addOrder(UUID order,UUID item,UUID product,UUID spec,String status) {
        jdbc.update("insert into orders (id,status,payment_method,total_amount,actual_amount) values (?,?,'BALANCE',20,20)",order,status);
        jdbc.update("insert into order_items (id,order_id,product_id,spec_id,product_title,quantity,unit_price,subtotal,cost_price_snapshot,retail_price_snapshot,product_no) select ?,?,?,?,'snapshot',1,20,20,1,20,product_no from products where id=?",
                item,order,product,spec,product);
    }
    private void removeOrder(UUID order) {
        jdbc.update("delete from supplier_aftercare_operations where purchase_id in (select id from supplier_purchases where order_id=?)",order);
        jdbc.update("delete from supplier_purchases where order_id=?",order);
        jdbc.update("delete from order_items where order_id=?",order);
        jdbc.update("delete from orders where id=?",order);
    }

    @Test void deletingSupplierProductUnbindsAndAllowsReimportWithoutChangingPurchase() throws Exception {
        var a=account("delete-reimport","fake-secret-a"); fixture.goods.put("201",product("201",1,0,"0.10",20));
        UUID old=service.importProduct(a.id(),"201",category).productId();
        UUID order=UUID.randomUUID(),item=UUID.randomUUID(),purchase=UUID.randomUUID();
        try {
            addOrder(order,item,old,null,"DELIVERED");
            jdbc.update("insert into supplier_purchases (id,order_id,order_item_id,account_id,remote_goods_id,remote_sku,goods_type,outer_number,buyer_quantity,multiple,actual_quantity,unit_cost,safe_price,status) values (?,?,?,?,?,'',1,?,1,2,2,0.1,0.2,'COMPLETED')",
                    purchase,order,item,a.id(),"201","fixture-"+purchase);
            adminProducts.deleteProduct(old);
            assertTrue(service.mappings(a.id()).isEmpty());
            UUID next=service.importProduct(a.id(),"201",category).productId();
            assertNotEquals(old,next);
            assertEquals("201",jdbc.queryForObject("select remote_goods_id from supplier_purchases where id=?",String.class,purchase));
            assertEquals(old,jdbc.queryForObject("select product_id from order_items where id=?",UUID.class,item));
        } finally { removeOrder(order); }
    }

    @Test void staleMappingCannotBeRemovedWhilePurchaseOrAftercareIsUnresolved() throws Exception {
        var a=account("stale-pending","fake-secret-a"); fixture.goods.put("202",product("202",1,0,"0.10",20));
        UUID old=service.importProduct(a.id(),"202",category).productId();
        var mapping=service.mappings(a.id()).getFirst();
        UUID order=UUID.randomUUID(),item=UUID.randomUUID(),purchase=UUID.randomUUID(),operation=UUID.randomUUID();
        try {
            addOrder(order,item,old,null,"DELIVERED");
            jdbc.update("insert into supplier_purchases (id,order_id,order_item_id,account_id,remote_goods_id,remote_sku,goods_type,outer_number,buyer_quantity,multiple,actual_quantity,unit_cost,safe_price,status) values (?,?,?,?,?,'',1,?,1,2,2,0.1,0.2,'UNKNOWN')",
                    purchase,order,item,a.id(),"202","fixture-"+purchase);
            assertThrows(SupplierException.class,()->adminProducts.deleteProduct(old));
            jdbc.update("update products set is_deleted=1,is_enabled=false where id=?",old);
            assertThrows(SupplierException.class,()->service.unbind(a.id(),mapping.id(),true));
            assertThrows(SupplierException.class,()->service.importProduct(a.id(),"202",category));
            jdbc.update("update supplier_purchases set status='COMPLETED' where id=?",purchase);
            jdbc.update("insert into supplier_aftercare_operations (id,account_id,purchase_id,kind,idempotency_key,operator_id,status) values (?,?,?,'REFUND',?,?,'UNKNOWN')",operation,a.id(),purchase,UUID.randomUUID(),UUID.randomUUID());
            assertThrows(SupplierException.class,()->service.unbind(a.id(),mapping.id(),true));
            jdbc.update("delete from supplier_aftercare_operations where id=?",operation);
            service.unbind(a.id(),mapping.id(),true);
            assertTrue(service.mappings(a.id()).isEmpty());
            assertNotEquals(old,service.importProduct(a.id(),"202",category).productId());
            assertEquals("202",jdbc.queryForObject("select remote_goods_id from supplier_purchases where id=?",String.class,purchase));
        } finally { removeOrder(order); }
    }

    @Test void legacyDeletedMappingCanBeMovedOrReimported() throws Exception {
        var a=account("stale-move","fake-secret-a"); fixture.goods.put("203",product("203",1,0,"0.10",20));
        UUID old=service.importProduct(a.id(),"203",category).productId();
        var source=service.mappings(a.id()).getFirst();
        jdbc.update("update products set is_deleted=1,is_enabled=false where id=?",old);
        UUID next=localProduct(false);
        bind(a.id(),"203",next,null,"",true,null,source);
        assertEquals(next,service.mappings(a.id()).getFirst().productId());
        adminProducts.deleteProduct(next);
        UUID deleted=service.importProduct(a.id(),"203",category).productId();
        jdbc.update("update products set is_deleted=1,is_enabled=false where id=?",deleted);
        assertNotEquals(deleted,service.importProduct(a.id(),"203",category).productId());
    }

    @Test void deletedLegacyMappingDoesNotBlockSyncOfAnotherBoundSku() throws Exception {
        var a=account("stale-sync","fake-secret-a"); fixture.goods.put("204",product("204",1,2,"0.10",20));
        UUID first=localProduct(true),second=localProduct(true),month=localSpec(first,"月卡",10),year=localSpec(second,"年卡",20);
        bind(a.id(),"204",first,month,"A",false,null,null);
        bind(a.id(),"204",second,year,"B",false,null,null);
        jdbc.update("update products set is_deleted=1,is_enabled=false where id=?",first);
        fixture.goods.put("204",product("204",1,2,"0.30",35));
        service.sync(a.id(),"204");
        var rows=service.mappings(a.id());
        assertEquals("PRODUCT_DELETED",rows.stream().filter(m -> m.productId().equals(first)).findFirst().orElseThrow().localState());
        assertEquals("LOCAL_DELETED",rows.stream().filter(m -> m.productId().equals(first)).findFirst().orElseThrow().syncError());
        assertEquals(new BigDecimal("0.60"),jdbc.queryForObject("select cost_price from product_specs where id=?",BigDecimal.class,year));
        jdbc.update("update products set is_deleted=1,is_enabled=false where id=?",second);
        assertTrue(mappings.findDue(org.springframework.data.domain.PageRequest.of(0,20)).stream().noneMatch(r -> "204".equals(r[1])));
    }

    @Test void editorCreatedProductBindsThreeIndependentSourcesAndStaysDownUntilComplete() throws Exception {
        var a=account("editor-create-a","fake-secret-a"); var b=account("editor-create-b","fake-secret-b");
        for (String id:List.of("210","211","212","213")) fixture.goods.put(id,product(id,1,0,"0.10",20));
        UUID month=UUID.randomUUID(),quarter=UUID.randomUUID(),year=UUID.randomUUID();
        var rows=List.of(
                new com.myshop.model.request.AdminProductSpecRequest(month,"月卡",new BigDecimal("0.20"),new BigDecimal("10.00"),true,0,null),
                new com.myshop.model.request.AdminProductSpecRequest(quarter,"季卡",new BigDecimal("0.20"),new BigDecimal("30.00"),true,1,null),
                new com.myshop.model.request.AdminProductSpecRequest(year,"年卡",new BigDecimal("0.20"),new BigDecimal("60.00"),true,2,null));
        var request=new com.myshop.model.request.AdminProductUpsertRequest("自定义会员","简介","自定义详情",null,
                null,new BigDecimal("10.00"),category,10,true,false,0,0,"AUTO",List.of(),StockMode.CARD_KEY,null,rows,List.of());
        UUID local=adminProducts.createProduct(request).id();
        bind(a.id(),"210",local,month,"",false,null,null);
        assertFalse(products.findById(local).orElseThrow().isEnabled());
        assertThrows(SupplierException.class,()->checkout.validateVisibleBindings(products.findById(local).orElseThrow(),List.of(month,quarter,year)));
        bind(a.id(),"211",local,quarter,"",false,null,null);
        bind(b.id(),"212",local,year,"",false,null,null);
        checkout.validateVisibleBindings(products.findById(local).orElseThrow(),List.of(month,quarter,year));
        var oldQuarter=service.productMappings(local).stream().filter(m -> quarter.equals(m.specId())).findFirst().orElseThrow();
        bind(b.id(),"213",local,quarter,"",false,oldQuarter,null);
        assertEquals("210",service.productMappings(local).stream().filter(m -> month.equals(m.specId())).findFirst().orElseThrow().remoteGoodsId());
        assertEquals("213",service.productMappings(local).stream().filter(m -> quarter.equals(m.specId())).findFirst().orElseThrow().remoteGoodsId());
        assertEquals("212",service.productMappings(local).stream().filter(m -> year.equals(m.specId())).findFirst().orElseThrow().remoteGoodsId());
        assertEquals("自定义会员",products.findById(local).orElseThrow().getTitle());
        assertEquals(new BigDecimal("30.00"),jdbc.queryForObject("select retail_price from product_specs where id=?",BigDecimal.class,quarter));
        assertFalse(products.findById(local).orElseThrow().isEnabled());
    }

    @Test void unbindWaitsForCheckoutAndRejectsItsPendingPurchase() throws Exception {
        var a=account("unbind-race","fake-secret-a"); fixture.goods.put("214",product("214",1,0,"0.10",20));
        UUID local=service.importProduct(a.id(),"214",category).productId();
        var mapping=service.mappings(a.id()).getFirst();
        UUID order=UUID.randomUUID(),item=UUID.randomUUID(),purchase=UUID.randomUUID();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        try {
            var checkout=pool.submit(()->new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("select id from products where id=? for update",UUID.class,local);
                locked.countDown();
                try { if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                addOrder(order,item,local,null,"PENDING");
                jdbc.update("insert into supplier_purchases (id,order_id,order_item_id,account_id,remote_goods_id,remote_sku,goods_type,outer_number,buyer_quantity,multiple,actual_quantity,unit_cost,safe_price,status) values (?,?,?,?,?,'',1,?,1,2,2,0.1,0.2,'WAITING_PAYMENT')",purchase,order,item,a.id(),"214","fixture-"+purchase);
                return null;
            }));
            assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var unbind=pool.submit(()-> { service.unbind(a.id(),mapping.id(),true); return null; });
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3); boolean waiting=false;
            while (System.nanoTime()<deadline) {
                if (jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query ilike '%products%'",Integer.class)>0) { waiting=true; break; }
                Thread.sleep(20);
            }
            assertTrue(waiting,"unbind must wait for the checkout product lock");
            release.countDown(); checkout.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertInstanceOf(SupplierException.class,assertThrows(java.util.concurrent.ExecutionException.class,()->unbind.get(5,java.util.concurrent.TimeUnit.SECONDS)).getCause());
            assertEquals(mapping.id(),service.mappings(a.id()).getFirst().id());
        } finally { release.countDown(); pool.shutdownNow(); removeOrder(order); }
    }

    @Test void returningToLocalStockRequiresUnbindingAndNoUnresolvedAftercare() throws Exception {
        var a=account("return-local","fake-secret-a"); fixture.goods.put("215",product("215",1,0,"0.10",20));
        UUID local=service.importProduct(a.id(),"215",category).productId();
        var mapping=service.mappings(a.id()).getFirst();
        var request=new com.myshop.model.request.AdminProductUpsertRequest("本地商品","简介","详情",null,
                new BigDecimal("0.20"),new BigDecimal("1.00"),category,10,false,false,0,0,"AUTO",List.of(),StockMode.CARD_KEY,null,List.of(),List.of());
        assertThrows(SupplierException.class,()->adminProducts.updateProduct(local,request));
        UUID order=UUID.randomUUID(),item=UUID.randomUUID(),purchase=UUID.randomUUID(),operation=UUID.randomUUID();
        try {
            addOrder(order,item,local,null,"DELIVERED");
            jdbc.update("insert into supplier_purchases (id,order_id,order_item_id,account_id,remote_goods_id,remote_sku,goods_type,outer_number,buyer_quantity,multiple,actual_quantity,unit_cost,safe_price,status) values (?,?,?,?,?,'',1,?,1,2,2,0.1,0.2,'COMPLETED')",purchase,order,item,a.id(),"215","fixture-"+purchase);
            service.unbind(a.id(),mapping.id(),true);
            jdbc.update("insert into supplier_aftercare_operations (id,account_id,purchase_id,kind,idempotency_key,operator_id,status) values (?,?,?,'REFUND',?,?,'UNKNOWN')",operation,a.id(),purchase,UUID.randomUUID(),UUID.randomUUID());
            assertThrows(SupplierException.class,()->adminProducts.updateProduct(local,request));
            jdbc.update("delete from supplier_aftercare_operations where id=?",operation);
            adminProducts.updateProduct(local,request);
            assertEquals(StockMode.CARD_KEY,products.findById(local).orElseThrow().getStockMode());
            assertFalse(products.findById(local).orElseThrow().isEnabled());
            assertEquals("215",jdbc.queryForObject("select remote_goods_id from supplier_purchases where id=?",String.class,purchase));
        } finally { removeOrder(order); }
    }

    @Test void bindsIndependentSpecsAcrossSitesAndSyncsOnlyMappedSku() throws Exception {
        var a=account("spec-a","fake-secret-a"); var b=account("spec-b","fake-secret-b");
        fixture.goods.put("111",product("111",1,0,"0.10",20));
        fixture.goods.put("112",product("112",1,2,"0.20",30));
        fixture.goods.put("113",product("113",1,0,"0.30",40));
        UUID local=localProduct(true), month=localSpec(local,"月卡",10), quarter=localSpec(local,"季卡",30), year=localSpec(local,"年卡",60);
        bind(a.id(),"111",local,month,"",false,null,null);
        bind(a.id(),"112",local,quarter,"A",false,null,null);
        bind(b.id(),"113",local,year,"",false,null,null);
        var all=service.productMappings(local);
        assertEquals(3,all.size());
        assertEquals(List.of("111","112","113"),List.of(
                all.stream().filter(x->month.equals(x.specId())).findFirst().orElseThrow().remoteGoodsId(),
                all.stream().filter(x->quarter.equals(x.specId())).findFirst().orElseThrow().remoteGoodsId(),
                all.stream().filter(x->year.equals(x.specId())).findFirst().orElseThrow().remoteGoodsId()));
        assertEquals(StockMode.SUPPLIER,products.findById(local).orElseThrow().getStockMode());
        var original=fixture.goods.get("112");
        fixture.goods.put("112",new SupplierModels.Product(original.id(),"远程改名",original.goodsType(),original.status(),original.skuType(),
                original.multiple(),original.minQuantity(),original.maxQuantity(),List.of(
                new SupplierModels.Sku("A","远程改名","",new BigDecimal("0.45"),8,1),
                new SupplierModels.Sku("NEW","新增规格","",new BigDecimal("0.60"),9,1)),original.snapshot()));
        service.sync(a.id(),"112");
        assertEquals(3,jdbc.queryForObject("select count(*) from product_specs where product_id=?",Integer.class,local));
        assertEquals(3,service.productMappings(local).size());
        assertEquals("季卡",jdbc.queryForObject("select name from product_specs where id=?",String.class,quarter));
        assertEquals(new BigDecimal("30.00"),jdbc.queryForObject("select retail_price from product_specs where id=?",BigDecimal.class,quarter));
        assertEquals(new BigDecimal("0.90"),jdbc.queryForObject("select cost_price from product_specs where id=?",BigDecimal.class,quarter));
        assertEquals(new BigDecimal("0.60"),jdbc.queryForObject("select cost_price from product_specs where id=?",BigDecimal.class,year));
        assertEquals(40,service.productMappings(local).stream().filter(x->year.equals(x.specId())).findFirst().orElseThrow().stock());
        assertEquals("本地名称",products.findById(local).orElseThrow().getTitle());
        assertEquals("本地详情",products.findById(local).orElseThrow().getDetailMd());
    }

    @Test void presentationIsImportedAndOnlyExplicitSyncReplacesLocalContent() throws Exception {
        var a=account("presentation","fake-secret-a"); var initial=product("115",1,0,"0.10",20);
        var snapshot=json.readTree("{\"imgUrl\":\"/img/main.png\",\"goodsDescribe\":\"简介\",\"goodsDetail\":\"<p>上游详情</p><img src='/img/a.png'>\",\"buyNotice\":\"购买前阅读\"}");
        fixture.goods.put("115",new SupplierModels.Product(initial.id(),initial.name(),initial.goodsType(),initial.status(),initial.skuType(),
                initial.multiple(),initial.minQuantity(),initial.maxQuantity(),initial.skus(),snapshot));
        UUID local=service.importProduct(a.id(),"115",category).productId();
        var imported=products.findById(local).orElseThrow();
        assertEquals("上游商品115",imported.getTitle()); assertEquals("简介",imported.getDescription());
        assertEquals("https://supplier.example/img/main.png",imported.getCoverUrl());
        assertTrue(imported.getDetailMd().contains("https://supplier.example/img/a.png"));
        assertTrue(imported.getDetailMd().contains("购买提醒"));
        jdbc.update("update products set title='自己命名',description='自己简介',detail_md='自己详情',retail_price=9.99 where id=?",local);
        service.sync(a.id(),"115");
        assertEquals("自己详情",products.findById(local).orElseThrow().getDetailMd());
        assertThrows(SupplierException.class,()->service.syncPresentation(a.id(),"115",local,false));
        service.syncPresentation(a.id(),"115",local,true);
        var updated=products.findById(local).orElseThrow();
        assertEquals("自己命名",updated.getTitle()); assertEquals(new BigDecimal("9.99"),updated.getRetailPrice());
        assertEquals(category,updated.getCategoryId()); assertEquals("简介",updated.getDescription());
        assertTrue(updated.getDetailMd().contains("上游详情"));
    }

    @Test void partiallyBoundLocalProductIsTakenDownAndCanStillEditNamesAndPrices() throws Exception {
        var a=account("edit-converted","fake-secret-a"); fixture.goods.put("117",product("117",1,0,"0.10",20));
        UUID local=localProduct(true),first=localSpec(local,"月卡",10),second=localSpec(local,"季卡",30);
        jdbc.update("update products set is_enabled=true where id=?",local);
        bind(a.id(),"117",local,first,"",false,null,null);
        assertFalse(products.findById(local).orElseThrow().isEnabled());
        var requested=List.of(
                new com.myshop.model.request.AdminProductSpecRequest(first,"新月卡",new BigDecimal("99"),new BigDecimal("12"),true,0,null),
                new com.myshop.model.request.AdminProductSpecRequest(second,"新季卡",new BigDecimal("99"),new BigDecimal("32"),true,1,null));
        var update=new com.myshop.model.request.AdminProductUpsertRequest("我的商品","简介","详情",null,
                null,new BigDecimal("12"),category,10,true,false,0,0,"AUTO",List.of(),StockMode.SUPPLIER,null,requested,List.of());
        adminProducts.updateProduct(local,update);
        assertFalse(products.findById(local).orElseThrow().isEnabled());
        assertEquals("我的商品",products.findById(local).orElseThrow().getTitle());
        assertEquals("新月卡",jdbc.queryForObject("select name from product_specs where id=?",String.class,first));
        assertEquals(new BigDecimal("12.00"),jdbc.queryForObject("select retail_price from product_specs where id=?",BigDecimal.class,first));
        assertEquals(new BigDecimal("0.20"),jdbc.queryForObject("select cost_price from product_specs where id=?",BigDecimal.class,first));
        assertEquals(new BigDecimal("1.00"),jdbc.queryForObject("select cost_price from product_specs where id=?",BigDecimal.class,second));
        assertThrows(BusinessException.class,()->orders.createDirectOrder(Map.of("product_id",local.toString(),"spec_id",first.toString(),"quantity",1,"payment_method","BALANCE"),null,"127.0.0.1","guest-session"));
    }

    @Test void syncUpdatesDifferentProductsBoundToOneUpstreamGoodsWithoutAddingSpecs() throws Exception {
        var a=account("split-goods","fake-secret-a"); fixture.goods.put("116",product("116",1,2,"0.10",20));
        UUID one=localProduct(true),two=localProduct(true),first=localSpec(one,"我的月卡",10),second=localSpec(two,"我的年卡",50);
        bind(a.id(),"116",one,first,"A",false,null,null);
        bind(a.id(),"116",two,second,"B",false,null,null);
        fixture.goods.put("116",product("116",1,2,"0.25",8));
        service.sync(a.id(),"116");
        assertEquals(new BigDecimal("0.50"),jdbc.queryForObject("select cost_price from product_specs where id=?",BigDecimal.class,first));
        assertEquals(new BigDecimal("0.50"),jdbc.queryForObject("select cost_price from product_specs where id=?",BigDecimal.class,second));
        assertEquals(2,jdbc.queryForObject("select count(*) from product_specs where product_id in (?,?)",Integer.class,one,two));
        assertEquals("我的月卡",jdbc.queryForObject("select name from product_specs where id=?",String.class,first));
        assertEquals("我的年卡",jdbc.queryForObject("select name from product_specs where id=?",String.class,second));
    }

    @Test void sourceMoveRequiresConfirmationAndPreservesHistoricalPurchase() throws Exception {
        var a=account("move","fake-secret-a"); fixture.goods.put("120",product("120",1,0,"0.10",20));
        UUID old=service.importProduct(a.id(),"120",category).productId(), target=localProduct(false);
        var source=service.mappings(a.id()).getFirst();
        UUID order=UUID.randomUUID(),item=UUID.randomUUID(),purchase=UUID.randomUUID(),operation=UUID.randomUUID();
        try {
            addOrder(order,item,old,null,"DELIVERED");
            jdbc.update("insert into supplier_purchases (id,order_id,order_item_id,account_id,remote_goods_id,remote_sku,goods_type,outer_number,buyer_quantity,multiple,actual_quantity,unit_cost,safe_price,status) values (?,?,?,?,?,'',1,?,1,2,2,0.1,0.2,'UNKNOWN')",
                    purchase,order,item,a.id(),"120","fixture-"+purchase);
            assertThrows(SupplierException.class,()->bind(a.id(),"120",target,null,"",false,null,source));
            assertThrows(SupplierException.class,()->bind(a.id(),"120",target,null,"",true,null,source));
            jdbc.update("update supplier_purchases set status='COMPLETED' where id=?",purchase);
            jdbc.update("insert into supplier_aftercare_operations (id,account_id,purchase_id,kind,idempotency_key,operator_id,status) values (?,?,?,'REFUND',?,?,'UNKNOWN')",operation,a.id(),purchase,UUID.randomUUID(),UUID.randomUUID());
            assertThrows(SupplierException.class,()->bind(a.id(),"120",target,null,"",true,null,source));
            jdbc.update("delete from supplier_aftercare_operations where id=?",operation);
            bind(a.id(),"120",target,null,"",true,null,source);
            assertEquals(target,service.mappings(a.id()).getFirst().productId());
            assertTrue(service.productMappings(old).isEmpty());
            assertEquals("120",jdbc.queryForObject("select remote_goods_id from supplier_purchases where id=?",String.class,purchase));
            assertEquals(a.id(),jdbc.queryForObject("select account_id from supplier_purchases where id=?",UUID.class,purchase));
            assertEquals("",jdbc.queryForObject("select remote_sku from supplier_purchases where id=?",String.class,purchase));
            assertEquals("本地名称",products.findById(target).orElseThrow().getTitle());
        } finally { removeOrder(order); }
    }

    @Test void localCardKeysPreventConversionAndAreNotModified() throws Exception {
        var a=account("local-stock","fake-secret-a"); fixture.goods.put("130",product("130",1,0,"0.10",20));
        UUID local=localProduct(false), card=UUID.randomUUID();
        try {
            jdbc.update("insert into card_keys (id,product_id,content_parts,content_fingerprint,status) values (?,?,'[\"fixture\"]'::jsonb,?,'AVAILABLE')",card,local,"a".repeat(64));
            assertThrows(SupplierException.class,()->bind(a.id(),"130",local,null,"",false,null,null));
            assertEquals(StockMode.CARD_KEY,products.findById(local).orElseThrow().getStockMode());
            assertEquals("AVAILABLE",jdbc.queryForObject("select status from card_keys where id=?",String.class,card));
            assertTrue(service.productMappings(local).isEmpty());
        } finally { jdbc.update("delete from card_keys where id=?",card); }
    }

    @Test void reservedManualStockPreventsBinding() throws Exception {
        var a=account("blocked-manual-stock","fake-secret-a"); fixture.goods.put("131",product("131",1,0,"0.10",20));
        UUID local=localProduct(false), stock=UUID.randomUUID();
        try {
            jdbc.update("update products set stock_mode='MANUAL',delivery_type='MANUAL' where id=?",local);
            jdbc.update("insert into manual_stocks (id,product_id,available_quantity,reserved_quantity) values (?,?,0,1)",stock,local);
            assertThrows(SupplierException.class,()->bind(a.id(),"131",local,null,"",false,null,null));
            assertEquals(1,jdbc.queryForObject("select reserved_quantity from manual_stocks where id=?",Integer.class,stock));
            assertTrue(service.productMappings(local).isEmpty());
        } finally { jdbc.update("delete from manual_stocks where id=?",stock); }
    }

    @Test void concurrentConfirmedBindingsCannotSilentlyReplaceOneAnother() throws Exception {
        var a=account("race-a","fake-secret-a"); var b=account("race-b","fake-secret-b");
        fixture.goods.put("141",product("141",1,0,"0.10",20)); fixture.goods.put("142",product("142",1,0,"0.20",20));
        UUID local=localProduct(false);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2); var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var one=pool.submit(()-> { start.await(); try { bind(a.id(),"141",local,null,"",false,null,null); return true; } catch (SupplierException ex) { return false; } });
            var two=pool.submit(()-> { start.await(); try { bind(b.id(),"142",local,null,"",false,null,null); return true; } catch (SupplierException ex) { return false; } });
            start.countDown();
            assertNotEquals(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,service.productMappings(local).size());
            var winner=service.productMappings(local).getFirst();
            UUID loser=winner.accountId().equals(a.id()) ? b.id() : a.id();
            String goods=winner.accountId().equals(a.id()) ? "142" : "141";
            assertThrows(SupplierException.class,()->bind(loser,goods,local,null,"",false,null,null));
            bind(loser,goods,local,null,"",false,winner,null);
            assertEquals(loser,service.productMappings(local).getFirst().accountId());
        } finally { pool.shutdownNow(); }
    }

    @Test void conversionWaitsForCheckoutLockAndRejectsNewPendingOrder() throws Exception {
        var a=account("order-lock","fake-secret-a"); fixture.goods.put("150",product("150",1,0,"0.10",20));
        UUID local=localProduct(false),order=UUID.randomUUID(),item=UUID.randomUUID();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        try {
            var checkout=pool.submit(()->new TransactionTemplate(transactionManager).execute(status->{
                jdbc.queryForObject("select id from products where id=? for update",UUID.class,local);
                locked.countDown();
                try { if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                addOrder(order,item,local,null,"PENDING"); return null;
            }));
            assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var binding=pool.submit(()-> { bind(a.id(),"150",local,null,"",false,null,null); return null; });
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3); boolean waiting=false;
            while (System.nanoTime()<deadline) {
                if (jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query ilike '%products%'",Integer.class)>0) { waiting=true; break; }
                Thread.sleep(20);
            }
            assertTrue(waiting,"binding must wait for checkout product lock");
            release.countDown(); checkout.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertInstanceOf(SupplierException.class,assertThrows(java.util.concurrent.ExecutionException.class,()->binding.get(5,java.util.concurrent.TimeUnit.SECONDS)).getCause());
            assertTrue(service.productMappings(local).isEmpty());
            assertEquals(StockMode.CARD_KEY,products.findById(local).orElseThrow().getStockMode());
        } finally { release.countDown(); pool.shutdownNow(); removeOrder(order); }
    }

    @Test void checkoutCanPersistExistingSupplierSnapshotWhileReplacementWaits() throws Exception {
        var a=account("checkout-source","fake-secret-a"); var b=account("checkout-new","fake-secret-b");
        fixture.goods.put("151",product("151",1,0,"0.10",20)); fixture.goods.put("152",product("152",1,0,"0.20",20));
        UUID local=localProduct(false),order=UUID.randomUUID(),item=UUID.randomUUID(),purchase=UUID.randomUUID();
        bind(a.id(),"151",local,null,"",false,null,null); var target=service.productMappings(local).getFirst();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        try {
            var checkout=pool.submit(()->new TransactionTemplate(transactionManager).execute(status->{
                jdbc.queryForObject("select id from products where id=? for update",UUID.class,local); locked.countDown();
                try { if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                addOrder(order,item,local,null,"PENDING");
                jdbc.update("insert into supplier_purchases (id,order_id,order_item_id,account_id,remote_goods_id,remote_sku,goods_type,outer_number,buyer_quantity,multiple,actual_quantity,unit_cost,safe_price,status) values (?,?,?,?,?,'',1,?,1,2,2,0.1,0.2,'WAITING_PAYMENT')",
                        purchase,order,item,a.id(),"151","fixture-"+purchase);
                return null;
            }));
            assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var binding=pool.submit(()-> { bind(b.id(),"152",local,null,"",false,target,null); return null; });
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3); boolean waiting=false;
            while (System.nanoTime()<deadline) {
                if (jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query ilike '%products%'",Integer.class)>0) { waiting=true; break; }
                Thread.sleep(20);
            }
            assertTrue(waiting); release.countDown();
            checkout.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertInstanceOf(SupplierException.class,assertThrows(java.util.concurrent.ExecutionException.class,()->binding.get(10,java.util.concurrent.TimeUnit.SECONDS)).getCause());
            assertEquals(a.id(),service.productMappings(local).getFirst().accountId());
            assertEquals("151",jdbc.queryForObject("select remote_goods_id from supplier_purchases where id=?",String.class,purchase));
        } finally { release.countDown(); pool.shutdownNow(); removeOrder(order); }
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
        var source=json.readTree(mappingResponse).path("data").get(0);
        String unbindPath="/api/admin/suppliers/"+accountId+"/mappings/"+source.path("id").asText()+"/unbind";
        mvc.perform(post(unbindPath).contextPath("/api").contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(unbindPath).contextPath("/api").with(user("buyer").roles("USER"))
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(unbindPath).contextPath("/api").cookie(auth,csrf).header("X-CSRF-Token","test-csrf")
                        .contentType("application/json").content("{\"confirmed\":false}"))
                .andExpect(status().isBadRequest());
        UUID target=localProduct(true), targetSpec=localSpec(target,"自选规格",50);
        var bindBody=json.createObjectNode().put("product_id",target.toString()).put("spec_id",targetSpec.toString())
                .put("remote_sku",source.path("remote_sku").asText()).put("confirmed",true).put("move_confirmed",false)
                .put("expected_source_mapping_id",source.path("id").asText()).put("expected_source_product_id",source.path("product_id").asText())
                .put("expected_source_spec_id",source.path("spec_id").asText());
        String bindPath="/api/admin/suppliers/"+accountId+"/goods/60/bind";
        mvc.perform(post(bindPath).contextPath("/api").with(user("buyer").roles("USER")).cookie(csrf).header("X-CSRF-Token","test-csrf")
                .contentType("application/json").content(bindBody.toString())).andExpect(status().isForbidden());
        mvc.perform(post(bindPath).contextPath("/api").cookie(auth,csrf).header("X-CSRF-Token","test-csrf")
                .contentType("application/json").content(bindBody.toString())).andExpect(status().isBadRequest());
        bindBody.put("move_confirmed",true);
        mvc.perform(post(bindPath).contextPath("/api").cookie(auth,csrf).header("X-CSRF-Token","test-csrf")
                .contentType("application/json").content(bindBody.toString())).andExpect(status().isOk());
        assertEquals(1,jdbc.queryForObject("select count(*) from operation_logs where user_id=? and action='supplier.bind'",Integer.class,adminId));
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

    @Test void supplierProductActivationAndOrdersFollowSupplierAccountState() throws Exception {
        var a=account("guard","fake-secret-a"); fixture.goods.put("30",product("30",1,0,"0.01",9));
        UUID id=service.importProduct(a.id(),"30",category).productId();
        assertTrue(adminProducts.setStatus(id,new AdminProductStatusRequest(true)).enabled());
        adminProducts.setStatus(id,new AdminProductStatusRequest(false));
        service.setEnabled(a.id(),false);
        assertThrows(BusinessException.class,()->adminProducts.setStatus(id,new AdminProductStatusRequest(true)));
        jdbc.update("update products set is_enabled=true where id=?",id);
        long before=jdbc.queryForObject("select count(*) from orders",Long.class);
        String expectedPrice=products.findById(id).orElseThrow().getRetailPrice().toPlainString();
        var req=Map.<String,Object>of("product_id",id.toString(),"quantity",1,"payment_method","BALANCE",
                "expected_unit_price",expectedPrice);
        BusinessException ex=assertThrows(BusinessException.class,()->orders.createDirectOrder(req,null,"127.0.0.1","guest-session"));
        assertTrue(ex.getMessage().contains("停用"));
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
