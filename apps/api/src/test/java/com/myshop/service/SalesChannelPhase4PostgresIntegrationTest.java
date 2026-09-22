package com.myshop.service;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingSpec;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingSpecRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.service.impl.FulfillmentTransactionService;
import com.myshop.saleschannel.model.SalesChannelDtos.AgentOrder;
import com.myshop.utils.CardContentParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL-only Phase 4 invariants. Testcontainers skips this class when a
 * Docker daemon is unavailable; compilation still protects the production
 * service/repository contract.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class SalesChannelPhase4PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SalesChannelAccountRepository accountRepository;

    @Autowired
    private SalesChannelListingRepository listingRepository;

    @Autowired
    private SalesChannelListingSpecRepository listingSpecRepository;

    @Autowired
    private SalesChannelOrderRepository channelOrderRepository;

    @Autowired
    private SalesChannelDeliveryOperationRepository deliveryOperationRepository;

    @Autowired
    private SalesChannelOrderImportService importService;

    @Autowired
    private FulfillmentTransactionService fulfillmentService;

    @Autowired
    private SalesChannelDeliveryPlanner deliveryPlanner;

    @MockitoBean
    private com.myshop.service.EmailService emailService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("jwt.secret", () -> "integration-test-secret-with-at-least-64-bytes-and-random-looking-value-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
        registry.add("sales-channel.worker.enabled", () -> false);
        registry.add("sales-channel.order-sync.enabled", () -> false);
        registry.add("sales-channel.delivery.worker-enabled", () -> false);
    }

    @Test
    void replayAndQuantityThreeCreateOneOrderAndExactlyThreeSoldKeys() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("replay");
        SalesChannelListing listing = listing(account, productId, "item-replay", true);
        insertCard(productId, null, "replay-card-1");
        insertCard(productId, null, "replay-card-2");
        insertCard(productId, null, "replay-card-3");
        insertCard(productId, null, "replay-card-4");

        AgentOrder external = order(account, "xy-replay-1", "item-replay", null, null, 3, "60.00");
        SalesChannelOrder first = importService.importOrder(account, external);
        SalesChannelOrder second = importService.importOrder(account, external);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, count("select count(*) from sales_channel_orders where external_order_no = ?", "xy-replay-1"));
        assertEquals(1, count("select count(*) from orders where id = ?", first.getOrderId()));
        assertEquals("SALES_CHANNEL", scalar("select order_type from orders where id = ?", String.class, first.getOrderId()));
        assertNull(scalar("select payment_method from orders where id = ?", String.class, first.getOrderId()));
        assertEquals("XIANYU", scalar("select payment_channel_code from orders where id = ?", String.class, first.getOrderId()));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, first.getOrderId()));
        assertEquals(3, count("select count(*) from card_keys where order_id = ? and status = 'SOLD'", first.getOrderId()));
        assertEquals(3, scalar("select quantity from order_items where order_id = ?", Integer.class, first.getOrderId()));
        assertEquals(listing.getId(), first.getSalesChannelListingId());
    }

    @Test
    void shortageLeavesPaidAndRetryAfterRestockConsumesOnlyRequestedQuantity() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("shortage");
        listing(account, productId, "item-shortage", true);
        insertCard(productId, null, "shortage-card-1");
        insertCard(productId, null, "shortage-card-2");

        SalesChannelOrder row = importService.importOrder(
                account, order(account, "xy-shortage-1", "item-shortage", null, null, 3, "30.00"));

        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, row.getOrderId()));
        assertEquals(0, count("select count(*) from card_keys where order_id = ?", row.getOrderId()));

        insertCard(productId, null, "shortage-card-3");
        fulfillmentService.fulfillOrder(row.getOrderId());

        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, row.getOrderId()));
        assertEquals(3, count("select count(*) from card_keys where order_id = ? and status = 'SOLD'", row.getOrderId()));
    }

    @Test
    void changedFingerprintIsSourceConflictAndDoesNotMutateImportedOrder() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("conflict");
        listing(account, productId, "item-conflict", false);

        importService.importOrder(account, order(account, "xy-conflict-1", "item-conflict", null, null, 1, "10.00"));
        SalesChannelOrder conflict = importService.importOrder(
                account, order(account, "xy-conflict-1", "item-conflict", null, null, 2, "20.00"));

        assertEquals("SOURCE_CONFLICT", conflict.getIngestStatus().name());
        assertEquals(1, count("select count(*) from sales_channel_orders where external_order_no = ?", "xy-conflict-1"));
        assertEquals(1, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-conflict-1"));
        assertEquals(1, scalar("select quantity from order_items where order_id = ?", Integer.class, conflict.getOrderId()));
    }

    @Test
    void concurrentFirstImportSerializesOnAccountAndCreatesOneOrder() throws Exception {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("concurrent");
        listing(account, productId, "item-concurrent", false);
        AgentOrder external = order(account, "xy-concurrent-1", "item-concurrent", null, null, 1, "10.00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SalesChannelOrder> first = executor.submit(() -> importAfter(start, ready, account, external));
            Future<SalesChannelOrder> second = executor.submit(() -> importAfter(start, ready, account, external));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(first.get(30, TimeUnit.SECONDS).getId(), second.get(30, TimeUnit.SECONDS).getId());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, count("select count(*) from sales_channel_orders where external_order_no = ?", "xy-concurrent-1"));
        assertEquals(1, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-concurrent-1"));
    }

    @Test
    void exactSkuMappingSelectsOnlyTheMappedSpecAndPlannerIsIdempotent() {
        UUID productId = insertProduct(true);
        UUID specId = insertProductSpec(productId, "月卡");
        SalesChannelAccount account = account("spec");
        SalesChannelListing listing = listing(account, productId, "item-spec", true);
        SalesChannelListingSpec mapping = new SalesChannelListingSpec();
        mapping.setSalesChannelListingId(listing.getId());
        mapping.setProductSpecId(specId);
        mapping.setExternalSkuId("sku-month");
        mapping.setProviderMapping(Map.of("provider_spec_name", "规格", "provider_spec_value", "月卡"));
        listingSpecRepository.saveAndFlush(mapping);
        insertCard(productId, specId, "spec-card");

        SalesChannelOrder row = importService.importOrder(
                account, order(account, "xy-spec-1", "item-spec", "sku-month", "月卡", 1, "20.00"));
        deliveryPlanner.plan(row.getId());
        deliveryPlanner.plan(row.getId());

        assertEquals(specId, scalar("select spec_id from order_items where order_id = ?", UUID.class, row.getOrderId()));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, row.getOrderId()));
        List<SalesChannelDeliveryOperation> operations = deliveryOperationRepository
                .findBySalesChannelOrderIdOrderByCreatedAtAsc(row.getId());
        assertEquals(1, operations.stream().filter(operation -> "SEND_MESSAGE".equals(operation.getOperationType().name())).count());
        assertEquals(1, operations.stream().filter(operation -> specId != null && operation.getCardKeyId() != null).count());
        assertEquals(0, operations.stream().filter(operation -> operation.getResultJson() != null
                && operation.getResultJson().containsKey("content")).count());
    }

    @Test
    void wrongAccountCannotCrossMatchAnotherAccountListing() {
        UUID productId = insertProduct(false);
        SalesChannelAccount owner = account("owner");
        SalesChannelAccount wrongAccount = account("wrong");
        listing(owner, productId, "item-owned-by-owner", false);

        SalesChannelOrder row = importService.importOrder(wrongAccount,
                order(wrongAccount, "xy-cross-account-1", "item-owned-by-owner", null, null, 1, "10.00"));

        assertEquals("MAPPING_ERROR", row.getIngestStatus().name());
        assertEquals("LISTING_NOT_FOUND", row.getLastErrorCode());
        assertNull(row.getOrderId());
        assertEquals(0, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + wrongAccount.getProviderAccountId() + ":xy-cross-account-1"));
    }

    private SalesChannelOrder importAfter(CountDownLatch start, CountDownLatch ready,
                                          SalesChannelAccount account, AgentOrder external) throws Exception {
        ready.countDown();
        if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("concurrency test did not start");
        return importService.importOrder(account, external);
    }

    private SalesChannelAccount account(String suffix) {
        SalesChannelAccount account = new SalesChannelAccount();
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setProviderAccountId("phase4-account-" + suffix + "-" + UUID.randomUUID());
        account.setDisplayName("Phase 4 " + suffix);
        account.setStatus(SalesChannelAccountStatus.CONNECTED);
        account.setEnabled(true);
        account.setCapabilities(Map.of("account_type", "FISH_SHOP", "publish", true, "edit", true,
                "stock_sync", true, "sku", true, "video", false));
        return accountRepository.saveAndFlush(account);
    }

    private SalesChannelListing listing(SalesChannelAccount account, UUID productId,
                                        String externalItemId, boolean autoFulfillment) {
        SalesChannelListing listing = new SalesChannelListing();
        listing.setSalesChannelAccountId(account.getId());
        listing.setProductId(productId);
        listing.bindExternalItemId(externalItemId);
        listing.setEnabled(true);
        listing.setAutoFulfillmentEnabled(autoFulfillment);
        listing.setDesiredState(SalesChannelListingDesiredState.ONLINE);
        listing.setStatus(SalesChannelListingStatus.ONLINE);
        listing.setProviderConfig(Map.of());
        return listingRepository.saveAndFlush(listing);
    }

    private AgentOrder order(SalesChannelAccount account, String externalOrderNo,
                             String itemId, String sku, String specValue,
                             int quantity, String amount) {
        return new AgentOrder(account.getProviderAccountId(), externalOrderNo, "pending_ship", itemId,
                sku, specValue == null ? null : "规格", specValue, quantity, amount, "CNY",
                "2026-09-22T00:00:00Z", "2026-09-22T00:00:01Z");
    }

    private UUID insertProduct(boolean specEnabled) {
        UUID productId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into product_categories (id, created_at, updated_at, name, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, 0, 0)
                """, categoryId, "Phase 4 category " + productId);
        jdbcTemplate.update("""
                insert into products (id, created_at, updated_at, title, product_no, retail_price, cost_price, category_id,
                    delivery_type, stock_mode, spec_enabled, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, 'Phase 4 product', nextval('product_no_sequence'),
                    10.00, 4.00, ?, 'AUTO', 'CARD_KEY', ?, true, 0)
                """, productId, categoryId, specEnabled);
        return productId;
    }

    private UUID insertProductSpec(UUID productId, String name) {
        UUID specId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into product_specs (id, created_at, updated_at, product_id, name, retail_price,
                    cost_price, is_visible, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, ?, 20.00, 8.00, true, 0, 0)
                """, specId, productId, name);
        return specId;
    }

    private UUID insertCard(UUID productId, UUID specId, String content) {
        UUID cardId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into card_keys (id, created_at, updated_at, product_id, spec_id, content_parts,
                    content_fingerprint, status)
                values (?, current_timestamp, current_timestamp, ?, ?, jsonb_build_array(?), ?, ?)
                """, cardId, productId, specId, content, CardContentParser.fingerprint(List.of(content)),
                CardKeyStatus.AVAILABLE.name());
        return cardId;
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private <T> T scalar(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }
}
