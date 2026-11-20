package com.myshop.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.entity.*;
import com.myshop.model.CustomerInputField;
import com.myshop.repository.*;
import com.myshop.supplier.*;
import com.myshop.supplier.kayixin.KayixinCallbackController;
import com.myshop.supplier.kayixin.KayixinProvider;
import com.myshop.supplier.kayixin.KayixinCallbackCodec;
import com.myshop.supplier.kayixin.KayixinComplaintCallbackController;
import com.myshop.supplier.kayixin.KayixinGoodsCallbackController;
import com.myshop.service.OrderService;
import com.myshop.service.OrderAccessService;
import com.myshop.service.OrderRefundService;
import com.myshop.service.FulfillmentService;
import com.myshop.service.AdminProductService;
import com.myshop.service.AfterSaleService;
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
import org.springframework.test.util.ReflectionTestUtils;
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
        final AtomicInteger refunds=new AtomicInteger(), voids=new AtomicInteger(), complaints=new AtomicInteger();
        final Map<String,Integer> cardStatuses=new ConcurrentHashMap<>();
        volatile int nextRefundStatus=9, nextVoidStatus=2;
        volatile int cardCancellationAllowed=1;
        volatile boolean loseRefundResponse,loseVoidResponse;
        volatile boolean rejectRefund;
        volatile boolean loseComplaintResponse;
        volatile BigDecimal returnedMoney=BigDecimal.ZERO;
        final Map<String,SupplierModels.PurchaseDetail> details=new ConcurrentHashMap<>();
        volatile SupplierModels.PurchaseRequest lastRequest;
        volatile SupplierModels.ComplaintRequest lastComplaint;
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
        @Override public SupplierModels.RefundResult requestRefund(SupplierModels.Connection c,String outer) {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            refunds.incrementAndGet();
            if (rejectRefund) throw new SupplierException("BUSINESS","模拟退款拒绝");
            var old=details.get(outer);
            details.put(outer,new SupplierModels.PurchaseDetail(old.orderNumber(),outer,nextRefundStatus,old.count(),old.money(),returnedMoney,
                    "退款处理",null,null,null,old.cards()));
            if (loseRefundResponse) throw new SupplierException("CONNECTION","模拟响应丢失");
            return new SupplierModels.RefundResult(nextRefundStatus);
        }
        @Override public SupplierModels.CardDetail cardDetail(SupplierModels.Connection c,String card) {
            return new SupplierModels.CardDetail(card,cardStatuses.getOrDefault(card,0),cardCancellationAllowed,"状态查询");
        }
        @Override public SupplierModels.CardVoidResult voidCard(SupplierModels.Connection c,String card) {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            voids.incrementAndGet(); cardStatuses.put(card,nextVoidStatus);
            if (loseVoidResponse) throw new SupplierException("CONNECTION","模拟响应丢失");
            return new SupplierModels.CardVoidResult(nextVoidStatus,"处理");
        }
        @Override public SupplierModels.ComplaintResult createComplaint(SupplierModels.Connection c,SupplierModels.ComplaintRequest request) {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            lastComplaint=request;
            int n=complaints.incrementAndGet();
            if (loseComplaintResponse) throw new SupplierException("CONNECTION","模拟申诉响应丢失");
            return new SupplierModels.ComplaintResult(1000L+n);
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
    @Autowired AfterSaleService afterSale;
    @Autowired SupplierGoodsNotifications goodsNotifications;
    @Autowired SupplierAftercareService aftercare;
    @Autowired SupplierAftercareOperationRepository aftercareOperations;
    @Autowired SupplierAftercareTransactions aftercareTx;
    @Autowired SupplierComplaintNotifications complaintNotifications;
    @Autowired KayixinCallbackCodec callbackCodec;
    @Autowired KayixinProvider kayixin;
    @Autowired com.myshop.service.PrivateUploadStorage privateStorage;
    @Autowired WebApplicationContext webContext;
    @Autowired FilterChainProxy security;

    @BeforeEach void reset(){fake.creates.set(0);fake.refunds.set(0);fake.voids.set(0);fake.complaints.set(0);fake.cardStatuses.clear();fake.nextRefundStatus=9;fake.nextVoidStatus=2;fake.cardCancellationAllowed=1;fake.loseRefundResponse=false;fake.loseVoidResponse=false;fake.loseComplaintResponse=false;fake.rejectRefund=false;fake.returnedMoney=BigDecimal.ZERO;fake.details.clear();fake.loseResponse=false;fake.absent=false;fake.unsupportedFields=false;fake.lastRequest=null;fake.lastComplaint=null;fake.balance=BigDecimal.valueOf(100);fake.committedClaimObserved=false;}

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
    @Test void disabledPurchaseMarksLatePaymentForManualAttentionWithoutCreating() throws Exception {
        Fixture f=fixture(1,0);
        ReflectionTestUtils.setField(worker,"purchaseEnabled",false);
        try {
            assertTrue(purchases.findRecoveryCandidates(false,org.springframework.data.domain.PageRequest.of(0,10000)).contains(f.purchase()));
            worker.process(f.purchase());
            SupplierPurchase saved=purchases.findById(f.purchase()).orElseThrow();
            assertEquals("BLOCKED",saved.getStatus());
            assertEquals("PURCHASE_DISABLED",saved.getErrorCode());
            assertNull(saved.getSubmitStartedAt());
            assertEquals(0,fake.creates.get());
            assertEquals("PAID",value("select status from orders where id=?",f.order()));
        } finally { ReflectionTestUtils.setField(worker,"purchaseEnabled",true); }
    }
    @Test void disabledPurchaseStillRecoversPreviouslySubmittedOrder() throws Exception {
        Fixture f=fixture(3,0);
        assertTrue(tx.claim(f.purchase()));
        SupplierPurchase p=purchases.findById(f.purchase()).orElseThrow();
        String outer=p.getOuterNumber();
        jdbc.update("update supplier_purchases set submit_started_at=current_timestamp-interval '2 minutes' where id=?",f.purchase());
        fake.details.put(outer,new SupplierModels.PurchaseDetail("UP-HISTORY",outer,3,1,
                new BigDecimal("0.02"),BigDecimal.ZERO,"完成",null,null,null,List.of()));
        ReflectionTestUtils.setField(worker,"purchaseEnabled",false);
        try {
            worker.process(f.purchase());
            assertEquals("COMPLETED",purchases.findById(f.purchase()).orElseThrow().getStatus());
            assertEquals(outer,purchases.findById(f.purchase()).orElseThrow().getOuterNumber());
            assertEquals(0,fake.creates.get());
        } finally { ReflectionTestUtils.setField(worker,"purchaseEnabled",true); }
    }
    @Test void disabledPurchaseRecoveryIncludesPaidOrderAlreadyInAfterSale() throws Exception {
        Fixture f=fixture(1,0);
        jdbc.update("update orders set status='AFTER_SALE' where id=?",f.order());
        ReflectionTestUtils.setField(worker,"purchaseEnabled",false);
        try {
            assertTrue(purchases.findRecoveryCandidates(false,org.springframework.data.domain.PageRequest.of(0,10000)).contains(f.purchase()));
            worker.process(f.purchase());
            assertEquals("BLOCKED",purchases.findById(f.purchase()).orElseThrow().getStatus());
            assertEquals("PURCHASE_DISABLED",purchases.findById(f.purchase()).orElseThrow().getErrorCode());
            assertEquals(0,fake.creates.get());
        } finally { ReflectionTestUtils.setField(worker,"purchaseEnabled",true); }
    }
    @Test void productNotificationRefreshesOnlyItsAccountAndNeverRetailPrice() throws Exception {
        Fixture f=fixture(1,0);
        var first=purchases.findById(f.purchase()).orElseThrow();
        String goods=first.getRemoteGoodsId();
        UUID category=UUID.randomUUID();
        jdbc.update("insert into product_categories(id,name,sort_order,is_deleted) values (?,?,0,0)",category,"other-"+category);
        var second=suppliers.saveAccount(null,new SupplierService.AccountRequest("other","PURCHASE_TEST","https://other.invalid","other-id","test-secret",true));
        UUID otherProduct=suppliers.importProduct(second.id(),goods,category).productId();
        jdbc.update("update products set retail_price=88.88 where id=?",f.product());
        var old=fake.product;
        fake.product=new SupplierModels.Product(goods,old.name(),1,2,0,old.multiple(),old.minQuantity(),old.maxQuantity(),
                List.of(new SupplierModels.Sku("","规格","",new BigDecimal("0.03"),3,1)),json.readTree("{}"));
        UUID event=goodsNotifications.accept(f.account(),goods,"a".repeat(64));
        assertEquals(event,goodsNotifications.accept(f.account(),goods,"a".repeat(64)));
        goodsNotifications.process(event);
        assertEquals("SYNCED",value("select status from supplier_goods_notifications where id=?",event));
        assertEquals(0,new BigDecimal("88.88").compareTo(jdbc.queryForObject("select retail_price from products where id=?",BigDecimal.class,f.product())));
        assertEquals(0,new BigDecimal("0.03").compareTo(jdbc.queryForObject("select cost from supplier_product_mappings where account_id=? and remote_goods_id=?",BigDecimal.class,f.account(),goods)));
        assertEquals(0,new BigDecimal("0.01").compareTo(jdbc.queryForObject("select cost from supplier_product_mappings where product_id=?",BigDecimal.class,otherProduct)));
        UUID unknown=goodsNotifications.accept(f.account(),"999999999999","b".repeat(64));
        goodsNotifications.process(unknown);
        assertEquals("IGNORED",value("select status from supplier_goods_notifications where id=?",unknown));
    }
    @Test void concurrentRefundRequestsSubmitOnceAndKeepCustomerMoneySeparate() throws Exception {
        Fixture f=fixture(3,0); worker.process(f.purchase());
        ExecutorService pool=Executors.newFixedThreadPool(8); CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Map<String,Object>>> results=new ArrayList<>();
            for(int i=0;i<8;i++) results.add(pool.submit(() -> {
                RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"admin","ADMIN"));
                try { start.await(); return aftercare.refund(f.order(),f.purchase(),UUID.randomUUID(),true); }
                finally { RequestContext.clear(); }
            }));
            start.countDown(); Set<Object> ids=new HashSet<>();
            for(var future:results) ids.add(future.get(20,TimeUnit.SECONDS).get("id"));
            assertEquals(1,ids.size()); assertEquals(1,fake.refunds.get());
            assertEquals("REFUNDING",purchases.findById(f.purchase()).orElseThrow().getStatus());
            assertEquals("DELIVERED",value("select status from orders where id=?",f.order()));
        } finally { pool.shutdownNow(); }
    }
    @Test void lostRefundResponseOnlyQueriesAndPreservesActualPartialRefund() throws Exception {
        Fixture f=fixture(3,0); worker.process(f.purchase()); fake.loseRefundResponse=true;
        fake.nextRefundStatus=5; fake.returnedMoney=new BigDecimal("0.01");
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"admin","ADMIN"));
        try {
            var result=aftercare.refund(f.order(),f.purchase(),UUID.randomUUID(),true);
            assertEquals("COMPLETED",result.get("status"));
            assertEquals(0,new BigDecimal("0.01").compareTo(purchases.findById(f.purchase()).orElseThrow().getRefundedAmount()));
            aftercare.refund(f.order(),f.purchase(),UUID.randomUUID(),true);
            assertEquals(1,fake.refunds.get());
            assertEquals("DELIVERED",value("select status from orders where id=?",f.order()));
        } finally { RequestContext.clear(); }
    }
    @Test void explicitSupplierBusinessFailureIsRecordedWithoutRetry() throws Exception {
        Fixture f=fixture(3,0); worker.process(f.purchase()); fake.rejectRefund=true;
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"admin","ADMIN"));
        try {
            assertEquals("REJECTED",aftercare.refund(f.order(),f.purchase(),UUID.randomUUID(),true).get("status"));
            aftercare.refund(f.order(),f.purchase(),UUID.randomUUID(),true);
            assertEquals(1,fake.refunds.get());
        } finally { RequestContext.clear(); }
        assertEquals("DELIVERED",value("select status from orders where id=?",f.order()));
    }
    @Test void voidLostResponseQueriesCardAndNeverSubmitsAgain() throws Exception {
        Fixture f=fixture(1,0); worker.process(f.purchase());
        UUID card=jdbc.queryForObject("select id from supplier_delivery_cards where purchase_id=? order by created_at limit 1",UUID.class,f.purchase());
        fake.loseVoidResponse=true;
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"admin","ADMIN"));
        try {
            var result=aftercare.voidCard(f.order(),f.purchase(),card,UUID.randomUUID(),true);
            assertEquals("COMPLETED",result.get("status"));
            aftercare.voidCard(f.order(),f.purchase(),card,UUID.randomUUID(),true);
            assertEquals(1,fake.voids.get());
            assertEquals(2,jdbc.queryForObject("select card_status from supplier_delivery_cards where id=?",Integer.class,card));
        } finally { RequestContext.clear(); }
    }
    private UUID registerOwner(Fixture f) {
        UUID user=UUID.randomUUID();
        Long number=jdbc.queryForObject("select coalesce(max(user_no),9999)+1 from users",Long.class);
        UUID level=jdbc.queryForObject("select id from user_levels where is_default=true",UUID.class);
        jdbc.update("""
            insert into users(id,username,email,password_hash,role,is_deleted,user_no,user_level_id,balance)
            values (?,?,?,'test-hash','USER',0,?,?,5.00)
            """,user,"supplier-owner-"+user,user+"@example.invalid",number,level);
        jdbc.update("update orders set user_id=?,session_token=null where id=?",user,f.order());
        return user;
    }
    @Test void customerRefundRequiresVerifiedFullSupplierRefundAndIsNotRepeated() throws Exception {
        Fixture f=fixture(3,0); UUID user=registerOwner(f); worker.process(f.purchase());
        fake.nextRefundStatus=5; fake.returnedMoney=new BigDecimal("0.02");
        RequestContext.set(new RequestContext.UserInfo(user,"admin","ADMIN"));
        try {
            aftercare.refund(f.order(),f.purchase(),UUID.randomUUID(),true);
            assertEquals(0,new BigDecimal("5.00").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,user)));
            refunds.refund(f.order(),"核实后退款",true,false);
            assertEquals(0,new BigDecimal("15.00").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,user)));
            assertThrows(com.myshop.exception.BusinessException.class,()->refunds.refund(f.order(),"重复退款",true,false));
            assertEquals(0,new BigDecimal("15.00").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,user)));
        } finally { RequestContext.clear(); }
    }
    @Test void partialOrZeroSupplierRefundCannotTriggerCustomerFullRefund() throws Exception {
        for (String amount:List.of("0.00","0.01")) {
            Fixture f=fixture(3,0); UUID user=registerOwner(f); worker.process(f.purchase());
            fake.nextRefundStatus=5; fake.returnedMoney=new BigDecimal(amount);
            RequestContext.set(new RequestContext.UserInfo(user,"admin","ADMIN"));
            try {
                aftercare.refund(f.order(),f.purchase(),UUID.randomUUID(),true);
                assertThrows(com.myshop.exception.BusinessException.class,()->refunds.refund(f.order(),"待核实",true,false));
                assertEquals(0,new BigDecimal("5.00").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,user)));
            } finally { RequestContext.clear(); }
        }
    }
    @Test void upstreamRefundSurvivesCustomerLedgerFailure() throws Exception {
        Fixture f=fixture(3,0); UUID user=registerOwner(f); worker.process(f.purchase());
        jdbc.update("update users set balance=9999999999.99 where id=?",user);
        fake.nextRefundStatus=5; fake.returnedMoney=new BigDecimal("0.02");
        RequestContext.set(new RequestContext.UserInfo(user,"admin","ADMIN"));
        try {
            aftercare.refund(f.order(),f.purchase(),UUID.randomUUID(),true);
            assertThrows(com.myshop.exception.BusinessException.class,()->refunds.refund(f.order(),"余额上限",true,false));
            assertEquals("REFUNDED",purchases.findById(f.purchase()).orElseThrow().getStatus());
            assertEquals("DELIVERED",value("select status from orders where id=?",f.order()));
            assertEquals(0,new BigDecimal("9999999999.99").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,user)));
        } finally { RequestContext.clear(); }
    }
    @Test void mixedOrderAndGuestRefundsRespectPerItemSupplierEvidence() throws Exception {
        Fixture guest=fixture(3,0); worker.process(guest.purchase());
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"admin","ADMIN"));
        try { assertThrows(com.myshop.exception.BusinessException.class,() -> refunds.refund(guest.order(),"游客退款",true,false)); }
        finally { RequestContext.clear(); }
        Fixture f=fixture(3,0); UUID user=registerOwner(f),local=UUID.randomUUID(),line=UUID.randomUUID();
        jdbc.update("""
            insert into products(id,title,retail_price,cost_price,category_id,delivery_type,stock_mode,is_enabled)
            values (?,'manual-mixed',10,1,(select category_id from products where id=?),'MANUAL','UNLIMITED',true)
            """,local,f.product());
        jdbc.update("""
            insert into order_items(id,order_id,product_id,product_no,product_title,quantity,unit_price,cost_price_snapshot,
                 retail_price_snapshot,pricing_source,subtotal,delivery_type,stock_mode,fulfillment_status)
            values(?,?,?,(select product_no from products where id=?),'manual-mixed',1,10,1,10,'RETAIL',10,'MANUAL','UNLIMITED','PENDING')
            """,line,f.order(),local,local);
        jdbc.update("update orders set total_amount=20,actual_amount=20 where id=?",f.order());
        worker.process(f.purchase()); fake.nextRefundStatus=5; fake.returnedMoney=new BigDecimal("0.01");
        RequestContext.set(new RequestContext.UserInfo(user,"admin","ADMIN"));
        try {
            aftercare.refund(f.order(),f.purchase(),UUID.randomUUID(),true);
            assertThrows(com.myshop.exception.BusinessException.class,() -> refunds.refund(f.order(),"部分退款",true,false));
            assertEquals(0,new BigDecimal("5.00").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,user)));
            SupplierPurchase p=purchases.findById(f.purchase()).orElseThrow();
            var old=fake.details.get(p.getOuterNumber());
            fake.details.put(p.getOuterNumber(),new SupplierModels.PurchaseDetail(old.orderNumber(),old.outerNumber(),5,old.count(),old.money(),
                    old.money(),"全额退款",null,null,null,old.cards()));
            worker.refresh(f.purchase());
            refunds.refund(f.order(),"逐项核实",true,false);
            assertEquals(0,new BigDecimal("25.00").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,user)));
            assertEquals("CANCELLED",value("select fulfillment_status from order_items where id=?",line));
        } finally { RequestContext.clear(); }
    }
    @Test void unknownRefundOrVoidOnlyQueriesAndCannotSubmitAgain() throws Exception {
        Fixture refund=fixture(3,0); worker.process(refund.purchase());
        fake.nextRefundStatus=3; fake.loseRefundResponse=true;
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"admin","ADMIN"));
        try {
            assertEquals("UNKNOWN",aftercare.refund(refund.order(),refund.purchase(),UUID.randomUUID(),true).get("status"));
            aftercare.refund(refund.order(),refund.purchase(),UUID.randomUUID(),true);
            assertEquals(1,fake.refunds.get());
        } finally { RequestContext.clear(); }
        Fixture cardFixture=fixture(1,0); worker.process(cardFixture.purchase());
        UUID card=jdbc.queryForObject("select id from supplier_delivery_cards where purchase_id=? order by created_at limit 1",UUID.class,cardFixture.purchase());
        fake.nextVoidStatus=0; fake.loseVoidResponse=true;
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"admin","ADMIN"));
        try {
            assertEquals("UNKNOWN",aftercare.voidCard(cardFixture.order(),cardFixture.purchase(),card,UUID.randomUUID(),true).get("status"));
            aftercare.voidCard(cardFixture.order(),cardFixture.purchase(),card,UUID.randomUUID(),true);
            assertEquals(1,fake.voids.get());
        } finally { RequestContext.clear(); }
    }
    @Test void crashedRefundClaimRecoversOnlyByQueryAndRemainsVisible() throws Exception {
        Fixture f=fixture(3,0); worker.process(f.purchase()); UUID operator=UUID.randomUUID(),key=UUID.randomUUID();
        RequestContext.set(new RequestContext.UserInfo(operator,"admin","ADMIN"));
        UUID operation;
        try { operation=aftercareTx.intent(f.order(),f.purchase(),"REFUND",key,null,null,operator,null,null,List.of()); }
        finally { RequestContext.clear(); }
        assertTrue(aftercareTx.claim(operation));
        jdbc.update("update supplier_aftercare_operations set submitted_at=current_timestamp-interval '2 minutes' where id=?",operation);
        aftercare.recover();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime()<deadline && !"UNKNOWN".equals(aftercareOperations.findById(operation).orElseThrow().getStatus())) Thread.sleep(20);
        assertEquals("UNKNOWN",aftercareOperations.findById(operation).orElseThrow().getStatus());
        RequestContext.set(new RequestContext.UserInfo(operator,"admin","ADMIN"));
        try {
            aftercare.recordReview(f.order(),f.purchase(),operation,"RESULT_UNCONFIRMED");
            aftercare.refund(f.order(),f.purchase(),UUID.randomUUID(),true);
            assertEquals("UNKNOWN",aftercareOperations.findById(operation).orElseThrow().getStatus());
        } finally { RequestContext.clear(); }
        assertEquals(0,fake.refunds.get());
    }
    @Test void cardStatesAndCancellationPermissionAreCheckedBeforeVoid() throws Exception {
        for (int remoteStatus=0;remoteStatus<=4;remoteStatus++) {
            Fixture f=fixture(1,0); worker.process(f.purchase());
            UUID card=jdbc.queryForObject("select id from supplier_delivery_cards where purchase_id=? order by created_at limit 1",UUID.class,f.purchase());
            String remote=jdbc.queryForObject("select remote_card_id from supplier_delivery_cards where id=?",String.class,card);
            fake.cardStatuses.put(remote,remoteStatus);
            RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"admin","ADMIN"));
            try {
                assertEquals(remoteStatus,aftercare.queryCard(f.order(),f.purchase(),card).get("status"));
                if (remoteStatus>=2) assertThrows(com.myshop.exception.BusinessException.class,() -> aftercare.voidCard(f.order(),f.purchase(),card,UUID.randomUUID(),true));
            } finally { RequestContext.clear(); }
        }
        Fixture blocked=fixture(1,0); worker.process(blocked.purchase());
        UUID card=jdbc.queryForObject("select id from supplier_delivery_cards where purchase_id=? order by created_at limit 1",UUID.class,blocked.purchase());
        fake.cardCancellationAllowed=0;
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"admin","ADMIN"));
        try { assertThrows(com.myshop.exception.BusinessException.class,() -> aftercare.voidCard(blocked.order(),blocked.purchase(),card,UUID.randomUUID(),true)); }
        finally { RequestContext.clear(); }
        assertEquals(0,fake.voids.get());
    }
    @Test void identicalUpstreamOrderAndCardNumbersRemainIsolatedByAccount() throws Exception {
        Fixture one=fixture(1,0); worker.process(one.purchase());
        Fixture two=fixture(1,0); worker.process(two.purchase());
        UUID c1=jdbc.queryForObject("select id from supplier_delivery_cards where purchase_id=? order by created_at limit 1",UUID.class,one.purchase());
        UUID c2=jdbc.queryForObject("select id from supplier_delivery_cards where purchase_id=? order by created_at limit 1",UUID.class,two.purchase());
        jdbc.update("update supplier_delivery_cards set remote_card_id='same-card' where id in (?,?)",c1,c2);
        jdbc.update("update supplier_purchases set upstream_order_number='same-order' where id in (?,?)",one.purchase(),two.purchase());
        aftercareTx.cardChecked(one.order(),one.purchase(),c1,new SupplierModels.CardDetail("same-card",2,0,"已作废"),LocalDateTime.now());
        assertEquals(2,jdbc.queryForObject("select card_status from supplier_delivery_cards where id=?",Integer.class,c1));
        assertEquals(0,jdbc.queryForObject("select card_status from supplier_delivery_cards where id=?",Integer.class,c2));
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"admin","ADMIN"));
        try { assertThrows(com.myshop.exception.BusinessException.class,() -> aftercare.voidCard(one.order(),one.purchase(),c2,UUID.randomUUID(),true)); }
        finally { RequestContext.clear(); }
        assertEquals(0,fake.voids.get());
    }
    @Test void complaintUpdatesAreCorrelatedDeduplicatedAndNeverRegress() throws Exception {
        Fixture f=fixture(3,0); UUID user=registerOwner(f); worker.process(f.purchase());
        RequestContext.set(new RequestContext.UserInfo(user,"customer","USER"));
        UUID sale;
        try { sale=(UUID)((Map<?,?>)afterSale.apply(f.order(),user,"充值处理售后申请",List.of()).get("after_sale")).get("id"); }
        finally { RequestContext.clear(); }
        RequestContext.set(new RequestContext.UserInfo(user,"admin","ADMIN"));
        UUID operation;
        try {
            var result=aftercare.complaint(f.order(),f.purchase(),sale,UUID.randomUUID(),true,"申诉主题","说明内容",List.of());
            operation=(UUID)result.get("id");
        } finally { RequestContext.clear(); }
        var p=purchases.findById(f.purchase()).orElseThrow();
        complaintNotifications.accept(f.account(),1001,p.getUpstreamOrderNumber(),p.getOuterNumber(),2,"已经受理","c".repeat(64),List.of());
        complaintNotifications.accept(f.account(),1001,p.getUpstreamOrderNumber(),p.getOuterNumber(),2,"已经受理","c".repeat(64),List.of());
        complaintNotifications.accept(f.account(),1001,p.getUpstreamOrderNumber(),p.getOuterNumber(),3,"处理完成","d".repeat(64),List.of());
        complaintNotifications.accept(f.account(),1001,p.getUpstreamOrderNumber(),p.getOuterNumber(),1,"旧消息","e".repeat(64),List.of());
        assertEquals(2,complaintNotifications.forAfterSale(sale).size());
        assertEquals(3,aftercareOperations.findById(operation).orElseThrow().getUpstreamStatus());
        assertThrows(com.myshop.exception.BusinessException.class,() -> complaintNotifications.accept(UUID.randomUUID(),1001,p.getUpstreamOrderNumber(),p.getOuterNumber(),3,"完成","f".repeat(64),List.of()));
        RequestContext.set(new RequestContext.UserInfo(user,"admin","ADMIN"));
        try { assertThrows(com.myshop.exception.BusinessException.class,() -> aftercare.complaint(f.order(),f.purchase(),sale,UUID.randomUUID(),true,"","追加说明",List.of())); }
        finally { RequestContext.clear(); }
        assertEquals(1,fake.complaints.get());
    }
    @Test void complaintTimeoutCannotBeResubmittedAndAppendWorksOnlyAfterKnownAcceptance() throws Exception {
        Fixture f=fixture(3,0); UUID user=registerOwner(f); worker.process(f.purchase());
        RequestContext.set(new RequestContext.UserInfo(user,"customer","USER"));
        UUID sale;
        try { sale=(UUID)((Map<?,?>)afterSale.apply(f.order(),user,"充值处理售后申请",List.of()).get("after_sale")).get("id"); }
        finally { RequestContext.clear(); }
        RequestContext.set(new RequestContext.UserInfo(user,"admin","ADMIN"));
        try {
            var first=aftercare.complaint(f.order(),f.purchase(),sale,UUID.randomUUID(),true,"主题","首次内容",List.of());
            assertEquals("ACCEPTED",first.get("status"));
            fake.loseComplaintResponse=true; UUID key=UUID.randomUUID();
            var second=aftercare.complaint(f.order(),f.purchase(),sale,key,true,"","追加内容",List.of());
            assertEquals("UNKNOWN",second.get("status"));
            aftercare.complaint(f.order(),f.purchase(),sale,key,true,"","追加内容",List.of());
            assertThrows(com.myshop.exception.BusinessException.class,() -> aftercare.complaint(f.order(),f.purchase(),sale,UUID.randomUUID(),true,"","重复内容",List.of()));
            assertEquals(2,fake.complaints.get());
        } finally { RequestContext.clear(); }
    }
    @Test void complaintReusesOwnedPrivateAttachmentAsBase64() throws Exception {
        Fixture f=fixture(3,0); UUID user=registerOwner(f); worker.process(f.purchase());
        var buffer=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",buffer);
        byte[] png=buffer.toByteArray();
        var gifBuffer=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"gif",gifBuffer);
        byte[] gif=gifBuffer.toByteArray();
        byte[] svg="<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"1\" height=\"1\"/></svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var files=List.of(new org.springframework.mock.web.MockMultipartFile("attachments","proof.png","image/png",png),
                new org.springframework.mock.web.MockMultipartFile("attachments","proof.gif","image/gif",gif),
                new org.springframework.mock.web.MockMultipartFile("attachments","proof.svg","image/svg+xml",svg));
        RequestContext.set(new RequestContext.UserInfo(user,"customer","USER"));
        UUID sale;
        try { sale=(UUID)((Map<?,?>)afterSale.apply(f.order(),user,"充值处理售后申请",new ArrayList<>(files)).get("after_sale")).get("id"); }
        finally { RequestContext.clear(); }
        List<UUID> attachmentIds=jdbc.queryForList("select id from after_sale_attachments where request_id=?",UUID.class,sale);
        List<String> storageKeys=jdbc.queryForList("select storage_key from after_sale_attachments where request_id=?",String.class,sale);
        RequestContext.set(new RequestContext.UserInfo(user,"admin","ADMIN"));
        try {
            aftercare.complaint(f.order(),f.purchase(),sale,UUID.randomUUID(),true,"主题","附带凭证",attachmentIds);
            assertEquals(Set.of(Base64.getEncoder().encodeToString(png),Base64.getEncoder().encodeToString(gif),Base64.getEncoder().encodeToString(svg)),new HashSet<>(fake.lastComplaint.images()));
            assertFalse(fake.lastComplaint.toString().contains(fake.lastComplaint.images().getFirst()));
            assertThrows(com.myshop.exception.BusinessException.class,() -> aftercare.complaint(f.order(),f.purchase(),UUID.randomUUID(),UUID.randomUUID(),true,"主题","错误关联",attachmentIds));
        } finally { RequestContext.clear(); storageKeys.forEach(privateStorage::deleteManagedFile); }
    }
    @Test void supplierEvidenceValidatesSupportedFormatsAndRejectsMalformedData() throws Exception {
        var pixel=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var jpeg=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixel,"jpeg",jpeg);
        var gif=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixel,"gif",gif);
        String webp="UklGRjwAAABXRUJQVlA4IDAAAADQAQCdASoBAAEAAUAmJaACdLoB+AADsAD+8ut//NgVzXPv9//S4P0uD9Lg/9KQAAA=";
        String svg=Base64.getEncoder().encodeToString("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"1\" height=\"1\"/></svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var types=complaintNotifications.validateImages(List.of(Base64.getEncoder().encodeToString(jpeg.toByteArray()),
                Base64.getEncoder().encodeToString(gif.toByteArray()),webp,svg));
        assertEquals(List.of("image/jpeg","image/gif","image/webp","image/svg+xml"),types);
        assertThrows(com.myshop.exception.BusinessException.class,() -> complaintNotifications.validateImages(List.of(Base64.getEncoder().encodeToString(new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a}))));
    }
    @Test void signedComplaintCallbackStoresPrivateEvidenceAndRejectsReplayForgeryAndCrossAccount() throws Exception {
        Fixture f=fixture(3,0); UUID user=registerOwner(f); worker.process(f.purchase());
        RequestContext.set(new RequestContext.UserInfo(user,"customer","USER"));
        UUID sale;
        try { sale=(UUID)((Map<?,?>)afterSale.apply(f.order(),user,"充值处理售后申请",List.of()).get("after_sale")).get("id"); }
        finally { RequestContext.clear(); }
        RequestContext.set(new RequestContext.UserInfo(user,"admin","ADMIN"));
        try { aftercare.complaint(f.order(),f.purchase(),sale,UUID.randomUUID(),true,"主题","申诉内容",List.of()); }
        finally { RequestContext.clear(); }
        SupplierPurchase p=purchases.findById(f.purchase()).orElseThrow();
        String secret="01234567890123456789012345678901";
        var connection=new SupplierModels.Connection("https://fake.invalid","test-id",secret);
        SupplierService mock=org.mockito.Mockito.mock(SupplierService.class);
        org.mockito.Mockito.when(mock.runtime(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(new SupplierService.RuntimeAccount(false,kayixin,connection));
        var controller=new KayixinComplaintCallbackController(mock,complaintNotifications,callbackCodec);
        var imageBuffer=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",imageBuffer);
        String image=Base64.getEncoder().encodeToString(imageBuffer.toByteArray());
        byte[] body=json.writeValueAsBytes(Map.of("orderNumber",p.getUpstreamOrderNumber(),"outerNumber",p.getOuterNumber(),
                "reqId",1001,"status",2,"replay","已受理","base64Image",List.of(image)));
        String time=Long.toString(java.time.Instant.now().getEpochSecond());
        String signature=KayixinProvider.signature("test-id",secret,"3.0",time,body);
        try {
            byte[] invalid=json.writeValueAsBytes(Map.of("orderNumber",p.getUpstreamOrderNumber(),"outerNumber",p.getOuterNumber(),
                    "reqId",1001,"status",2,"replay","无效凭证","base64Image",List.of(Base64.getEncoder().encodeToString(new byte[]{1,2,3}))));
            assertEquals(400,controller.receive(f.account(),"test-id","3.0",time,KayixinProvider.signature("test-id",secret,"3.0",time,invalid),invalid).getStatusCode().value());
            assertEquals(200,controller.receive(f.account(),"test-id","3.0",time,signature,body).getStatusCode().value());
            assertEquals(200,controller.receive(f.account(),"test-id","3.0",time,signature,body).getStatusCode().value());
            assertEquals(400,controller.receive(f.account(),"test-id","3.0",time,"0".repeat(32),body).getStatusCode().value());
            String old=Long.toString(java.time.Instant.now().getEpochSecond()-600);
            assertEquals(400,controller.receive(f.account(),"test-id","3.0",old,KayixinProvider.signature("test-id",secret,"3.0",old,body),body).getStatusCode().value());
            assertEquals(400,controller.receive(UUID.randomUUID(),"test-id","3.0",time,signature,body).getStatusCode().value());
            assertEquals(1,complaintNotifications.forAfterSale(sale).size());
            UUID evidence=jdbc.queryForObject("select a.id from supplier_complaint_attachments a join supplier_complaint_events e on e.id=a.event_id where e.account_id=?",UUID.class,f.account());
            RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"other","USER"));
            try { assertThrows(com.myshop.exception.BusinessException.class,() -> complaintNotifications.download(evidence)); }
            finally { RequestContext.clear(); }
            RequestContext.set(new RequestContext.UserInfo(user,"owner","USER"));
            try { assertEquals("image/png",complaintNotifications.download(evidence).contentType()); }
            finally { RequestContext.clear(); }
        } finally {
            jdbc.queryForList("select a.storage_key from supplier_complaint_attachments a join supplier_complaint_events e on e.id=a.event_id where e.account_id=?",String.class,f.account()).forEach(privateStorage::deleteManagedFile);
        }
    }
    @Test void productCallbackChecksSignatureAndStoresBeforeAcknowledging() throws Exception {
        Fixture f=fixture(1,0);
        var p=purchases.findById(f.purchase()).orElseThrow();
        String secret="01234567890123456789012345678901";
        var connection=new SupplierModels.Connection("https://fake.invalid","test-id",secret);
        SupplierService mock=org.mockito.Mockito.mock(SupplierService.class);
        org.mockito.Mockito.when(mock.runtime(f.account(),false)).thenReturn(new SupplierService.RuntimeAccount(true,kayixin,connection));
        var controller=new KayixinGoodsCallbackController(mock,goodsNotifications,callbackCodec);
        byte[] body=json.writeValueAsBytes(Map.of("goodsId",Long.parseLong(p.getRemoteGoodsId()),"money","0.01"));
        String time=Long.toString(java.time.Instant.now().getEpochSecond());
        String signature=KayixinProvider.signature("test-id",secret,"3.0",time,body);
        assertEquals(400,controller.receive(f.account(),"test-id","3.0",time,"0".repeat(32),body).getStatusCode().value());
        assertEquals(200,controller.receive(f.account(),"test-id","3.0",time,signature,body).getStatusCode().value());
        assertEquals(200,controller.receive(f.account(),"test-id","3.0",time,signature,body).getStatusCode().value());
        assertEquals(1,jdbc.queryForObject("select count(*) from supplier_goods_notifications where account_id=? and remote_goods_id=?",Integer.class,f.account(),p.getRemoteGoodsId()));
        String later=Long.toString(Long.parseLong(time)+1);
        assertEquals(200,controller.receive(f.account(),"test-id","3.0",later,KayixinProvider.signature("test-id",secret,"3.0",later,body),body).getStatusCode().value());
        assertEquals(2,jdbc.queryForObject("select count(*) from supplier_goods_notifications where account_id=? and remote_goods_id=?",Integer.class,f.account(),p.getRemoteGoodsId()));
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
    @Test void administratorCanRecordUnknownInvestigationWithoutChangingPurchase() throws Exception {
        Fixture f=fixture(1,0); fake.absent=true; worker.process(f.purchase());
        SupplierPurchase p=purchases.findById(f.purchase()).orElseThrow();
        String outer=p.getOuterNumber();
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"test-user","USER"));
        try {
            assertThrows(com.myshop.exception.BusinessException.class,
                    () -> adminOrders.recordReview(f.order(),f.purchase(),"MANUAL_HANDLING"));
        } finally { RequestContext.clear(); }
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"test-admin","ADMIN"));
        try {
            assertThrows(com.myshop.exception.BusinessException.class,
                    () -> adminOrders.recordReview(UUID.randomUUID(),f.purchase(),"MANUAL_HANDLING"));
            var view=adminOrders.recordReview(f.order(),f.purchase(),"ORDER_NOT_FOUND_UNCONFIRMED");
            assertEquals(1,((List<?>)view.get("reviews")).size());
        } finally { RequestContext.clear(); }
        assertEquals("UNKNOWN",purchases.findById(f.purchase()).orElseThrow().getStatus());
        assertEquals(outer,purchases.findById(f.purchase()).orElseThrow().getOuterNumber());
        worker.process(f.purchase());
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
            assertTrue(e.getMessage().contains("核实"));
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
    @Test void completedUpstreamOrderCanEnterReturnProcessingWithoutCustomerRefund() throws Exception {
        Fixture f=fixture(3,0); worker.process(f.purchase());
        SupplierPurchase p=purchases.findById(f.purchase()).orElseThrow();
        tx.applyDetail(p.getId(),new SupplierModels.PurchaseDetail(p.getUpstreamOrderNumber(),p.getOuterNumber(),8,1,
                new BigDecimal("0.02"),BigDecimal.ZERO,"退单中",null,null,null,List.of()),LocalDateTime.now().plusSeconds(1));
        var updated=purchases.findById(p.getId()).orElseThrow();
        assertEquals(8,updated.getUpstreamStatus()); assertEquals("REFUNDING",updated.getStatus());
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
    @Test void afterSaleAndCompletionSerializeAndTheResultSurvivesRejection() throws Exception {
        Fixture f=fixture(3,0); assertTrue(tx.claim(f.purchase()));
        UUID user=UUID.randomUUID();
        Long number=jdbc.queryForObject("select coalesce(max(user_no),9999)+1 from users",Long.class);
        UUID level=jdbc.queryForObject("select id from user_levels where is_default=true",UUID.class);
        jdbc.update("""
            insert into users(id,username,email,password_hash,role,is_deleted,user_no,user_level_id,balance)
            values (?,?,?,'test-hash','USER',0,?,?,5.00)
            """,user,"after-sale-"+user,user+"@example.invalid",number,level);
        jdbc.update("update orders set user_id=?,session_token=null where id=?",user,f.order());
        SupplierPurchase p=purchases.findById(f.purchase()).orElseThrow();
        var detail=new SupplierModels.PurchaseDetail("UP-RACE",p.getOuterNumber(),3,1,
                new BigDecimal("0.02"),BigDecimal.ZERO,"完成",null,null,null,List.of());
        fake.details.put(p.getOuterNumber(),detail);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch saleLocked=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            Future<?> sale=pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                try {
                    RequestContext.set(new RequestContext.UserInfo(user,"customer","USER"));
                    afterSale.apply(f.order(),user,"测试售后申请",List.of());
                    saleLocked.countDown();
                    if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException();
                    return null;
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                finally { RequestContext.clear(); }
            }));
            assertTrue(saleLocked.await(5,TimeUnit.SECONDS));
            Future<?> completion=pool.submit(() -> tx.applyDetail(f.purchase(),detail,LocalDateTime.now()));
            Thread.sleep(100); assertFalse(completion.isDone());
            release.countDown(); sale.get(5,TimeUnit.SECONDS); completion.get(5,TimeUnit.SECONDS);
            assertEquals("AFTER_SALE",value("select status from orders where id=?",f.order()));
            assertEquals("PENDING",value("select fulfillment_status from order_items where id=?",f.item()));
            assertEquals("AFTER_SALE",purchases.findById(f.purchase()).orElseThrow().getErrorCode());
            RequestContext.set(new RequestContext.UserInfo(user,"test-admin","ADMIN"));
            try { afterSale.reject(f.order(),"已核实"); } finally { RequestContext.clear(); }
            worker.refresh(f.purchase());
            assertEquals("DELIVERED",value("select status from orders where id=?",f.order()));
            assertEquals("COMPLETED",purchases.findById(f.purchase()).orElseThrow().getStatus());
        } finally { release.countDown(); pool.shutdownNow(); }
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
    @Test void supplierAftercareMutationEndpointsRequireAdministrator() throws Exception {
        Fixture f=fixture(3,0); worker.process(f.purchase());
        var mvc=MockMvcBuilders.webAppContextSetup(webContext).addFilters(security).build();
        String path="/api/admin/orders/{order}/supplier-purchases/{purchase}/refunds";
        String body="{\"idempotency_key\":\""+UUID.randomUUID()+"\",\"confirmed\":true}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path,f.order(),f.purchase()).contextPath("/api")
                .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path,f.order(),f.purchase()).contextPath("/api")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("buyer").roles("USER"))
                .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertEquals(0,fake.refunds.get());
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
            assertTrue(error.getMessage().contains("核实"));
        } finally { RequestContext.clear(); }
        assertEquals(0,new BigDecimal("10.00").compareTo(jdbc.queryForObject("select balance from users where id=?",BigDecimal.class,user)));
        UUID again=(UUID)((Map<?,?>)orderService.createDirectOrder(request,user,"127.0.0.1",null).get("order")).get("id");
        assertEquals(orderId,again); assertEquals(1,fake.creates.get());
    }
}
