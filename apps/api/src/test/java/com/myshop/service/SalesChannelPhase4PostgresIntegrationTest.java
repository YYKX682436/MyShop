package com.myshop.service;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelDeliveryOperationStatus;
import com.myshop.constant.SalesChannelDeliveryOperationType;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOrderDeliveryStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingSpec;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.exception.SalesChannelSpecMappingException;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingSpecRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.service.impl.AutoFulfillmentServiceImpl;
import com.myshop.service.impl.FulfillmentTransactionService;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.model.SalesChannelDtos.AgentOrder;
import com.myshop.saleschannel.model.SalesChannelDtos.DeliveryReconcileData;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSpecMappingsData;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSpecification;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSpecificationValue;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSkuRow;
import com.myshop.utils.CardContentParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL-only Phase 4 invariants. Testcontainers skips this class when a
 * Docker daemon is unavailable; compilation still protects the production
 * service/repository contract.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class SalesChannelPhase4PostgresIntegrationTest {

    private int nextTestUnitIndex;

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
    private SalesChannelSpecMappingSyncService specMappingSyncService;

    @Autowired
    private FulfillmentTransactionService fulfillmentService;

    @Autowired
    private SalesChannelDeliveryPlanner deliveryPlanner;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private AutoFulfillmentServiceImpl autoFulfillmentService;

    @Autowired
    private SalesChannelDeliveryClaimService deliveryClaimService;

    @Autowired
    private SalesChannelDeliveryRecoveryService deliveryRecoveryService;

    @Autowired
    private SalesChannelDeliveryOperationStateService deliveryStateService;

    @Autowired
    private CardKeyRepository cardKeyRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @MockitoBean
    private SalesChannelProviderClient providerClient;

    @PersistenceContext
    private EntityManager entityManager;

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
    void specMappingBackfillIsExactAtomicIdempotentAndDoesNotCreateListingOperations() {
        UUID productId = insertProduct(true);
        UUID monthId = insertProductSpec(productId, "月卡");
        UUID yearId = insertProductSpec(productId, "年卡");
        UUID lifetimeId = insertProductSpec(productId, "永久卡");
        SalesChannelAccount account = account("spec-backfill");
        SalesChannelListing listing = listing(account, productId, "item-spec-backfill", false);
        insertListingSpec(listing, monthId);
        insertListingSpec(listing, yearId);
        insertListingSpec(listing, lifetimeId);

        long desiredRevision = listing.getDesiredRevision();
        long appliedRevision = listing.getAppliedRevision();
        int operationCount = count("select count(*) from sales_channel_operations where sales_channel_listing_id = ?",
                listing.getId());

        when(providerClient.getListingSpecMappings(any())).thenReturn(new IntegrationEnvelope<>(
                "provider-read",
                Outcome.SUCCEEDED,
                new ListingSpecMappingsData("item-spec-backfill",
                        List.of(new SpecMappingSpecification("规格", List.of(
                                new SpecMappingSpecificationValue("月卡"),
                                new SpecMappingSpecificationValue("年卡"),
                                new SpecMappingSpecificationValue("永久卡"),
                                new SpecMappingSpecificationValue("测试卡")))),
                        List.of(
                                new SpecMappingSkuRow(null, Map.of("规格", "永久卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "月卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "年卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "测试卡")))),
                null));

        SalesChannelSpecMappingException failure = assertThrows(SalesChannelSpecMappingException.class,
                () -> specMappingSyncService.sync(listing.getId()));
        assertEquals("SPEC_MAPPING_INCOMPLETE", failure.getCode());
        assertEquals(3, count("select count(*) from sales_channel_listing_specs "
                + "where sales_channel_listing_id = ? and provider_mapping = '{}'::jsonb and external_sku_id is null",
                listing.getId()));
        assertEquals(desiredRevision, listingRepository.findById(listing.getId()).orElseThrow().getDesiredRevision());
        assertEquals(appliedRevision, listingRepository.findById(listing.getId()).orElseThrow().getAppliedRevision());
        assertEquals(operationCount, count("select count(*) from sales_channel_operations where sales_channel_listing_id = ?",
                listing.getId()));

        when(providerClient.getListingSpecMappings(any())).thenReturn(new IntegrationEnvelope<>(
                "provider-read",
                Outcome.SUCCEEDED,
                new ListingSpecMappingsData("item-spec-backfill",
                        List.of(new SpecMappingSpecification("规格", List.of(
                                new SpecMappingSpecificationValue("月卡"),
                                new SpecMappingSpecificationValue("年卡"),
                                new SpecMappingSpecificationValue("永久卡")))),
                        List.of(
                                new SpecMappingSkuRow(null, Map.of("规格", "永久卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "月卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "年卡")))),
                null));

        var first = specMappingSyncService.sync(listing.getId());
        assertEquals(3, first.matched());
        assertEquals("item-spec-backfill", first.itemId());

        Map<UUID, SalesChannelListingSpec> rows = listingSpecRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(SalesChannelListingSpec::getProductSpecId, row -> row));
        assertEquals(Map.of("provider_spec_name", "规格", "provider_spec_value", "月卡"),
                rows.get(monthId).getProviderMapping());
        assertEquals(Map.of("provider_spec_name", "规格", "provider_spec_value", "年卡"),
                rows.get(yearId).getProviderMapping());
        assertEquals(Map.of("provider_spec_name", "规格", "provider_spec_value", "永久卡"),
                rows.get(lifetimeId).getProviderMapping());
        assertNull(rows.get(monthId).getExternalSkuId());
        assertNull(rows.get(yearId).getExternalSkuId());
        assertNull(rows.get(lifetimeId).getExternalSkuId());
        assertEquals(desiredRevision, listingRepository.findById(listing.getId()).orElseThrow().getDesiredRevision());
        assertEquals(appliedRevision, listingRepository.findById(listing.getId()).orElseThrow().getAppliedRevision());
        assertEquals(operationCount, count("select count(*) from sales_channel_operations where sales_channel_listing_id = ?",
                listing.getId()));

        var second = specMappingSyncService.sync(listing.getId());
        assertEquals(first.mappings(), second.mappings());
        assertEquals(3, count("select count(*) from sales_channel_listing_specs where sales_channel_listing_id = ?",
                listing.getId()));
        assertEquals(operationCount, count("select count(*) from sales_channel_operations where sales_channel_listing_id = ?",
                listing.getId()));
        verify(providerClient, org.mockito.Mockito.times(3)).getListingSpecMappings(any());
    }

    @Test
    void specMappingOrderImportUsesExactProviderValueAndRejectsFuzzyValue() {
        UUID productId = insertProduct(true);
        UUID monthId = insertProductSpec(productId, "月卡");
        UUID yearId = insertProductSpec(productId, "年卡");
        UUID lifetimeId = insertProductSpec(productId, "永久卡");
        SalesChannelAccount account = account("spec-order-backfill");
        SalesChannelListing listing = listing(account, productId, "item-spec-order-backfill", false);
        insertListingSpec(listing, monthId);
        insertListingSpec(listing, yearId);
        insertListingSpec(listing, lifetimeId);
        when(providerClient.getListingSpecMappings(any())).thenReturn(new IntegrationEnvelope<>(
                "provider-read",
                Outcome.SUCCEEDED,
                new ListingSpecMappingsData("item-spec-order-backfill",
                        List.of(new SpecMappingSpecification("规格", List.of(
                                new SpecMappingSpecificationValue("永久卡"),
                                new SpecMappingSpecificationValue("月卡"),
                                new SpecMappingSpecificationValue("年卡")))),
                        List.of(
                                new SpecMappingSkuRow(null, Map.of("规格", "永久卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "月卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "年卡")))),
                null));
        specMappingSyncService.sync(listing.getId());

        SalesChannelOrder month = importService.importOrder(account,
                order(account, "xy-spec-backfill-month", "item-spec-order-backfill", null, "月卡", 1, "20.00"));
        SalesChannelOrder year = importService.importOrder(account,
                order(account, "xy-spec-backfill-year", "item-spec-order-backfill", null, "年卡", 1, "20.00"));
        SalesChannelOrder lifetime = importService.importOrder(account,
                order(account, "xy-spec-backfill-lifetime", "item-spec-order-backfill", null, "永久卡", 1, "20.00"));

        assertEquals(monthId, scalar("select spec_id from order_items where order_id = ?", UUID.class, month.getOrderId()));
        assertEquals(yearId, scalar("select spec_id from order_items where order_id = ?", UUID.class, year.getOrderId()));
        assertEquals(lifetimeId, scalar("select spec_id from order_items where order_id = ?", UUID.class, lifetime.getOrderId()));

        SalesChannelOrder fuzzy = importService.importOrder(account,
                order(account, "xy-spec-backfill-fuzzy", "item-spec-order-backfill", null, "月", 1, "20.00"));
        assertEquals("MAPPING_ERROR", fuzzy.getIngestStatus().name());
        assertEquals("SPEC_MAPPING_ERROR", fuzzy.getLastErrorCode());
        assertNull(fuzzy.getOrderId());
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

    @Test
    void channelAutoFulfillmentSnapshotControlsGlobalRetryScheduler() {
        UUID disabledProductId = insertProduct(false);
        SalesChannelAccount disabledAccount = account("retry-disabled");
        listing(disabledAccount, disabledProductId, "item-retry-disabled", false);
        UUID disabledCardId = insertCard(disabledProductId, null, "retry-disabled-card");
        SalesChannelOrder disabled = importService.importOrder(disabledAccount,
                order(disabledAccount, "xy-retry-disabled", "item-retry-disabled", null, null, 1, "10.00"));

        UUID enabledProductId = insertProduct(false);
        SalesChannelAccount enabledAccount = account("retry-enabled");
        listing(enabledAccount, enabledProductId, "item-retry-enabled", true);
        SalesChannelOrder enabled = importService.importOrder(enabledAccount,
                order(enabledAccount, "xy-retry-enabled", "item-retry-enabled", null, null, 1, "10.00"));
        assertEquals(OrderStatus.PAID.name(), scalar("select status from orders where id = ?", String.class,
                enabled.getOrderId()));
        UUID enabledCardId = insertCard(enabledProductId, null, "retry-enabled-card");
        UUID normalProductId = insertProduct(false);
        UUID normalOrderId = insertNormalRetryOrder(normalProductId);
        UUID normalCardId = insertCard(normalProductId, null, "retry-normal-card");

        List<com.myshop.entity.Order> due = orderRepository.findPaidOrdersWithPendingAutoItems(
                PageRequest.of(0, 100));
        assertFalse(due.stream().anyMatch(candidate -> disabled.getOrderId().equals(candidate.getId())));
        assertTrue(due.stream().anyMatch(candidate -> enabled.getOrderId().equals(candidate.getId())));
        assertTrue(due.stream().anyMatch(candidate -> normalOrderId.equals(candidate.getId())));

        autoFulfillmentService.retryPendingPaidOrders();

        assertEquals(OrderStatus.PAID.name(), scalar("select status from orders where id = ?", String.class,
                disabled.getOrderId()));
        assertEquals(0, count("select count(*) from card_keys where id = ? and status = 'SOLD'", disabledCardId));
        assertEquals(OrderStatus.DELIVERED.name(), scalar("select status from orders where id = ?", String.class,
                enabled.getOrderId()));
        assertEquals(1, count("select count(*) from card_keys where id = ? and status = 'SOLD'", enabledCardId));
        assertEquals(OrderStatus.DELIVERED.name(), scalar("select status from orders where id = ?", String.class,
                normalOrderId));
        assertEquals(1, count("select count(*) from card_keys where id = ? and status = 'SOLD'", normalCardId));
    }

    @Test
    void deliveryDueQueryAndClaimRejectPermanentAndFutureFailures() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("due-query");
        listing(account, productId, "item-due-query", false);
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-due-query", "item-due-query", null, null, 1, "10.00"));

        SalesChannelDeliveryOperation permanent = deliveryOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.FAILED, null);
        SalesChannelDeliveryOperation future = deliveryOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.FAILED, LocalDateTime.now().plusMinutes(5));
        SalesChannelDeliveryOperation past = deliveryOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.FAILED, LocalDateTime.now().minusMinutes(5));
        List<SalesChannelDeliveryOperation> due = deliveryOperationRepository.findDue(
                List.of(SalesChannelDeliveryOperationStatus.PENDING, SalesChannelDeliveryOperationStatus.FAILED),
                LocalDateTime.now(), PageRequest.of(0, 100));

        assertFalse(due.stream().anyMatch(operation -> permanent.getId().equals(operation.getId())));
        assertFalse(due.stream().anyMatch(operation -> future.getId().equals(operation.getId())));
        assertTrue(due.stream().anyMatch(operation -> past.getId().equals(operation.getId())));
        assertTrue(deliveryClaimService.claim(permanent.getId()).isEmpty());
        assertTrue(deliveryClaimService.claim(future.getId()).isEmpty());
        assertTrue(deliveryClaimService.claim(past.getId()).isPresent());
    }

    @Test
    void maxAttemptsStopsRetryAndReconcileExhaustionRequiresReview() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("max-attempts");
        listing(account, productId, "item-max-attempts", false);
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-max-attempts", "item-max-attempts", null, null, 1, "10.00"));

        SalesChannelDeliveryOperation send = deliveryOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.RUNNING, null);
        send.setAttemptCount(8);
        deliveryOperationRepository.saveAndFlush(send);
        deliveryStateService.failed(send.getId(), "TEMPORARY_PROVIDER_ERROR", "retryable", true);
        SalesChannelDeliveryOperation storedSend = deliveryOperationRepository.findById(send.getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, storedSend.getStatus());
        assertNull(storedSend.getNextAttemptAt());
        assertEquals("DELIVERY_MAX_ATTEMPTS_EXCEEDED", storedSend.getLastErrorCode());
        assertEquals(SalesChannelOrderDeliveryStatus.FAILED,
                channelOrderRepository.findById(row.getId()).orElseThrow().getDeliveryStatus());
        assertTrue(deliveryClaimService.claim(send.getId()).isEmpty());

        SalesChannelDeliveryOperation reconcile = deliveryOperation(row,
                SalesChannelDeliveryOperationType.RECONCILE_MESSAGE,
                SalesChannelDeliveryOperationStatus.RUNNING, null);
        reconcile.setAttemptCount(8);
        deliveryOperationRepository.saveAndFlush(reconcile);
        deliveryStateService.failed(reconcile.getId(), "MESSAGE_UNKNOWN", "still unknown", true);
        assertEquals(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED,
                channelOrderRepository.findById(row.getId()).orElseThrow().getDeliveryStatus());
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED,
                deliveryOperationRepository.findById(reconcile.getId()).orElseThrow().getStatus());
        assertNull(deliveryOperationRepository.findById(reconcile.getId()).orElseThrow().getNextAttemptAt());
    }

    @Test
    void staleSendConfirmAndReconcileRecoverWithoutReplayingSideEffects() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("stale-recovery");
        listing(account, productId, "item-stale-recovery", false);
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-stale-recovery", "item-stale-recovery", null, null, 1, "10.00"));
        LocalDateTime old = LocalDateTime.now().minusMinutes(10);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);

        SalesChannelDeliveryOperation send = deliveryOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.RUNNING, null);
        send.setStartedAt(old);
        send.setAttemptCount(1);
        deliveryOperationRepository.saveAndFlush(send);

        SalesChannelDeliveryOperation confirm = deliveryOperation(row, SalesChannelDeliveryOperationType.CONFIRM_DELIVERY,
                SalesChannelDeliveryOperationStatus.RUNNING, null);
        confirm.setStartedAt(old);
        confirm.setAttemptCount(1);
        deliveryOperationRepository.saveAndFlush(confirm);

        SalesChannelDeliveryOperation reconcile = deliveryOperation(row,
                SalesChannelDeliveryOperationType.RECONCILE_MESSAGE,
                SalesChannelDeliveryOperationStatus.RUNNING, null);
        reconcile.setStartedAt(old);
        reconcile.setAttemptCount(1);
        reconcile.setResultJson(new LinkedHashMap<>(Map.of("target_request_id", send.getRequestId())));
        deliveryOperationRepository.saveAndFlush(reconcile);

        assertEquals(3, deliveryRecoveryService.recoverStaleRunning(cutoff, 100));

        SalesChannelDeliveryOperation storedSend = deliveryOperationRepository.findById(send.getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN, storedSend.getStatus());
        SalesChannelDeliveryOperation sendReconcile = deliveryOperationRepository
                .findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(row.getId(),
                        SalesChannelDeliveryOperationType.RECONCILE_MESSAGE).stream()
                .filter(operation -> send.getRequestId().equals(
                        String.valueOf(operation.getResultJson().get("target_request_id"))))
                .findFirst().orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.PENDING, sendReconcile.getStatus());

        SalesChannelDeliveryOperation storedConfirm = deliveryOperationRepository.findById(confirm.getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN, storedConfirm.getStatus());
        SalesChannelDeliveryOperation confirmReconcile = deliveryOperationRepository
                .findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(row.getId(),
                        SalesChannelDeliveryOperationType.RECONCILE_CONFIRM).stream()
                .findFirst().orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.PENDING, confirmReconcile.getStatus());

        SalesChannelDeliveryOperation storedReconcile = deliveryOperationRepository.findById(reconcile.getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.PENDING, storedReconcile.getStatus());
        assertEquals(reconcile.getRequestId(), storedReconcile.getRequestId());
        assertNotNull(storedReconcile.getNextAttemptAt());
    }

    @Test
    void plannerPreservesMessageUnknownProjection() {
        UnknownMessageFixture fixture = unknownMessageFixture("planner-unknown");

        deliveryPlanner.plan(fixture.row().getId());

        assertEquals(SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN,
                channelOrderRepository.findById(fixture.row().getId()).orElseThrow().getDeliveryStatus());
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN,
                deliveryOperationRepository.findById(fixture.send().getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelDeliveryOperationStatus.PENDING,
                deliveryOperationRepository.findById(fixture.reconcile().getId()).orElseThrow().getStatus());
    }

    @Test
    void reconcileUnknownRetryPreservesProjectionAndNeverSendsAgain() {
        UnknownMessageFixture fixture = unknownMessageFixture("reconcile-unknown");
        when(providerClient.reconcileDeliveryUnit(any())).thenReturn(
                reconcileResponse(fixture.send().getRequestId(), Outcome.UNKNOWN, false));

        deliveryWorker().process(fixture.reconcile());

        SalesChannelDeliveryOperation reconcile = deliveryOperationRepository
                .findById(fixture.reconcile().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, reconcile.getStatus());
        assertNotNull(reconcile.getNextAttemptAt());
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN,
                deliveryOperationRepository.findById(fixture.send().getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN,
                channelOrderRepository.findById(fixture.row().getId()).orElseThrow().getDeliveryStatus());

        deliveryPlanner.plan(fixture.row().getId());
        assertEquals(SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN,
                channelOrderRepository.findById(fixture.row().getId()).orElseThrow().getDeliveryStatus());
        verify(providerClient, never()).sendDeliveryUnit(any());
    }

    @Test
    void retryableFailedMessageReconciliationRestoresPendingAndKeepsRequestId() {
        UnknownMessageFixture fixture = unknownMessageFixture("reconcile-retryable");
        String requestId = fixture.send().getRequestId();
        when(providerClient.reconcileDeliveryUnit(any())).thenReturn(
                reconcileResponse(requestId, Outcome.FAILED, true));

        deliveryWorker().process(fixture.reconcile());

        SalesChannelDeliveryOperation target = deliveryOperationRepository
                .findById(fixture.send().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, target.getStatus());
        assertNotNull(target.getNextAttemptAt());
        assertEquals(requestId, target.getRequestId());
        assertEquals(SalesChannelOrderDeliveryStatus.MESSAGE_PENDING,
                channelOrderRepository.findById(fixture.row().getId()).orElseThrow().getDeliveryStatus());

        target.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        deliveryOperationRepository.saveAndFlush(target);
        SalesChannelDeliveryOperation claimed = deliveryClaimService.claim(target.getId()).orElseThrow();
        assertEquals(requestId, claimed.getRequestId());
        verify(providerClient, never()).sendDeliveryUnit(any());
    }

    @Test
    void retryableFailedConfirmReconciliationRestoresPendingAndKeepsRequestId() {
        UnknownConfirmFixture fixture = unknownConfirmFixture("reconcile-retryable-confirm");
        String requestId = fixture.target().getRequestId();
        when(providerClient.reconcileConfirmDelivery(any())).thenReturn(
                reconcileResponse(requestId, Outcome.FAILED, true));

        deliveryWorker().process(fixture.reconcile());

        SalesChannelDeliveryOperation target = deliveryOperationRepository
                .findById(fixture.target().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, target.getStatus());
        assertNotNull(target.getNextAttemptAt());
        assertEquals(requestId, target.getRequestId());
        assertEquals(SalesChannelOrderDeliveryStatus.CONFIRM_PENDING,
                channelOrderRepository.findById(fixture.row().getId()).orElseThrow().getDeliveryStatus());

        target.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        deliveryOperationRepository.saveAndFlush(target);
        SalesChannelDeliveryOperation claimed = deliveryClaimService.claim(target.getId()).orElseThrow();
        assertEquals(requestId, claimed.getRequestId());
        verify(providerClient, never()).confirmDelivery(any());
    }

    @Test
    void permanentFailedMessageAndConfirmReconciliationProjectFailedAndStopRetry() {
        UnknownMessageFixture message = unknownMessageFixture("reconcile-permanent-message");
        UnknownConfirmFixture confirm = unknownConfirmFixture("reconcile-permanent-confirm");
        when(providerClient.reconcileDeliveryUnit(any())).thenReturn(
                reconcileResponse(message.send().getRequestId(), Outcome.FAILED, false));
        when(providerClient.reconcileConfirmDelivery(any())).thenReturn(
                reconcileResponse(confirm.target().getRequestId(), Outcome.FAILED, false));

        SalesChannelDeliveryWorker worker = deliveryWorker();
        worker.process(message.reconcile());
        worker.process(confirm.reconcile());

        SalesChannelDeliveryOperation messageTarget = deliveryOperationRepository
                .findById(message.send().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, messageTarget.getStatus());
        assertNull(messageTarget.getNextAttemptAt());
        assertEquals(SalesChannelOrderDeliveryStatus.FAILED,
                channelOrderRepository.findById(message.row().getId()).orElseThrow().getDeliveryStatus());
        assertTrue(deliveryOperationRepository.findDue(
                List.of(SalesChannelDeliveryOperationStatus.PENDING, SalesChannelDeliveryOperationStatus.FAILED),
                LocalDateTime.now(), PageRequest.of(0, 100)).stream()
                .noneMatch(operation -> messageTarget.getId().equals(operation.getId())));

        SalesChannelDeliveryOperation confirmTarget = deliveryOperationRepository
                .findById(confirm.target().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, confirmTarget.getStatus());
        assertNull(confirmTarget.getNextAttemptAt());
        assertEquals(SalesChannelOrderDeliveryStatus.FAILED,
                channelOrderRepository.findById(confirm.row().getId()).orElseThrow().getDeliveryStatus());
        verify(providerClient, never()).sendDeliveryUnit(any());
        verify(providerClient, never()).confirmDelivery(any());
    }

    @Test
    void staleReconciliationAtMaxAttemptsBecomesReviewWithoutChangingUnknownTarget() {
        UnknownMessageFixture message = unknownMessageFixture("stale-reconcile-max-message");
        UnknownConfirmFixture confirm = unknownConfirmFixture("stale-reconcile-max-confirm");
        LocalDateTime old = LocalDateTime.now().minusMinutes(10);

        prepareStaleReconcileAtMax(message.reconcile(), message.send().getRequestId(), old);
        prepareStaleReconcileAtMax(confirm.reconcile(), confirm.target().getRequestId(), old);

        assertEquals(2, deliveryRecoveryService.recoverStaleRunning(
                LocalDateTime.now().minusMinutes(5), 100));

        SalesChannelDeliveryOperation messageReconcile = deliveryOperationRepository
                .findById(message.reconcile().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, messageReconcile.getStatus());
        assertNull(messageReconcile.getNextAttemptAt());
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN,
                deliveryOperationRepository.findById(message.send().getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED,
                channelOrderRepository.findById(message.row().getId()).orElseThrow().getDeliveryStatus());

        SalesChannelDeliveryOperation confirmReconcile = deliveryOperationRepository
                .findById(confirm.reconcile().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, confirmReconcile.getStatus());
        assertNull(confirmReconcile.getNextAttemptAt());
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN,
                deliveryOperationRepository.findById(confirm.target().getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED,
                channelOrderRepository.findById(confirm.row().getId()).orElseThrow().getDeliveryStatus());
        verify(providerClient, never()).reconcileDeliveryUnit(any());
        verify(providerClient, never()).reconcileConfirmDelivery(any());
    }

    @Test
    void plannerAndRecoveryCannotOverwriteReviewProjection() {
        UnknownMessageFixture fixture = unknownMessageFixture("review-sticky");
        SalesChannelOrder row = channelOrderRepository.findById(fixture.row().getId()).orElseThrow();
        row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED);
        row.setLastErrorCode("MANUAL_REVIEW");
        channelOrderRepository.saveAndFlush(row);

        deliveryPlanner.plan(row.getId());
        deliveryRecoveryService.recoverStaleRunning(LocalDateTime.now().minusMinutes(5), 100);
        deliveryPlanner.plan(row.getId());

        SalesChannelOrder stored = channelOrderRepository.findById(row.getId()).orElseThrow();
        assertEquals(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED, stored.getDeliveryStatus());
        assertEquals("MANUAL_REVIEW", stored.getLastErrorCode());
    }

    @Test
    void stableCardKeyUnitMappingSurvivesRepeatedPlanningAndReload() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("stable-units");
        listing(account, productId, "item-stable-units", true);
        insertCard(productId, null, "stable-card-1");
        insertCard(productId, null, "stable-card-2");
        insertCard(productId, null, "stable-card-3");
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-stable-units", "item-stable-units", null, null, 3, "30.00"));

        deliveryPlanner.plan(row.getId());
        Map<UUID, Integer> first = deliveryOperationRepository
                .findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(row.getId(),
                        SalesChannelDeliveryOperationType.SEND_MESSAGE).stream()
                .collect(java.util.stream.Collectors.toMap(SalesChannelDeliveryOperation::getCardKeyId,
                        SalesChannelDeliveryOperation::getUnitIndex));
        entityManager.clear();
        deliveryPlanner.plan(row.getId());
        deliveryPlanner.plan(row.getId());
        Map<UUID, Integer> second = deliveryOperationRepository
                .findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(row.getId(),
                        SalesChannelDeliveryOperationType.SEND_MESSAGE).stream()
                .collect(java.util.stream.Collectors.toMap(SalesChannelDeliveryOperation::getCardKeyId,
                        SalesChannelDeliveryOperation::getUnitIndex));

        assertEquals(3, first.size());
        assertEquals(first, second);
        assertEquals(3, second.size());
    }

    @Test
    void terminalStatusWithSameImmutableFingerprintIsHandledAndNotConflicted() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("terminal-fingerprint");
        listing(account, productId, "item-terminal-fingerprint", false);
        AgentOrder pending = order(account, "xy-terminal-fingerprint", "item-terminal-fingerprint",
                null, null, 1, "10.00");
        SalesChannelOrder row = importService.importOrder(account, pending);
        AgentOrder refunded = new AgentOrder(account.getProviderAccountId(), pending.externalOrderNo(), "refunded",
                pending.itemId(), pending.externalSkuId(), pending.specName(), pending.specValue(), pending.quantity(),
                pending.amount(), pending.currency(), pending.placedAt(), "2026-09-22T00:00:02Z");

        SalesChannelOrder updated = importService.importOrder(account, refunded);

        assertEquals(row.getId(), updated.getId());
        assertEquals("IMPORTED", updated.getIngestStatus().name());
        assertEquals("refunded", updated.getExternalStatus());
        assertEquals(OrderStatus.REFUNDED.name(), scalar("select status from orders where id = ?", String.class,
                row.getOrderId()));
        assertEquals("CANCELLED", scalar("select fulfillment_status from order_items where order_id = ?",
                String.class, row.getOrderId()));
        assertEquals(0, count("select count(*) from sales_channel_orders where ingest_status = 'SOURCE_CONFLICT' and id = ?",
                row.getId()));
    }

    @Test
    void terminalRefundAndFulfillmentConcurrencyNeverProducesRefundedSold() throws Exception {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("refund-race");
        listing(account, productId, "item-refund-race", false);
        insertCard(productId, null, "refund-race-card-1");
        insertCard(productId, null, "refund-race-card-2");
        insertCard(productId, null, "refund-race-card-3");
        AgentOrder pending = order(account, "xy-refund-race", "item-refund-race", null, null, 3, "30.00");
        SalesChannelOrder row = importService.importOrder(account, pending);
        AgentOrder refunded = new AgentOrder(account.getProviderAccountId(), pending.externalOrderNo(), "refunded",
                pending.itemId(), pending.externalSkuId(), pending.specName(), pending.specValue(), pending.quantity(),
                pending.amount(), pending.currency(), pending.placedAt(), "2026-09-22T00:00:02Z");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> refund = executor.submit(() -> {
                ready.countDown();
                await(start);
                importService.importOrder(account, refunded);
            });
            Future<?> fulfill = executor.submit(() -> {
                ready.countDown();
                await(start);
                fulfillmentService.fulfillOrder(row.getOrderId());
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            refund.get(30, TimeUnit.SECONDS);
            fulfill.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        String status = scalar("select status from orders where id = ?", String.class, row.getOrderId());
        int sold = count("select count(*) from card_keys where order_id = ? and status = 'SOLD'", row.getOrderId());
        assertFalse(OrderStatus.REFUNDED.name().equals(status) && sold > 0,
                "a refunded order must never retain SOLD CardKeys");
        if (OrderStatus.REFUNDED.name().equals(status)) {
            assertEquals(0, sold);
        } else {
            assertEquals(OrderStatus.DELIVERED.name(), status);
            assertEquals(3, sold);
            assertEquals(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED,
                    channelOrderRepository.findById(row.getId()).orElseThrow().getDeliveryStatus());
        }
    }

    private UnknownMessageFixture unknownMessageFixture(String suffix) {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account(suffix);
        listing(account, productId, "item-" + suffix, false);
        UUID cardId = insertCard(productId, null, "card-" + suffix);
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-" + suffix, "item-" + suffix, null, null, 1, "10.00"));
        fulfillmentService.fulfillOrder(row.getOrderId());

        String sendRequestId = "XIANYU_DELIVERY:" + row.getId() + ":0";
        SalesChannelDeliveryOperation send = persistOperation(row,
                SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.UNKNOWN, 0, cardId, sendRequestId,
                new LinkedHashMap<>());
        SalesChannelDeliveryOperation reconcile = persistOperation(row,
                SalesChannelDeliveryOperationType.RECONCILE_MESSAGE,
                SalesChannelDeliveryOperationStatus.PENDING, 0, null,
                "XIANYU_RECONCILE_MESSAGE:" + row.getId() + ":0",
                new LinkedHashMap<>(Map.of("target_request_id", sendRequestId)));
        row = channelOrderRepository.findById(row.getId()).orElseThrow();
        row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN);
        channelOrderRepository.saveAndFlush(row);
        return new UnknownMessageFixture(row, send, reconcile);
    }

    private UnknownConfirmFixture unknownConfirmFixture(String suffix) {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account(suffix);
        listing(account, productId, "item-" + suffix, false);
        UUID cardId = insertCard(productId, null, "card-" + suffix);
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-" + suffix, "item-" + suffix, null, null, 1, "10.00"));
        fulfillmentService.fulfillOrder(row.getOrderId());

        String sendRequestId = "XIANYU_DELIVERY:" + row.getId() + ":0";
        persistOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.SUCCEEDED, 0, cardId, sendRequestId,
                new LinkedHashMap<>(Map.of("status", "sent")));
        String confirmRequestId = "XIANYU_CONFIRM:" + row.getId();
        SalesChannelDeliveryOperation target = persistOperation(row,
                SalesChannelDeliveryOperationType.CONFIRM_DELIVERY,
                SalesChannelDeliveryOperationStatus.UNKNOWN, null, null, confirmRequestId,
                new LinkedHashMap<>());
        SalesChannelDeliveryOperation reconcile = persistOperation(row,
                SalesChannelDeliveryOperationType.RECONCILE_CONFIRM,
                SalesChannelDeliveryOperationStatus.RUNNING, null, null,
                "XIANYU_RECONCILE_CONFIRM:" + row.getId(),
                new LinkedHashMap<>(Map.of("target_request_id", confirmRequestId)));
        row = channelOrderRepository.findById(row.getId()).orElseThrow();
        row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.CONFIRM_UNKNOWN);
        channelOrderRepository.saveAndFlush(row);
        return new UnknownConfirmFixture(row, target, reconcile);
    }

    private void prepareStaleReconcileAtMax(SalesChannelDeliveryOperation operation,
                                            String targetRequestId, LocalDateTime startedAt) {
        operation.setStatus(SalesChannelDeliveryOperationStatus.RUNNING);
        operation.setAttemptCount(8);
        operation.setStartedAt(startedAt);
        operation.setResultJson(new LinkedHashMap<>(Map.of("target_request_id", targetRequestId)));
        deliveryOperationRepository.saveAndFlush(operation);
    }

    private SalesChannelDeliveryOperation persistOperation(
            SalesChannelOrder row,
            SalesChannelDeliveryOperationType type,
            SalesChannelDeliveryOperationStatus status,
            Integer unitIndex,
            UUID cardKeyId,
            String requestId,
            Map<String, Object> result) {
        SalesChannelDeliveryOperation operation = new SalesChannelDeliveryOperation();
        operation.setSalesChannelOrderId(row.getId());
        operation.setOperationType(type);
        operation.setUnitIndex(unitIndex);
        operation.setCardKeyId(cardKeyId);
        operation.setRequestId(requestId);
        operation.setPayloadFingerprint("b".repeat(64));
        operation.setStatus(status);
        operation.setResultJson(result);
        return deliveryOperationRepository.saveAndFlush(operation);
    }

    private IntegrationEnvelope<DeliveryReconcileData> reconcileResponse(String targetRequestId,
                                                                           Outcome targetOutcome,
                                                                           boolean targetRetryable) {
        return new IntegrationEnvelope<>("reconcile-request", Outcome.SUCCEEDED,
                new DeliveryReconcileData(targetRequestId, targetOutcome, "DELIVERY",
                        targetRetryable, Map.of("status", targetOutcome.name().toLowerCase())), null);
    }

    private SalesChannelDeliveryWorker deliveryWorker() {
        return new SalesChannelDeliveryWorker(deliveryPlanner, deliveryOperationRepository,
                deliveryClaimService, deliveryRecoveryService, deliveryStateService,
                channelOrderRepository, orderRepository, accountRepository,
                cardKeyRepository, orderItemRepository, providerClient);
    }

    private record UnknownMessageFixture(SalesChannelOrder row,
                                         SalesChannelDeliveryOperation send,
                                         SalesChannelDeliveryOperation reconcile) { }

    private record UnknownConfirmFixture(SalesChannelOrder row,
                                         SalesChannelDeliveryOperation target,
                                         SalesChannelDeliveryOperation reconcile) { }

    private SalesChannelDeliveryOperation deliveryOperation(
            SalesChannelOrder row,
            SalesChannelDeliveryOperationType type,
            SalesChannelDeliveryOperationStatus status,
            LocalDateTime nextAttemptAt) {
        SalesChannelDeliveryOperation operation = new SalesChannelDeliveryOperation();
        operation.setSalesChannelOrderId(row.getId());
        operation.setOperationType(type);
        operation.setUnitIndex(type == SalesChannelDeliveryOperationType.SEND_MESSAGE
                || type == SalesChannelDeliveryOperationType.RECONCILE_MESSAGE ? nextTestUnitIndex++ : null);
        operation.setRequestId("TEST_DELIVERY:" + UUID.randomUUID());
        operation.setPayloadFingerprint("a".repeat(64));
        operation.setStatus(status);
        operation.setNextAttemptAt(nextAttemptAt);
        operation.setResultJson(new LinkedHashMap<>());
        return deliveryOperationRepository.saveAndFlush(operation);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency test did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency test interrupted", exception);
        }
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

    private SalesChannelListingSpec insertListingSpec(SalesChannelListing listing, UUID productSpecId) {
        SalesChannelListingSpec mapping = new SalesChannelListingSpec();
        mapping.setSalesChannelListingId(listing.getId());
        mapping.setProductSpecId(productSpecId);
        mapping.setProviderMapping(Map.of());
        return listingSpecRepository.saveAndFlush(mapping);
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

    private UUID insertNormalRetryOrder(UUID productId) {
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, actual_amount, total_amount, status,
                    order_type, payment_method, payment_channel_code)
                values (?, current_timestamp, current_timestamp, 10.00, 10.00, 'PAID',
                    'NORMAL', 'ONLINE', 'phase4-normal-channel')
                """, orderId);
        jdbcTemplate.update("""
                insert into order_items (id, created_at, updated_at, order_id, product_id, product_no,
                    product_title, quantity, unit_price, cost_price_snapshot, retail_price_snapshot,
                    pricing_source, subtotal, delivery_type, stock_mode, fulfillment_status)
                values (?, current_timestamp, current_timestamp, ?, ?,
                    (select product_no from products where id = ?), 'Phase 4 normal product', 1,
                    10.00, 4.00, 10.00, 'RETAIL', 10.00, 'AUTO', 'CARD_KEY', 'PENDING')
                """, itemId, orderId, productId, productId);
        return orderId;
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private <T> T scalar(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }
}
