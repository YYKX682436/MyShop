package com.myshop.service.impl;

import com.myshop.exception.BusinessException;
import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderType;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.model.request.AdminCardKeyBatchPickupRequest;
import com.myshop.model.request.AdminOrderSearchRequest;
import com.myshop.payment.EpayPaymentProvider;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentProvider;
import com.myshop.payment.PaymentStatus;
import com.myshop.service.AutoFulfillmentService;
import com.myshop.service.AdminCardKeyService;
import com.myshop.service.AdminOrderService;
import com.myshop.service.FulfillmentService;
import com.myshop.service.ManualStockService;
import com.myshop.service.OrderService;
import com.myshop.service.PaymentService;
import com.myshop.service.AdminProductService;
import com.myshop.repository.ProductRepository;
import com.myshop.service.WebhookService;
import com.myshop.utils.CardContentParser;
import com.myshop.utils.JwtUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIf(value = "postgresAvailable", disabledReason = "PostgreSQL test database or Docker required")
@ExtendWith(SpringExtension.class)
@SpringBootTest
class PostgresDeliveryIntegrationTest {

    static final IntegrationPostgres POSTGRES = new IntegrationPostgres();

    static boolean postgresAvailable() { return POSTGRES.available(); }

    @AfterAll
    static void stopPostgres() { POSTGRES.close(); }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AdminCardKeyService adminCardKeyService;

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired private UserSecretPriceService secretPrices;
    @Autowired private com.myshop.service.PublicProductService publicProducts;
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private FilterChainProxy springSecurityFilterChain;
    @Autowired private JwtUtils jwtUtils;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AdminOrderService adminOrderService;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private AutoFulfillmentService autoFulfillmentService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private FulfillmentService fulfillmentService;

    @Autowired
    private ManualStockService manualStockService;

    @Autowired private com.myshop.service.OrderRefundService orderRefundService;
    @Autowired private com.myshop.service.AfterSaleService afterSaleService;

    @MockitoBean
    private com.myshop.service.EmailService emailService;

    @MockitoBean
    private EpayPaymentProvider epayPaymentProvider;

    @BeforeEach
    void configureProvider() {
        when(epayPaymentProvider.id()).thenReturn("epay");
        when(epayPaymentProvider.paymentTypes()).thenReturn(List.of("alipay", "wxpay"));
        when(epayPaymentProvider.successAcknowledgement())
                .thenReturn(PaymentProvider.WebhookAcknowledgement.success());
        when(epayPaymentProvider.failureAcknowledgement())
                .thenReturn(PaymentProvider.WebhookAcknowledgement.failure());
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("jwt.secret", () -> "integration-test-secret-with-at-least-64-bytes-and-random-looking-value-2026");
        registry.add("app.public-site-url", () -> "https://mc.mc520.top");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
    }

    @Test
    void secretPricesArePrivateOverrideLevelPricesAndEnforceTargets() throws Exception {
        UUID a = insertUser("secret-a-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com");
        UUID b = insertUser("secret-b-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com");
        UUID product = UUID.randomUUID();
        insertUnlimitedProduct(product);
        UUID level = scalar("select user_level_id from users where id = ?", UUID.class, a);
        jdbcTemplate.update("insert into product_level_price_overrides(product_id, user_level_id, price) values (?, ?, 7.00)", product, level);
        var secret = secretPrices.create(a, new com.myshop.model.request.UserSecretPriceRequest(product, null, new BigDecimal("9.00")));
        assertEquals(new BigDecimal("7.00"), secret.levelPrice());
        assertEquals(new BigDecimal("9.00"), publicProducts.getProduct(product, a).price());
        assertEquals(new BigDecimal("7.00"), publicProducts.getProduct(product, b).price());
        assertEquals(new BigDecimal("10.00"), publicProducts.getProduct(product, null).price());
        assertThrows(BusinessException.class, () -> secretPrices.create(a, new com.myshop.model.request.UserSecretPriceRequest(product, null, new BigDecimal("8.00"))));
        assertThrows(BusinessException.class, () -> secretPrices.create(b, new com.myshop.model.request.UserSecretPriceRequest(product, UUID.randomUUID(), new BigDecimal("8.00"))));
        assertThrows(BusinessException.class, () -> secretPrices.create(b, new com.myshop.model.request.UserSecretPriceRequest(product, null, new BigDecimal("0"))));
        assertThrows(BusinessException.class, () -> secretPrices.create(b, new com.myshop.model.request.UserSecretPriceRequest(product, null, new BigDecimal("1.001"))));
        assertThrows(BusinessException.class, () -> secretPrices.create(b, new com.myshop.model.request.UserSecretPriceRequest(product, null, new BigDecimal("10.01"))));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbcTemplate.update("insert into user_secret_prices(user_id, product_id, price) values (?, ?, 8.00)", a, product));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbcTemplate.update("update products set retail_price = 8.00 where id = ?", product));
        assertThrows(BusinessException.class, () -> adminProductService.updatePrices(product,
                new com.myshop.model.request.AdminProductPricesUpdateRequest(new BigDecimal("10.00"), new BigDecimal("8.00"), null)));
        UUID category = scalar("select category_id from products where id = ?", UUID.class, product);
        var aggregate = new com.myshop.model.request.AdminProductUpsertRequest("Integration product", null, null, null,
                new BigDecimal("10.00"), new BigDecimal("8.00"), category, 10, false, true, 0, 0,
                "MANUAL", List.of(), StockMode.UNLIMITED, null, List.of(), null);
        assertThrows(BusinessException.class, () -> adminProductService.updateProduct(product, aggregate));
        assertThrows(BusinessException.class, () -> secretPrices.delete(b, secret.id()));
        deleteSecretPriceAsAdmin(a, secret.id(), product);
        assertEquals(new BigDecimal("7.00"), publicProducts.getProduct(product, a).price());
        UUID markupLevel = UUID.randomUUID();
        jdbcTemplate.update("insert into user_levels(id,name,pricing_type,markup_rate,upgrade_rank) values (?, ?, 'COST_MARKUP', 10.0000, (select coalesce(max(upgrade_rank),0)+100 from user_levels))", markupLevel, "secret-markup-" + markupLevel);
        jdbcTemplate.update("update users set user_level_id = ? where id in (?, ?)", markupLevel, a, b);
        jdbcTemplate.update("update products set retail_price = 20.00 where id = ?", product);
        secretPrices.create(a, new com.myshop.model.request.UserSecretPriceRequest(product, null, new BigDecimal("12.00")));
        assertEquals(new BigDecimal("12.00"), publicProducts.getProduct(product, a).price());
        assertEquals(new BigDecimal("11.00"), publicProducts.getProduct(product, b).price());
        jdbcTemplate.update("update users set is_deleted = 1 where id = ?", a);
        assertEquals(new BigDecimal("11.00"), secretPrices.list(a).get(0).levelPrice());
        assertEquals(new BigDecimal("20.00"), publicProducts.getProduct(product, a).price());
    }

    @Test
    void specSecretsDriveDisplaySortingAndAreRemovedWhenTargetsChange() {
        UUID a = insertUser("spec-a-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com");
        UUID b = insertUser("spec-b-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com");
        UUID product = UUID.randomUUID(); insertUnlimitedProduct(product);
        UUID first = addSpec(product, "first"); UUID second = addSpec(product, "second");
        UUID other = UUID.randomUUID(); insertUnlimitedProduct(other);
        UUID category = scalar("select category_id from products where id = ?", UUID.class, product);
        jdbcTemplate.update("update products set category_id = ? where id = ?", category, other);
        secretPrices.create(a, new com.myshop.model.request.UserSecretPriceRequest(product, first, new BigDecimal("8.00")));
        secretPrices.create(a, new com.myshop.model.request.UserSecretPriceRequest(product, second, new BigDecimal("3.00")));
        var detail = publicProducts.getProduct(product, a);
        assertEquals(new BigDecimal("3.00"), detail.price());
        var specPrices = detail.specs().stream().collect(java.util.stream.Collectors.toMap(s -> s.id(), s -> s.price()));
        assertEquals(new BigDecimal("8.00"), specPrices.get(first));
        assertEquals(new BigDecimal("3.00"), specPrices.get(second));
        assertEquals(new BigDecimal("10.00"), publicProducts.getProduct(product, b).price());
        assertThrows(BusinessException.class, () -> secretPrices.create(b, new com.myshop.model.request.UserSecretPriceRequest(product, null, new BigDecimal("4.00"))));
        assertThrows(BusinessException.class, () -> secretPrices.create(b, new com.myshop.model.request.UserSecretPriceRequest(other, first, new BigDecimal("4.00"))));
        assertThrows(BusinessException.class, () -> adminProductService.updatePrices(product,
                new com.myshop.model.request.AdminProductPricesUpdateRequest(null, null, List.of(
                        new com.myshop.model.request.AdminProductSpecPriceRequest(first, new BigDecimal("10.00"), new BigDecimal("7.00")),
                        new com.myshop.model.request.AdminProductSpecPriceRequest(second, new BigDecimal("10.00"), new BigDecimal("10.00"))))));
        var filtered = publicProducts.listProducts(category, null, 1, 20, a, "price_low", false, new BigDecimal("2.00"), new BigDecimal("9.00"));
        assertEquals(List.of(product), filtered.getList().stream().map(p -> p.id()).toList());
        var sorted = publicProducts.listProducts(category, null, 1, 20, a, "price_high", false, null, null);
        assertEquals(List.of(other, product), sorted.getList().stream().map(p -> p.id()).toList());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbcTemplate.update("update product_specs set retail_price = 2.00 where id = ?", second));
        jdbcTemplate.update("update product_specs set is_deleted = 1 where id = ?", second);
        assertEquals(1, number("select count(*) from user_secret_prices where product_id = ?", product));
        jdbcTemplate.update("update products set spec_enabled = false where id = ?", product);
        assertEquals(0, number("select count(*) from user_secret_prices where product_id = ?", product));
        secretPrices.create(a, new com.myshop.model.request.UserSecretPriceRequest(product, null, new BigDecimal("5.00")));
        jdbcTemplate.update("update products set is_deleted = 1 where id = ?", product);
        assertEquals(0, number("select count(*) from user_secret_prices where product_id = ?", product));
    }

    @Test
    void subsiteCustomerCanBuyActivelyConfiguredZeroPriceAndReceiveRefund() {
        UUID owner=insertUser("zero-owner-guard-"+UUID.randomUUID(),UUID.randomUUID()+"@example.invalid");
        UUID customer=insertUser("zero-buyer-guard-"+UUID.randomUUID(),UUID.randomUUID()+"@example.invalid");
        com.myshop.entity.Subsite site=zeroSupplySite(owner);
        jdbcTemplate.update("update users set subsite_id=? where id=?",site.getId(),customer);
        UUID level=scalar("select user_level_id from users where id=?",UUID.class,customer);
        jdbcTemplate.update("insert into subsite_level_markups(subsite_id,user_level_id,markup_rate) values (?,?,0)",site.getId(),level);
        UUID product=UUID.randomUUID(); insertManualProduct(product);
        jdbcTemplate.update("update products set cost_price=0 where id=?",product);
        insertManualStock(product,null,1,0);
        Map<String,Object> request=balanceOrderRequest(product,"zero-guard-"+UUID.randomUUID(),"same");
        request.put("expected_unit_price","0.00");
        com.myshop.context.SiteRequestContext.set(site);
        UUID id;
        try { id=(UUID)((Map<?,?>)orderService.createDirectOrder(request,customer,"127.0.0.1",null).get("order")).get("id"); }
        finally { com.myshop.context.SiteRequestContext.clear(); }
        UUID item=scalar("select id from order_items where order_id=?",UUID.class,id);
        fulfillmentService.completeManualItems(id,List.of(new FulfillmentService.ManualFulfillmentItem(item,"完成")));
        UUID admin=insertAdminUser("zero-buyer-admin-"+UUID.randomUUID(),UUID.randomUUID()+"@example.invalid");
        com.myshop.context.RequestContext.set(new com.myshop.context.RequestContext.UserInfo(admin,"admin","ADMIN"));
        try { assertEquals(0,orderRefundService.refund(id,"测试售后",true,false).refundAmount().signum()); }
        finally { com.myshop.context.RequestContext.clear(); }
        assertEquals("REFUNDED",scalar("select status from orders where id=?",String.class,id));
        assertEquals(0,number("select count(*) from balance_transactions where order_id=?",id));
    }

    @Test
    void zeroPriceOwnerOrderDeliversOnceAndRefundsWithoutBalanceEntry() throws Exception {
        UUID owner=insertUser("zero-owner-"+UUID.randomUUID(),UUID.randomUUID()+"@example.invalid");
        com.myshop.entity.Subsite site=zeroSupplySite(owner);
        UUID product=UUID.randomUUID(); insertProduct(product);
        jdbcTemplate.update("update products set cost_price=0 where id=?",product);
        UUID card=insertCard(product,"zero-order-test-card");
        String key="zero-"+UUID.randomUUID();
        Map<String,Object> request=balanceOrderRequest(product,key,"same"); request.put("expected_unit_price","0.00");
        ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Map<String,Object>> buy=() -> {
                com.myshop.context.SiteRequestContext.set(site);
                try { start.await(); return orderService.createDirectOrder(request,owner,"127.0.0.1",null); }
                finally { com.myshop.context.SiteRequestContext.clear(); }
            };
            Future<Map<String,Object>> first=workers.submit(buy), second=workers.submit(buy);
            start.countDown();
            Map<String,Object> one=first.get(20,TimeUnit.SECONDS), two=second.get(20,TimeUnit.SECONDS);
            UUID orderId=(UUID)((Map<?,?>)one.get("order")).get("id");
            assertEquals(orderId,((Map<?,?>)two.get("order")).get("id"));
            assertEquals(true,((Map<?,?>)one.get("payment")).get("paid"));
            fulfillmentService.fulfillOrder(orderId);
            assertEquals("DELIVERED",scalar("select status from orders where id=?",String.class,orderId));
            assertEquals("SOLD",scalar("select status from card_keys where id=?",String.class,card));
            assertEquals(1,number("select count(*) from orders where idempotency_key=?",key));
            assertEquals(0,number("select count(*) from balance_transactions where order_id=?",orderId));
            assertEquals(0,number("select count(*) from payment_attempts where order_id=?",orderId));
            UUID operator=insertAdminUser("zero-admin-"+UUID.randomUUID(),UUID.randomUUID()+"@example.invalid");
            com.myshop.context.RequestContext.set(new com.myshop.context.RequestContext.UserInfo(operator,"admin","ADMIN"));
            try {
                var result=orderRefundService.refund(orderId,"零元订单售后",true,true);
                assertEquals(0,result.refundAmount().signum());
                assertThrows(BusinessException.class,() -> orderRefundService.refund(orderId,"repeat",true,true));
            } finally { com.myshop.context.RequestContext.clear(); }
            assertEquals("REFUNDED",scalar("select status from orders where id=?",String.class,orderId));
            assertEquals("AVAILABLE",scalar("select status from card_keys where id=?",String.class,card));
            assertEquals(0,number("select count(*) from balance_transactions where order_id=?",orderId));
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    @Test
    void zeroPriceManualOrderSupportsAfterSaleApproval() {
        UUID owner=insertUser("zero-manual-"+UUID.randomUUID(),UUID.randomUUID()+"@example.invalid");
        com.myshop.entity.Subsite site=zeroSupplySite(owner);
        UUID product=UUID.randomUUID(); insertManualProduct(product);
        jdbcTemplate.update("update products set cost_price=0 where id=?",product);
        insertManualStock(product,null,1,0);
        Map<String,Object> request=balanceOrderRequest(product,"zero-manual-"+UUID.randomUUID(),"same");
        request.put("expected_unit_price","0.00");
        com.myshop.context.SiteRequestContext.set(site);
        UUID orderId;
        try { orderId=(UUID)((Map<?,?>)orderService.createDirectOrder(request,owner,"127.0.0.1",null).get("order")).get("id"); }
        finally { com.myshop.context.SiteRequestContext.clear(); }
        UUID item=scalar("select id from order_items where order_id=?",UUID.class,orderId);
        fulfillmentService.completeManualItems(orderId,List.of(new FulfillmentService.ManualFulfillmentItem(item,"完成")));
        com.myshop.context.SiteRequestContext.set(site);
        com.myshop.context.RequestContext.set(new com.myshop.context.RequestContext.UserInfo(owner,"owner","USER"));
        try { afterSaleService.apply(orderId,owner,"需要售后处理",List.of()); }
        finally { com.myshop.context.SiteRequestContext.clear(); com.myshop.context.RequestContext.clear(); }
        UUID admin=insertAdminUser("zero-manual-admin-"+UUID.randomUUID(),UUID.randomUUID()+"@example.invalid");
        com.myshop.context.RequestContext.set(new com.myshop.context.RequestContext.UserInfo(admin,"admin","ADMIN"));
        try { assertEquals(0,orderRefundService.approveAfterSale(orderId,true,false).refundAmount().signum()); }
        finally { com.myshop.context.RequestContext.clear(); }
        assertEquals("REFUNDED",scalar("select status from orders where id=?",String.class,orderId));
        assertEquals("REFUNDED",scalar("select status from after_sale_requests where order_id=?",String.class,orderId));
        assertEquals(0,number("select count(*) from balance_transactions where order_id=?",orderId));
    }

    private com.myshop.entity.Subsite zeroSupplySite(UUID owner) {
        UUID siteId=UUID.randomUUID(), level=UUID.randomUUID();
        jdbcTemplate.update("insert into user_levels(id,name,pricing_type,markup_rate,enabled,is_default,upgrade_rank) values (?,?,'COST_MARKUP',0,true,false,(select coalesce(max(upgrade_rank),0)+100 from user_levels))",level,"zero-level-"+level);
        jdbcTemplate.update("update subsite_settings set supply_level_id=? where id=1",level);
        jdbcTemplate.update("insert into subsites(id,owner_id,prefix,name) values (?,?,?,?)",siteId,owner,"z"+siteId.toString().replace("-",""),"zero");
        com.myshop.entity.Subsite site=new com.myshop.entity.Subsite(); site.setId(siteId); site.setOwnerId(owner); return site;
    }

    @Test
    void onlineSubsiteCheckoutRejectsCustomerTransferredWhileWaitingForLock() throws Exception {
        UUID owner=insertUser("transfer-owner-"+UUID.randomUUID(),UUID.randomUUID()+"@example.invalid");
        UUID buyer=insertUser("transfer-buyer-"+UUID.randomUUID(),UUID.randomUUID()+"@example.invalid");
        UUID siteId=UUID.randomUUID(), product=UUID.randomUUID();
        jdbcTemplate.update("insert into subsites(id,owner_id,prefix,name) values (?,?,?,?)",siteId,owner,"transfer-test","transfer test");
        jdbcTemplate.update("update users set subsite_id=? where id=?",siteId,buyer);
        insertUnlimitedProduct(product);
        com.myshop.entity.Subsite site=new com.myshop.entity.Subsite(); site.setId(siteId); site.setOwnerId(owner); site.setPrefix("transfer-test");
        ExecutorService workers=Executors.newSingleThreadExecutor();
        try (var gate=jdbcTemplate.getDataSource().getConnection()) {
            gate.setAutoCommit(false);
            try (var update=gate.prepareStatement("update users set subsite_id=null,auth_version=auth_version+1 where id=?")) {
                update.setObject(1,buyer); update.executeUpdate();
            }
            Future<Integer> checkout=workers.submit(() -> {
                com.myshop.context.SiteRequestContext.set(site);
                try {
                    orderService.createDirectOrder(directOrderRequest(product,"unused"),buyer,"127.0.0.1",null);
                    return 0;
                } catch (BusinessException exception) { return exception.getCode(); }
                finally { com.myshop.context.SiteRequestContext.clear(); }
            });
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                String waiting="select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query ilike '%users%'";
                while (System.nanoTime()<deadline && number(waiting)<1) Thread.sleep(25);
                assertTrue(number(waiting)>0,"online checkout must lock customer before checking ownership");
            } finally { gate.commit(); }
            assertEquals(ErrorCode.FORBIDDEN,checkout.get(15,TimeUnit.SECONDS));
            assertEquals(0,number("select count(*) from orders where user_id=?",buyer));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void balanceCheckoutLocksProductBeforeUser() throws Exception {
        UUID buyer = insertUser("lock-order-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com");
        setBalance(buyer, new BigDecimal("100.00"));
        UUID product = UUID.randomUUID(); insertUnlimitedProduct(product);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (var gate = jdbcTemplate.getDataSource().getConnection()) {
            gate.setAutoCommit(false);
            try (var lock = gate.prepareStatement("select id from users where id=? for update")) {
                lock.setObject(1, buyer); lock.executeQuery().close();
            }
            Future<Map<String, Object>> checkout = workers.submit(() -> orderService.createDirectOrder(
                    balanceOrderRequest(product, "lock-order-" + UUID.randomUUID(), "fingerprint"),
                    buyer, "127.0.0.1", "unused"));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                String waiting = "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query ilike '%users%'";
                while (System.nanoTime() < deadline && number(waiting) < 1) Thread.sleep(25);
                assertTrue(number(waiting) >= 1, "checkout must be waiting for the user lock");
                try (var probe = jdbcTemplate.getDataSource().getConnection()) {
                    probe.setAutoCommit(false);
                    try (var lock = probe.prepareStatement("select id from products where id=? for update nowait")) {
                        lock.setObject(1, product);
                        assertThrows(java.sql.SQLException.class, lock::executeQuery,
                                "checkout must already hold the product lock while waiting for user");
                    } finally { probe.rollback(); }
                }
            } finally { gate.rollback(); }
            assertNotNull(checkout.get(15, TimeUnit.SECONDS).get("order"));
        } finally { workers.shutdownNow(); }
    }

    @Test
    void secretPriceIsRecalculatedForBothPaymentsAndExistingOrderKeepsSnapshot() throws Exception {
        UUID a = insertUser("order-secret-a-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com");
        UUID b = insertUser("order-secret-b-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com");
        setBalance(a, new BigDecimal("100.00"));
        UUID product = UUID.randomUUID(); insertUnlimitedProduct(product);
        var secret = secretPrices.create(a, new com.myshop.model.request.UserSecretPriceRequest(product, null, new BigDecimal("6.00")));
        Map<String, Object> balance = balanceOrderRequest(product, "secret-" + UUID.randomUUID(), "fingerprint");
        balance.put("expected_unit_price", "6.00");
        balance.put("user_id", b.toString());
        Map<?, ?> order = (Map<?, ?>) orderService.createDirectOrder(balance, a, "203.0.113.1", "unused").get("order");
        UUID orderId = (UUID) order.get("id");
        assertEquals(new BigDecimal("6.00"), scalar("select actual_amount from orders where id = ?", BigDecimal.class, orderId));
        assertEquals(new BigDecimal("94.00"), scalar("select balance from users where id = ?", BigDecimal.class, a));
        assertEquals("USER_SECRET", scalar("select pricing_source from order_items where order_id = ?", String.class, orderId));
        assertEquals(new BigDecimal("10.00"), scalar("select cost_price_snapshot from order_items where order_id = ?", BigDecimal.class, orderId));
        Map<?, ?> adminDetail = (Map<?, ?>) adminOrderService.getOrderDetail(orderId);
        Map<?, ?> adminItem = (Map<?, ?>) ((List<?>) adminDetail.get("items")).get(0);
        assertEquals(com.myshop.constant.PricingSource.USER_SECRET, adminItem.get("pricing_source"));
        assertEquals(new BigDecimal("-4.00"), adminItem.get("gross_profit"));
        secretPrices.update(a, secret.id(), new com.myshop.model.request.UserSecretPriceRequest(null, null, new BigDecimal("8.00")));
        assertEquals(new BigDecimal("6.00"), scalar("select unit_price from order_items where order_id = ?", BigDecimal.class, orderId));
        assertThrows(BusinessException.class, () -> orderService.createDirectOrder(balanceOrderRequest(product, "stale-" + UUID.randomUUID(), "fingerprint"), a, "203.0.113.1", "unused"));
        insertPaymentChannel("secret-channel-" + product);
        configurePaymentCreation("secret-trade-" + product);
        Map<String, Object> online = directOrderRequest(product, "secret-channel-" + product);
        online.put("expected_unit_price", "8.00");
        Map<?, ?> onlineOrder = (Map<?, ?>) orderService.createDirectOrder(online, a, "203.0.113.1", "unused").get("order");
        UUID onlineId = (UUID) onlineOrder.get("id");
        assertEquals(new BigDecimal("8.00"), scalar("select actual_amount from orders where id = ?", BigDecimal.class, onlineId));
        assertEquals("USER_SECRET", scalar("select pricing_source from order_items where order_id = ?", String.class, onlineId));
        assertEquals(new BigDecimal("8.00"), scalar("select amount from payment_attempts where order_id = ?", BigDecimal.class, onlineId));
        assertEquals(new BigDecimal("8.00"), publicProducts.getProduct(product, a).price());
        deleteSecretPriceAsAdmin(a, secret.id(), product);
        assertEquals(new BigDecimal("10.00"), publicProducts.getProduct(product, a).price());
        assertEquals(new BigDecimal("6.00"), scalar("select unit_price from order_items where order_id = ?", BigDecimal.class, orderId));
    }

    @Test
    void secretPriceDeletionWithoutAnAuthenticatedOperatorRollsBackAndUserIsForbidden() throws Exception {
        String username = "secret-buyer-" + UUID.randomUUID();
        UUID userId = insertUser(username, UUID.randomUUID() + "@example.com");
        UUID product = UUID.randomUUID();
        insertUnlimitedProduct(product);
        var secret = secretPrices.create(userId,
                new com.myshop.model.request.UserSecretPriceRequest(product, null, new BigDecimal("6.00")));

        assertThrows(IllegalStateException.class, () -> secretPrices.delete(userId, secret.id()));
        assertEquals(1, number("select count(*) from user_secret_prices where id = ?", secret.id()));

        int authVersion = scalar("select auth_version from users where id = ?", Integer.class, userId);
        String token = jwtUtils.generateToken(userId, username, "USER", authVersion);
        secretPriceDeleteRequest(userId, secret.id(), token).andExpect(status().isForbidden());
        assertEquals(1, number("select count(*) from user_secret_prices where id = ?", secret.id()));
        assertEquals(0, number("select count(*) from operation_logs where action = 'secret_price.delete' and target_id = ?", userId.toString()));
    }

    @Test
    void secretPriceDeletionRollsBackIfAdministratorAuditCannotBeStored() throws Exception {
        UUID userId = insertUser("audit-buyer-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com");
        UUID product = UUID.randomUUID();
        insertUnlimitedProduct(product);
        var secret = secretPrices.create(userId,
                new com.myshop.model.request.UserSecretPriceRequest(product, null, new BigDecimal("6.00")));
        String username = "audit-admin-" + UUID.randomUUID();
        UUID adminId = insertAdminUser(username, UUID.randomUUID() + "@example.com");
        int authVersion = scalar("select auth_version from users where id = ?", Integer.class, adminId);
        String token = jwtUtils.generateToken(adminId, username, "ADMIN", authVersion);

        jdbcTemplate.execute("alter table operation_logs add constraint test_secret_audit_failure check (action <> 'secret_price.delete') not valid");
        try {
            secretPriceDeleteRequest(userId, secret.id(), token).andExpect(status().isBadRequest());
            assertEquals(1, number("select count(*) from user_secret_prices where id = ?", secret.id()));
            assertEquals(0, number("select count(*) from operation_logs where user_id = ? and action = 'secret_price.delete'", adminId));
        } finally {
            jdbcTemplate.execute("alter table operation_logs drop constraint test_secret_audit_failure");
        }
    }

    private void deleteSecretPriceAsAdmin(UUID userId, UUID secretId, UUID productId) throws Exception {
        String username = "secret-admin-" + UUID.randomUUID();
        UUID adminId = insertAdminUser(username, UUID.randomUUID() + "@example.com");
        int authVersion = scalar("select auth_version from users where id = ?", Integer.class, adminId);
        String token = jwtUtils.generateToken(adminId, username, "ADMIN", authVersion);
        secretPriceDeleteRequest(userId, secretId, token).andExpect(status().isOk());
        assertEquals(1, number("""
                select count(*) from operation_logs
                where user_id = ? and username = ? and action = 'secret_price.delete'
                    and target_type = 'USER' and target_id = ? and detail = ?
                """, adminId, username, userId.toString(),
                "删除用户密价：密价记录=" + secretId + " 商品=" + productId + " 规格=null"));
    }

    private org.springframework.test.web.servlet.ResultActions secretPriceDeleteRequest(
            UUID userId, UUID secretId, String token) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain).build();
        String csrf = UUID.randomUUID().toString();
        return mvc.perform(delete("/api/admin/users/{userId}/secret-prices/{id}", userId, secretId)
                .contextPath("/api")
                .cookie(new Cookie("myshop_access_token", token), new Cookie("myshop_csrf", csrf))
                .header("X-CSRF-Token", csrf));
    }

    @Test
    void adminPickupUsesDatabaseRoleAndConsumesCardThroughNormalFulfillment() {
        UUID adminId = insertAdminUser("pickup-admin", "pickup-admin@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID cardId = insertCard(productId, "admin-pickup-card");

        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("product_id", productId.toString());
        request.put("quantity", 1);
        request.put("admin_pickup", false);

        Map<String, Object> result = orderService.createDirectOrder(request, adminId,
                "203.0.113.30", "admin-pickup-session");
        Map<?, ?> orderResult = (Map<?, ?>) result.get("order");
        Map<?, ?> paymentResult = (Map<?, ?>) result.get("payment");
        UUID orderId = (UUID) orderResult.get("id");

        assertEquals(OrderType.ADMIN_PICKUP, orderResult.get("order_type"));
        assertNotNull(orderResult.get("order_no"));
        assertEquals(null, orderResult.get("payment_method"));
        assertEquals(null, orderResult.get("payment_channel_code"));
        assertEquals(new BigDecimal("10.00"), orderResult.get("total_amount"));
        assertEquals(new BigDecimal("0.00"), orderResult.get("actual_amount"));
        assertEquals(true, paymentResult.get("paid"));
        assertEquals(true, paymentResult.get("admin_pickup"));
        assertEquals(java.util.Set.of("order_id", "expires_at",
                        "order_no", "order_type", "admin_pickup", "paid"), paymentResult.keySet());
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("ADMIN_PICKUP", scalar("select order_type from orders where id = ?", String.class, orderId));
        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(orderId, scalar("select order_id from card_keys where id = ?", UUID.class, cardId));
        assertTrue(number("select count(*) from card_keys where id = ? and order_item_id is not null and sold_at is not null", cardId) == 1);
        verify(epayPaymentProvider, never()).create(any(PaymentProvider.PaymentRequest.class));
        verify(emailService, never()).sendDeliveryEmail(any(), anyList());
    }

    @Test
    void balancePaymentDebitsExactlyOnceAndFulfillsAfterTheOrderIsPaid() {
        UUID userId = insertUser("balance-buyer-" + UUID.randomUUID(), "balance-buyer-" + UUID.randomUUID() + "@example.com");
        setBalance(userId, new BigDecimal("10.00"));
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID cardId = insertCard(productId, "balance-card");

        Map<String, Object> result = orderService.createDirectOrder(
                balanceOrderRequest(productId, "balance-success-key", "balance-success-fingerprint"),
                userId, "203.0.113.31", "balance-success-session");
        Map<?, ?> order = (Map<?, ?>) result.get("order");
        Map<?, ?> payment = (Map<?, ?>) result.get("payment");
        UUID orderId = (UUID) order.get("id");

        assertEquals("PAID", order.get("status"));
        assertNotNull(order.get("order_no"));
        assertEquals("BALANCE", order.get("payment_method"));
        assertEquals(null, order.get("payment_channel_code"));
        assertEquals("PAID", payment.get("status"));
        assertEquals(order.get("order_no"), payment.get("order_no"));
        assertEquals(true, payment.get("paid"));
        assertEquals("BALANCE", payment.get("payment_method"));
        assertEquals(null, payment.get("payment_channel_code"));
        assertEquals(null, payment.get("next_action"));
        assertEquals(new BigDecimal("0.00"), scalar("select balance from users where id = ?", BigDecimal.class, userId));
        assertEquals(1, number("select count(*) from balance_transactions "
                + "where user_id = ? and type = 'ORDER_PAYMENT' and order_id = ?", userId, orderId));
        assertEquals(new BigDecimal("10.00"), scalar(
                "select amount from balance_transactions where order_id = ?", BigDecimal.class, orderId));
        assertEquals(new BigDecimal("-10.00"), scalar(
                "select delta from balance_transactions where order_id = ?", BigDecimal.class, orderId));
        assertEquals(new BigDecimal("10.00"), scalar(
                "select balance_before from balance_transactions where order_id = ?", BigDecimal.class, orderId));
        assertEquals(new BigDecimal("0.00"), scalar(
                "select balance_after from balance_transactions where order_id = ?", BigDecimal.class, orderId));
        assertEquals(orderId, scalar("select order_id from balance_transactions where order_id = ?", UUID.class, orderId));
        assertEquals(userId, scalar("select operator_id from balance_transactions where order_id = ?", UUID.class, orderId));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(0, number("select count(*) from payment_attempts where order_id = ?", orderId));
        verify(epayPaymentProvider, never()).create(any(PaymentProvider.PaymentRequest.class));
    }

    @Test
    void balanceShortageRollsBackOrderItemsManualReservationLedgerAndBalance() {
        UUID userId = insertUser("balance-shortage-" + UUID.randomUUID(), "balance-shortage-" + UUID.randomUUID() + "@example.com");
        setBalance(userId, new BigDecimal("9.99"));
        UUID productId = UUID.randomUUID();
        insertManualProduct(productId);
        insertManualStock(productId, null, 1, 0);

        BusinessException exception = assertThrows(BusinessException.class, () -> orderService.createDirectOrder(
                balanceOrderRequest(productId, "balance-shortage-key", "balance-shortage-fingerprint"),
                userId, "203.0.113.32", "balance-shortage-session"));

        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, exception.getCode());
        assertEquals(new BigDecimal("9.99"), scalar("select balance from users where id = ?", BigDecimal.class, userId));
        assertEquals(0, number("select count(*) from orders where user_id = ?", userId));
        assertEquals(0, number("select count(*) from balance_transactions where user_id = ?", userId));
        assertEquals(1, number("select available_quantity from manual_stocks "
                + "where product_id = ? and spec_id is null", productId));
        assertEquals(0, number("select reserved_quantity from manual_stocks "
                + "where product_id = ? and spec_id is null", productId));
    }

    @Test
    void guestBalancePaymentIsRejectedAndLeavesNoPendingOrder() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertCard(productId, "guest-balance-card");

        BusinessException exception = assertThrows(BusinessException.class, () -> orderService.createDirectOrder(
                balanceOrderRequest(productId, "guest-balance-key", "guest-balance-fingerprint"),
                null, "203.0.113.33", "guest-balance-session"));

        assertEquals(ErrorCode.BALANCE_PAYMENT_REQUIRES_LOGIN, exception.getCode());
        assertEquals(0, number("select count(*) from orders where session_token = ?", "guest-balance-session"));
    }

    @Test
    void concurrentBalanceOrdersLockTheUserAndOnlyOneCanSpendTheAvailableBalance() throws Exception {
        UUID userId = insertUser("balance-concurrent-" + UUID.randomUUID(), "balance-concurrent-" + UUID.randomUUID() + "@example.com");
        setBalance(userId, new BigDecimal("10.00"));
        UUID productId = UUID.randomUUID();
        insertUnlimitedProduct(productId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> tryBalanceOrder(
                    ready, start, productId, userId, "balance-concurrent-key-a", "balance-concurrent-fingerprint-a"));
            Future<Boolean> second = executor.submit(() -> tryBalanceOrder(
                    ready, start, productId, userId, "balance-concurrent-key-b", "balance-concurrent-fingerprint-b"));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(new BigDecimal("0.00"), scalar("select balance from users where id = ?", BigDecimal.class, userId));
        assertEquals(1, number("select count(*) from orders where user_id = ?", userId));
        assertEquals(1, number("select count(*) from balance_transactions "
                + "where user_id = ? and type = 'ORDER_PAYMENT'", userId));
    }

    @Test
    void balanceIdempotencyReplayReturnsPaidOrderWithoutSecondDebitLedgerOrFulfillment() {
        UUID userId = insertUser("balance-idempotent-" + UUID.randomUUID(), "balance-idempotent-" + UUID.randomUUID() + "@example.com");
        setBalance(userId, new BigDecimal("10.00"));
        UUID productId = UUID.randomUUID();
        insertUnlimitedProduct(productId);
        Map<String, Object> request = balanceOrderRequest(
                productId, "balance-idempotent-key", "balance-idempotent-fingerprint");

        Map<String, Object> first = orderService.createDirectOrder(
                request, userId, "203.0.113.34", "balance-idempotent-session");
        Map<String, Object> second = orderService.createDirectOrder(
                new java.util.LinkedHashMap<>(request), userId, "203.0.113.34", "balance-idempotent-session");
        UUID firstOrderId = (UUID) ((Map<?, ?>) first.get("order")).get("id");
        UUID secondOrderId = (UUID) ((Map<?, ?>) second.get("order")).get("id");

        assertEquals(firstOrderId, secondOrderId);
        assertEquals("PAID", ((Map<?, ?>) second.get("payment")).get("status"));
        assertEquals(1, number("select count(*) from orders where idempotency_key = ?", "balance-idempotent-key"));
        assertEquals(1, number("select count(*) from balance_transactions "
                + "where order_id = ? and type = 'ORDER_PAYMENT'", firstOrderId));
        assertEquals(0, number("select count(*) from payment_attempts where order_id = ?", firstOrderId));
    }

    @Test
    void normalOrderRejectsBalanceRequestThatIncludesAnOnlineChannel() {
        UUID userId = insertUser("balance-protocol-" + UUID.randomUUID(), "balance-protocol-" + UUID.randomUUID() + "@example.com");
        setBalance(userId, new BigDecimal("10.00"));
        UUID productId = UUID.randomUUID();
        insertUnlimitedProduct(productId);
        Map<String, Object> request = balanceOrderRequest(productId, "balance-protocol-key", "balance-protocol-fingerprint");
        request.put("payment_channel_code", "alipay");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orderService.createDirectOrder(request, userId, "203.0.113.35", "balance-protocol-session"));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getCode());
        assertEquals(0, number("select count(*) from orders where user_id = ?", userId));
    }

    @Test
    void adminBatchPickupReturnsOneResultPerCardKeyAndBindsTheDeliveredOrderItem() {
        String suffix = UUID.randomUUID().toString();
        UUID operatorId = insertAdminUser("batch-pickup-admin-" + suffix, "batch-pickup-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID firstCardId = insertCardParts(productId, List.of("first-user", "first-password"));
        UUID secondCardId = insertCardParts(productId, List.of("second-user", "second-password"));

        var result = adminCardKeyService.batchPickup(
                new AdminCardKeyBatchPickupRequest(productId, null, 2), operatorId);

        assertEquals(2, result.count());
        assertNotNull(result.orderNo());
        assertEquals(2, result.cards().size());
        assertEquals(List.of(firstCardId, secondCardId),
                result.cards().stream().map(card -> card.id()).toList());
        assertEquals(List.of("first-user", "first-password"), result.cards().get(0).contentParts());
        UUID orderId = result.orderId();
        assertEquals("ADMIN_PICKUP", scalar("select order_type from orders where id = ?", String.class, orderId));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(new BigDecimal("0.00"), scalar("select total_amount from orders where id = ?", BigDecimal.class, orderId));
        assertEquals(new BigDecimal("0.00"), scalar("select actual_amount from orders where id = ?", BigDecimal.class, orderId));
        assertEquals(1, number("select count(*) from orders where id = ? and delivered_at is not null", orderId));
        assertEquals(1, number("select count(*) from order_items where order_id = ? and quantity = 2 "
                + "and unit_price = 0 and subtotal = 0 and stock_mode = 'CARD_KEY' "
                + "and fulfillment_status = 'DELIVERED' and delivered_at is not null", orderId));
        assertEquals(2, number("select count(*) from card_keys where order_id = ? and order_item_id is not null "
                + "and status = 'SOLD' and sold_at is not null", orderId));
        assertEquals(2, number("select count(*) from card_keys where id in (?, ?) and order_id = ?", firstCardId, secondCardId, orderId));
        verify(emailService, never()).sendDeliveryEmail(any(), anyList());
    }

    @Test
    void adminBatchPickupShortageRollsBackTheWholeTransaction() {
        String suffix = UUID.randomUUID().toString();
        UUID operatorId = insertAdminUser("shortage-pickup-admin-" + suffix, "shortage-pickup-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID cardId = insertCard(productId, "only-pickup-card");

        assertThrows(BusinessException.class, () -> adminCardKeyService.batchPickup(
                new AdminCardKeyBatchPickupRequest(productId, null, 2), operatorId));

        assertEquals("AVAILABLE", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(0, number("select count(*) from orders where user_id = ? and order_type = 'ADMIN_PICKUP'", operatorId));
        assertEquals(0, number("select count(*) from card_keys where order_id is not null and id = ?", cardId));
    }

    @Test
    void adminSinglePickupLocksAndConsumesOnlyTheRequestedCardKey() {
        String suffix = UUID.randomUUID().toString();
        UUID operatorId = insertAdminUser("single-pickup-admin-" + suffix, "single-pickup-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID firstCardId = insertCard(productId, "first-single-card");
        UUID requestedCardId = insertCardParts(productId, List.of("requested-user", "requested-password"));

        var result = adminCardKeyService.pickupCardKey(requestedCardId, operatorId);

        assertEquals(1, result.count());
        assertNotNull(result.orderNo());
        assertEquals(requestedCardId, result.cards().get(0).id());
        assertEquals("AVAILABLE", scalar("select status from card_keys where id = ?", String.class, firstCardId));
        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, requestedCardId));
        assertEquals(result.orderId(), scalar("select order_id from card_keys where id = ?", UUID.class, requestedCardId));
        assertEquals(ErrorCode.CARD_KEY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> adminCardKeyService.pickupCardKey(requestedCardId, operatorId)).getCode());

        UUID invalidCardId = insertCard(productId, "invalid-single-card");
        jdbcTemplate.update("update card_keys set status = 'INVALID' where id = ?", invalidCardId);
        assertEquals(ErrorCode.CARD_KEY_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> adminCardKeyService.pickupCardKey(invalidCardId, operatorId)).getCode());
    }

    @Test
    void adminPickupIsExcludedFromNormalAdminOrderList() {
        String suffix = UUID.randomUUID().toString();
        UUID operatorId = insertAdminUser("list-pickup-admin-" + suffix, "list-pickup-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertOrder("normal-list-order-" + UUID.randomUUID());
        insertCard(productId, "list-pickup-card");
        adminCardKeyService.batchPickup(
                new AdminCardKeyBatchPickupRequest(productId, null, 1), operatorId);

        var result = adminOrderService.searchOrders(new AdminOrderSearchRequest(1, 100, null, null, null, null, null));
        List<?> orders = result.getList();
        assertTrue(orders.stream().noneMatch(value -> value instanceof Map<?, ?> map
                && OrderType.ADMIN_PICKUP.name().equals(String.valueOf(map.get("order_type")))));
    }

    @Test
    void adminOrderSearchMatchesEveryOperationalFieldWithoutDuplicateOrders() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID userId = insertUser("search-user-" + suffix, "registered-search-" + suffix + "@example.com");
        jdbcTemplate.update("UPDATE users SET phone = ? WHERE id = ?", "13800138000", userId);
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID orderId = insertSearchOrder(userId, "search-epay-" + suffix,
                LocalDateTime.of(2026, 3, 3, 10, 0), "PAID", new BigDecimal("62.00"));
        UUID firstItemId = insertOrderItem(orderId, productId, "AUTO", "CARD_KEY", 2, 0);
        UUID secondItemId = insertOrderItem(orderId, productId, "AUTO", "CARD_KEY", 1, 0);
        jdbcTemplate.update("""
                UPDATE order_items
                SET product_title = ?, spec_name = ?, customer_inputs = CAST(? AS jsonb),
                    unit_price = ?, cost_price_snapshot = ?, retail_price_snapshot = ?, subtotal = ?
                WHERE id = ?
                """, "Search Product Title", "Search Spec Name",
                "[{\"id\":\"account\",\"label\":\"账号\",\"value\":\"search-customer-value\"}]",
                new BigDecimal("25.00"), new BigDecimal("8.00"), new BigDecimal("30.00"),
                new BigDecimal("50.00"), firstItemId);
        jdbcTemplate.update("""
                UPDATE order_items
                SET product_title = ?, spec_name = ?, customer_inputs = CAST(? AS jsonb),
                    unit_price = ?, cost_price_snapshot = ?, retail_price_snapshot = ?, subtotal = ?
                WHERE id = ?
                """, "Second Search Product", "Second Search Spec",
                "[{\"id\":\"region\",\"label\":\"区服\",\"value\":\"second-customer-value\"}]",
                new BigDecimal("12.00"), new BigDecimal("5.00"), new BigDecimal("12.00"),
                new BigDecimal("12.00"), secondItemId);
        UUID cardId = insertCardParts(productId, List.of("search-card-user", "search-card-password"));
        UUID secondCardId = insertCardParts(productId, List.of("search-card-user-2", "search-card-password"));
        jdbcTemplate.update("""
                UPDATE card_keys
                SET order_id = ?, order_item_id = ?, status = 'SOLD', sold_at = current_timestamp
                WHERE id = ?
                """, orderId, firstItemId, cardId);
        jdbcTemplate.update("""
                UPDATE card_keys
                SET order_id = ?, order_item_id = ?, status = 'SOLD', sold_at = current_timestamp
                WHERE id = ?
                """, orderId, secondItemId, secondCardId);

        Long productNo = scalar("SELECT product_no FROM products WHERE id = ?", Long.class, productId);
        Long userNo = scalar("SELECT user_no FROM users WHERE id = ?", Long.class, userId);
        Long orderNo = scalar("SELECT order_no FROM orders WHERE id = ?", Long.class, orderId);
        List<String> matchingKeywords = List.of(
                orderNo.toString(),
                "Search Product Title", "Search Spec Name",
                "search-customer-value", "search-user-" + suffix,
                "registered-search-" + suffix + "@example.com",
                "13800138000", "search-card-password",
                "search-epay-" + suffix, "search-channel-search-epay-" + suffix, "alipay");
        for (String keyword : matchingKeywords) {
            assertSearchMatches(keyword, orderId);
        }
        assertSearchContains(productNo.toString(), orderId);
        assertSearchContains(userNo.toString(), orderId);
        assertSearchDoesNotMatch(orderId.toString());
        assertSearchDoesNotMatch(userId.toString());
        assertSearchDoesNotMatch(cardId.toString());
        assertEquals(0L, adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, "definitely-no-order-match-" + suffix, null, null, null, "desc"))
                .getPagination().getTotal());

        var summary = (Map<?, ?>) adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, orderNo.toString(), null, null, null, "desc")).getList().get(0);
        assertEquals(orderId, summary.get("id"));
        assertEquals(orderNo, summary.get("order_no"));
        assertEquals("REGISTERED", summary.get("customer_type"));
        assertEquals("search-user-" + suffix, summary.get("username"));
        assertEquals(userNo, summary.get("user_no"));
        assertTrue(!summary.containsKey("email"));
        assertTrue(!summary.containsKey("order_email"));
        assertEquals(new BigDecimal("21.00"), summary.get("cost_total"));
        assertEquals(new BigDecimal("41.00"), summary.get("gross_profit_total"));
        assertEquals(new BigDecimal("66.13"), summary.get("gross_margin_total"));
    }

    @Test
    void adminOrderSearchUsesCreatedAtHalfOpenDatesAndStableSortDirection() {
        UUID oldest = insertSearchOrder(null, "trade-oldest",
                LocalDateTime.of(2026, 4, 2, 10, 0), "PAID", new BigDecimal("10.00"));
        UUID middle = insertSearchOrder(null, "trade-middle",
                LocalDateTime.of(2026, 4, 3, 10, 0), "PAID", new BigDecimal("11.00"));
        UUID boundary = insertSearchOrder(null, "trade-boundary",
                LocalDateTime.of(2026, 4, 4, 0, 0), "PAID", new BigDecimal("12.00"));

        var ascending = adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, null, "PAID", "2026-04-02", "2026-04-04", "asc"));
        assertEquals(2L, ascending.getPagination().getTotal());
        assertEquals(List.of(oldest, middle), ascending.getList().stream()
                .map(value -> ((Map<?, ?>) value).get("id")).toList());
        assertTrue(ascending.getList().stream().noneMatch(value ->
                boundary.equals(((Map<?, ?>) value).get("id"))));

        var descending = adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, null, "PAID", "2026-04-02", "2026-04-04", "desc"));
        assertEquals(2L, descending.getPagination().getTotal());
        assertEquals(List.of(middle, oldest), descending.getList().stream()
                .map(value -> ((Map<?, ?>) value).get("id")).toList());
    }

    @Test
    void normalFulfillmentAndAdminPickupCannotConsumeTheSameCardConcurrently() throws Exception {
        String suffix = UUID.randomUUID().toString();
        UUID operatorId = insertAdminUser("concurrent-pickup-admin-" + suffix, "concurrent-pickup-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID normalOrderId = insertOrder("normal-vs-pickup-" + UUID.randomUUID());
        insertOrderItem(normalOrderId, productId);
        UUID cardId = insertCard(productId, "normal-vs-pickup-card");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> admin = executor.submit(() -> pickupAfter(start, ready, productId, operatorId));
            Future<Boolean> normal = executor.submit(() -> fulfillAfter(start, ready, normalOrderId));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            assertNotEquals(admin.get(20, TimeUnit.SECONDS), normal.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(1, number("select count(*) from card_keys where id = ? and order_id is not null and order_item_id is not null", cardId));
    }

    @Test
    void loggedInUserCanCreateNormalPaymentOrderDespiteMultiplePendingOrders() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID userId = insertUser("pending-user-" + suffix, "pending-user-" + suffix + "@example.com");
        UUID productId = UUID.randomUUID();
        String clientIp = "203.0.113.40";
        String channel = "pending-user-" + suffix;
        insertProduct(productId);
        insertCard(productId, "pending-user-card-" + suffix);
        insertPaymentChannel(channel);
        for (int i = 0; i < 5; i++) {
            insertPendingOrder(channel, userId, clientIp);
        }
        configurePaymentCreation("pending-user-trade-" + suffix);

        Map<String, Object> result = orderService.createDirectOrder(
                directOrderRequest(productId, channel), userId, clientIp, "pending-user-session-" + suffix);

        assertNormalPendingPaymentOrder(result);
        verify(epayPaymentProvider, times(1)).create(any(PaymentProvider.PaymentRequest.class));
    }

    @Test
    void guestCanCreateNormalPaymentOrderDespiteMultiplePendingOrdersFromSameIp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID productId = UUID.randomUUID();
        String clientIp = "203.0.113.41";
        String channel = "pending-guest-" + suffix;
        insertProduct(productId);
        insertCard(productId, "pending-guest-card-" + suffix);
        insertPaymentChannel(channel);
        for (int i = 0; i < 5; i++) {
            insertPendingOrder(channel, null, clientIp);
        }
        configurePaymentCreation("pending-guest-trade-" + suffix);

        Map<String, Object> result = orderService.createDirectOrder(
                directOrderRequest(productId, channel),
                null, clientIp, "pending-guest-session-" + suffix);

        assertNormalPendingPaymentOrder(result);
        verify(epayPaymentProvider, times(1)).create(any(PaymentProvider.PaymentRequest.class));
    }

    @Test
    void guestCanCreateNormalPaymentOrderDespiteMultiplePendingOrdersAcrossIps() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID productId = UUID.randomUUID();
        String channel = "pending-guest-cross-ip-" + suffix;
        insertProduct(productId);
        insertCard(productId, "pending-guest-cross-ip-card-" + suffix);
        insertPaymentChannel(channel);
        for (int i = 0; i < 5; i++) {
            insertPendingOrder(channel, null, "198.51.100." + (40 + i));
        }
        configurePaymentCreation("pending-guest-cross-ip-trade-" + suffix);

        Map<String, Object> result = orderService.createDirectOrder(
                directOrderRequest(productId, channel), null, "198.51.100.50",
                "pending-guest-cross-ip-session-" + suffix);

        assertNormalPendingPaymentOrder(result);
        verify(epayPaymentProvider, times(1)).create(any(PaymentProvider.PaymentRequest.class));
    }

    @Test
    void shortageRollsBackAllEarlierCardPoolMutations() {
        UUID orderId = insertOrder("rollback-session");
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        insertProduct(firstProductId);
        insertProduct(secondProductId);
        UUID firstItemId = insertOrderItem(orderId, firstProductId);
        insertOrderItem(orderId, secondProductId);
        UUID cardId = insertCard(firstProductId, "rollback-card");

        assertThrows(BusinessException.class, () -> fulfillmentService.fulfillOrder(orderId));

        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("AVAILABLE", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(0, number("select count(*) from card_keys where order_id = ?", orderId));
        assertEquals(1, number("select count(*) from order_items where id = ? and order_id = ?", firstItemId, orderId));
    }

    @Test
    void automaticShortageDoesNotRollBackTheManualWaitingStateInAMixedOrder() {
        UUID orderId = insertOrder("mixed-shortage-session");
        UUID autoProductId = UUID.randomUUID();
        UUID manualProductId = UUID.randomUUID();
        insertProduct(autoProductId);
        insertManualProduct(manualProductId);
        insertOrderItem(orderId, autoProductId, "AUTO", "CARD_KEY", 1, 0);
        UUID manualItemId = insertOrderItem(orderId, manualProductId, "MANUAL", "MANUAL", 1, 1);
        insertManualStock(manualProductId, null, 0, 1);

        assertThrows(BusinessException.class, () -> fulfillmentService.fulfillOrder(orderId));

        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("PENDING", scalar(
                "select fulfillment_status from order_items where product_id = ?", String.class, autoProductId));
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, manualItemId));
        assertEquals(1, number(
                "select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", manualProductId));
    }

    @Test
    void concurrentDeliveryOfOneOrderAllocatesExactlyOnce() throws Exception {
        UUID orderId = insertOrder("concurrent-session");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertOrderItem(orderId, productId);
        insertCard(productId, "concurrent-card");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = executor.submit(() -> fulfillResultAfter(start, ready, orderId));
            Future<Map<String, Object>> second = executor.submit(() -> fulfillResultAfter(start, ready, orderId));
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();

            assertEquals("DELIVERED", first.get().get("status"));
            assertEquals("DELIVERED", second.get().get("status"));
        } finally {
            executor.shutdownNow();
        }

        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
    }

    @Test
    void automaticItemsSharingOnePoolKeepTheirCardBoundaries() {
        UUID orderId = insertOrder("shared-pool-session");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID firstItemId = insertOrderItem(orderId, productId);
        UUID secondItemId = insertOrderItem(orderId, productId);
        insertCard(productId, "shared-pool-card-1");
        insertCard(productId, "shared-pool-card-2");

        Map<?, ?> delivery = fulfillmentService.fulfillOrder(orderId);

        assertEquals("DELIVERED", delivery.get("status"));
        assertEquals(2, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
        assertEquals(1, number("select count(*) from card_keys where order_item_id = ?", firstItemId));
        assertEquals(1, number("select count(*) from card_keys where order_item_id = ?", secondItemId));
    }

    @Test
    void concurrentDuplicateCheckedImportsAddOnlyOneHistoricalFingerprint() throws Exception {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Map<String, Object> importRequest = Map.of(
                    "product_id", productId.toString(),
                    "content_columns", List.of("same-card"),
                    "check_duplicate", true);
            Future<Map<String, Object>> first = executor.submit(() ->
                    adminCardKeyService.importCardKeys(importRequest, UUID.randomUUID()));
            Future<Map<String, Object>> second = executor.submit(() ->
                    adminCardKeyService.importCardKeys(importRequest, UUID.randomUUID()));

            Map<String, Object> firstResult = first.get(20, TimeUnit.SECONDS);
            Map<String, Object> secondResult = second.get(20, TimeUnit.SECONDS);
            assertEquals(1, ((Number) firstResult.get("success_count")).intValue()
                    + ((Number) secondResult.get("success_count")).intValue());
            assertEquals(1, ((Number) firstResult.get("duplicate_count")).intValue()
                    + ((Number) secondResult.get("duplicate_count")).intValue());
            assertEquals(1, number("select count(*) from card_keys where product_id = ?", productId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void emptyPoolAcceptsItsFirstTwoColumnImport() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);

        Map<String, Object> result = adminCardKeyService.importCardKeys(Map.of(
                "product_id", productId.toString(),
                "content_columns", List.of("account", "password")), UUID.randomUUID());

        assertEquals(1, result.get("success_count"));
        assertEquals(2, result.get("column_count"));
        assertEquals(1, number("select count(*) from card_keys where product_id = ?", productId));
        assertEquals(2, scalar("select column_count from card_import_batches where id = ?",
                Integer.class, result.get("id")));
        assertEquals(2, scalar("select card_content_column_count from products where id = ?", Integer.class, productId));
    }

    @Test
    void twoColumnsPairRowsIntoOneCardPerRow() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        Map<String, Object> result = adminCardKeyService.importCardKeys(Map.of(
                "product_id", productId.toString(),
                "content_columns", List.of("first-account\nsecond-account", "first-code\nsecond-code")), UUID.randomUUID());
        assertEquals(2, result.get("success_count"));
        assertEquals(2, number("select count(*) from card_keys where product_id = ?", productId));
        assertEquals(1, number("select count(*) from card_keys where product_id = ? and content_parts = '[\"first-account\",\"first-code\"]'::jsonb", productId));
        assertEquals(1, number("select count(*) from card_keys where product_id = ? and content_parts = '[\"second-account\",\"second-code\"]'::jsonb", productId));
    }

    @Test
    void rolledBackFirstImportDoesNotEstablishAFormatOrLeaveInventory() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            adminCardKeyService.importCardKeys(Map.of("product_id", productId.toString(),
                    "content_columns", List.of("account", "code")), UUID.randomUUID());
            throw new IllegalStateException("force transaction rollback");
        }));
        assertNull(scalar("select card_content_column_count from products where id = ?", Integer.class, productId));
        assertEquals(0, number("select count(*) from card_keys where product_id = ?", productId));
        assertEquals(0, number("select count(*) from card_import_batches where product_id = ?", productId));
    }

    @Test
    void repeatedImportsInTheSameTransactionSeeTheEstablishedFormat() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            adminCardKeyService.importCardKeys(Map.of("product_id", productId.toString(),
                    "content_columns", List.of("account", "code")), UUID.randomUUID());
            BusinessException exception = assertThrows(BusinessException.class, () -> adminCardKeyService.importCardKeys(
                    Map.of("product_id", productId.toString(), "content_columns", List.of("wrong")), UUID.randomUUID()));
            assertEquals(ErrorCode.CARD_KEY_FORMAT_ERROR, exception.getCode());
            // The nested service marks the shared transaction rollback-only.
            status.setRollbackOnly();
        });
    }

    @Test
    void productDetailReportsThePersistedTemplateAndMixedHistory() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        adminCardKeyService.importCardKeys(Map.of("product_id", productId.toString(),
                "content_columns", List.of("account", "code")), UUID.randomUUID());
        assertEquals(2, adminProductService.getProduct(productId).cardContentColumnCount());
        assertEquals(List.of(), adminProductService.getProduct(productId).cardContentColumnCounts());
        insertCardParts(productId, List.of("legacy-single"), CardKeyStatus.INVALID);
        var detail = adminProductService.getProduct(productId);
        assertEquals(0, detail.cardContentColumnCount());
        assertEquals(List.of(1, 2), detail.cardContentColumnCounts());
    }

    @Test
    void staleProductEditCannotEraseAFormatEstablishedConcurrently() throws Exception {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch continueEdit = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> edit = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Product stale = productRepository.findById(productId).orElseThrow();
                read.countDown();
                try {
                    if (!continueEdit.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("edit timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                stale.setTitle("edited product");
            }));
            assertTrue(read.await(10, TimeUnit.SECONDS));
            adminCardKeyService.importCardKeys(Map.of("product_id", productId.toString(),
                    "content_columns", List.of("account", "code")), UUID.randomUUID());
            continueEdit.countDown();
            edit.get(20, TimeUnit.SECONDS);
            assertEquals(2, scalar("select card_content_column_count from products where id = ?", Integer.class, productId));
        } finally {
            continueEdit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void activeAvailableTwoColumnPoolAcceptsAnotherTwoColumnImport() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertCardParts(productId, List.of("existing-account", "existing-password"));

        Map<String, Object> result = adminCardKeyService.importCardKeys(Map.of(
                "product_id", productId.toString(),
                "content_columns", List.of("new-account", "new-password")), UUID.randomUUID());

        assertEquals(1, result.get("success_count"));
        assertEquals(2, number("select count(*) from card_keys where product_id = ?", productId));
        assertEquals(1, number("select count(distinct jsonb_array_length(content_parts)) from card_keys "
                + "where product_id = ? and status in ('AVAILABLE','LOCKED')", productId));
    }

    @Test
    void activeAvailableTwoColumnPoolRejectsOneColumnWithoutWrites() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertCardParts(productId, List.of("existing-account", "existing-password"));
        int keysBefore = number("select count(*) from card_keys where product_id = ?", productId);
        int batchesBefore = number("select count(*) from card_import_batches where product_id = ?", productId);

        BusinessException exception = assertThrows(BusinessException.class, () -> adminCardKeyService.importCardKeys(Map.of(
                "product_id", productId.toString(),
                "content_columns", List.of("one-column")), UUID.randomUUID()));

        assertEquals(ErrorCode.CARD_KEY_FORMAT_ERROR, exception.getCode());
        assertEquals("该商品固定为 2 列卡券内容，请按 2 列导入。", exception.getMessage());
        assertEquals(keysBefore, number("select count(*) from card_keys where product_id = ?", productId));
        assertEquals(batchesBefore, number("select count(*) from card_import_batches where product_id = ?", productId));
    }

    @Test
    void activeLockedTwoColumnPoolRejectsOneColumn() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertCardParts(productId, List.of("locked-account", "locked-password"), CardKeyStatus.LOCKED);

        BusinessException exception = assertThrows(BusinessException.class, () -> adminCardKeyService.importCardKeys(Map.of(
                "product_id", productId.toString(),
                "content_columns", List.of("one-column")), UUID.randomUUID()));

        assertEquals(ErrorCode.CARD_KEY_FORMAT_ERROR, exception.getCode());
        assertEquals(1, number("select count(*) from card_keys where product_id = ?", productId));
    }

    @Test
    void soldAndInvalidHistoryCannotChangeTheProductColumnCount() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertCardParts(productId, List.of("sold-account", "sold-password"), CardKeyStatus.SOLD);
        insertCardParts(productId, List.of("invalid-account", "invalid-password"), CardKeyStatus.INVALID);

        BusinessException exception = assertThrows(BusinessException.class, () -> adminCardKeyService.importCardKeys(Map.of(
                "product_id", productId.toString(),
                "content_columns", List.of("new-one-column")), UUID.randomUUID()));

        assertEquals(ErrorCode.CARD_KEY_FORMAT_ERROR, exception.getCode());
        assertEquals(2, scalar("select card_content_column_count from products where id = ?", Integer.class, productId));
        assertEquals(0, number("select count(*) from card_keys where product_id = ? and status = 'AVAILABLE'", productId));
    }

    @Test
    void dirtyActivePoolRejectsNewImportAndPreservesAllRows() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertCardParts(productId, List.of("one-column"), CardKeyStatus.AVAILABLE);
        insertCardParts(productId, List.of("two-account", "two-password"), CardKeyStatus.LOCKED);
        int keysBefore = number("select count(*) from card_keys where product_id = ?", productId);

        BusinessException exception = assertThrows(BusinessException.class, () -> adminCardKeyService.importCardKeys(Map.of(
                "product_id", productId.toString(),
                "content_columns", List.of("another", "two")), UUID.randomUUID()));

        assertEquals(ErrorCode.CARD_KEY_FORMAT_ERROR, exception.getCode());
        assertEquals("该商品历史卡券列数冲突（1、2列），暂不能导入，请联系管理员处理。", exception.getMessage());
        assertEquals(keysBefore, number("select count(*) from card_keys where product_id = ?", productId));
        assertEquals(0, number("select count(*) from card_import_batches where product_id = ?", productId));
    }

    @Test
    void duplicateCheckOffStillAppliesTheActivePoolFormatRule() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertCardParts(productId, List.of("existing-account", "existing-password"));

        BusinessException exception = assertThrows(BusinessException.class, () -> adminCardKeyService.importCardKeys(Map.of(
                "product_id", productId.toString(),
                "content_columns", List.of("one-column"),
                "check_duplicate", false), UUID.randomUUID()));

        assertEquals(ErrorCode.CARD_KEY_FORMAT_ERROR, exception.getCode());
        assertEquals(1, number("select count(*) from card_keys where product_id = ?", productId));
        assertEquals(0, number("select count(*) from card_import_batches where product_id = ?", productId));
    }

    @Test
    void concurrentFirstImportsWithDifferentWidthsAllowOnlyOneWinner() throws Exception {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> oneColumn = executor.submit(() -> importWidthAfter(
                    ready, start, productId, List.of("first-width-one")));
            Future<Boolean> twoColumns = executor.submit(() -> importWidthAfter(
                    ready, start, productId, List.of("second-width-one", "second-width-two")));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            boolean oneColumnSucceeded = oneColumn.get(20, TimeUnit.SECONDS);
            boolean twoColumnsSucceeded = twoColumns.get(20, TimeUnit.SECONDS);
            assertNotEquals(oneColumnSucceeded, twoColumnsSucceeded);
            assertEquals(1, number("select count(*) from card_import_batches where product_id = ?", productId));
            assertEquals(1, number("select count(*) from card_keys where product_id = ?", productId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void otherSpecificationsMustUseTheSameProductFormat() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID firstSpec = addSpec(productId, "first");
        UUID secondSpec = addSpec(productId, "second");
        adminCardKeyService.importCardKeys(Map.of("product_id", productId.toString(), "spec_id", firstSpec.toString(),
                "content_columns", List.of("account", "code")), UUID.randomUUID());
        BusinessException conflict = assertThrows(BusinessException.class, () -> adminCardKeyService.importCardKeys(Map.of(
                "product_id", productId.toString(), "spec_id", secondSpec.toString(),
                "content_columns", List.of("one"), "check_duplicate", false), UUID.randomUUID()));
        assertEquals(ErrorCode.CARD_KEY_FORMAT_ERROR, conflict.getCode());
        Map<String, Object> matching = adminCardKeyService.importCardKeys(Map.of("product_id", productId.toString(),
                "spec_id", secondSpec.toString(), "content_columns", List.of("account-2", "code-2")), UUID.randomUUID());
        assertEquals(1, matching.get("success_count"));
        assertEquals(2, number("select count(*) from card_keys where product_id = ?", productId));
    }

    @Test
    void concurrentFirstImportsAcrossSpecificationsEstablishOnlyOneWidth() throws Exception {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID firstSpec = addSpec(productId, "first-race");
        UUID secondSpec = addSpec(productId, "second-race");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = executor.submit(() -> importWidthAfter(ready, start, productId, firstSpec, List.of("one")));
            Future<Boolean> two = executor.submit(() -> importWidthAfter(ready, start, productId, secondSpec, List.of("one", "two")));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
            assertEquals(1, number("select count(*) from card_keys where product_id = ?", productId));
            assertEquals(1, number("select count(*) from card_import_batches where product_id = ?", productId));
            assertEquals(1, number("select count(*) from card_keys ck join products p on p.id = ck.product_id "
                    + "where p.id = ? and p.card_content_column_count = jsonb_array_length(ck.content_parts)", productId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentPaymentInitializationCreatesOneProviderPayment() throws Exception {
        UUID orderId = insertPendingOrder("payment-concurrency-channel");
        insertPaymentChannel("payment-concurrency-channel");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger providerCreateCalls = new AtomicInteger();
        when(epayPaymentProvider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenAnswer(invocation -> {
                    assertEquals(1, providerCreateCalls.incrementAndGet());
                    assertTrue(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    providerEntered.countDown();
                    assertTrue(releaseProvider.await(10, TimeUnit.SECONDS));
                    return new PaymentProvider.PaymentResult(
                            "payment-concurrency-trade",
                            new PaymentNextAction.QrCode("qr-payment-concurrency"));
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = executor.submit(() -> createPaymentAfter(
                    start, ready, orderId));
            Future<Map<String, Object>> second = executor.submit(() -> createPaymentAfter(
                    start, ready, orderId));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            assertTrue(providerEntered.await(10, TimeUnit.SECONDS));
            assertEquals("SUBMITTING", scalar("select creation_status from payment_attempts where order_id = ?", String.class, orderId));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!first.isDone() && !second.isDone() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(first.isDone() || second.isDone(), "concurrent caller must finish while the provider is paused");
            new TransactionTemplate(transactionManager).execute(status ->
                    jdbcTemplate.queryForObject("select id from orders where id = ? for update nowait", UUID.class, orderId));
            releaseProvider.countDown();

            Map<String, Object> firstResult = first.get(20, TimeUnit.SECONDS);
            Map<String, Object> secondResult = second.get(20, TimeUnit.SECONDS);
            Map<String, String> action = Map.of("type", "qr_code", "value", "qr-payment-concurrency");
            assertTrue(action.equals(firstResult.get("next_action")) || action.equals(secondResult.get("next_action")));
            assertEquals(1, providerCreateCalls.get(), "Provider.create must run once");

            Map<String, Object> stored = jdbcTemplate.queryForMap(
                    "select merchant_trade_no, provider_trade_no, next_action from payment_attempts where order_id = ?",
                    orderId);
            assertTrue(String.valueOf(stored.get("merchant_trade_no")).startsWith("m_"));
            assertEquals("payment-concurrency-trade", stored.get("provider_trade_no"));
            assertEquals("{\"type\":\"qr_code\",\"value\":\"qr-payment-concurrency\"}",
                    stored.get("next_action"));
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void simultaneousFirstCheckoutWithSameKeyCreatesOnlyOneOrderAndIntent() throws Exception {
        UUID product = UUID.randomUUID(); insertUnlimitedProduct(product); insertPaymentChannel("first-checkout-race");
        String key = "first-race-" + UUID.randomUUID();
        Map<String, Object> request = directOrderRequest(product, "first-checkout-race");
        request.put("idempotency_key", key); request.put("idempotency_fingerprint", "same-request");
        configurePaymentCreation("first-race-trade");
        jdbcTemplate.execute("create or replace function myshop_test_order_insert_gate() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(7362539); return NEW; end $$");
        jdbcTemplate.execute("create trigger myshop_test_order_insert_gate before insert on orders for each row when (NEW.idempotency_key='" + key + "') execute function myshop_test_order_insert_gate()");
        java.sql.Connection gate = webApplicationContext.getBean(javax.sql.DataSource.class).getConnection();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch bothStarted = new CountDownLatch(2);
        try {
            try (var statement = gate.createStatement()) { statement.execute("select pg_advisory_lock(7362539)"); }
            Future<Map<String, Object>> a = workers.submit(() -> {
                start.await(); bothStarted.countDown();
                return orderService.createDirectOrder(request, null, "127.0.0.1", "first-race-owner");
            });
            Future<Map<String, Object>> b = workers.submit(() -> {
                start.await(); bothStarted.countDown();
                return orderService.createDirectOrder(request, null, "127.0.0.1", "first-race-owner");
            });
            start.countDown();
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "both concurrent requests must start");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            String waiting = "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query ilike '%insert%orders%'";
            while (System.nanoTime() < deadline && number(waiting) < 1) Thread.sleep(25);
            assertTrue(number(waiting) >= 1, "one order insert must reach the database gate while the same-product checkout is serialized");
            try (var statement = gate.createStatement()) { statement.execute("select pg_advisory_unlock(7362539)"); }
            Map<?, ?> firstOrder = (Map<?, ?>) a.get(15, TimeUnit.SECONDS).get("order");
            Map<?, ?> secondOrder = (Map<?, ?>) b.get(15, TimeUnit.SECONDS).get("order");
            assertEquals(firstOrder.get("id"), secondOrder.get("id"));
            assertEquals(1, number("select count(*) from orders where idempotency_key=?", key));
            assertEquals(1, number("select count(*) from payment_attempts where order_id=?", firstOrder.get("id")));
            verify(epayPaymentProvider).create(any());
        } finally {
            try (var statement = gate.createStatement()) { statement.execute("select pg_advisory_unlock(7362539)"); }
            gate.close(); workers.shutdownNow();
            jdbcTemplate.execute("drop trigger if exists myshop_test_order_insert_gate on orders");
            jdbcTemplate.execute("drop function if exists myshop_test_order_insert_gate()");
        }
    }

    @Test
    void duplicateCheckoutSeesCommittedIntentWhileGatewayIsWaiting() throws Exception {
        UUID product = UUID.randomUUID(); insertUnlimitedProduct(product); insertPaymentChannel("checkout-double");
        Map<String, Object> request = directOrderRequest(product, "checkout-double");
        String key = "checkout-double-" + UUID.randomUUID();
        request.put("idempotency_key", key); request.put("idempotency_fingerprint", "same");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(epayPaymentProvider.create(any())).thenAnswer(invocation -> {
            PaymentProvider.PaymentRequest call = invocation.getArgument(0);
            assertTrue(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, number("select count(*) from orders where id=?", UUID.fromString(call.orderId())));
            assertEquals(1, number("select count(*) from payment_attempts where merchant_trade_no=? and creation_status='SUBMITTING'", call.merchantTradeNo()));
            entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
            return new PaymentProvider.PaymentResult("checkout-double-trade", new PaymentNextAction.QrCode("checkout-qr"));
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = workers.submit(() -> orderService.createDirectOrder(request, null, "127.0.0.1", "checkout-owner"));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<Map<String, Object>> second = workers.submit(() -> orderService.createDirectOrder(request, null, "127.0.0.1", "checkout-owner"));
            Map<String, Object> duplicate = second.get(3, TimeUnit.SECONDS);
            assertEquals(com.myshop.payment.PaymentCreationStatus.SUBMITTING, ((Map<?, ?>) duplicate.get("payment")).get("creation_status"));
            release.countDown();
            Map<String, Object> original = first.get(10, TimeUnit.SECONDS);
            assertEquals(((Map<?, ?>) original.get("order")).get("id"), ((Map<?, ?>) duplicate.get("order")).get("id"));
            assertEquals(1, number("select count(*) from orders where idempotency_key=?", key));
            verify(epayPaymentProvider).create(any());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test
    void doubleRepayDoesNotClaimAnotherIntentDuringGatewayCall() throws Exception {
        UUID orderId = insertPendingOrder("repay-double"); insertPaymentChannel("repay-double");
        jdbcTemplate.update("update orders set session_token='repay-owner' where id=?", orderId);
        configurePaymentCreation("repay-old");
        UUID oldId = (UUID) paymentService.createPayment(orderId, "repay-double", new BigDecimal("10.00"), "pc").get("payment_id");
        jdbcTemplate.update("update payment_attempts set created_at=current_timestamp-interval '1 minute' where id=?", oldId);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(epayPaymentProvider.create(any())).thenAnswer(invocation -> {
            assertTrue(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
            return new PaymentProvider.PaymentResult("repay-new", new PaymentNextAction.QrCode("repay-qr"));
        });
        var access = webApplicationContext.getBean(com.myshop.service.OrderAccessService.class);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = workers.submit(() -> access.repayOrder(orderId, "pc", null, "repay-owner"));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<Map<String, Object>> second = workers.submit(() -> access.repayOrder(orderId, "pc", null, "repay-owner"));
            Map<String, Object> duplicate = second.get(3, TimeUnit.SECONDS);
            assertEquals(com.myshop.payment.PaymentCreationStatus.SUBMITTING, duplicate.get("creation_status"));
            release.countDown();
            assertEquals(duplicate.get("payment_id"), first.get(10, TimeUnit.SECONDS).get("payment_id"));
            assertEquals(2, number("select count(*) from payment_attempts where order_id=?", orderId));
            verify(epayPaymentProvider, times(2)).create(any());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test
    void oldPaidLinkBeforeNewPaidLinkRequiresManualHandlingForBoth() {
        UUID orderId = insertPendingOrder("old-first"); insertPaymentChannel("old-first");
        UUID product = UUID.randomUUID(); insertProduct(product); insertOrderItem(orderId, product); insertCard(product, "old-first-card");
        when(epayPaymentProvider.create(any())).thenReturn(
                new PaymentProvider.PaymentResult("old-first-trade", new PaymentNextAction.QrCode("old")),
                new PaymentProvider.PaymentResult("new-second-trade", new PaymentNextAction.QrCode("new")));
        UUID oldId = (UUID) paymentService.createPayment(orderId, "old-first", new BigDecimal("10.00"), "pc").get("payment_id");
        String oldMerchant = scalar("select merchant_trade_no from payment_attempts where id=?", String.class, oldId);
        jdbcTemplate.update("update payment_attempts set created_at=current_timestamp-interval '1 minute' where id=?", oldId);
        UUID newId = (UUID) paymentService.repay(orderId, "pc").get("payment_id");
        String newMerchant = scalar("select merchant_trade_no from payment_attempts where id=?", String.class, newId);
        when(epayPaymentProvider.extractMerchantTradeNo(any())).thenAnswer(inv -> inv.getArgument(0, PaymentProvider.WebhookRequest.class).parameter("out_trade_no"));
        when(epayPaymentProvider.verifyAndParseCallback(any(), anyMap())).thenAnswer(inv -> {
            PaymentProvider.WebhookRequest req = inv.getArgument(0);
            return new PaymentProvider.CallbackResult("epay:" + req.parameter("trade_no"), req.parameter("out_trade_no"), req.parameter("trade_no"), PaymentStatus.PAID, new BigDecimal("10.00"));
        });
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(oldMerchant, "old-first-trade")).body());
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(newMerchant, "new-second-trade")).body());
        assertEquals(2, number("select count(*) from webhook_events where order_id=? and process_result like 'LATE_PAID_%'", orderId));
        assertEquals(0, number("select count(*) from card_keys where order_id=? and status='SOLD'", orderId));
        assertEquals("PENDING", scalar("select status from orders where id=?", String.class, orderId));
    }

    @Test
    void paymentReviewRequiresAdministratorAndKeepsAuditWithTheOrder() throws Exception {
        UUID orderId = insertPendingOrder("review-payment"); insertPaymentChannel("review-payment");
        configurePaymentCreation("review-trade");
        UUID paymentId = (UUID) paymentService.createPayment(orderId, "review-payment", new BigDecimal("10.00"), "pc").get("payment_id");
        String username = "payment-review-admin-" + UUID.randomUUID();
        UUID admin = insertAdminUser(username, UUID.randomUUID() + "@example.com");
        int version = scalar("select auth_version from users where id=?", Integer.class, admin);
        String token = jwtUtils.generateToken(admin, username, "ADMIN", version);
        String csrf = UUID.randomUUID().toString();
        String body = "{\"attempt_id\":\"" + paymentId + "\",\"note\":\"已联系网关核实\"}";
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(springSecurityFilterChain).build();
        String path = "/api/admin/orders/{id}/payment-reviews";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path, orderId).contextPath("/api")
                .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path, orderId).contextPath("/api")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("buyer").roles("USER"))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path, orderId).contextPath("/api")
                .cookie(new Cookie("myshop_access_token", token), new Cookie("myshop_csrf", csrf)).header("X-CSRF-Token", csrf)
                .contentType("application/json").content(body)).andExpect(status().isOk());
        assertEquals(1, number("select count(*) from operation_logs where user_id=? and action='order.payment_review' and target_id=?", admin, orderId.toString()));
        Map<?, ?> detail = (Map<?, ?>) adminOrderService.getOrderDetail(orderId);
        assertEquals(1, ((List<?>) detail.get("payment_reviews")).size());
    }

    @Test
    void replayAfterLateReceiptStillReturnsTheExistingOrderWithoutAnotherPayment() {
        UUID product = UUID.randomUUID(); insertUnlimitedProduct(product); insertPaymentChannel("late-replay");
        Map<String, Object> request = directOrderRequest(product, "late-replay");
        request.put("idempotency_key", "late-replay-" + UUID.randomUUID()); request.put("idempotency_fingerprint", "same-request");
        configurePaymentCreation("late-replay-trade");
        Map<String, Object> first = orderService.createDirectOrder(request, null, "127.0.0.1", "late-replay-owner");
        UUID orderId = (UUID) ((Map<?, ?>) first.get("order")).get("id");
        UUID attemptId = (UUID) ((Map<?, ?>) first.get("payment")).get("payment_id");
        String merchant = scalar("select merchant_trade_no from payment_attempts where id=?", String.class, attemptId);
        jdbcTemplate.update("update payment_attempts set status='CLOSED' where id=?", attemptId);
        configureSuccessfulCallback("late-replay-trade");
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(merchant, "late-replay-trade")).body());
        Map<String, Object> replay = orderService.createDirectOrder(request, null, "127.0.0.1", "late-replay-owner");
        assertEquals(orderId, ((Map<?, ?>) replay.get("order")).get("id"));
        assertNull(((Map<?, ?>) replay.get("payment")).get("next_action"));
        assertEquals(1, number("select count(*) from payment_attempts where order_id=?", orderId));
        verify(epayPaymentProvider).create(any());
    }

    @Test
    void signedReceiptAwaitingQueryIsDurableAndLaterConfirmsOnce() {
        UUID orderId = insertPendingOrder("pending-query"); insertPaymentChannel("pending-query");
        UUID product = UUID.randomUUID(); insertProduct(product); insertOrderItem(orderId, product); insertCard(product, "pending-query-card");
        configurePaymentCreation("pending-query-trade");
        UUID id = (UUID) paymentService.createPayment(orderId, "pending-query", new BigDecimal("10.00"), "pc").get("payment_id");
        String merchant = scalar("select merchant_trade_no from payment_attempts where id=?", String.class, id);
        configureSuccessfulCallback("pending-query-trade");
        when(epayPaymentProvider.verifyAndParseCallback(any(), anyMap())).thenAnswer(inv -> {
            PaymentProvider.WebhookRequest callback = inv.getArgument(0);
            return new PaymentProvider.CallbackResult("epay:pending-query-trade", callback.parameter("out_trade_no"),
                    "pending-query-trade", PaymentStatus.PAID, new BigDecimal("10.00"));
        });
        when(epayPaymentProvider.query(any())).thenReturn(null,
                new PaymentProvider.PaymentQueryResult(PaymentStatus.PAID, new BigDecimal("10.00"), "pending-query-trade"));
        var request = callback(merchant, "pending-query-trade");
        assertEquals("FAIL", webhookService.handleWebhook("epay", request).body());
        assertEquals(1, number("select count(*) from webhook_events where order_id=? and process_result='LATE_PAID_UNVERIFIED'", orderId));
        assertEquals("PENDING", scalar("select status from orders where id=?", String.class, orderId));
        assertEquals(0, number("select count(*) from card_keys where order_id=? and status='SOLD'", orderId));
        assertThrows(BusinessException.class, () -> paymentService.repay(orderId, "pc"));
        paymentService.refreshPayment(orderId);
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", request).body());
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", request).body());
        assertEquals(1, number("select count(*) from webhook_events where order_id=? and process_result='SUCCESS'", orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id=? and status='SOLD'", orderId));
        assertEquals("DELIVERED", scalar("select status from orders where id=?", String.class, orderId));
    }

    @Test
    void identicalGatewayEventIdsForDifferentMerchantTradesDoNotLoseAReceipt() {
        UUID one = insertPendingOrder("event-scope-one"), two = insertPendingOrder("event-scope-two");
        insertPaymentChannel("event-scope-one"); insertPaymentChannel("event-scope-two");
        when(epayPaymentProvider.create(any())).thenReturn(new PaymentProvider.PaymentResult("shared-trade", new PaymentNextAction.QrCode("mock")));
        UUID first = (UUID) paymentService.createPayment(one, "event-scope-one", new BigDecimal("10.00"), "pc").get("payment_id");
        UUID second = (UUID) paymentService.createPayment(two, "event-scope-two", new BigDecimal("10.00"), "pc").get("payment_id");
        String merchantOne = scalar("select merchant_trade_no from payment_attempts where id=?", String.class, first);
        String merchantTwo = scalar("select merchant_trade_no from payment_attempts where id=?", String.class, second);
        when(epayPaymentProvider.extractMerchantTradeNo(any())).thenAnswer(inv -> inv.getArgument(0, PaymentProvider.WebhookRequest.class).parameter("out_trade_no"));
        when(epayPaymentProvider.verifyAndParseCallback(any(), anyMap())).thenAnswer(inv -> {
            PaymentProvider.WebhookRequest req = inv.getArgument(0);
            return new PaymentProvider.CallbackResult("epay:shared-trade", req.parameter("out_trade_no"), "shared-trade", PaymentStatus.PAID, new BigDecimal("10.00"));
        });
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(merchantOne, "shared-trade")).body());
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(merchantTwo, "shared-trade")).body());
        assertEquals("PAID", scalar("select status from payment_attempts where id=?", String.class, first));
        assertEquals("PAID", scalar("select status from payment_attempts where id=?", String.class, second));
        assertEquals(2, number("select count(*) from webhook_events where payment_attempt_id in (?,?)", first, second));
    }

    @Test
    void unresolvedAttemptCannotBeBypassedByNewerReadyHistory() {
        UUID orderId = insertPendingOrder("unresolved-history"); insertPaymentChannel("unresolved-history");
        jdbcTemplate.update("update orders set session_token='unresolved-owner' where id=?", orderId);
        when(epayPaymentProvider.create(any())).thenThrow(new IllegalStateException("mock timeout"));
        UUID unknownId = (UUID) paymentService.createPayment(orderId, "unresolved-history", new BigDecimal("10.00"), "pc").get("payment_id");
        jdbcTemplate.update("update payment_attempts set created_at=current_timestamp-interval '1 minute' where id=?", unknownId);
        jdbcTemplate.update("""
                insert into payment_attempts(id,order_id,channel_id,channel_code_snapshot,provider_type,payment_type,
                    provider_config_snapshot,merchant_trade_no,amount,status,next_action,creation_status,expires_at)
                values (?,?,?,'unresolved-history','epay','wxpay','{}',?,10,'PENDING',
                    '{"type":"qr_code","value":"stale-link"}','READY',current_timestamp+interval '10 minutes')
                """, UUID.randomUUID(), orderId, UUID.randomUUID(), "stale-history-" + UUID.randomUUID());
        Map<String, Object> repaid = paymentService.repay(orderId, "pc");
        assertEquals(unknownId, repaid.get("payment_id"));
        assertEquals(com.myshop.payment.PaymentCreationStatus.UNKNOWN, repaid.get("creation_status"));
        Map<?, ?> view = (Map<?, ?>) webApplicationContext.getBean(com.myshop.service.OrderAccessService.class)
                .getOrder(orderId, null, "unresolved-owner").get("payment");
        assertEquals(unknownId, view.get("payment_id"));
        assertNull(view.get("next_action"));
        verify(epayPaymentProvider).create(any());
    }

    @Test
    void missingCarrierPreservesReturnedGatewayNumberAsUnknown() {
        UUID orderId = insertPendingOrder("missing-carrier"); insertPaymentChannel("missing-carrier");
        when(epayPaymentProvider.create(any())).thenReturn(new PaymentProvider.PaymentResult("carrierless-trade", null));
        Map<String, Object> result = paymentService.createPayment(orderId, "missing-carrier", new BigDecimal("10.00"), "pc");
        UUID id = (UUID) result.get("payment_id");
        assertEquals(com.myshop.payment.PaymentCreationStatus.UNKNOWN, result.get("creation_status"));
        assertNull(result.get("next_action"));
        assertEquals("carrierless-trade", scalar("select create_response_trade_no from payment_attempts where id=?", String.class, id));
        assertEquals(id, paymentService.repay(orderId, "pc").get("payment_id"));
        verify(epayPaymentProvider).create(any());
    }

    @Test
    void conflictingCreateResponseAfterCallbackPreservesBothTradeNumbers() {
        UUID orderId = insertPendingOrder("conflicting-response"); insertPaymentChannel("conflicting-response");
        configureSuccessfulCallback("callback-trade");
        when(epayPaymentProvider.create(any())).thenAnswer(invocation -> {
            PaymentProvider.PaymentRequest request = invocation.getArgument(0);
            assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(request.merchantTradeNo(), "callback-trade")).body());
            return new PaymentProvider.PaymentResult("other-create-trade", new PaymentNextAction.QrCode("do-not-show"));
        });
        Map<String, Object> result = paymentService.createPayment(orderId, "conflicting-response", new BigDecimal("10.00"), "pc");
        UUID id = (UUID) result.get("payment_id");
        assertEquals(PaymentStatus.PAID, result.get("status"));
        assertEquals(com.myshop.payment.PaymentCreationStatus.UNKNOWN, result.get("creation_status"));
        assertNull(result.get("next_action"));
        assertEquals("callback-trade", scalar("select provider_trade_no from payment_attempts where id=?", String.class, id));
        assertEquals("other-create-trade", scalar("select create_response_trade_no from payment_attempts where id=?", String.class, id));
    }

    @Test
    void aRealReceiptAfterManualMarkPaidIsRecordedWithoutAnotherFulfillment() {
        UUID orderId = insertPendingOrder("manual-paid-receipt"); insertPaymentChannel("manual-paid-receipt");
        configurePaymentCreation("manual-paid-trade");
        UUID id = (UUID) paymentService.createPayment(orderId, "manual-paid-receipt", new BigDecimal("10.00"), "pc").get("payment_id");
        String merchant = scalar("select merchant_trade_no from payment_attempts where id=?", String.class, id);
        jdbcTemplate.update("update orders set status='PAID',paid_at=current_timestamp where id=?", orderId);
        jdbcTemplate.update("update payment_attempts set status='PAID' where id=?", id);
        configureSuccessfulCallback("manual-paid-trade");
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(merchant, "manual-paid-trade")).body());
        assertEquals(1, number("select count(*) from webhook_events where payment_attempt_id=? and process_result='LATE_PAID_PAID'", id));
        assertEquals("PAID", scalar("select status from orders where id=?", String.class, orderId));
    }

    @Test
    void signedConflictingGatewayTradeIsPreservedForManualReview() {
        UUID orderId = insertPendingOrder("conflicting-trade"); insertPaymentChannel("conflicting-trade");
        configurePaymentCreation("first-trade");
        UUID id = (UUID) paymentService.createPayment(orderId, "conflicting-trade", new BigDecimal("10.00"), "pc").get("payment_id");
        String merchant = scalar("select merchant_trade_no from payment_attempts where id=?", String.class, id);
        configureSuccessfulCallback("different-trade");
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(merchant, "different-trade")).body());
        assertEquals(1, number("select count(*) from webhook_events where payment_attempt_id=? and process_result='LATE_PAID_CONFLICT'", id));
        assertEquals("first-trade", scalar("select provider_trade_no from payment_attempts where id=?", String.class, id));
        assertEquals("PENDING", scalar("select status from orders where id=?", String.class, orderId));
        assertThrows(BusinessException.class, () -> paymentService.repay(orderId, "pc"));
    }

    @Test
    void mainSiteBrandSettingsAreAdminOnlyAndReachPublicConfig() throws Exception {
        String username = "brand-admin-" + UUID.randomUUID();
        UUID admin = insertAdminUser(username, UUID.randomUUID() + "@example.com");
        String token = jwtUtils.generateToken(admin, username, "ADMIN", scalar("select auth_version from users where id=?", Integer.class, admin));
        String csrf = UUID.randomUUID().toString();
        var old = jdbcTemplate.queryForList("select config_key,config_value from site_configs where config_key in ('site_name','favicon_url')");
        UUID iconId = UUID.randomUUID();
        String iconPath = "images/" + iconId + "-display.png", iconUrl = "/api/uploads/" + iconPath;
        var uploadStorage = webApplicationContext.getBean(com.myshop.service.UploadStorage.class);
        java.nio.file.Files.write(uploadStorage.prepareManagedFile(iconPath), new byte[]{(byte) 137,80,78,71});
        jdbcTemplate.update("INSERT INTO media_assets(id,storage_path,display_path,clear_path,original_filename,media_type,mime_type,size_bytes,scope,processing_status) VALUES (?,?,?,?,?,'IMAGE','image/png',4,'SHARED','READY')",
                iconId, iconPath, iconPath, iconPath, "brand.png");
        String body = "{\"configs\":[{\"config_key\":\"site_name\",\"config_value\":\"品牌测试\"},{\"config_key\":\"favicon_url\",\"config_value\":\"" + iconUrl + "\"}]}";
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(springSecurityFilterChain).build();
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/site-config").contextPath("/api")
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("customer").roles("USER"))
                    .contentType("application/json").content(body)).andExpect(status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/site-config").contextPath("/api")
                    .cookie(new Cookie("myshop_access_token", token), new Cookie("myshop_csrf", csrf)).header("X-CSRF-Token", csrf)
                    .contentType("application/json").content(body)).andExpect(status().isOk());
            var config = webApplicationContext.getBean(com.myshop.service.SiteConfigService.class).getPublicConfig();
            assertEquals("品牌测试", config.get("site_name"));
            assertEquals(iconUrl, config.get("favicon_url"));
            assertFalse(config.containsKey("site_slogan"));
            assertFalse(config.containsKey("contact_items"));
        } finally {
            for (String key : List.of("site_name", "favicon_url")) {
                var previous = old.stream().filter(row -> key.equals(row.get("config_key"))).findFirst();
                if (previous.isPresent()) jdbcTemplate.update("update site_configs set config_value=? where config_key=?", previous.get().get("config_value"), key);
                else jdbcTemplate.update("delete from site_configs where config_key=?", key);
            }
            jdbcTemplate.update("DELETE FROM media_references WHERE asset_id=?", iconId);
            jdbcTemplate.update("DELETE FROM media_assets WHERE id=?", iconId);
            java.nio.file.Files.deleteIfExists(uploadStorage.resolve(iconPath));
        }
    }

    @Test
    void faviconUploadRequiresSiteOwnerAndCannotAccessAdminUpload() throws Exception {
        String username = "favicon-owner-" + UUID.randomUUID();
        String strangerName = "favicon-stranger-" + UUID.randomUUID();
        UUID owner = insertUser(username, UUID.randomUUID() + "@example.com");
        UUID stranger = insertUser(strangerName, UUID.randomUUID() + "@example.com");
        UUID site = UUID.randomUUID();
        jdbcTemplate.update("insert into subsites(id,owner_id,prefix,name) values (?,?,?,?)", site, owner,
                "icon" + site.toString().replace("-", ""), "图标测试");
        String ownerToken = jwtUtils.generateToken(owner, username, "USER", scalar("select auth_version from users where id=?", Integer.class, owner));
        String strangerToken = jwtUtils.generateToken(stranger, strangerName, "USER", scalar("select auth_version from users where id=?", Integer.class, stranger));
        String csrf = UUID.randomUUID().toString();
        var image = new java.awt.image.BufferedImage(3, 3, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, owner.hashCode());
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        var file = new org.springframework.mock.web.MockMultipartFile("file", "icon.png", "image/png", bytes.toByteArray());
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(springSecurityFilterChain).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/subsite/mine/favicon").file(file).contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/upload/image").file(file).contextPath("/api")
                .cookie(new Cookie("myshop_access_token", ownerToken), new Cookie("myshop_csrf", csrf)).header("X-CSRF-Token", csrf))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/subsite/mine/favicon").file(file).contextPath("/api")
                .cookie(new Cookie("myshop_access_token", strangerToken), new Cookie("myshop_csrf", csrf)).header("X-CSRF-Token", csrf))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/subsite/mine/favicon").file(file).contextPath("/api")
                .cookie(new Cookie("myshop_access_token", ownerToken), new Cookie("myshop_csrf", csrf)).header("X-CSRF-Token", csrf))
                .andExpect(status().isOk());
        String icon = scalar("select favicon_url from subsites where id=?", String.class, site);
        assertNotNull(icon);
        assertTrue(icon.startsWith("/api/uploads/"));
        var forged = new org.springframework.mock.web.MockMultipartFile("file", "icon.png", "image/png", "not an image".getBytes());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/subsite/mine/favicon").file(forged).contextPath("/api")
                .cookie(new Cookie("myshop_access_token", ownerToken), new Cookie("myshop_csrf", csrf)).header("X-CSRF-Token", csrf))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/subsite/mine").contextPath("/api")
                .cookie(new Cookie("myshop_access_token", ownerToken), new Cookie("myshop_csrf", csrf)).header("X-CSRF-Token", csrf)
                .contentType("application/json").content("{\"name\":\"图标测试\",\"favicon_url\":\"https://other.example/icon.png\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(icon, scalar("select favicon_url from subsites where id=?", String.class, site));
        webApplicationContext.getBean(com.myshop.service.MediaMaintenanceService.class).delete(icon.substring(icon.lastIndexOf('/') + 1));
        assertNull(scalar("select favicon_url from subsites where id=?", String.class, site));
    }

    @Test
    void subsiteGuestCheckoutAndSupportWorkWithoutExternalContactAndProtectSession() {
        UUID owner=insertUser("guest-site-"+UUID.randomUUID(),UUID.randomUUID()+"@example.com");
        UUID siteId=UUID.randomUUID(), product=UUID.randomUUID();
        String prefix="guest"+siteId.toString().replace("-", "");
        jdbcTemplate.update("insert into subsites(id,owner_id,prefix,name) values (?,?,?,?)",siteId,owner,prefix,"Guest");
        UUID level=scalar("select user_level_id from users where id=?",UUID.class,owner);
        jdbcTemplate.update("update subsite_settings set supply_level_id=? where id=1",level);
        insertManualProduct(product); insertManualStock(product,null,2,0);
        jdbcTemplate.update("insert into user_secret_prices(user_id,product_id,price) values (?,?,5.00)",owner,product);
        String channel="guest-site-"+UUID.randomUUID(); insertPaymentChannel(channel);
        configurePaymentCreation("guest-site-trade");
        var site=new com.myshop.entity.Subsite(); site.setId(siteId); site.setOwnerId(owner); site.setPrefix(prefix); site.setEnabled(true);
        com.myshop.context.SiteRequestContext.set(site);
        try {
            Map<String,Object> request=directOrderRequest(product,channel);
            var result=orderService.createDirectOrder(request,null,"127.0.0.1","guest-token");
            UUID orderId=(UUID)((Map<?,?>)result.get("order")).get("id");
            assertEquals(siteId,scalar("select subsite_id from orders where id=?",UUID.class,orderId));
            assertEquals(0,new BigDecimal("5.00").compareTo(scalar("select subsite_profit from orders where id=?",BigDecimal.class,orderId)));
            var access=webApplicationContext.getBean(com.myshop.service.OrderAccessService.class);
            assertNotNull(access.getOrder(orderId,null,"guest-token"));
            assertThrows(BusinessException.class,()->access.getOrder(orderId,null,"other-token"));
            var support=webApplicationContext.getBean(com.myshop.service.SupportService.class);
            var timeline=webApplicationContext.getBean(com.myshop.service.SupportTimelineService.class);
            assertNotNull(support.orderContext(orderId,"guest-token"));
            assertThrows(BusinessException.class,()->support.orderContext(orderId,"other-token"));
            var message=support.send(null,orderId,UUID.randomUUID(),"游客订单咨询",List.of(),false,"guest-token");
            assertNotNull(message.get("conversation_id"));
            assertThrows(BusinessException.class,()->timeline.detail((UUID)message.get("conversation_id"),false,"other-token"));
            com.myshop.context.SiteRequestContext.clear();
            assertThrows(BusinessException.class,()->access.getOrder(orderId,null,"guest-token"));
            assertTrue(scalar("select return_url_snapshot from payment_attempts where order_id=?",String.class,orderId).startsWith("https://"+prefix+".mc520.top/"));
            String merchant=scalar("select merchant_trade_no from payment_attempts where order_id=?",String.class,orderId);
            configureSuccessfulCallback("guest-site-trade");
            assertEquals("SUCCESS",webhookService.handleWebhook("epay",callback(merchant,"guest-site-trade")).body());
            assertEquals("PAID",scalar("select status from orders where id=?",String.class,orderId));
            UUID item=scalar("select id from order_items where order_id=?",UUID.class,orderId);
            fulfillmentService.completeManualItems(orderId,List.of(new FulfillmentService.ManualFulfillmentItem(item,"guest delivery")));
            assertEquals("DELIVERED",scalar("select status from orders where id=?",String.class,orderId));
            assertEquals(0,new BigDecimal("5.00").compareTo(scalar("select balance from users where id=?",BigDecimal.class,owner)));
            assertEquals(1,number("select count(*) from balance_transactions where order_id=? and type='SUBSITE_PROFIT'",orderId));
        } finally {
            com.myshop.context.SiteRequestContext.clear();
            jdbcTemplate.update("update subsite_settings set supply_level_id=null where id=1");
        }
    }

    @Test
    void expiryDuringGatewayCallReleasesStockAndDoesNotReopenPayment() throws Exception {
        UUID orderId = insertPendingOrder("expiry-in-flight"); insertPaymentChannel("expiry-in-flight");
        UUID owner = insertUser("late-site-" + UUID.randomUUID(), UUID.randomUUID() + "@example.com");
        UUID site = UUID.randomUUID();
        jdbcTemplate.update("insert into subsites(id,owner_id,prefix,name) values (?,?,?,?)", site, owner, "late" + site.toString().replace("-", ""), "Late");
        UUID product = UUID.randomUUID(); insertManualProduct(product); insertManualStock(product, null, 0, 1);
        insertOrderItem(orderId, product, "MANUAL", "MANUAL", 1, 1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(epayPaymentProvider.create(any())).thenAnswer(invocation -> {
            entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
            return new PaymentProvider.PaymentResult("expiry-trade", new PaymentNextAction.QrCode("expiry-qr"));
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> result = worker.submit(() -> paymentService.createPayment(orderId, "expiry-in-flight", new BigDecimal("10.00"), "pc"));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            jdbcTemplate.update("update orders set subsite_id=?,subsite_owner_id_snapshot=?,subsite_profit=5.00 where id=?", site, owner, orderId);
            jdbcTemplate.update("update orders set expires_at=current_timestamp-interval '1 minute' where id=?", orderId);
            webApplicationContext.getBean(com.myshop.service.OrderExpiryService.class).expireById(orderId);
            assertEquals("EXPIRED", scalar("select status from orders where id=?", String.class, orderId));
            assertEquals(1, number("select available_quantity from manual_stocks where product_id=?", product));
            assertEquals(0, number("select reserved_quantity from manual_stocks where product_id=?", product));
            release.countDown();
            Map<String, Object> payment = result.get(10, TimeUnit.SECONDS);
            assertNull(payment.get("next_action"));
            assertEquals(PaymentStatus.CLOSED, payment.get("status"));
            assertEquals("READY", scalar("select creation_status from payment_attempts where order_id=?", String.class, orderId));
            assertNotNull(scalar("select next_action from payment_attempts where order_id=?", String.class, orderId));
            configureSuccessfulCallback("expiry-trade");
            String merchant = scalar("select merchant_trade_no from payment_attempts where order_id=?", String.class, orderId);
            assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(merchant, "expiry-trade")).body());
            assertEquals(1, number("select count(*) from webhook_events where order_id=? and process_result like 'LATE_PAID_%'", orderId));
            assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(merchant, "expiry-trade")).body());
            assertEquals(1, number("select count(*) from webhook_events where order_id=? and process_result like 'LATE_PAID_%'", orderId));
            assertEquals("EXPIRED", scalar("select status from orders where id=?", String.class, orderId));
            assertNull(scalar("select subsite_profit_state from orders where id=?", String.class, orderId));
            assertEquals(0, number("select count(*) from balance_transactions where order_id=? and type='SUBSITE_PROFIT'", orderId));
            assertEquals(0, new BigDecimal("0.00").compareTo(scalar("select balance from users where id=?", BigDecimal.class, owner)));
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test
    void killedProcessAfterGatewayReceivedRequestNeverResubmits() throws Exception {
        exerciseActualProcessCrash(false);
    }

    @Test
    void killedProcessAfterGatewayResponseBeforeResultCommitNeverResubmits() throws Exception {
        exerciseActualProcessCrash(true);
    }

    private void exerciseActualProcessCrash(boolean afterResponse) throws Exception {
        UUID orderId = insertPendingOrder("crash-worker-" + UUID.randomUUID());
        String channel = scalar("select payment_channel_code from orders where id=?", String.class, orderId);
        insertPaymentChannel(channel);
        CountDownLatch received = new CountDownLatch(1), respond = new CountDownLatch(1), sent = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService gateway = Executors.newSingleThreadExecutor();
        server.setExecutor(gateway);
        server.createContext("/mapi.php", exchange -> {
            requests.incrementAndGet(); received.countDown();
            try {
                if (respond.await(15, TimeUnit.SECONDS)) {
                    byte[] body = "{\"code\":1,\"trade_no\":\"crash-test-trade\",\"qrcode\":\"crash-test-qr\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
                    sent.countDown();
                }
            } catch (Exception ignored) { sent.countDown(); }
        });
        server.start();
        String config = "{\"pid\":\"test-pid\",\"key\":\"test-key\",\"api_url\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/mapi.php\"}";
        jdbcTemplate.update("update payment_channels set config_data=? where channel_code=?", config, channel);
        java.nio.file.Path childDir = java.nio.file.Files.createTempDirectory("myshop-payment-crash-");
        java.nio.file.Path childLog = childDir.resolve("worker.log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", classpath,
                PaymentCrashWorker.class.getName(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                orderId.toString(), channel, childDir.toString()).redirectErrorStream(true).redirectOutput(childLog.toFile()).start();
        java.sql.Connection lock = null;
        try {
            assertTrue(received.await(40, TimeUnit.SECONDS), "child did not reach the mock gateway; worker log: " + childLog);
            assertEquals(1, requests.get());
            UUID attemptId = scalar("select id from payment_attempts where order_id=?", UUID.class, orderId);
            assertEquals("SUBMITTING", scalar("select creation_status from payment_attempts where id=?", String.class, attemptId));
            if (afterResponse) {
                lock = webApplicationContext.getBean(javax.sql.DataSource.class).getConnection();
                lock.setAutoCommit(false);
                try (var statement = lock.prepareStatement("select id from orders where id=? for update nowait")) {
                    statement.setObject(1, orderId); statement.executeQuery().close();
                }
                respond.countDown();
                assertTrue(sent.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (System.nanoTime() < deadline && number("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query ilike '%orders%'") == 0) Thread.sleep(25);
                assertTrue(number("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query ilike '%orders%'") > 0, "child did not wait at the result transaction");
            }
            child.destroyForcibly(); assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            if (lock != null) { lock.rollback(); lock.close(); lock = null; }
            jdbcTemplate.update("update payment_attempts set creation_started_at=current_timestamp-interval '3 minutes' where id=?", attemptId);
            Map<String, Object> recovered = paymentService.dispatchPayment(attemptId);
            assertEquals(com.myshop.payment.PaymentCreationStatus.UNKNOWN, recovered.get("creation_status"));
            assertNull(recovered.get("next_action"));
            verify(epayPaymentProvider, never()).create(any());
            assertEquals(1, requests.get());
        } finally {
            respond.countDown();
            if (lock != null) { lock.rollback(); lock.close(); }
            child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS);
            server.stop(0); gateway.shutdownNow();
            try (var paths = java.nio.file.Files.walk(childDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    @Test
    void committedIntentCanResumeButClaimedCrashNeverResubmits() {
        UUID orderId = insertPendingOrder("intent-resume");
        insertPaymentChannel("intent-resume");
        Map<String, Object> intent = new TransactionTemplate(transactionManager).execute(status ->
                paymentService.preparePayment(orderId, "intent-resume", new BigDecimal("10.00"), "pc"));
        UUID paymentId = (UUID) intent.get("payment_id");
        assertEquals("INTENT", scalar("select creation_status from payment_attempts where id = ?", String.class, paymentId));
        verify(epayPaymentProvider, never()).create(any());
        jdbcTemplate.update("update payment_attempts set creation_status='SUBMITTING', creation_started_at=current_timestamp-interval '3 minutes' where id=?", paymentId);
        Map<String, Object> resumed = paymentService.dispatchPayment(paymentId);
        assertEquals(com.myshop.payment.PaymentCreationStatus.UNKNOWN, resumed.get("creation_status"));
        assertNull(resumed.get("next_action"));
        paymentService.dispatchPayment(paymentId);
        verify(epayPaymentProvider, never()).create(any());
        assertEquals(1, number("select count(*) from payment_attempts where order_id = ?", orderId));
    }

    @Test
    void committedUnclaimedIntentIsSentOnceWithItsOriginalSnapshot() {
        UUID orderId = insertPendingOrder("intent-snapshot");
        insertPaymentChannel("intent-snapshot");
        Map<String, Object> intent = new TransactionTemplate(transactionManager).execute(status ->
                paymentService.preparePayment(orderId, "intent-snapshot", new BigDecimal("10.00"), "mobile"));
        UUID id = (UUID) intent.get("payment_id");
        String original = scalar("select merchant_trade_no from payment_attempts where id = ?", String.class, id);
        String notifyUrl = scalar("select notify_url_snapshot from payment_attempts where id = ?", String.class, id);
        String returnUrl = scalar("select return_url_snapshot from payment_attempts where id = ?", String.class, id);
        jdbcTemplate.update("update payment_channels set config_data='{}', is_enabled=false where channel_code='intent-snapshot'");
        when(epayPaymentProvider.create(any())).thenAnswer(invocation -> {
            PaymentProvider.PaymentRequest request = invocation.getArgument(0);
            assertEquals(original, request.merchantTradeNo());
            assertEquals("mobile", request.device());
            assertEquals("pid", request.config().get("pid"));
            assertEquals(notifyUrl, request.notifyUrl());
            assertEquals(returnUrl, request.returnUrl());
            assertTrue(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return new PaymentProvider.PaymentResult("snapshot-trade", new PaymentNextAction.QrCode("snapshot-qr"));
        });
        assertEquals(com.myshop.payment.PaymentCreationStatus.READY, paymentService.dispatchPayment(id).get("creation_status"));
        paymentService.dispatchPayment(id);
        verify(epayPaymentProvider).create(any());
    }

    @Test
    void lostCreateResponseStaysUnknownAndCanBeConfirmedByQuery() {
        UUID orderId = insertPendingOrder("lost-create");
        insertPaymentChannel("lost-create");
        when(epayPaymentProvider.create(any())).thenThrow(new IllegalStateException("simulated timeout"));
        Map<String, Object> result = paymentService.createPayment(orderId, "lost-create", new BigDecimal("10.00"), "pc");
        UUID id = (UUID) result.get("payment_id");
        assertEquals(com.myshop.payment.PaymentCreationStatus.UNKNOWN, result.get("creation_status"));
        assertNull(result.get("next_action"));
        assertEquals(id, paymentService.repay(orderId, "pc").get("payment_id"));
        verify(epayPaymentProvider).create(any());
        when(epayPaymentProvider.supportsServerSideQuery()).thenReturn(true);
        when(epayPaymentProvider.query(any())).thenReturn(null,
                new PaymentProvider.PaymentQueryResult(PaymentStatus.PAID, new BigDecimal("10.00"), "lost-trade"));
        paymentService.refreshPayment(orderId);
        assertEquals("UNKNOWN", scalar("select creation_status from payment_attempts where id=?", String.class, id));
        assertEquals(id, paymentService.repay(orderId, "pc").get("payment_id"));
        paymentService.refreshPayment(orderId);
        assertEquals("PAID", scalar("select status from payment_attempts where id=?", String.class, id));
        assertEquals(1, number("select count(*) from webhook_events where payment_attempt_id=?", id));
        String merchant = scalar("select merchant_trade_no from payment_attempts where id=?", String.class, id);
        configureSuccessfulCallback("lost-trade");
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(merchant, "lost-trade")).body());
        assertEquals(1, number("select count(*) from webhook_events where payment_attempt_id=?", id));
        verify(epayPaymentProvider).create(any());
    }

    @Test
    void failedIntentCommitDoesNotContactGatewayAndRollsBackOrder() {
        UUID product = UUID.randomUUID(); insertUnlimitedProduct(product); insertPaymentChannel("commit-fail");
        Map<String, Object> request = directOrderRequest(product, "commit-fail");
        String key = "commit-fail-" + UUID.randomUUID();
        request.put("idempotency_key", key); request.put("device", "intent-commit-fail");
        installPaymentCommitFailure("INSERT", "intent-commit-fail");
        try {
            assertThrows(RuntimeException.class, () -> orderService.createDirectOrder(request, null, "127.0.0.1", "commit-fail-session"));
            assertEquals(0, number("select count(*) from orders where idempotency_key=?", key));
            verify(epayPaymentProvider, never()).create(any());
        } finally { removePaymentCommitFailure(); }
    }

    @Test
    void resultCommitFailureLeavesUnknownAndNeverSendsTwice() {
        UUID orderId = insertPendingOrder("result-commit-fail"); insertPaymentChannel("result-commit-fail");
        when(epayPaymentProvider.create(any())).thenReturn(new PaymentProvider.PaymentResult("result-trade", new PaymentNextAction.QrCode("result-qr")));
        installPaymentCommitFailure("UPDATE", "result-commit-fail");
        try {
            Map<String, Object> result = paymentService.createPayment(orderId, "result-commit-fail", new BigDecimal("10.00"), "result-commit-fail");
            assertEquals(com.myshop.payment.PaymentCreationStatus.UNKNOWN, result.get("creation_status"));
            assertNull(result.get("next_action"));
            UUID id = (UUID) result.get("payment_id");
            assertEquals("UNKNOWN", scalar("select creation_status from payment_attempts where id=?", String.class, id));
            assertEquals(id, paymentService.repay(orderId, "pc").get("payment_id"));
            verify(epayPaymentProvider).create(any());
        } finally { removePaymentCommitFailure(); }
    }

    @Test
    void callbackBeforeCreateResponseDoesNotReopenPaymentOrDeliverTwice() {
        UUID orderId = insertPendingOrder("early-callback"); insertPaymentChannel("early-callback");
        UUID product = UUID.randomUUID(); insertProduct(product); insertOrderItem(orderId, product); insertCard(product, "early-callback-card");
        configureSuccessfulCallback("early-trade");
        when(epayPaymentProvider.create(any())).thenAnswer(invocation -> {
            PaymentProvider.PaymentRequest request = invocation.getArgument(0);
            assertTrue(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(request.merchantTradeNo(), "early-trade")).body());
            return new PaymentProvider.PaymentResult("early-trade", new PaymentNextAction.QrCode("too-late-to-show"));
        });
        Map<String, Object> result = paymentService.createPayment(orderId, "early-callback", new BigDecimal("10.00"), "pc");
        assertNull(result.get("next_action"));
        assertEquals(PaymentStatus.PAID, result.get("status"));
        assertEquals("DELIVERED", scalar("select status from orders where id=?", String.class, orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id=? and status='SOLD'", orderId));
        String merchant = scalar("select merchant_trade_no from payment_attempts where order_id=?", String.class, orderId);
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(merchant, "early-trade")).body());
        assertEquals(1, number("select count(*) from webhook_events where order_id=?", orderId));
    }

    @Test
    void oldAndNewPaidLinksBothLeaveEvidenceWithoutDoubleDelivery() {
        UUID orderId = insertPendingOrder("old-link"); insertPaymentChannel("old-link");
        UUID product = UUID.randomUUID(); insertProduct(product); insertOrderItem(orderId, product); insertCard(product, "old-link-card");
        when(epayPaymentProvider.create(any())).thenReturn(
                new PaymentProvider.PaymentResult("trade-old", new PaymentNextAction.QrCode("old")),
                new PaymentProvider.PaymentResult("trade-new", new PaymentNextAction.QrCode("new")));
        UUID oldId = (UUID) paymentService.createPayment(orderId, "old-link", new BigDecimal("10.00"), "pc").get("payment_id");
        String oldMerchant = scalar("select merchant_trade_no from payment_attempts where id=?", String.class, oldId);
        jdbcTemplate.update("update payment_attempts set created_at=current_timestamp-interval '1 minute' where id=?", oldId);
        UUID newId = (UUID) paymentService.repay(orderId, "pc").get("payment_id");
        String newMerchant = scalar("select merchant_trade_no from payment_attempts where id=?", String.class, newId);
        when(epayPaymentProvider.extractMerchantTradeNo(any())).thenAnswer(inv -> inv.getArgument(0, PaymentProvider.WebhookRequest.class).parameter("out_trade_no"));
        when(epayPaymentProvider.verifyAndParseCallback(any(), anyMap())).thenAnswer(inv -> {
            PaymentProvider.WebhookRequest req = inv.getArgument(0);
            return new PaymentProvider.CallbackResult("epay:" + req.parameter("trade_no"), req.parameter("out_trade_no"), req.parameter("trade_no"), PaymentStatus.PAID, new BigDecimal("10.00"));
        });
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(newMerchant, "trade-new")).body());
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(oldMerchant, "trade-old")).body());
        assertEquals("SUCCESS", webhookService.handleWebhook("epay", callback(oldMerchant, "trade-old")).body());
        assertEquals(2, number("select count(*) from webhook_events where order_id=?", orderId));
        assertEquals(1, number("select count(*) from webhook_events where order_id=? and process_result like 'LATE_PAID_%'", orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id=? and status='SOLD'", orderId));
        assertEquals("DELIVERED", scalar("select status from orders where id=?", String.class, orderId));
    }

    private void installPaymentCommitFailure(String event, String device) {
        jdbcTemplate.execute("create or replace function myshop_test_payment_commit_failure() returns trigger language plpgsql as $$ begin raise exception 'simulated deferred commit failure'; end $$");
        String condition = event.equals("UPDATE") ? " and NEW.creation_status = 'READY'" : "";
        jdbcTemplate.execute("create constraint trigger myshop_test_payment_commit_failure after " + event + " on payment_attempts deferrable initially deferred for each row when (NEW.creation_device = '" + device + "'" + condition + ") execute function myshop_test_payment_commit_failure()");
    }

    private void removePaymentCommitFailure() {
        jdbcTemplate.execute("drop trigger if exists myshop_test_payment_commit_failure on payment_attempts");
        jdbcTemplate.execute("drop function if exists myshop_test_payment_commit_failure()");
    }

    @Test
    void verifiedCallbackAutomaticallyDeliversWithoutBrowserRequest() {
        UUID orderId = insertPendingOrder("auto-channel");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertOrderItem(orderId, productId);
        insertCard(productId, "auto-card");
        insertPaymentChannel("auto-channel");
        configurePaymentCreation("auto-trade");
        String merchantTradeNo = createAttempt(orderId, "auto-channel", "auto-trade");
        configureSuccessfulCallback("auto-trade");

        assertEquals("SUCCESS", webhookService.handleWebhook(
                "epay", callback(merchantTradeNo, "auto-trade")).body());
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));

        assertEquals("SUCCESS", webhookService.handleWebhook(
                "epay", callback(merchantTradeNo, "auto-trade")).body());
        assertEquals(1, number("select count(*) from card_keys where order_id = ?", orderId));
    }

    @Test
    void shortageKeepsPaidFactAndRetryDeliversAfterStockIsRestored() {
        UUID orderId = insertPendingOrder("retry-channel");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertOrderItem(orderId, productId);
        insertPaymentChannel("retry-channel");
        configurePaymentCreation("retry-trade");
        String merchantTradeNo = createAttempt(orderId, "retry-channel", "retry-trade");
        configureSuccessfulCallback("retry-trade");

        assertEquals("SUCCESS", webhookService.handleWebhook(
                "epay", callback(merchantTradeNo, "retry-trade")).body());
        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(0, number("select count(*) from card_keys where order_id = ?", orderId));

        insertCard(productId, "retry-card");
        autoFulfillmentService.retryPendingPaidOrders();
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
    }

    @Test
    void guestIdempotencyReplayReturnsSameOrderAndSessionOwnership() {
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        insertCard(productId, "idempotent-card");
        insertPaymentChannel("idempotent-channel");
        when(epayPaymentProvider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenReturn(new PaymentProvider.PaymentResult("idempotent-trade",
                        new PaymentNextAction.Redirect("https://pay.example/idempotent")));

        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("product_id", productId.toString());
        request.put("quantity", 1);
        request.put("payment_method", "ONLINE");
        request.put("payment_channel_code", "idempotent-channel");
        request.put("expected_unit_price", "10.00");
        request.put("idempotency_key", "guest-idempotency-key");
        request.put("idempotency_fingerprint", "fingerprint-1");

        Map<String, Object> first = orderService.createDirectOrder(request, null, "203.0.113.10", "guest-session-a");
        Map<String, Object> second = orderService.createDirectOrder(request, null, "203.0.113.10", "guest-session-a");
        Map<?, ?> firstOrder = (Map<?, ?>) first.get("order");
        Map<?, ?> secondOrder = (Map<?, ?>) second.get("order");

        assertEquals(firstOrder.get("id"), secondOrder.get("id"));
        assertEquals("guest-session-a", scalar("select session_token from orders where id = ?", String.class,
                firstOrder.get("id")));
        assertEquals(1, number("select count(*) from orders where idempotency_key = ?", "guest-idempotency-key"));

        Map<String, Object> differentSession = new java.util.LinkedHashMap<>(request);
        assertThrows(BusinessException.class,
                () -> orderService.createDirectOrder(differentSession, null, "203.0.113.11", "guest-session-b"));
        assertEquals(1, number("select count(*) from orders where idempotency_key = ?", "guest-idempotency-key"));
    }

    @Test
    void concurrentManualReservationsPreventOversellAndRecoveryIsExact() throws Exception {
        UUID productId = UUID.randomUUID();
        insertManualStock(productId, null, 1, 0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> reserveAfter(start, ready, productId));
            Future<Boolean> second = executor.submit(() -> reserveAfter(start, ready, productId));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            assertNotEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, number("select available_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
        assertEquals(1, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));

        manualStockService.release(productId, null, 1);
        assertEquals(1, number("select available_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
    }

    @Test
    void manualOnlyOrderWaitsForAdminAndIsNotSelectedByAutomaticRetry() {
        UUID orderId = insertOrder("manual-session");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID itemId = insertOrderItem(orderId, productId, "MANUAL", "MANUAL", 1, 1);
        insertManualStock(productId, null, 0, 1);

        Map<?, ?> delivery = fulfillmentService.fulfillOrder(orderId);
        assertEquals("PAID", delivery.get("status"));
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, itemId));
        assertEquals(0, number("select count(*) from card_keys where order_id = ?", orderId));

        autoFulfillmentService.retryPendingPaidOrders();
        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(0, number("select count(*) from card_keys where order_id = ?", orderId));

        Map<?, ?> completed = fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(itemId, "账号：buyer\n\n已为你开通。")));
        assertEquals("DELIVERED", completed.get("status"));
        assertEquals("DELIVERED", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, itemId));
        assertEquals("账号：buyer\n\n已为你开通。", scalar(
                "select customer_delivery_content from order_items where id = ?", String.class, itemId));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));

        // A replay is rejected before any mutation and cannot consume the
        // same reservation or repeat the delivery transition.
        assertThrows(BusinessException.class, () -> fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(itemId, "replacement"))));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
    }

    @Test
    void manualFulfillmentBatchRequiresEveryWaitingItemAndMutatesAtomically() {
        UUID orderId = insertOrder("manual-batch-session");
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        insertProduct(firstProductId);
        insertProduct(secondProductId);
        UUID firstItemId = insertOrderItem(orderId, firstProductId, "MANUAL", "MANUAL", 1, 1);
        UUID secondItemId = insertOrderItem(orderId, secondProductId, "MANUAL", "MANUAL", 1, 1);
        insertManualStock(firstProductId, null, 0, 1);
        insertManualStock(secondProductId, null, 0, 1);

        fulfillmentService.fulfillOrder(orderId);
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, firstItemId));
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, secondItemId));

        assertThrows(BusinessException.class, () -> fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(firstItemId, "only one"))));
        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, firstItemId));
        assertEquals("WAITING_MANUAL", scalar(
                "select fulfillment_status from order_items where id = ?", String.class, secondItemId));
        assertNull(scalar("select customer_delivery_content from order_items where id = ?", String.class, firstItemId));
        assertNull(scalar("select customer_delivery_content from order_items where id = ?", String.class, secondItemId));
        assertEquals(1, number(
                "select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", firstProductId));
        assertEquals(1, number(
                "select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", secondProductId));

        Map<?, ?> completed = fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(firstItemId, "first result"),
                new FulfillmentService.ManualFulfillmentItem(secondItemId, "second result")));
        assertEquals("DELIVERED", completed.get("status"));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("first result", scalar(
                "select customer_delivery_content from order_items where id = ?", String.class, firstItemId));
        assertEquals("second result", scalar(
                "select customer_delivery_content from order_items where id = ?", String.class, secondItemId));
        assertEquals(0, number(
                "select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", firstProductId));
        assertEquals(0, number(
                "select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", secondProductId));
    }

    @Test
    void mixedOrderDeliversAutomaticItemWhileManualItemWaits() {
        UUID orderId = insertOrder("mixed-session");
        UUID autoProductId = UUID.randomUUID();
        UUID manualProductId = UUID.randomUUID();
        insertProduct(autoProductId);
        insertProduct(manualProductId);
        UUID autoItemId = insertOrderItem(orderId, autoProductId, "AUTO", "CARD_KEY", 1, 0);
        UUID manualItemId = insertOrderItem(orderId, manualProductId, "MANUAL", "MANUAL", 1, 1);
        insertCard(autoProductId, "mixed-auto-card");
        insertManualStock(manualProductId, null, 0, 1);

        Map<?, ?> first = fulfillmentService.fulfillOrder(orderId);
        assertEquals("PAID", first.get("status"));
        assertEquals("DELIVERED", scalar("select fulfillment_status from order_items where id = ?", String.class, autoItemId));
        assertEquals("WAITING_MANUAL", scalar("select fulfillment_status from order_items where id = ?", String.class, manualItemId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
        verify(emailService, times(1)).sendDeliveryEmail(eq(orderId), eq(List.of(autoItemId)));

        // A retry while the manual lane is still waiting must not resend the
        // already-completed automatic item or its card body.
        fulfillmentService.fulfillOrder(orderId);
        verify(emailService, times(1)).sendDeliveryEmail(eq(orderId), eq(List.of(autoItemId)));

        Map<?, ?> second = fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(manualItemId, null)));
        assertEquals("DELIVERED", second.get("status"));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        verify(emailService, times(1)).sendDeliveryEmail(eq(orderId), eq(List.of(manualItemId)));
    }

    @Test
    void expiredManualOrderReleasesReservationOnce() {
        UUID orderId = insertExpiredOrder("expired-manual-session");
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID itemId = insertOrderItem(orderId, productId, "MANUAL", "MANUAL", 2, 2);
        insertManualStock(productId, null, 0, 2);

        orderService.expireOrders();
        assertEquals("EXPIRED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("CANCELLED", scalar("select fulfillment_status from order_items where id = ?", String.class, itemId));
        assertEquals(0, number("select manual_stock_reserved from order_items where id = ?", itemId));
        assertEquals(2, number("select available_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));

        orderService.expireOrders();
        assertEquals(2, number("select available_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
    }

    @Test
    void expiredManualPaymentConfirmationRestoresAndAutomaticallyFulfillsCardOnlyOnce() {
        UUID orderId = insertExpiredOrder("manual-confirm-auto-" + UUID.randomUUID());
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID itemId = insertOrderItem(orderId, productId);
        UUID cardId = insertCard(productId, "manual-confirm-auto-card");

        webApplicationContext.getBean(com.myshop.service.OrderExpiryService.class).expireById(orderId);
        assertEquals("EXPIRED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("CANCELLED", scalar("select fulfillment_status from order_items where id = ?", String.class, itemId));

        markPaidAsAdmin(orderId);

        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("DELIVERED", scalar("select fulfillment_status from order_items where id = ?", String.class, itemId));
        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));

        markPaidAsAdmin(orderId);
        autoFulfillmentService.trigger(orderId);

        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
        assertEquals(2, number("select count(*) from operation_logs where action = 'order.mark_paid' and target_type = 'ORDER' and target_id = ? and detail like '%人工确认支付%'", orderId.toString()));
        verify(emailService, times(1)).sendDeliveryEmail(eq(orderId), eq(List.of(itemId)));
    }

    @Test
    void expiredManualConfirmationStaysPaidWhenInventoryIsShortAndFulfillsAfterRestock() {
        UUID orderId = insertExpiredOrder("manual-confirm-stock-" + UUID.randomUUID());
        UUID productId = UUID.randomUUID();
        insertManualProduct(productId);
        UUID itemId = insertOrderItem(orderId, productId, "MANUAL", "MANUAL", 2, 2);
        insertManualStock(productId, null, 0, 2);

        webApplicationContext.getBean(com.myshop.service.OrderExpiryService.class).expireById(orderId);
        manualStockService.setAvailable(productId, null, 1);

        markPaidAsAdmin(orderId);

        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("WAITING_MANUAL", scalar("select fulfillment_status from order_items where id = ?", String.class, itemId));
        assertEquals(0, number("select manual_stock_reserved from order_items where id = ?", itemId));
        assertEquals(1, number("select available_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));

        assertThrows(BusinessException.class, () -> fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(itemId, "delivery"))));
        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, orderId));

        manualStockService.setAvailable(productId, null, 2);
        Map<?, ?> result = fulfillmentService.completeManualItems(orderId, List.of(
                new FulfillmentService.ManualFulfillmentItem(itemId, "delivery")));

        assertEquals("DELIVERED", result.get("status"));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals(0, number("select manual_stock_reserved from order_items where id = ?", itemId));
        assertEquals(0, number("select available_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
        assertEquals(0, number("select reserved_quantity from manual_stocks where product_id = ? and spec_id is null", productId));
    }

    @Test
    void lateCallbackAfterManualConfirmationRecordsGatewayTradeWithoutSecondDelivery() {
        String channel = "manual-late-" + UUID.randomUUID();
        UUID orderId = insertPendingOrder(channel);
        insertPaymentChannel(channel);
        UUID productId = UUID.randomUUID();
        insertProduct(productId);
        UUID itemId = insertOrderItem(orderId, productId);
        UUID cardId = insertCard(productId, "manual-late-callback-card");
        configurePaymentCreation("late-real-trade");
        Map<String, Object> payment = paymentService.createPayment(orderId, channel, new BigDecimal("10.00"), "pc");
        UUID attemptId = (UUID) payment.get("payment_id");
        String merchantTradeNo = scalar("select merchant_trade_no from payment_attempts where id = ?", String.class, attemptId);

        jdbcTemplate.update("update orders set expires_at = current_timestamp - interval '1 minute' where id = ?", orderId);
        webApplicationContext.getBean(com.myshop.service.OrderExpiryService.class).expireById(orderId);
        markPaidAsAdmin(orderId);
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));

        configureSuccessfulCallback("late-real-trade");
        assertEquals("SUCCESS", webhookService.handleWebhook(
                "epay", callback(merchantTradeNo, "late-real-trade")).body());
        assertEquals("SUCCESS", webhookService.handleWebhook(
                "epay", callback(merchantTradeNo, "late-real-trade")).body());

        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, orderId));
        assertEquals("CLOSED", scalar("select status from payment_attempts where id = ?", String.class, attemptId));
        assertEquals("late-real-trade", scalar("select provider_trade_no from payment_attempts where id = ?", String.class, attemptId));
        assertEquals("SOLD", scalar("select status from card_keys where id = ?", String.class, cardId));
        assertEquals(1, number("select count(*) from webhook_events where payment_attempt_id = ? and process_result = 'LATE_PAID_CLOSED'", attemptId));
        assertEquals(1, number("select count(*) from card_keys where order_id = ? and status = 'SOLD'", orderId));
        verify(emailService, times(1)).sendDeliveryEmail(eq(orderId), eq(List.of(itemId)));
    }

    private Map<String, Object> fulfillResultAfter(CountDownLatch start, CountDownLatch ready,
                                                    UUID orderId) throws Exception {
        ready.countDown();
        start.await();
        return fulfillmentService.fulfillOrder(orderId);
    }

    private boolean pickupAfter(CountDownLatch start, CountDownLatch ready,
                                UUID productId, UUID operatorId) throws Exception {
        ready.countDown();
        start.await();
        try {
            adminCardKeyService.batchPickup(
                    new AdminCardKeyBatchPickupRequest(productId, null, 1), operatorId);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private boolean importWidthAfter(CountDownLatch ready, CountDownLatch start,
                                     UUID productId, List<String> columns) throws Exception {
        return importWidthAfter(ready, start, productId, null, columns);
    }

    private boolean importWidthAfter(CountDownLatch ready, CountDownLatch start,
                                     UUID productId, UUID specId, List<String> columns) throws Exception {
        ready.countDown();
        if (!start.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("card import concurrency test did not start");
        }
        try {
            Map<String, Object> request = new java.util.HashMap<>();
            request.put("product_id", productId.toString());
            request.put("content_columns", columns);
            if (specId != null) request.put("spec_id", specId.toString());
            adminCardKeyService.importCardKeys(request, UUID.randomUUID());
            return true;
        } catch (BusinessException exception) {
            assertEquals(ErrorCode.CARD_KEY_FORMAT_ERROR, exception.getCode());
            return false;
        }
    }

    private boolean fulfillAfter(CountDownLatch start, CountDownLatch ready,
                                UUID orderId) throws Exception {
        ready.countDown();
        start.await();
        try {
            fulfillmentService.fulfillOrder(orderId);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private boolean reserveAfter(CountDownLatch start, CountDownLatch ready, UUID productId) throws Exception {
        ready.countDown();
        start.await();
        try {
            manualStockService.reserveOrThrow(productId, null, 1);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    private Map<String, Object> createPaymentAfter(CountDownLatch start, CountDownLatch ready,
                                                   UUID orderId) throws Exception {
        ready.countDown();
        start.await();
        return paymentService.createPayment(orderId, "payment-concurrency-channel",
                new BigDecimal("10.00"), "pc");
    }

    private void assertSearchMatches(String keyword, UUID expectedOrderId) {
        var result = adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, keyword, null, null, null, "desc"));
        assertEquals(1L, result.getPagination().getTotal(), "keyword=" + keyword);
        assertEquals(1, result.getList().size(), "keyword=" + keyword);
        Map<?, ?> row = (Map<?, ?>) result.getList().get(0);
        assertEquals(expectedOrderId, row.get("id"), "keyword=" + keyword);
    }

    private void assertSearchContains(String keyword, UUID expectedOrderId) {
        var result = adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, keyword, null, null, null, "desc"));
        assertTrue(result.getPagination().getTotal() >= 1, "keyword=" + keyword);
        assertTrue(result.getList().stream().anyMatch(value -> {
            Map<?, ?> row = (Map<?, ?>) value;
            return expectedOrderId.equals(row.get("id"));
        }), "keyword=" + keyword);
    }

    private void assertSearchDoesNotMatch(String keyword) {
        var result = adminOrderService.searchOrders(new AdminOrderSearchRequest(
                1, 20, keyword, null, null, null, "desc"));
        assertEquals(0L, result.getPagination().getTotal(), "keyword=" + keyword);
        assertTrue(result.getList().isEmpty(), "keyword=" + keyword);
    }

    private UUID insertSearchOrder(UUID userId, String tradeNo,
                                   LocalDateTime createdAt, String status, BigDecimal amount) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO orders(id, created_at, updated_at, user_id, total_amount, actual_amount,
                                   status, order_type, payment_method, payment_channel_code, paid_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'NORMAL', 'ONLINE', ?, ?)
                """, orderId, createdAt, createdAt, userId, amount, amount, status,
                "search-channel-" + tradeNo, createdAt);
        jdbcTemplate.update("""
                INSERT INTO payment_attempts(id, created_at, updated_at, order_id, channel_id,
                    channel_code_snapshot, provider_type, payment_type, provider_config_snapshot,
                    merchant_trade_no, provider_trade_no, amount, status, next_action)
                VALUES (?, ?, ?, ?, ?, ?, 'epay', 'alipay',
                    '{\"pid\":\"pid\",\"key\":\"secret\",\"api_url\":\"https://pay.example\"}',
                    ?, ?, ?, 'PAID', '{\"type\":\"redirect\",\"url\":\"https://pay.example/attempt\"}')
                """, UUID.randomUUID(), createdAt, createdAt, orderId, UUID.randomUUID(),
                "search-channel-" + tradeNo, "merchant-" + tradeNo, tradeNo, amount);
        return orderId;
    }

    private UUID insertOrder(String sessionToken) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, actual_amount, total_amount, status,
                    order_type, payment_method, payment_channel_code, session_token)
                values (?, current_timestamp, current_timestamp, 10.00, 10.00, 'PAID',
                    'NORMAL', 'ONLINE', 'integration-channel', ?)
                """, orderId, sessionToken);
        return orderId;
    }

    private UUID insertAdminUser(String username, String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into users (id, created_at, updated_at, username, email, password_hash,
                    role, is_deleted, user_no)
                values (?, current_timestamp, current_timestamp, ?, ?, 'integration-hash', 'ADMIN', 0, null)
                """, userId, username, email);
        return userId;
    }

    private UUID insertUser(String username, String email) {
        UUID userId = UUID.randomUUID();
        Long userNo = jdbcTemplate.queryForObject(
                "select coalesce(max(user_no), 9999) + 1 from users", Long.class);
        UUID userLevelId = jdbcTemplate.queryForObject(
                "select id from user_levels where is_default = true", UUID.class);
        jdbcTemplate.update("""
                insert into users (id, created_at, updated_at, username, email, password_hash,
                    role, is_deleted, user_no, user_level_id)
                values (?, current_timestamp, current_timestamp, ?, ?, 'integration-hash', 'USER', 0, ?, ?)
                """, userId, username, email, userNo, userLevelId);
        return userId;
    }

    private void markPaidAsAdmin(UUID orderId) {
        UUID adminId = insertAdminUser("manual-payment-admin-" + UUID.randomUUID(),
                "manual-payment-admin-" + UUID.randomUUID() + "@example.com");
        com.myshop.context.RequestContext.set(
                new com.myshop.context.RequestContext.UserInfo(adminId, "manual-payment-admin", "ADMIN"));
        try {
            adminOrderService.markPaid(orderId, true);
        } finally {
            com.myshop.context.RequestContext.clear();
        }
    }

    private UUID insertPendingOrder(String paymentChannelCode) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, actual_amount, total_amount, status,
                    order_type, payment_method, payment_channel_code, expires_at)
                values (?, current_timestamp, current_timestamp, 10.00, 10.00, 'PENDING',
                    'NORMAL', 'ONLINE', ?,
                    current_timestamp + interval '15 minutes')
                """, orderId, paymentChannelCode);
        return orderId;
    }

    private UUID insertPendingOrder(String paymentChannelCode, UUID userId, String clientIp) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, user_id, actual_amount, total_amount,
                    status, order_type, payment_method, payment_channel_code, expires_at, client_ip)
                values (?, current_timestamp, current_timestamp, ?, 10.00, 10.00, 'PENDING',
                    'NORMAL', 'ONLINE', ?,
                    current_timestamp + interval '15 minutes', ?)
                """, orderId, userId, paymentChannelCode, clientIp);
        return orderId;
    }

    private UUID insertExpiredOrder(String sessionToken) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, actual_amount, total_amount, status,
                    order_type, payment_method, payment_channel_code, session_token, expires_at)
                values (?, current_timestamp, current_timestamp, 10.00, 10.00, 'PENDING',
                    'NORMAL', 'ONLINE', 'integration-channel', ?,
                    current_timestamp - interval '1 minute')
                """, orderId, sessionToken);
        return orderId;
    }

    private void insertProduct(UUID productId) {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into product_categories (id, created_at, updated_at, name, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, 0, 0)
                """, categoryId, "Integration category " + productId);
        jdbcTemplate.update("""
                insert into products (id, created_at, updated_at, title, product_no, retail_price, cost_price, category_id,
                    delivery_type, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, 'Integration product',
                    nextval('product_no_sequence'), 10.00, 10.00, ?,
                    'AUTO', true, 0)
                """, productId, categoryId);
    }

    private UUID addSpec(UUID productId, String name) {
        UUID specId = UUID.randomUUID();
        jdbcTemplate.update("update products set spec_enabled = true where id = ?", productId);
        jdbcTemplate.update("insert into product_specs(id, product_id, name, retail_price, cost_price) values (?, ?, ?, 10.00, 10.00)",
                specId, productId, name);
        return specId;
    }

    private void insertUnlimitedProduct(UUID productId) {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into product_categories (id, created_at, updated_at, name, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, 0, 0)
                """, categoryId, "Unlimited integration category " + productId);
        jdbcTemplate.update("""
                insert into products (id, created_at, updated_at, title, product_no, retail_price, cost_price, category_id,
                    delivery_type, stock_mode, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, 'Unlimited integration product',
                    nextval('product_no_sequence'), 10.00, 10.00, ?,
                    'AUTO', 'UNLIMITED', true, 0)
                """, productId, categoryId);
    }

    private void insertManualProduct(UUID productId) {
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into product_categories (id, created_at, updated_at, name, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, 0, 0)
                """, categoryId, "Manual integration category " + productId);
        jdbcTemplate.update("""
                insert into products (id, created_at, updated_at, title, product_no, retail_price, cost_price, category_id,
                    delivery_type, stock_mode, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, 'Manual integration product',
                    nextval('product_no_sequence'), 10.00, 10.00, ?,
                    'MANUAL', 'MANUAL', true, 0)
                """, productId, categoryId);
    }

    private void setBalance(UUID userId, BigDecimal balance) {
        jdbcTemplate.update("UPDATE users SET balance = ? WHERE id = ?", balance, userId);
    }

    private Map<String, Object> balanceOrderRequest(UUID productId, String idempotencyKey,
                                                     String idempotencyFingerprint) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("product_id", productId.toString());
        request.put("quantity", 1);
        request.put("payment_method", "BALANCE");
        request.put("expected_unit_price", "10.00");
        request.put("idempotency_key", idempotencyKey);
        request.put("idempotency_fingerprint", idempotencyFingerprint);
        return request;
    }

    private boolean tryBalanceOrder(CountDownLatch ready, CountDownLatch start,
                                    UUID productId, UUID userId, String idempotencyKey,
                                    String idempotencyFingerprint) throws InterruptedException {
        ready.countDown();
        if (!start.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("balance concurrency test did not start");
        }
        try {
            orderService.createDirectOrder(
                    balanceOrderRequest(productId, idempotencyKey, idempotencyFingerprint),
                    userId, "203.0.113.36", idempotencyKey + "-session");
            return true;
        } catch (BusinessException exception) {
            if (exception.getCode() == ErrorCode.INSUFFICIENT_BALANCE) return false;
            throw exception;
        }
    }

    private void insertPaymentChannel(String channelCode) {
        jdbcTemplate.update("""
                insert into payment_channels (id, created_at, updated_at, channel_code, channel_name,
                    provider_type, payment_type, config_data, is_enabled, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, ?, 'epay', 'wxpay', ?, true, 0, 0)
                """, UUID.randomUUID(), channelCode, channelCode,
                "{\"pid\":\"pid\",\"key\":\"secret\",\"api_url\":\"https://pay.example\"}");
    }

    private void configureSuccessfulCallback(String tradeNo) {
        when(epayPaymentProvider.supportsServerSideQuery()).thenReturn(true);
        when(epayPaymentProvider.extractMerchantTradeNo(any(PaymentProvider.WebhookRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, PaymentProvider.WebhookRequest.class)
                        .parameter("out_trade_no"));
        when(epayPaymentProvider.verifyAndParseCallback(
                any(PaymentProvider.WebhookRequest.class), anyMap()))
                .thenAnswer(invocation -> {
                    PaymentProvider.WebhookRequest request = invocation.getArgument(0);
                    String merchantTradeNo = request.parameter("out_trade_no");
                    return new PaymentProvider.CallbackResult("epay:" + tradeNo + ":" + merchantTradeNo,
                            merchantTradeNo, tradeNo, PaymentStatus.PAID, new BigDecimal("10.00"));
                });
        when(epayPaymentProvider.query(any(PaymentProvider.PaymentQueryRequest.class)))
                .thenReturn(new PaymentProvider.PaymentQueryResult(
                        PaymentStatus.PAID, new BigDecimal("10.00"), tradeNo));
    }

    private void configurePaymentCreation(String tradeNo) {
        when(epayPaymentProvider.create(any(PaymentProvider.PaymentRequest.class)))
                .thenReturn(new PaymentProvider.PaymentResult(
                        tradeNo, new PaymentNextAction.Redirect("https://pay.example/" + tradeNo)));
    }

    private Map<String, Object> directOrderRequest(UUID productId, String paymentMethod) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("product_id", productId.toString());
        request.put("quantity", 1);
        request.put("payment_method", "ONLINE");
        request.put("payment_channel_code", paymentMethod);
        request.put("expected_unit_price", "10.00");
        return request;
    }

    private void assertNormalPendingPaymentOrder(Map<String, Object> result) {
        Map<?, ?> order = (Map<?, ?>) result.get("order");
        Map<?, ?> payment = (Map<?, ?>) result.get("payment");
        UUID orderId = (UUID) order.get("id");

        assertEquals(OrderType.NORMAL, order.get("order_type"));
        assertNotNull(order.get("order_no"));
        assertEquals(order.get("order_no"), payment.get("order_no"));
        assertEquals("PENDING", order.get("status"));
        assertEquals("PENDING", scalar("select status from orders where id = ?", String.class, orderId));
        Map<?, ?> nextAction = (Map<?, ?>) payment.get("next_action");
        assertEquals("redirect", nextAction.get("type"));
        assertTrue(String.valueOf(nextAction.get("url")).startsWith("https://pay.example/"));
    }

    private PaymentProvider.WebhookRequest callback(String merchantTradeNo, String tradeNo) {
        return new PaymentProvider.WebhookRequest("POST", Map.of(), Map.of(), Map.of(
                "trade_no", List.of(tradeNo),
                "out_trade_no", List.of(merchantTradeNo),
                "trade_status", List.of("TRADE_SUCCESS"),
                "money", List.of("10.00"),
                "sign", List.of("valid-signature")), "");
    }

    private String createAttempt(UUID orderId, String channel, String tradeNo) {
        Map<String, Object> result = paymentService.createPayment(
                orderId, channel, new BigDecimal("10.00"), "pc");
        UUID paymentId = (UUID) result.get("payment_id");
        return scalar("select merchant_trade_no from payment_attempts where id = ?",
                String.class, paymentId);
    }

    private UUID insertOrderItem(UUID orderId, UUID productId) {
        return insertOrderItem(orderId, productId, "AUTO", "CARD_KEY", 1, 0);
    }

    private UUID insertOrderItem(UUID orderId, UUID productId, String deliveryType,
                                 String stockMode, int quantity, int manualStockReserved) {
        UUID itemId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into order_items (id, created_at, updated_at, order_id, product_id, product_no,
                    product_title, quantity, unit_price, cost_price_snapshot, retail_price_snapshot, pricing_source, subtotal, delivery_type,
                    stock_mode, fulfillment_status, manual_stock_reserved)
                values (?, current_timestamp, current_timestamp, ?, ?, (select product_no from products where id = ?), 'Integration product', ?, 10.00, 10.00, 10.00,
                    'RETAIL', ?, ?, ?, 'PENDING', ?)
                """, itemId, orderId, productId, productId, quantity,
                BigDecimal.TEN.multiply(BigDecimal.valueOf(quantity)), deliveryType,
                stockMode, manualStockReserved);
        return itemId;
    }

    private void insertManualStock(UUID productId, UUID specId, int available, int reserved) {
        jdbcTemplate.update("""
                insert into manual_stocks (id, created_at, updated_at, product_id, spec_id,
                    available_quantity, reserved_quantity)
                values (?, current_timestamp, current_timestamp, ?, ?, ?, ?)
                """, UUID.randomUUID(), productId, specId, available, reserved);
    }

    private UUID insertCard(UUID productId, String content) {
        return insertCardParts(productId, List.of(content), CardKeyStatus.AVAILABLE);
    }

    private UUID insertCardParts(UUID productId, List<String> parts) {
        return insertCardParts(productId, parts, CardKeyStatus.AVAILABLE);
    }

    private UUID insertCardParts(UUID productId, List<String> parts, CardKeyStatus status) {
        if (parts.size() != 1 && parts.size() != 2) {
            throw new IllegalArgumentException("This test helper expects one or two card parts");
        }
        UUID cardId = UUID.randomUUID();
        if (parts.size() == 1) {
            jdbcTemplate.update("""
                    insert into card_keys (id, created_at, updated_at, product_id, content_parts,
                                           content_fingerprint, status)
                    values (?, current_timestamp, current_timestamp, ?, jsonb_build_array(?), ?, ?)
                    """, cardId, productId, parts.get(0), CardContentParser.fingerprint(parts), status.name());
        } else {
            jdbcTemplate.update("""
                    insert into card_keys (id, created_at, updated_at, product_id, content_parts,
                                           content_fingerprint, status)
                    values (?, current_timestamp, current_timestamp, ?, jsonb_build_array(?, ?), ?, ?)
                    """, cardId, productId, parts.get(0), parts.get(1),
                    CardContentParser.fingerprint(parts), status.name());
        }
        // Direct SQL fixtures stand in for a previously successful import.
        jdbcTemplate.update("""
                UPDATE products SET card_content_column_count = CASE
                    WHEN card_content_column_count IS NULL THEN ?
                    WHEN card_content_column_count <> ? THEN 0
                    ELSE card_content_column_count END WHERE id = ?
                """, parts.size(), parts.size(), productId);
        return cardId;
    }

    private int number(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private <T> T scalar(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }
}
