package com.myshop.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.*;
import com.myshop.model.CustomerInputField;
import com.myshop.repository.*;
import com.myshop.supplier.*;
import com.myshop.supplier.kayixin.KayixinCallbackController;
import com.myshop.supplier.kayixin.KayixinProvider;
import com.myshop.service.OrderService;
import com.myshop.service.OrderAccessService;
import com.myshop.service.OrderRefundService;
import com.myshop.service.FulfillmentService;
import com.myshop.service.AdminProductService;
import com.myshop.service.PublicProductService;
import com.myshop.model.request.AdminProductStatusRequest;
import com.myshop.context.RequestContext;
import com.myshop.supplier.SupplierAdminOrders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIf(value="postgresAvailable",disabledReason="Disposable PostgreSQL required")
@SpringBootTest(classes={com.myshop.MyShopApplication.class,SupplierPurchasePostgresIntegrationTest.Config.class})
class SupplierPurchasePostgresIntegrationTest {
    static final IntegrationPostgres POSTGRES=new IntegrationPostgres();
    static boolean postgresAvailable(){return POSTGRES.available();}
    @AfterAll static void close(){POSTGRES.close();}
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword); r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.flyway.enabled",()->true);
        r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("SUPPLIER_MASTER_KEY",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("supplier.sync-initial-delay-ms",()->"3600000"); r.add("supplier.purchase-initial-delay-ms",()->"3600000");
        r.add("supplier.purchase-enabled",()->true);
        r.add("app.public-site-url",()->"https://shop.example");
        r.add("jwt.secret",()->"purchase-test-secret-at-least-64-bytes-long-2026-and-further-example-content");
        r.add("mail.enabled",()->false);
    }
    @TestConfiguration static class Config { @Bean FakeProvider fakeProvider(JdbcTemplate jdbc){return new FakeProvider(jdbc);} }
    static class FakeProvider implements SupplierProvider {
        final JdbcTemplate jdbc;
        FakeProvider(JdbcTemplate jdbc) { this.jdbc=jdbc; }
        volatile SupplierModels.Product product;
        volatile boolean loseResponse;
        volatile boolean absent;
        volatile boolean unsupportedFields;
        volatile BigDecimal balance=BigDecimal.valueOf(100);
        final AtomicInteger creates=new AtomicInteger();
        final Map<String,SupplierModels.PurchaseDetail> details=new ConcurrentHashMap<>();
        volatile SupplierModels.PurchaseRequest lastRequest;
        volatile boolean committedClaimObserved;
        @Override public String type(){return "PURCHASE_TEST";}
        @Override public SupplierModels.Account account(SupplierModels.Connection c){return new SupplierModels.Account(balance);}
        @Override public List<SupplierModels.Category> categories(SupplierModels.Connection c){return List.of();}
        @Override public SupplierModels.Page list(SupplierModels.Connection c,String k,String d,int p){return new SupplierModels.Page(List.of(),0,0);}
        @Override public SupplierModels.Product detail(SupplierModels.Connection c,String id){return product;}
        @Override public List<CustomerInputField> customerFields(JsonNode snapshot){
            if (unsupportedFields) return List.of(new CustomerInputField("image","图片",CustomerInputField.Type.IMAGE,true,null,null,null));
            return product.goodsType()==3 ? List.of(new CustomerInputField("account","充值账号",CustomerInputField.Type.TEXT,true,null,null,null)) : List.of();
        }
        @Override public SupplierModels.CreatedPurchase createPurchase(SupplierModels.Connection c,SupplierModels.PurchaseRequest request){
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            committedClaimObserved="SUBMITTING".equals(jdbc.queryForObject(
                    "select status from supplier_purchases where outer_number=?",String.class,request.outerNumber()));
            creates.incrementAndGet(); lastRequest=request;
            List<SupplierModels.Card> cards=product.goodsType()==3 ? List.of() : List.of(
                    new SupplierModels.Card(request.outerNumber()+"-1","test-number", "",0,1),
                    new SupplierModels.Card(request.outerNumber()+"-2","", "test-password",0,1));
            var detail=new SupplierModels.PurchaseDetail("UP-"+request.outerNumber(),request.outerNumber(),3,request.count(),
                    new BigDecimal("0.02"),BigDecimal.ZERO,"完成",null,null,null,cards);
            details.put(request.outerNumber(),detail);
            if (loseResponse) throw new SupplierException("CONNECTION","模拟响应丢失");
            return new SupplierModels.CreatedPurchase(detail.orderNumber(),cards);
        }
        @Override public SupplierModels.PurchaseDetail purchaseDetail(SupplierModels.Connection c,String outer){
            if (absent || !details.containsKey(outer)) throw new SupplierException("BUSINESS","模拟订单不存在");
            return details.get(outer);
        }
    }
    @Autowired FakeProvider fake;
    @Autowired SupplierService suppliers;
    @Autowired SupplierCheckout checkout;
    @Autowired SupplierPurchaseTransactions tx;
    @Autowired SupplierPurchaseWorker worker;
    @Autowired SupplierPurchaseRepository purchases;
    @Autowired SupplierAccountRepository accounts;
    @Autowired ProductRepository products;
    @Autowired OrderItemRepository items;
    @Autowired OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired KayixinCallbackController callbacks;
    @Autowired OrderService orderService;
    @Autowired OrderAccessService orderAccess;
    @Autowired OrderRefundService refunds;
    @Autowired FulfillmentService fulfillment;
    @Autowired AdminProductService adminProducts;
    @Autowired PublicProductService publicProducts;
    @Autowired SupplierAdminOrders adminOrders;
    @Autowired WebApplicationContext webContext;
    @Autowired FilterChainProxy security;

    @BeforeEach void reset(){fake.creates.set(0);fake.details.clear();fake.loseResponse=false;fake.absent=false;fake.unsupportedFields=false;fake.lastRequest=null;fake.balance=BigDecimal.valueOf(100);fake.committedClaimObserved=false;}

    private Fixture fixture(int goodsType,int skuType) throws Exception {
        String goodsId=Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits()%1_000_000_000L)+1);
        String sku=skuType==0 ? "" : "SKU-REAL";
        fake.product=new SupplierModels.Product(goodsId,"测试商品",goodsType,1,skuType,2,1,20,
                List.of(new SupplierModels.Sku(sku,"规格","",new BigDecimal("0.01"),20,1)),json.readTree("{}"));
        UUID category=UUID.randomUUID();
        jdbc.update("insert into product_categories(id,name,sort_order,is_deleted) values (?,?,0,0)",category,"test-"+category);
        var account=suppliers.saveAccount(null,new SupplierService.AccountRequest("test","PURCHASE_TEST","https://test.invalid","test-id","test-secret",true));
        var imported=suppliers.importProduct(account.id(),goodsId,category);
        UUID product=imported.productId();
        jdbc.update("update products set is_enabled=true where id=?",product);
        UUID spec=skuType==0 ? null : jdbc.queryForObject("select id from product_specs where product_id=?",UUID.class,product);
        UUID order=UUID.randomUUID(),item=UUID.randomUUID();
        jdbc.update("""
            insert into orders(id,actual_amount,total_amount,status,order_type,payment_method,payment_channel_code,session_token)
            values (?,10.00,10.00,'PAID','NORMAL','ONLINE','test','owner-session')
            """,order);
        jdbc.update("""
            insert into order_items(id,order_id,product_id,product_no,product_title,spec_id,quantity,unit_price,
                    cost_price_snapshot,retail_price_snapshot,pricing_source,subtotal,delivery_type,stock_mode,fulfillment_status)
            values(?,?,?,(select product_no from products where id=?),'测试商品',?,1,10,0.01,10,'RETAIL',10,'AUTO','SUPPLIER','PENDING')
            """,item,order,product,product,spec);
        UUID purchase=new TransactionTemplate(manager).execute(status -> {
            var line=items.findById(item).orElseThrow();
            Object input=goodsType==3 ? List.of(Map.of("id","account","value","test-account")) : null;
            var prep=checkout.prepare(products.findById(product).orElseThrow(),spec,1,BigDecimal.TEN,input);
            checkout.persist(line,prep);
            return purchases.findByOrderItemId(item).orElseThrow().getId();
        });
        return new Fixture(account.id(),product,order,item,purchase);
    }
    private record Fixture(UUID account,UUID product,UUID order,UUID item,UUID purchase){}
    private String value(String sql,UUID id){return jdbc.queryForObject(sql,String.class,id);}

    @Test void concurrentWorkersCreateOnlyOnceAndDeliverTwoCards() throws Exception {
        Fixture f=fixture(1,1);
        ExecutorService pool=Executors.newFixedThreadPool(8); CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<?>> work=new ArrayList<>();
            for(int i=0;i<8;i++) work.add(pool.submit(()->{start.await();worker.process(f.purchase());return null;}));
            start.countDown(); for(var task:work) task.get(15,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(1,fake.creates.get());
        assertTrue(fake.committedClaimObserved);
        assertEquals("SKU-REAL",fake.lastRequest.sku()); assertEquals(1,fake.lastRequest.count());
        assertEquals(0,new BigDecimal("0.02").compareTo(fake.lastRequest.safePrice()));
        assertEquals("DELIVERED",value("select status from orders where id=?",f.order()));
        assertEquals("DELIVERED",value("select fulfillment_status from order_items where id=?",f.item()));
        assertEquals(2,jdbc.queryForObject("select count(*) from supplier_delivery_cards where purchase_id=?",Integer.class,f.purchase()));
        worker.refresh(f.purchase());
        assertEquals(2,jdbc.queryForObject("select count(*) from supplier_delivery_cards where purchase_id=?",Integer.class,f.purchase()));
    }
    @Test void lostResponseIsRecoveredByTheOriginalNumberWithoutAnotherCreate() throws Exception {
        Fixture f=fixture(1,0); fake.loseResponse=true; worker.process(f.purchase());
        assertEquals(1,fake.creates.get()); assertEquals("COMPLETED",purchases.findById(f.purchase()).orElseThrow().getStatus());
        worker.process(f.purchase()); worker.refresh(f.purchase()); assertEquals(1,fake.creates.get());
    }
    @Test void queryNotFoundAfterCreateRemainsUnknownAndNeverRecreates() throws Exception {
        Fixture f=fixture(1,0); fake.absent=true; worker.process(f.purchase());
        assertEquals(1,fake.creates.get());
        assertEquals("UNKNOWN",purchases.findById(f.purchase()).orElseThrow().getStatus());
        worker.process(f.purchase()); assertEquals(1,fake.creates.get());
        fake.absent=false; worker.process(f.purchase());
        assertEquals("COMPLETED",purchases.findById(f.purchase()).orElseThrow().getStatus());
        assertEquals(1,fake.creates.get());
    }
    @Test void unknownPurchaseCannotUseLegacyCustomerRefund() throws Exception {
        Fixture f=fixture(1,0); UUID user=UUID.randomUUID();
        Long number=jdbc.queryForObject("select coalesce(max(user_no),9999)+1 from users",Long.class);
        UUID level=jdbc.queryForObject("select id from user_levels where is_default=true",UUID.class);
        jdbc.update("""
            insert into users(id,username,email,password_hash,role,is_deleted,user_no,user_level_id,balance)
            values (?,?,?,'test-hash','USER',0,?,?,5.00)
            """,user,"unknown-"+user,user+"@example.invalid",number,level);
        jdbc.update("update orders set user_id=?,session_token=null where id=?",user,f.order());
        assertTrue(tx.claim(f.purchase()));
        jdbc.update("update supplier_purchases set submit_started_at=current_timestamp-interval '2 minutes' where id=?",f.purchase());
        fake.absent=true; worker.process(f.purchase());
        assertEquals("UNKNOWN",purchases.findById(f.purchase()).orElseThrow().getStatus());
        try {
            RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"test-admin","ADMIN"));
            var e=assertThrows(com.myshop.exception.BusinessException.class,()->refunds.refund(f.order(),"核实",true,true));
            assertTrue(e.getMessage().contains("上游退款尚未接入"));
        } finally { RequestContext.clear(); }
        assertEquals("PAID",value("select status from orders where id=?",f.order()));
        assertEquals(0,new BigDecimal("5.00").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,user)));
        assertEquals(0,fake.creates.get());
    }
    @Test void claimedBeforeCrashAndMissingRemoteOrderNeverCausesAnotherCreate() throws Exception {
        Fixture f=fixture(1,0);
        String javaBinary=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Process process=new ProcessBuilder(javaBinary,"-cp",classpath,SupplierPurchaseCrashHelper.class.getName(),
                POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword(),f.purchase().toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15,TimeUnit.SECONDS),"crash simulator timed out");
            assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes()));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        assertFalse(tx.claim(f.purchase()));
        jdbc.update("update supplier_purchases set submit_started_at=current_timestamp-interval '2 minutes' where id=?",f.purchase());
        worker.process(f.purchase()); worker.process(f.purchase());
        assertEquals(0,fake.creates.get()); assertEquals("UNKNOWN",purchases.findById(f.purchase()).orElseThrow().getStatus());
        assertEquals("PAID",value("select status from orders where id=?",f.order()));
    }
    @Test void databaseEnforcesUniquePurchaseIntentAndOuterNumber() throws Exception {
        Fixture a=fixture(1,0), b=fixture(1,0);
        var first=purchases.findById(a.purchase()).orElseThrow();
        var second=purchases.findById(b.purchase()).orElseThrow();
        assertNotEquals(first.getOuterNumber(),second.getOuterNumber());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("update supplier_purchases set outer_number=? where id=?",first.getOuterNumber(),second.getId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("update supplier_purchases set order_item_id=? where id=?",first.getOrderItemId(),second.getId()));
    }
    @Test void priceRiseBlocksBeforeClaimAndLeavesPaymentIntact() throws Exception {
        Fixture f=fixture(1,0);
        fake.product=new SupplierModels.Product(fake.product.id(),"测试商品",1,1,0,2,1,20,
                List.of(new SupplierModels.Sku("","","",new BigDecimal("0.02"),20,1)),json.readTree("{}"));
        worker.process(f.purchase());
        var p=purchases.findById(f.purchase()).orElseThrow(); assertEquals("BLOCKED",p.getStatus());
        assertEquals("COST_EXCEEDED",p.getErrorCode()); assertEquals(0,fake.creates.get());
        assertEquals("PAID",value("select status from orders where id=?",f.order()));
    }
    @Test void claimRechecksLocalCostAndStockChangesWithoutCreating() throws Exception {
        Fixture price=fixture(1,0);
        jdbc.update("update supplier_product_mappings set cost=0.02 where product_id=?",price.product());
        assertFalse(tx.claim(price.purchase()));
        assertEquals("COST_EXCEEDED",purchases.findById(price.purchase()).orElseThrow().getErrorCode());
        Fixture stock=fixture(1,0);
        jdbc.update("update supplier_product_mappings set stock=1 where product_id=?",stock.product());
        assertFalse(tx.claim(stock.purchase()));
        assertEquals("STOCK",purchases.findById(stock.purchase()).orElseThrow().getErrorCode());
        assertEquals(0,fake.creates.get());
    }
    @Test void insufficientSupplierBalanceDoesNotCreatePurchase() throws Exception {
        Fixture f=fixture(1,0); fake.balance=BigDecimal.ZERO; worker.process(f.purchase());
        var p=purchases.findById(f.purchase()).orElseThrow(); assertEquals("BLOCKED",p.getStatus());
        assertEquals("BALANCE",p.getErrorCode()); assertEquals(0,fake.creates.get());
    }
    @Test void rechargeWithoutCardsCompletesAndRefundStatesRemainSeparate() throws Exception {
        Fixture f=fixture(3,2); worker.process(f.purchase());
        var p=purchases.findById(f.purchase()).orElseThrow(); assertEquals("COMPLETED",p.getStatus());
        assertEquals("DELIVERED",value("select status from orders where id=?",f.order()));
        assertEquals(0,jdbc.queryForObject("select count(*) from supplier_delivery_cards where purchase_id=?",Integer.class,p.getId()));
        var original=fake.details.get(p.getOuterNumber());
        tx.applyDetail(p.getId(),new SupplierModels.PurchaseDetail(original.orderNumber(),p.getOuterNumber(),9,1,new BigDecimal("0.02"),BigDecimal.ZERO,"退款中",null,null,null,List.of()),LocalDateTime.now().plusSeconds(1));
        assertEquals("REFUNDING",purchases.findById(p.getId()).orElseThrow().getStatus());
        tx.applyDetail(p.getId(),new SupplierModels.PurchaseDetail(original.orderNumber(),p.getOuterNumber(),5,1,new BigDecimal("0.02"),new BigDecimal("0.01"),"部分退款",null,null,null,List.of()),LocalDateTime.now().plusSeconds(2));
        p=purchases.findById(p.getId()).orElseThrow(); assertEquals("REFUNDED",p.getStatus());
        assertEquals(0,new BigDecimal("0.01").compareTo(p.getRefundedAmount()));
        assertEquals("DELIVERED",value("select status from orders where id=?",f.order()));
    }
    @Test void disabledAccountAllowsHistoryQueryButCannotClaimNewPurchase() throws Exception {
        Fixture f=fixture(3,0); assertTrue(tx.claim(f.purchase()));
        SupplierPurchase p=purchases.findById(f.purchase()).orElseThrow();
        fake.details.put(p.getOuterNumber(),new SupplierModels.PurchaseDetail("UP-X",p.getOuterNumber(),3,1,new BigDecimal("0.02"),BigDecimal.ZERO,"完成",null,null,null,List.of()));
        suppliers.setEnabled(f.account(),false); worker.refresh(f.purchase());
        assertEquals("COMPLETED",purchases.findById(p.getId()).orElseThrow().getStatus()); assertEquals(0,fake.creates.get());
        Fixture another=fixture(1,0); suppliers.setEnabled(another.account(),false);
        assertFalse(tx.claim(another.purchase())); assertEquals(0,fake.creates.get());
    }
    @Test void accountDisableSerializesWithThePurchaseClaim() throws Exception {
        Fixture f=fixture(1,0);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1);
        try {
            Future<?> disable=pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                var account=accounts.findForUpdate(f.account()).orElseThrow(); account.setEnabled(false);
                locked.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                return null;
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            CountDownLatch started=new CountDownLatch(1);
            Future<Boolean> claim=pool.submit(() -> { started.countDown(); return tx.claim(f.purchase()); });
            assertTrue(started.await(5,TimeUnit.SECONDS)); Thread.sleep(100); assertFalse(claim.isDone());
            release.countDown(); disable.get(5,TimeUnit.SECONDS);
            assertFalse(claim.get(5,TimeUnit.SECONDS)); assertEquals(0,fake.creates.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void afterSaleTransitionSerializesWithThePurchaseClaim() throws Exception {
        Fixture f=fixture(1,0);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1);
        try {
            Future<?> afterSale=pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                var order=orderRepository.findByIdForUpdate(f.order()).orElseThrow();
                order.setStatus(com.myshop.constant.OrderStatus.AFTER_SALE); locked.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                return null;
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            CountDownLatch started=new CountDownLatch(1);
            Future<Boolean> claim=pool.submit(() -> { started.countDown(); return tx.claim(f.purchase()); });
            assertTrue(started.await(5,TimeUnit.SECONDS)); Thread.sleep(100); assertFalse(claim.isDone());
            release.countDown(); afterSale.get(5,TimeUnit.SECONDS);
            assertFalse(claim.get(5,TimeUnit.SECONDS)); assertEquals(0,fake.creates.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void productDisableSerializesWithThePurchaseClaim() throws Exception {
        Fixture f=fixture(1,0);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1);
        try {
            Future<?> disable=pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                var product=products.findByIdForUpdate(f.product()).orElseThrow(); product.setEnabled(false); locked.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                return null;
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            CountDownLatch started=new CountDownLatch(1);
            Future<Boolean> claim=pool.submit(() -> { started.countDown(); return tx.claim(f.purchase()); });
            assertTrue(started.await(5,TimeUnit.SECONDS)); Thread.sleep(100); assertFalse(claim.isDone());
            release.countDown(); disable.get(5,TimeUnit.SECONDS);
            assertFalse(claim.get(5,TimeUnit.SECONDS));
            assertEquals("PRODUCT_DISABLED",purchases.findById(f.purchase()).orElseThrow().getErrorCode());
            assertEquals(0,fake.creates.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void signedDuplicateAndOutOfOrderCallbacksPersistOnceAndCannotRedeliver() throws Exception {
        Fixture f=fixture(1,0); assertTrue(tx.claim(f.purchase()));
        String secret="0123456789abcdef0123456789abcdef";
        suppliers.saveAccount(f.account(),new SupplierService.AccountRequest("test","PURCHASE_TEST","https://test.invalid","test-id",secret,true));
        jdbc.update("update supplier_accounts set provider_type='KAYIXIN',base_url='http://127.0.0.1:1' where id=?",f.account());
        var p=purchases.findById(f.purchase()).orElseThrow();
        String cards="[{\"cardId\":\"callback-1\",\"cardNo\":\"test-1\",\"cardPwd\":\"\",\"status\":0,\"cancelRequest\":1},{\"cardId\":\"callback-2\",\"cardNo\":\"\",\"cardPwd\":\"test-2\",\"status\":0,\"cancelRequest\":1}]";
        javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"AES"));
        String encrypted=Base64.getEncoder().encodeToString(cipher.doFinal(cards.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        byte[] body=("{\"orderNumber\":\"UP-CALLBACK\",\"outerNumber\":\""+p.getOuterNumber()+"\",\"status\":3,\"cards\":\""+encrypted+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String timestamp=Long.toString(System.currentTimeMillis()/1000);
        String signature=KayixinProvider.signature("test-id",secret,"3.0",timestamp,body);
        assertEquals("ok",callbacks.receive(f.account(),"test-id","3.0",timestamp,signature,body).getBody());
        assertEquals("ok",callbacks.receive(f.account(),"test-id","3.0",timestamp,signature,body).getBody());
        ExecutorService callbacksPool=Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> replies=new ArrayList<>();
            for (int i=0;i<4;i++) replies.add(callbacksPool.submit(() -> callbacks.receive(f.account(),"test-id","3.0",timestamp,signature,body).getBody()));
            for (var reply:replies) assertEquals("ok",reply.get(10,TimeUnit.SECONDS));
        } finally { callbacksPool.shutdownNow(); }
        assertEquals(400,callbacks.receive(f.account(),"test-id","3.0",timestamp,"00000000000000000000000000000000",body).getStatusCode().value());
        assertEquals(1,jdbc.queryForObject("select count(*) from supplier_callback_events where purchase_id=?",Integer.class,p.getId()));
        assertEquals(1,jdbc.queryForObject("select count(*) from supplier_callback_events where purchase_id=? and processed=false",Integer.class,p.getId()));
        assertEquals(2,jdbc.queryForObject("select count(*) from supplier_delivery_cards where purchase_id=?",Integer.class,p.getId()));
        tx.applyDetail(p.getId(),new SupplierModels.PurchaseDetail("UP-CALLBACK",p.getOuterNumber(),3,1,new BigDecimal("0.02"),BigDecimal.ZERO,"完成",null,null,null,List.of()),LocalDateTime.now().plusSeconds(1));
        assertEquals(0,jdbc.queryForObject("select count(*) from supplier_callback_events where purchase_id=? and processed=false",Integer.class,p.getId()));
        tx.applyDetail(p.getId(),new SupplierModels.PurchaseDetail("UP-CALLBACK",p.getOuterNumber(),1,1,new BigDecimal("0.02"),BigDecimal.ZERO,"进行中",null,null,null,List.of()),LocalDateTime.now().plusSeconds(2));
        assertEquals("COMPLETED",purchases.findById(p.getId()).orElseThrow().getStatus());
        assertEquals("DELIVERED",value("select status from orders where id=?",f.order()));
        assertEquals(2,jdbc.queryForObject("select count(*) from supplier_delivery_cards where purchase_id=?",Integer.class,p.getId()));
    }
    @Test void balanceCheckoutCreatesIntentDeliversCardsAndPreservesOwnership() throws Exception {
        runCheckout(1);
    }
    @Test void balanceCheckoutCompletesRechargeWithoutCards() throws Exception {
        runCheckout(3);
    }
    @Test void existingLocalCustomerFieldsArePreservedAlongsideSupplierFields() throws Exception {
        Fixture f=fixture(3,0); var product=products.findById(f.product()).orElseThrow();
        product.setCustomerInputFields(List.of(new CustomerInputField("local-contact","本地联系方式")));
        product=products.saveAndFlush(product);
        var request=List.of(Map.of("id","local-contact","value","contact"),Map.of("id","account","value","recharge"));
        var prepared=checkout.prepare(product,null,1,BigDecimal.TEN,request);
        assertEquals(2,prepared.inputs().values().size());
        assertEquals(1,prepared.inputs().attach().size());
        assertEquals("recharge",prepared.inputs().attach().getFirst().value());
        Product current=product;
        assertThrows(com.myshop.exception.BusinessException.class,()->checkout.prepare(current,null,1,BigDecimal.TEN,List.of(Map.of("id","account","value","recharge"))));
        assertEquals(List.of("local-contact","account"),publicProducts.getProduct(f.product(),null).customerInputFields().stream().map(CustomerInputField::id).toList());
    }
    @Test void administratorCanEnableSupportedFieldsButUnverifiedFieldsStayDisabled() throws Exception {
        Fixture f=fixture(3,0);
        jdbc.update("update products set is_enabled=false where id=?",f.product());
        fake.unsupportedFields=true;
        assertThrows(com.myshop.exception.BusinessException.class,()->adminProducts.setStatus(f.product(),new AdminProductStatusRequest(true)));
        assertFalse(products.findById(f.product()).orElseThrow().isEnabled());
        fake.unsupportedFields=false;
        adminProducts.setStatus(f.product(),new AdminProductStatusRequest(true));
        assertTrue(products.findById(f.product()).orElseThrow().isEnabled());
    }
    @Test void mixedOrderWaitsForBothSupplierAndManualDelivery() throws Exception {
        Fixture f=fixture(1,0); UUID local=UUID.randomUUID(), manualItem=UUID.randomUUID();
        jdbc.update("""
            insert into products(id,title,retail_price,cost_price,category_id,delivery_type,stock_mode,is_enabled)
            values (?,'manual-test',10,1,(select category_id from products where id=?),'MANUAL','UNLIMITED',true)
            """,local,f.product());
        jdbc.update("""
            insert into order_items(id,order_id,product_id,product_no,product_title,quantity,unit_price,cost_price_snapshot,
                 retail_price_snapshot,pricing_source,subtotal,delivery_type,stock_mode,fulfillment_status)
            values(?,?,?,(select product_no from products where id=?),'manual-test',1,10,1,10,'RETAIL',10,'MANUAL','UNLIMITED','PENDING')
            """,manualItem,f.order(),local,local);
        worker.process(f.purchase());
        assertEquals("PAID",value("select status from orders where id=?",f.order()));
        fulfillment.fulfillOrder(f.order());
        assertEquals("WAITING_MANUAL",value("select fulfillment_status from order_items where id=?",manualItem));
        assertEquals("DELIVERED",value("select fulfillment_status from order_items where id=?",f.item()));
        fulfillment.completeManualItems(f.order(),List.of(new FulfillmentService.ManualFulfillmentItem(manualItem,"完成")));
        assertEquals("DELIVERED",value("select status from orders where id=?",f.order()));
    }
    @Test void allDocumentedUpstreamStatusesMapWithoutInventingCustomerRefunds() throws Exception {
        Map<Integer,String> expected=Map.of(0,"UNPAID",1,"PROCESSING",2,"PROCESSING",3,"COMPLETED",
                4,"WITHDRAWN",5,"REFUNDED",7,"PROCESSING",8,"REFUNDING",9,"REFUNDING");
        for (var entry:expected.entrySet()) {
            Fixture f=fixture(3,0); assertTrue(tx.claim(f.purchase()));
            var p=purchases.findById(f.purchase()).orElseThrow();
            var detail=new SupplierModels.PurchaseDetail("UP-"+p.getOuterNumber(),p.getOuterNumber(),entry.getKey(),1,
                    new BigDecimal("0.02"),entry.getKey()==5 ? new BigDecimal("0.01") : BigDecimal.ZERO,
                    "处理结果",null,null,null,List.of());
            tx.applyDetail(p.getId(),detail,LocalDateTime.now());
            var updated=purchases.findById(p.getId()).orElseThrow();
            assertEquals(entry.getValue(),updated.getStatus()); assertEquals(entry.getKey(),updated.getUpstreamStatus());
            assertEquals(entry.getKey()==3 ? "DELIVERED" : "PAID",value("select status from orders where id=?",f.order()));
        }
    }
    @Test void guestSessionIsRequiredForSupplierCards() throws Exception {
        Fixture f=fixture(1,0); worker.process(f.purchase());
        assertTrue(orderAccess.exportCardKeys(f.order(),null,"owner-session").contains("test-password"));
        assertThrows(com.myshop.exception.BusinessException.class,()->orderAccess.getOrder(f.order(),null,"another-session"));
        assertThrows(com.myshop.exception.BusinessException.class,()->orderAccess.exportCardKeys(f.order(),null,"another-session"));
    }
    @Test void supplierCardsStayHiddenUntilUpstreamCompletionIsConfirmed() throws Exception {
        Fixture f=fixture(1,0); assertTrue(tx.claim(f.purchase()));
        tx.recordCreated(f.purchase(),new SupplierModels.CreatedPurchase("UP-HOLD-CARDS",List.of(
                new SupplierModels.Card("hold-1","number","",0,1),
                new SupplierModels.Card("hold-2","","password",0,1))));
        var view=orderAccess.getOrder(f.order(),null,"owner-session");
        assertTrue(((List<?>)((Map<?,?>)view.get("delivery")).get("groups")).isEmpty());
        assertThrows(com.myshop.exception.BusinessException.class,()->orderAccess.exportCardKeys(f.order(),null,"owner-session"));
        assertEquals("PAID",value("select status from orders where id=?",f.order()));
    }
    @Test void lateCreateResponseCannotUndoAnAuthoritativeRefundedCardStatus() throws Exception {
        Fixture f=fixture(1,0); assertTrue(tx.claim(f.purchase()));
        var p=purchases.findById(f.purchase()).orElseThrow();
        var refunded=new SupplierModels.Card("late-card","number","",2,1);
        tx.applyDetail(p.getId(),new SupplierModels.PurchaseDetail("UP-LATE",p.getOuterNumber(),5,1,
                new BigDecimal("0.02"),new BigDecimal("0.01"),"部分退款",null,null,null,List.of(refunded)),LocalDateTime.now());
        tx.recordCreated(p.getId(),new SupplierModels.CreatedPurchase("UP-LATE",List.of(new SupplierModels.Card("late-card","number","",0,1))));
        assertEquals("REFUNDED",purchases.findById(p.getId()).orElseThrow().getStatus());
        assertEquals(2,jdbc.queryForObject("select card_status from supplier_delivery_cards where purchase_id=?",Integer.class,p.getId()));
    }
    @Test void afterSalePausesNewDeliveryUntilOrderReturnsToPaid() throws Exception {
        Fixture f=fixture(3,0); assertTrue(tx.claim(f.purchase()));
        var p=purchases.findById(f.purchase()).orElseThrow();
        var detail=new SupplierModels.PurchaseDetail("UP-HOLD",p.getOuterNumber(),3,1,new BigDecimal("0.02"),BigDecimal.ZERO,"完成",null,null,null,List.of());
        jdbc.update("update orders set status='AFTER_SALE' where id=?",f.order());
        tx.applyDetail(p.getId(),detail,LocalDateTime.now());
        assertEquals("PENDING",value("select fulfillment_status from order_items where id=?",f.item()));
        assertEquals("AFTER_SALE",purchases.findById(p.getId()).orElseThrow().getErrorCode());
        jdbc.update("update orders set status='PAID' where id=?",f.order());
        tx.applyDetail(p.getId(),detail,LocalDateTime.now().plusSeconds(1));
        assertEquals("DELIVERED",value("select status from orders where id=?",f.order()));
    }
    @Test void callbackEndpointAcceptsOnlySignedAccountAndAdministratorRefreshIsScoped() throws Exception {
        Fixture f=fixture(1,0); assertTrue(tx.claim(f.purchase()));
        String secret="0123456789abcdef0123456789abcdef";
        suppliers.saveAccount(f.account(),new SupplierService.AccountRequest("test","PURCHASE_TEST","https://test.invalid","test-id",secret,true));
        jdbc.update("update supplier_accounts set provider_type='KAYIXIN',base_url='http://127.0.0.1:1' where id=?",f.account());
        var p=purchases.findById(f.purchase()).orElseThrow();
        byte[] body=("{\"orderNumber\":\"UP-HTTP\",\"outerNumber\":\""+p.getOuterNumber()+"\",\"status\":1}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String time=Long.toString(System.currentTimeMillis()/1000), signature=KayixinProvider.signature("test-id",secret,"3.0",time,body);
        var mvc=MockMvcBuilders.webAppContextSetup(webContext).addFilters(security).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/supplier-callback/{id}",f.account())
                .contextPath("/api").header("X-App-Id","test-id").header("X-Version","3.0")
                .header("X-Timestamp",time).header("X-Signature",signature).contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("ok"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/supplier-callback/{id}",f.account())
                .contextPath("/api").header("X-App-Id","wrong-id").header("X-Version","3.0")
                .header("X-Timestamp",time).header("X-Signature",signature).contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertThrows(com.myshop.exception.BusinessException.class,()->adminOrders.refresh(UUID.randomUUID(),p.getId()));
        assertEquals(1,jdbc.queryForObject("select count(*) from supplier_callback_events where purchase_id=?",Integer.class,p.getId()));
    }
    @Test void supplierRefreshRequiresAdminAndOnlyQueriesTheExistingPurchase() throws Exception {
        Fixture f=fixture(3,0); assertTrue(tx.claim(f.purchase()));
        var p=purchases.findById(f.purchase()).orElseThrow();
        fake.details.put(p.getOuterNumber(),new SupplierModels.PurchaseDetail("UP-ADMIN",p.getOuterNumber(),3,1,
                new BigDecimal("0.02"),BigDecimal.ZERO,"完成",null,null,null,List.of()));
        var mvc=MockMvcBuilders.webAppContextSetup(webContext).addFilters(security).build();
        String path="/api/admin/orders/{order}/supplier-purchases/{purchase}/refresh";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path,f.order(),p.getId()).contextPath("/api"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path,f.order(),p.getId()).contextPath("/api")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("buyer").roles("USER")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path,f.order(),p.getId()).contextPath("/api")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals(0,fake.creates.get()); assertEquals("COMPLETED",purchases.findById(p.getId()).orElseThrow().getStatus());
    }
    private void runCheckout(int goodsType) throws Exception {
        Fixture f=fixture(goodsType,0);
        jdbc.update("update products set retail_price=10.00 where id=?",f.product());
        UUID user=UUID.randomUUID(); Long userNo=jdbc.queryForObject("select coalesce(max(user_no),9999)+1 from users",Long.class);
        UUID level=jdbc.queryForObject("select id from user_levels where is_default=true",UUID.class);
        jdbc.update("""
            insert into users(id,username,email,password_hash,role,is_deleted,user_no,user_level_id,balance)
            values (?,?,?,'test-hash','USER',0,?,?,20.00)
            """,user,"buyer-"+user,user+"@example.invalid",userNo,level);
        Map<String,Object> request=new LinkedHashMap<>();
        request.put("product_id",f.product().toString()); request.put("quantity",1);
        request.put("payment_method","BALANCE"); request.put("expected_unit_price","10.00");
        request.put("idempotency_key","checkout-"+UUID.randomUUID()); request.put("idempotency_fingerprint","test-fingerprint");
        if (goodsType==3) request.put("customer_inputs",List.of(Map.of("id","account","value","my-account")));
        Map<?,?> order=(Map<?,?>)orderService.createDirectOrder(request,user,"127.0.0.1",null).get("order");
        UUID orderId=(UUID)order.get("id");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (!"DELIVERED".equals(value("select status from orders where id=?",orderId)) && System.nanoTime()<deadline) Thread.sleep(20);
        assertEquals("DELIVERED",value("select status from orders where id=?",orderId));
        assertEquals(1,fake.creates.get());
        assertEquals(0,new BigDecimal("10.00").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,user)));
        var p=purchases.findByOrderId(orderId).getFirst();
        assertEquals(2,p.getActualQuantity()); assertEquals(0,new BigDecimal("0.02").compareTo(p.getSafePrice()));
        if (goodsType==3) {
            assertEquals("my-account",p.getAttach().get(0).path("value").asText());
            assertEquals("完成",p.getResult());
        } else {
            assertTrue(orderAccess.exportCardKeys(orderId,user,null).contains("test-password"));
        }
        assertThrows(com.myshop.exception.BusinessException.class,()->orderAccess.getOrder(orderId,UUID.randomUUID(),null));
        assertThrows(com.myshop.exception.BusinessException.class,()->orderAccess.exportCardKeys(orderId,UUID.randomUUID(),null));
        try {
            RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"test-admin","ADMIN"));
            var error=assertThrows(com.myshop.exception.BusinessException.class,()->refunds.refund(orderId,"核实",true,true));
            assertTrue(error.getMessage().contains("上游退款尚未接入"));
        } finally { RequestContext.clear(); }
        assertEquals(0,new BigDecimal("10.00").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,user)));
        UUID again=(UUID)((Map<?,?>)orderService.createDirectOrder(request,user,"127.0.0.1",null).get("order")).get("id");
        assertEquals(orderId,again); assertEquals(1,fake.creates.get());
    }
}
