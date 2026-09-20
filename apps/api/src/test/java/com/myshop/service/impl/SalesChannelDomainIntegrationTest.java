package com.myshop.service.impl;

import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.Product;
import com.myshop.entity.ProductCategory;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingSpec;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.exception.BusinessException;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingSpecRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@SpringBootTest
class SalesChannelDomainIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ProductCategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductSpecRepository productSpecRepository;
    @Autowired private SalesChannelAccountRepository accountRepository;
    @Autowired private SalesChannelListingRepository listingRepository;
    @Autowired private SalesChannelListingSpecRepository listingSpecRepository;
    @Autowired private SalesChannelOperationRepository operationRepository;
    @Autowired private SalesChannelAccountServiceImpl accountService;
    @Autowired private SalesChannelListingServiceImpl listingService;
    @Autowired private SalesChannelOperationServiceImpl operationService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> false);
        registry.add("jwt.secret", () ->
                "sales-channel-integration-secret-with-at-least-64-bytes-and-random-looking-value-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
    }

    @BeforeEach
    void resetSalesChannelFixtures() {
        jdbcTemplate.update("DELETE FROM sales_channel_operations");
        jdbcTemplate.update("DELETE FROM sales_channel_listing_specs");
        jdbcTemplate.update("DELETE FROM sales_channel_listings");
        jdbcTemplate.update("DELETE FROM sales_channel_accounts");
        jdbcTemplate.update("DELETE FROM product_specs WHERE product_id IN "
                + "(SELECT id FROM products WHERE title LIKE 'sales-channel-test-%')");
        jdbcTemplate.update("DELETE FROM products WHERE title LIKE 'sales-channel-test-%'");
        jdbcTemplate.update("DELETE FROM product_categories WHERE name LIKE 'sales-channel-test-%'");
        entityManager.clear();
    }

    @Test
    void accountAndListingUpsertsAreStableAndDatabaseKeysRejectDuplicates() {
        Fixture fixture = fixture(false);
        SalesChannelAccount repeated = accountService.upsert(SalesChannelProvider.XIANYU,
                fixture.account().getProviderAccountId(), "renamed", SalesChannelAccountStatus.AUTH_REQUIRED,
                Map.of("supports_sync", true), LocalDateTime.now());
        assertEquals(fixture.account().getId(), repeated.getId());
        assertEquals("renamed", repeated.getDisplayName());
        assertEquals(SalesChannelAccountStatus.AUTH_REQUIRED, repeated.getStatus());
        assertTrue(repeated.isEnabled());

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO sales_channel_accounts(id, provider, provider_account_id, status)
                VALUES (?, 'XIANYU', ?, 'CONNECTED')
                """, UUID.randomUUID(), fixture.account().getProviderAccountId()));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO sales_channel_listings(id, sales_channel_account_id, product_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), fixture.account().getId(), fixture.product().getId()));

        SalesChannelListing secondCall = listingService.ensureDraft(fixture.account().getId(), fixture.product().getId());
        assertEquals(fixture.listing().getId(), secondCall.getId());
        assertEquals(0, listingSpecRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(secondCall.getId()).size());

        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("DELETE FROM products WHERE id = ?", fixture.product().getId()));
        assertEquals(1, listingRepository.count());
        assertNotNull(productRepository.findById(fixture.product().getId()).orElse(null));
    }

    @Test
    void accountCanBeDisabledWithoutDeletingItAndLaterUpsertKeepsDisabledState() {
        Fixture fixture = fixture(false);
        accountService.setEnabled(fixture.account().getId(), false);
        SalesChannelAccount updated = accountService.upsert(SalesChannelProvider.XIANYU,
                fixture.account().getProviderAccountId(), "still retained", SalesChannelAccountStatus.CONNECTED,
                null, null);

        assertEquals(fixture.account().getId(), updated.getId());
        assertFalse(updated.isEnabled());
        assertEquals(1, accountRepository.count());
    }

    @Test
    void concurrentFirstAccountUpsertsCreateOneStableIdentity() throws Exception {
        String providerAccountId = "concurrent-" + UUID.randomUUID();
        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(callers)) {
            List<java.util.concurrent.Future<UUID>> results = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                        return accountService.upsert(SalesChannelProvider.XIANYU, providerAccountId,
                                        "concurrent caller " + index, SalesChannelAccountStatus.CONNECTED,
                                        Map.of("caller", index), LocalDateTime.now())
                                .getId();
                    }))
                    .toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Set<UUID> ids = results.stream().map(result -> {
                try {
                    return result.get(20, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).collect(Collectors.toSet());
            assertEquals(1, ids.size());
            assertEquals(1, accountRepository.count());
        }
    }

    @Test
    void activeSpecMappingsUseStableProductSpecIdsAndNeverInventRowsForProductsWithoutSpecs() {
        Fixture noSpecs = fixture(false);
        assertTrue(listingSpecRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(noSpecs.listing().getId())
                .isEmpty());

        Fixture withSpecs = fixture(true);
        UUID visibleSpecId = addSpec(withSpecs.product().getId(), "visible", true, 0);
        UUID hiddenButActiveSpecId = addSpec(withSpecs.product().getId(), "hidden", false, 1);
        UUID retiredSpecId = addSpec(withSpecs.product().getId(), "retired", true, 2);
        ProductSpec retired = productSpecRepository.findById(retiredSpecId).orElseThrow();
        retired.setIsDeleted(1);
        productSpecRepository.saveAndFlush(retired);

        listingService.ensureDraft(withSpecs.account().getId(), withSpecs.product().getId());
        List<SalesChannelListingSpec> mappings = listingSpecRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(withSpecs.listing().getId());
        Set<UUID> mappedSpecIds = mappings.stream().map(SalesChannelListingSpec::getProductSpecId)
                .collect(Collectors.toSet());
        assertEquals(Set.of(visibleSpecId, hiddenButActiveSpecId), mappedSpecIds);
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO sales_channel_listing_specs(id, sales_channel_listing_id, product_spec_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), withSpecs.listing().getId(), visibleSpecId));
        entityManager.clear();
        assertTrue(listingSpecRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(
                withSpecs.listing().getId()).stream().anyMatch(mapping -> visibleSpecId.equals(mapping.getProductSpecId())));
    }

    @Test
    void providerConfigAndDesiredStateOnlyAdvanceRevisionWhenValuesChangeAndSupersedePendingWork() {
        Fixture fixture = fixture(false);
        assertEquals(1, listingService.setDesiredState(fixture.listing().getId(),
                SalesChannelListingDesiredState.ONLINE).getDesiredRevision());
        SalesChannelOperation first = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of("snapshot", "initial"));
        SalesChannelOperation duplicate = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of("snapshot", "must not replace original"));
        assertEquals(first.getId(), duplicate.getId());
        assertEquals(first.getIdempotencyKey(), duplicate.getIdempotencyKey());
        assertEquals(Map.of("snapshot", "initial"), duplicate.getPayload());
        assertTrue(first.getIdempotencyKey().matches("XIANYU:" + fixture.listing().getId()
                + ":PUBLISH:1"));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO sales_channel_operations(id, sales_channel_listing_id, operation_type,
                    status, desired_revision, idempotency_key)
                VALUES (?, ?, 'PUBLISH', 'PENDING', 0, ?)
                """, UUID.randomUUID(), fixture.listing().getId(), first.getIdempotencyKey()));

        Map<String, Object> config = Map.of("title", "listing title", "category", "general");
        SalesChannelListing revisionOne = listingService.updateProviderConfig(fixture.listing().getId(), config);
        assertEquals(2, revisionOne.getDesiredRevision());
        assertEquals(SalesChannelOperationStatus.SUPERSEDED,
                operationRepository.findById(first.getId()).orElseThrow().getStatus());
        assertEquals(2, listingService.updateProviderConfig(fixture.listing().getId(), new LinkedHashMap<>(config))
                .getDesiredRevision());

        SalesChannelOperation second = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of());
        SalesChannelListing revisionThree = listingService.setDesiredState(fixture.listing().getId(),
                SalesChannelListingDesiredState.OFFLINE);
        assertEquals(3, revisionThree.getDesiredRevision());
        assertEquals(3, listingService.setDesiredState(fixture.listing().getId(),
                SalesChannelListingDesiredState.OFFLINE).getDesiredRevision());
        assertEquals(SalesChannelOperationStatus.SUPERSEDED,
                operationRepository.findById(second.getId()).orElseThrow().getStatus());
    }

    @Test
    void runningUnknownSucceededAndFailedOperationsAreNotSupersededByNewRevision() {
        Fixture runningFixture = fixture(false);
        listingService.setDesiredState(runningFixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        SalesChannelOperation running = operationService.request(runningFixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of());
        assertTrue(operationService.markRunning(running.getId()));
        listingService.setDesiredState(runningFixture.listing().getId(), SalesChannelListingDesiredState.OFFLINE);
        assertEquals(SalesChannelOperationStatus.RUNNING,
                operationRepository.findById(running.getId()).orElseThrow().getStatus());

        Fixture unknownFixture = fixture(false);
        SalesChannelOperation unknown = operationService.request(unknownFixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of());
        assertTrue(operationService.markRunning(unknown.getId()));
        assertTrue(operationService.markUnknown(unknown.getId(), "TIMEOUT", "outcome not confirmed"));
        listingService.setDesiredState(unknownFixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        assertEquals(SalesChannelOperationStatus.UNKNOWN,
                operationRepository.findById(unknown.getId()).orElseThrow().getStatus());

        Fixture succeededFixture = fixture(false);
        SalesChannelOperation succeeded = operationService.request(succeededFixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of());
        assertTrue(operationService.markRunning(succeeded.getId()));
        assertTrue(operationService.markSucceeded(succeeded.getId(), Map.of("confirmed", true), null));
        listingService.updateProviderConfig(succeededFixture.listing().getId(), Map.of("title", "next revision"));
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(succeeded.getId()).orElseThrow().getStatus());

        Fixture failedFixture = fixture(false);
        SalesChannelOperation failed = operationService.request(failedFixture.listing().getId(),
                SalesChannelOperationType.OFFLINE, Map.of());
        assertTrue(operationService.markRunning(failed.getId()));
        assertTrue(operationService.markFailed(failed.getId(), "PROVIDER_ERROR", "operation failed"));
        listingService.setDesiredState(failedFixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        assertEquals(SalesChannelOperationStatus.FAILED,
                operationRepository.findById(failed.getId()).orElseThrow().getStatus());

        Fixture pendingFixture = fixture(false);
        SalesChannelOperation pending = operationService.request(pendingFixture.listing().getId(),
                SalesChannelOperationType.DELETE, Map.of());
        listingService.setDesiredState(pendingFixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        assertEquals(SalesChannelOperationStatus.SUPERSEDED,
                operationRepository.findById(pending.getId()).orElseThrow().getStatus());
    }

    @Test
    void aListingCannotRunTwoOperationsAtOnce() {
        Fixture fixture = fixture(false);
        listingService.setDesiredState(fixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        SalesChannelOperation publish = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of());
        SalesChannelOperation sync = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of());

        assertTrue(operationService.markRunning(publish.getId()));
        assertFalse(operationService.markRunning(sync.getId()));
        assertEquals(SalesChannelOperationStatus.PENDING,
                operationRepository.findById(sync.getId()).orElseThrow().getStatus());
        assertTrue(operationService.findReadyPending(LocalDateTime.now(), 10).isEmpty());
        assertTrue(operationService.markSucceeded(publish.getId(), Map.of("published", true), null));
        assertEquals(List.of(sync.getId()), operationService.findReadyPending(LocalDateTime.now(), 10)
                .stream().map(SalesChannelOperation::getId).toList());
        assertTrue(operationService.markRunning(sync.getId()));
        assertTrue(operationService.markSucceeded(sync.getId(), Map.of("synced", true), null));
    }

    @Test
    void failedReconciliationKeepsUnknownUntilALaterSuccessfulReconciliation() {
        Fixture fixture = fixture(false);
        SalesChannelOperation ambiguous = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of());
        assertTrue(operationService.markRunning(ambiguous.getId()));
        assertTrue(operationService.markUnknown(ambiguous.getId(), "TIMEOUT", "provider result is unknown"));

        SalesChannelOperation failedReconciliation = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.RECONCILE, Map.of());
        assertTrue(operationService.markRunning(failedReconciliation.getId()));
        assertTrue(operationService.markFailed(failedReconciliation.getId(), "RECONCILE_FAILED", "not confirmed"));
        SalesChannelListing stillUnknown = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(SalesChannelListingStatus.UNKNOWN, stillUnknown.getStatus());
        assertEquals("TIMEOUT", stillUnknown.getLastErrorCode());
        assertThrows(IllegalStateException.class, () -> operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.OFFLINE, Map.of()));

        listingService.updateProviderConfig(fixture.listing().getId(), Map.of("title", "recheck"));
        SalesChannelOperation successfulReconciliation = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.RECONCILE, Map.of());
        assertTrue(operationService.markRunning(successfulReconciliation.getId()));
        assertTrue(operationRepository.findById(successfulReconciliation.getId()).orElseThrow().getStartedAt()
                .isAfter(operationRepository.findById(ambiguous.getId()).orElseThrow().getFinishedAt()));
        assertTrue(operationService.markSucceeded(successfulReconciliation.getId(), Map.of("checked", true), null));
        SalesChannelListing reconciled = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(SalesChannelListingStatus.OFFLINE, reconciled.getStatus());
        assertNull(reconciled.getLastErrorCode());
    }

    @Test
    void staleCompletionCannotApplyOldRevisionAndLaterQueueCanApplyCurrent() {
        Fixture fixture = fixture(false);
        SalesChannelOperation stale = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of());
        assertTrue(operationService.markRunning(stale.getId()));

        listingService.updateProviderConfig(fixture.listing().getId(), Map.of("title", "new revision"));
        SalesChannelOperation current = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of());
        assertFalse(operationService.markRunning(current.getId()));
        assertTrue(operationService.markSucceeded(stale.getId(), Map.of("late", true), "stale-fingerprint"));
        SalesChannelListing afterStaleCompletion = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(0, afterStaleCompletion.getAppliedRevision());
        assertNull(afterStaleCompletion.getAppliedFingerprint());
        assertEquals(SalesChannelListingStatus.SYNCING, afterStaleCompletion.getStatus());

        assertTrue(operationService.markRunning(current.getId()));
        assertTrue(operationService.markSucceeded(current.getId(), Map.of("remote", "confirmed"), "new-fingerprint"));
        SalesChannelListing applied = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(1, applied.getAppliedRevision());
        assertEquals("new-fingerprint", applied.getAppliedFingerprint());
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                UPDATE sales_channel_listings SET applied_revision = 0 WHERE id = ?
                """, fixture.listing().getId()));

        assertFalse(operationService.markSucceeded(stale.getId(), Map.of("duplicate", true), "duplicate-fingerprint"));
        assertEquals(applied.getAppliedRevision(),
                listingRepository.findById(fixture.listing().getId()).orElseThrow().getAppliedRevision());

        listingService.updateProviderConfig(fixture.listing().getId(), Map.of("title", "ambiguous snapshot"));
        SalesChannelOperation ambiguous = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of());
        assertTrue(operationService.markRunning(ambiguous.getId()));

        listingService.updateProviderConfig(fixture.listing().getId(), Map.of("title", "newer desired snapshot"));
        SalesChannelOperation waiting = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.DELETE, Map.of());
        assertTrue(operationService.markUnknown(ambiguous.getId(), "RESULT_UNKNOWN", "needs reconciliation"));
        assertEquals(SalesChannelOperationStatus.UNKNOWN,
                operationRepository.findById(ambiguous.getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelListingStatus.SYNCING,
                listingRepository.findById(fixture.listing().getId()).orElseThrow().getStatus());
        assertTrue(operationService.findReadyPending(LocalDateTime.now(), 20).isEmpty());
        assertFalse(operationService.markRunning(waiting.getId()));
        assertThrows(IllegalStateException.class, () -> operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.OFFLINE, Map.of("another", "not allowed")));

        SalesChannelOperation reconciliation = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.RECONCILE, Map.of());
        assertEquals(List.of(reconciliation.getId()), operationService.findReadyPending(LocalDateTime.now(), 20)
                .stream().map(SalesChannelOperation::getId).toList());
        assertTrue(operationService.markRunning(reconciliation.getId()));
        assertTrue(operationService.markSucceeded(reconciliation.getId(), Map.of("checked", true), null));
        assertEquals(SalesChannelOperationStatus.UNKNOWN,
                operationRepository.findById(ambiguous.getId()).orElseThrow().getStatus());
        assertEquals(List.of(waiting.getId()), operationService.findReadyPending(LocalDateTime.now(), 20)
                .stream().map(SalesChannelOperation::getId).toList());
        assertTrue(operationService.markRunning(waiting.getId()));
    }

    @Test
    void durableQueueSelectsDuePendingOperationsInOrderAndSupportsSkipLockedClaims() {
        Fixture fixture = fixture(false);
        listingService.setDesiredState(fixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        SalesChannelOperation first = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of());
        SalesChannelOperation second = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of());
        SalesChannelOperation future = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.RECONCILE, Map.of());
        LocalDateTime now = LocalDateTime.now();
        first.setNextAttemptAt(now.minusMinutes(2));
        second.setNextAttemptAt(now.minusMinutes(1));
        future.setNextAttemptAt(now.plusHours(1));
        operationRepository.saveAllAndFlush(List.of(first, second, future));

        assertEquals(List.of(first.getId(), second.getId()), operationService.findReadyPending(now, 10)
                .stream().map(SalesChannelOperation::getId).toList());
        List<UUID> claimed = new TransactionTemplate(transactionManager).execute(status ->
                operationRepository.findReadyPendingForUpdate(now, 10).stream()
                        .map(SalesChannelOperation::getId).toList());
        assertEquals(List.of(first.getId(), second.getId()), claimed);
    }

    @Test
    void deletedProductsCannotAcquireListings() {
        Fixture fixture = fixture(false);
        Product deleted = fixture.product();
        deleted.setIsDeleted(1);
        productRepository.saveAndFlush(deleted);

        assertThrows(BusinessException.class,
                () -> listingService.ensureDraft(fixture.account().getId(), deleted.getId()));
    }

    private Fixture fixture(boolean withSpecs) {
        ProductCategory category = new ProductCategory();
        category.setName("sales-channel-test-" + UUID.randomUUID());
        category = categoryRepository.saveAndFlush(category);

        Product product = new Product();
        product.setProductNo(productRepository.nextProductNoValue());
        product.setTitle("sales-channel-test-" + UUID.randomUUID());
        product.setRetailPrice(new BigDecimal("10.00"));
        product.setCostPrice(new BigDecimal("5.00"));
        product.setCategoryId(category.getId());
        product.setSpecEnabled(withSpecs);
        product.setEnabled(true);
        product = productRepository.saveAndFlush(product);

        SalesChannelAccount account = accountService.upsert(SalesChannelProvider.XIANYU,
                "account-" + UUID.randomUUID(), "test account", SalesChannelAccountStatus.CONNECTED,
                Map.of(), LocalDateTime.now());
        SalesChannelListing listing = listingService.ensureDraft(account.getId(), product.getId());
        return new Fixture(account, product, listing);
    }

    private UUID addSpec(UUID productId, String name, boolean visible, int order) {
        ProductSpec spec = new ProductSpec();
        spec.setId(UUID.randomUUID());
        spec.setProductId(productId);
        spec.setName(name);
        spec.setRetailPrice(new BigDecimal("10.00"));
        spec.setCostPrice(new BigDecimal("5.00"));
        spec.setVisible(visible);
        spec.setSortOrder(order);
        return productSpecRepository.saveAndFlush(spec).getId();
    }

    private record Fixture(SalesChannelAccount account, Product product, SalesChannelListing listing) {}
}
