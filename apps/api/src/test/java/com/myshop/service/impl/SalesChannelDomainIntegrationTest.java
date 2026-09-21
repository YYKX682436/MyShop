package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.StockMode;
import com.myshop.entity.CardKey;
import com.myshop.entity.Product;
import com.myshop.entity.ProductCategory;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingSpec;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.exception.BusinessException;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingSpecRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.service.ManualStockService;
import com.myshop.service.SalesChannelAdminService;
import com.myshop.service.SalesChannelDesiredStatePlanner;
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
import java.time.temporal.ChronoUnit;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    @Autowired private CardKeyRepository cardKeyRepository;
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
    @Autowired private SalesChannelDesiredStatePlanner desiredStatePlanner;
    @Autowired private ManualStockService manualStockService;
    @Autowired private SalesChannelAdminService salesChannelAdminService;
    @Autowired private ObjectMapper objectMapper;

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
        jdbcTemplate.update("DELETE FROM card_keys WHERE product_id IN "
                + "(SELECT id FROM products WHERE title LIKE 'sales-channel-test-%')");
        jdbcTemplate.update("DELETE FROM manual_stocks WHERE product_id IN "
                + "(SELECT id FROM products WHERE title LIKE 'sales-channel-test-%')");
        jdbcTemplate.update("DELETE FROM product_specs WHERE product_id IN "
                + "(SELECT id FROM products WHERE title LIKE 'sales-channel-test-%')");
        jdbcTemplate.update("DELETE FROM products WHERE title LIKE 'sales-channel-test-%'");
        jdbcTemplate.update("DELETE FROM product_categories WHERE name LIKE 'sales-channel-test-%'");
        entityManager.clear();
    }

    @Test
    void accountAndListingUpsertsAreStableAndDatabaseKeysRejectDuplicates() {
        Fixture fixture = fixture(false);
        assertFalse(fixture.listing().isEnabled());
        assertNull(fixture.listing().getDesiredFingerprint());
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

        fixture.listing().setStatus(SalesChannelListingStatus.RELIST_REQUIRED);
        listingRepository.saveAndFlush(fixture.listing());
        assertEquals(SalesChannelListingStatus.RELIST_REQUIRED,
                listingRepository.findById(fixture.listing().getId()).orElseThrow().getStatus());

        SalesChannelListing secondCall = listingService.ensureDraft(fixture.account().getId(), fixture.product().getId());
        assertEquals(fixture.listing().getId(), secondCall.getId());
        assertEquals(0, listingSpecRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(secondCall.getId()).size());

        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("DELETE FROM products WHERE id = ?", fixture.product().getId()));
        assertEquals(1, listingRepository.count());
        assertNotNull(productRepository.findById(fixture.product().getId()).orElse(null));
    }

    @Test
    void incompleteDraftConfigCanBeSavedWithoutCreatingAnOperation() {
        Fixture fixture = fixture(false);
        Map<String, Object> partial = Map.of("titleOverride", "Draft title");
        var config = objectMapper.convertValue(partial, com.myshop.saleschannel.xianyu.model.XianyuProviderConfig.class);

        var saved = salesChannelAdminService.updateXianyuListing(
                fixture.product().getId(), fixture.account().getId(), config);

        assertFalse(saved.isEnabled());
        assertEquals("Draft title", saved.providerConfig().titleOverride());
        assertTrue(operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(
                UUID.fromString(saved.id())).isEmpty());
    }

    @Test
    void enablingWithoutAddressRollsBackAndCreatesNoOperation() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        savePublishConfig(fixture, false, true);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> desiredStatePlanner.setEnabled(fixture.listing().getId(), true));

        assertTrue(failure.getMessage().contains("address"));
        assertFalse(listingRepository.findById(fixture.listing().getId()).orElseThrow().isEnabled());
        assertTrue(operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(
                fixture.listing().getId()).isEmpty());
    }

    @Test
    void enablingWithoutCategoryRollsBackAndCreatesNoOperation() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        savePublishConfig(fixture, true, false);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> desiredStatePlanner.setEnabled(fixture.listing().getId(), true));

        assertTrue(failure.getMessage().contains("platformCategoryId"));
        assertTrue(failure.getMessage().contains("platformTbCategoryId"));
        assertFalse(listingRepository.findById(fixture.listing().getId()).orElseThrow().isEnabled());
        assertTrue(operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(
                fixture.listing().getId()).isEmpty());
    }

    @Test
    void completePreflightAllowsPublishAndAddressExpectedTextFallsBackToAddress() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        savePublishConfig(fixture, true, true);

        SalesChannelListing enabled = desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        List<SalesChannelOperation> operations = operationRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(enabled.getId());

        assertTrue(enabled.isEnabled());
        assertEquals(1, operations.size());
        assertEquals(SalesChannelOperationType.PUBLISH, operations.getFirst().getOperationType());
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) operations.getFirst().getPayload().get("desired_snapshot");
        assertEquals("East Lake", snapshot.get("address"));
        assertEquals("East Lake", snapshot.get("addressExpectedText"));
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
    void desiredFingerprintDeduplicatesDriftAndFishShopStockZeroUsesSyncWithoutRepublish() {
        Fixture fixture = fixture(false);
        accountService.upsert(SalesChannelProvider.XIANYU, fixture.account().getProviderAccountId(),
                fixture.account().getDisplayName(), SalesChannelAccountStatus.CONNECTED,
                Map.of("account_type", "FISH_SHOP"), LocalDateTime.now());
        savePublishConfigWithoutPlanning(fixture, true, true);
        Product product = productRepository.findById(fixture.product().getId()).orElseThrow();
        product.setStockMode(StockMode.MANUAL);
        productRepository.saveAndFlush(product);
        manualStockService.setAvailable(product.getId(), null, 10);

        SalesChannelListing firstPlan = desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        assertEquals(1, firstPlan.getDesiredRevision());
        desiredStatePlanner.refresh(fixture.listing().getId());
        desiredStatePlanner.refresh(fixture.listing().getId());
        assertEquals(1, listingRepository.findById(fixture.listing().getId()).orElseThrow().getDesiredRevision());
        List<SalesChannelOperation> firstRevision = operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(fixture.listing().getId(), 1);
        assertEquals(1, firstRevision.size());
        SalesChannelOperation publish = firstRevision.get(0);
        assertEquals(SalesChannelOperationType.PUBLISH, publish.getOperationType());
        @SuppressWarnings("unchecked")
        Map<String, Object> initialSnapshot = (Map<String, Object>) publish.getPayload().get("desired_snapshot");
        assertEquals(10, ((Number) initialSnapshot.get("quantity")).intValue());

        assertTrue(operationService.markRunning(publish.getId()));
        assertTrue(operationService.markSucceeded(publish.getId(), Map.of("item_id", "fish-shop-item-1"),
                String.valueOf(publish.getPayload().get("desired_fingerprint"))));

        manualStockService.setAvailable(product.getId(), null, 0);
        SalesChannelListing zeroPlan = desiredStatePlanner.refresh(fixture.listing().getId());
        assertEquals(2, zeroPlan.getDesiredRevision());
        SalesChannelOperation zeroSync = operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(fixture.listing().getId(), 2)
                .stream().filter(operation -> operation.getOperationType() == SalesChannelOperationType.SYNC)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> zeroSnapshot = (Map<String, Object>) zeroSync.getPayload().get("desired_snapshot");
        assertEquals(0, ((Number) zeroSnapshot.get("quantity")).intValue());

        manualStockService.setAvailable(product.getId(), null, 5);
        SalesChannelListing fivePlan = desiredStatePlanner.refresh(fixture.listing().getId());
        assertEquals(3, fivePlan.getDesiredRevision());
        assertEquals(SalesChannelOperationStatus.SUPERSEDED,
                operationRepository.findById(zeroSync.getId()).orElseThrow().getStatus());
        List<SalesChannelOperation> currentOperations = operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(fixture.listing().getId(), 3);
        assertEquals(1, currentOperations.size());
        assertEquals(SalesChannelOperationType.SYNC, currentOperations.get(0).getOperationType());
        @SuppressWarnings("unchecked")
        Map<String, Object> fiveSnapshot = (Map<String, Object>) currentOperations.get(0)
                .getPayload().get("desired_snapshot");
        assertEquals(5, ((Number) fiveSnapshot.get("quantity")).intValue());

        desiredStatePlanner.refresh(fixture.listing().getId());
        assertEquals(3, listingRepository.findById(fixture.listing().getId()).orElseThrow().getDesiredRevision());
        assertEquals(1, operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(fixture.listing().getId(), 3).size());
    }

    @Test
    void cardKeySkuStockCapChangesOnlyChannelSnapshotAndProviderFingerprint() {
        Fixture fixture = fixture(true);
        List<UUID> specIds = addStockCapSpecs(fixture);
        Product product = productRepository.findById(fixture.product().getId()).orElseThrow();
        product.setStockMode(StockMode.CARD_KEY);
        productRepository.saveAndFlush(product);
        addAvailableCardKeys(product.getId(), specIds.get(0), 5);
        addAvailableCardKeys(product.getId(), specIds.get(1), 4);
        addAvailableCardKeys(product.getId(), specIds.get(2), 4);
        assertAvailableCardKeys(product.getId(), specIds, List.of(5, 4, 4));

        verifiedAccount(fixture, "FISH_SHOP");
        desiredStatePlanner.saveConfig(fixture.listing().getId(), publishConfigWithStockCap(fixture, null));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);

        SalesChannelOperation publish = operationAtRevision(fixture, 1, SalesChannelOperationType.PUBLISH);
        assertSkuSnapshot(publish, List.of(5, 4, 4), 13);
        SalesChannelListing nullCapListing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        String nullCapFingerprint = nullCapListing.getDesiredFingerprint();

        salesChannelAdminService.updateXianyuListing(fixture.product().getId(), fixture.account().getId(),
                publishConfigWithStockCap(fixture, null));
        SalesChannelListing repeatedNullCap = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(1, repeatedNullCap.getDesiredRevision());
        assertEquals(nullCapFingerprint, repeatedNullCap.getDesiredFingerprint());
        assertEquals(1, operationRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(fixture.listing().getId()).size());
        assertAvailableCardKeys(product.getId(), specIds, List.of(5, 4, 4));

        assertTrue(operationService.markRunning(publish.getId()));
        assertTrue(operationService.markSucceeded(publish.getId(), Map.of("item_id", "stock-cap-test-item"),
                String.valueOf(publish.getPayload().get("desired_fingerprint"))));

        salesChannelAdminService.updateXianyuListing(fixture.product().getId(), fixture.account().getId(),
                publishConfigWithStockCap(fixture, 1));
        SalesChannelListing capOneListing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(2, capOneListing.getDesiredRevision());
        assertEquals(1, ((Number) capOneListing.getProviderConfig().get("stockCapPerSku")).intValue());
        assertNotEquals(nullCapFingerprint, capOneListing.getDesiredFingerprint());
        assertSkuSnapshot(operationAtRevision(fixture, 2, SalesChannelOperationType.SYNC),
                List.of(1, 1, 1), 3);
        assertAvailableCardKeys(product.getId(), specIds, List.of(5, 4, 4));

        String capOneFingerprint = capOneListing.getDesiredFingerprint();
        salesChannelAdminService.updateXianyuListing(fixture.product().getId(), fixture.account().getId(),
                publishConfigWithStockCap(fixture, 1));
        SalesChannelListing repeatedCapOne = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(2, repeatedCapOne.getDesiredRevision());
        assertEquals(capOneFingerprint, repeatedCapOne.getDesiredFingerprint());
        assertEquals(1, operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(fixture.listing().getId(), 2)
                .size());
        assertAvailableCardKeys(product.getId(), specIds, List.of(5, 4, 4));

        salesChannelAdminService.updateXianyuListing(fixture.product().getId(), fixture.account().getId(),
                publishConfigWithStockCap(fixture, 0));
        SalesChannelListing capZeroListing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(3, capZeroListing.getDesiredRevision());
        assertNotEquals(capOneFingerprint, capZeroListing.getDesiredFingerprint());
        assertSkuSnapshot(operationAtRevision(fixture, 3, SalesChannelOperationType.SYNC),
                List.of(0, 0, 0), 0);
        assertAvailableCardKeys(product.getId(), specIds, List.of(5, 4, 4));

        String capZeroFingerprint = capZeroListing.getDesiredFingerprint();
        salesChannelAdminService.updateXianyuListing(fixture.product().getId(), fixture.account().getId(),
                publishConfigWithStockCap(fixture, 10));
        SalesChannelListing capTenListing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(4, capTenListing.getDesiredRevision());
        assertNotEquals(capZeroFingerprint, capTenListing.getDesiredFingerprint());
        assertSkuSnapshot(operationAtRevision(fixture, 4, SalesChannelOperationType.SYNC),
                List.of(5, 4, 4), 13);
        assertAvailableCardKeys(product.getId(), specIds, List.of(5, 4, 4));
    }

    @Test
    void cardKeyInventoryDriftAtFixedCapCreatesSyncAndRestoringInventoryCreatesAnotherSync() {
        Fixture fixture = fixture(true);
        List<UUID> specIds = addStockCapSpecs(fixture);
        Product product = productRepository.findById(fixture.product().getId()).orElseThrow();
        product.setStockMode(StockMode.CARD_KEY);
        productRepository.saveAndFlush(product);
        addAvailableCardKeys(product.getId(), specIds.get(0), 5);
        addAvailableCardKeys(product.getId(), specIds.get(1), 4);
        addAvailableCardKeys(product.getId(), specIds.get(2), 4);
        verifiedAccount(fixture, "FISH_SHOP");
        desiredStatePlanner.saveConfig(fixture.listing().getId(), publishConfigWithStockCap(fixture, 1));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);

        SalesChannelOperation publish = operationAtRevision(fixture, 1, SalesChannelOperationType.PUBLISH);
        assertSkuSnapshot(publish, List.of(1, 1, 1), 3);
        assertTrue(operationService.markRunning(publish.getId()));
        assertTrue(operationService.markSucceeded(publish.getId(), Map.of("item_id", "stock-drift-test-item"),
                String.valueOf(publish.getPayload().get("desired_fingerprint"))));
        String fullStockFingerprint = listingRepository.findById(fixture.listing().getId()).orElseThrow()
                .getDesiredFingerprint();
        assertAvailableCardKeys(product.getId(), specIds, List.of(5, 4, 4));

        jdbcTemplate.update("UPDATE card_keys SET status = 'INVALID' "
                        + "WHERE product_id = ? AND spec_id = ? AND status = 'AVAILABLE'",
                product.getId(), specIds.get(0));
        assertAvailableCardKeys(product.getId(), specIds, List.of(0, 4, 4));
        SalesChannelListing noAStock = desiredStatePlanner.refresh(fixture.listing().getId());
        assertEquals(2, noAStock.getDesiredRevision());
        assertNotEquals(fullStockFingerprint, noAStock.getDesiredFingerprint());
        assertSkuSnapshot(operationAtRevision(fixture, 2, SalesChannelOperationType.SYNC),
                List.of(0, 1, 1), 2);

        jdbcTemplate.update("UPDATE card_keys SET status = 'AVAILABLE' "
                        + "WHERE product_id = ? AND spec_id = ? AND status = 'INVALID'",
                product.getId(), specIds.get(0));
        assertAvailableCardKeys(product.getId(), specIds, List.of(5, 4, 4));
        SalesChannelListing restoredAStock = desiredStatePlanner.refresh(fixture.listing().getId());
        assertEquals(3, restoredAStock.getDesiredRevision());
        assertEquals(fullStockFingerprint, restoredAStock.getDesiredFingerprint());
        assertSkuSnapshot(operationAtRevision(fixture, 3, SalesChannelOperationType.SYNC),
                List.of(1, 1, 1), 3);
    }

    @Test
    void manualSkuStockUsesTheSameChannelCapWithoutChangingManualAvailability() {
        Fixture fixture = fixture(true);
        List<UUID> specIds = addStockCapSpecs(fixture);
        Product product = productRepository.findById(fixture.product().getId()).orElseThrow();
        product.setStockMode(StockMode.MANUAL);
        productRepository.saveAndFlush(product);
        manualStockService.setAvailable(product.getId(), specIds.get(0), 5);
        manualStockService.setAvailable(product.getId(), specIds.get(1), 4);
        manualStockService.setAvailable(product.getId(), specIds.get(2), 4);
        verifiedAccount(fixture, "FISH_SHOP");
        desiredStatePlanner.saveConfig(fixture.listing().getId(), publishConfigWithStockCap(fixture, 1));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);

        assertSkuSnapshot(operationAtRevision(fixture, 1, SalesChannelOperationType.PUBLISH),
                List.of(1, 1, 1), 3);
        assertEquals(5, manualStockService.getAvailable(product.getId(), specIds.get(0)));
        assertEquals(4, manualStockService.getAvailable(product.getId(), specIds.get(1)));
        assertEquals(4, manualStockService.getAvailable(product.getId(), specIds.get(2)));
    }

    @Test
    void unlimitedSkuStockContinuesUsingUnlimitedStockQuantityInsteadOfPerSkuCap() {
        Fixture fixture = fixture(true);
        List<UUID> specIds = addStockCapSpecs(fixture);
        Product product = productRepository.findById(fixture.product().getId()).orElseThrow();
        product.setStockMode(StockMode.UNLIMITED);
        productRepository.saveAndFlush(product);
        verifiedAccount(fixture, "FISH_SHOP");
        desiredStatePlanner.saveConfig(fixture.listing().getId(), publishConfigWithStockCap(fixture, 0));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);

        assertSkuSnapshot(operationAtRevision(fixture, 1, SalesChannelOperationType.PUBLISH),
                List.of(999, 999, 999), 2997);
        assertTrue(specIds.stream().allMatch(specId -> cardKeyRepository
                .countByProductIdAndSpecIdAndStatus(product.getId(), specId, CardKeyStatus.AVAILABLE) == 0));
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
        bindExistingItem(unknownFixture);
        SalesChannelOperation unknown = operationService.request(unknownFixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of());
        assertTrue(operationService.markRunning(unknown.getId()));
        assertTrue(operationService.markUnknown(unknown.getId(), "TIMEOUT", "outcome not confirmed"));
        listingService.setDesiredState(unknownFixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        assertEquals(SalesChannelOperationStatus.UNKNOWN,
                operationRepository.findById(unknown.getId()).orElseThrow().getStatus());

        Fixture succeededFixture = fixture(false);
        bindExistingItem(succeededFixture);
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
        assertTrue(operationService.markSucceeded(publish.getId(), Map.of("item_id", "publish-123"), null));
        assertEquals(List.of(sync.getId()), operationService.findReadyPending(LocalDateTime.now(), 10)
                .stream().map(SalesChannelOperation::getId).toList());
        assertTrue(operationService.markRunning(sync.getId()));
        assertTrue(operationService.markSucceeded(sync.getId(), Map.of("synced", true), null));
    }

    @Test
    void failedReconciliationKeepsUnknownUntilLaterResolutionOfItsTarget() {
        Fixture fixture = fixture(false);
        bindExistingItem(fixture);
        SalesChannelOperation ambiguous = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of());
        assertTrue(operationService.markRunning(ambiguous.getId()));
        assertTrue(operationService.markUnknown(ambiguous.getId(), "TIMEOUT", "provider result is unknown"));

        SalesChannelOperation failedReconciliation = operationService.requestReconciliation(ambiguous.getId());
        assertTrue(operationService.markRunning(failedReconciliation.getId()));
        assertTrue(operationService.markFailed(failedReconciliation.getId(), "RECONCILE_FAILED", "not confirmed"));
        SalesChannelListing stillUnknown = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(SalesChannelListingStatus.UNKNOWN, stillUnknown.getStatus());
        assertEquals("TIMEOUT", stillUnknown.getLastErrorCode());
        SalesChannelOperation latestIntent = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.OFFLINE, Map.of("intent", "preserved while unknown"));
        assertEquals(SalesChannelOperationStatus.PENDING, latestIntent.getStatus());
        assertFalse(operationService.markRunning(latestIntent.getId()));

        listingService.updateProviderConfig(fixture.listing().getId(), Map.of("title", "recheck"));
        SalesChannelOperation successfulReconciliation = operationService.requestReconciliation(ambiguous.getId());
        assertTrue(operationService.markRunning(successfulReconciliation.getId()));
        assertTrue(operationRepository.findById(successfulReconciliation.getId()).orElseThrow().getStartedAt()
                .isAfter(operationRepository.findById(ambiguous.getId()).orElseThrow().getFinishedAt()));
        assertThrows(IllegalStateException.class, () -> operationService.markSucceeded(
                successfulReconciliation.getId(), Map.of("checked", true), null));
        assertTrue(operationService.resolveUnknownAsSucceeded(successfulReconciliation.getId(), ambiguous.getId(),
                Map.of("checked", true), Map.of("target_outcome", "SUCCEEDED", "target_result", Map.of("checked", true)),
                null));
        SalesChannelListing reconciled = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(SalesChannelListingStatus.UNKNOWN, reconciled.getStatus());
        assertEquals("TIMEOUT", reconciled.getLastErrorCode());
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(ambiguous.getId()).orElseThrow().getStatus());
    }

    @Test
    void staleCompletionCannotApplyOldRevisionAndLaterQueueCanApplyCurrent() {
        Fixture fixture = fixture(false);
        bindExistingItem(fixture);
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
        assertEquals(SalesChannelOperationStatus.PENDING,
                operationRepository.findById(waiting.getId()).orElseThrow().getStatus());

        SalesChannelOperation reconciliation = operationService.requestReconciliation(ambiguous.getId());
        assertEquals(List.of(reconciliation.getId()), operationService.findReadyPending(LocalDateTime.now(), 20)
                .stream().map(SalesChannelOperation::getId).toList());
        assertTrue(operationService.markRunning(reconciliation.getId()));
        assertTrue(operationService.resolveUnknownAsSucceeded(reconciliation.getId(), ambiguous.getId(),
                Map.of("checked", true), Map.of("target_outcome", "SUCCEEDED"), null));
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(ambiguous.getId()).orElseThrow().getStatus());
        assertEquals(List.of(waiting.getId()), operationService.findReadyPending(LocalDateTime.now(), 20)
                .stream().map(SalesChannelOperation::getId).toList());
        assertTrue(operationService.markRunning(waiting.getId()));
    }

    @Test
    void stalePublishBindsExternalIdentityWithoutChangingNewerDesiredState() {
        Fixture fixture = fixture(false);
        listingService.setDesiredState(fixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        SalesChannelOperation stalePublish = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of("revision", 1));
        assertTrue(operationService.markRunning(stalePublish.getId()));

        listingService.updateProviderConfig(fixture.listing().getId(), Map.of("title", "revision two"));
        SalesChannelListing beforeCompletion = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(2, beforeCompletion.getDesiredRevision());
        assertEquals(SalesChannelListingStatus.PUBLISHING, beforeCompletion.getStatus());

        assertTrue(operationService.markSucceeded(stalePublish.getId(), Map.of("item_id", "remote-123"),
                "revision-one-fingerprint"));
        SalesChannelListing afterCompletion = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals("remote-123", afterCompletion.getExternalItemId());
        assertEquals(0, afterCompletion.getAppliedRevision());
        assertNull(afterCompletion.getAppliedFingerprint());
        assertEquals(SalesChannelListingStatus.PUBLISHING, afterCompletion.getStatus());
    }

    @Test
    void stalePersonalPublishDoesNotCreateOrRunAutomaticSync() {
        Fixture fixture = fixture(false);
        accountService.upsert(SalesChannelProvider.XIANYU, fixture.account().getProviderAccountId(),
                fixture.account().getDisplayName(), SalesChannelAccountStatus.CONNECTED,
                Map.of("account_type", "PERSONAL"), LocalDateTime.now());
        savePublishConfigWithoutPlanning(fixture, true, true);
        SalesChannelListing enabled = desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        SalesChannelOperation publish = operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(enabled.getId(), 1).getFirst();
        assertEquals(SalesChannelOperationType.PUBLISH, publish.getOperationType());
        assertTrue(operationService.markRunning(publish.getId()));

        Product product = productRepository.findById(fixture.product().getId()).orElseThrow();
        product.setTitle(product.getTitle() + " updated");
        productRepository.saveAndFlush(product);
        assertEquals(2, desiredStatePlanner.refresh(enabled.getId()).getDesiredRevision());
        assertTrue(operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(enabled.getId(), 2).isEmpty());

        assertTrue(operationService.markSucceeded(publish.getId(), Map.of("item_id", "personal-item-1"),
                "revision-one-fingerprint"));
        SalesChannelListing afterPublish = desiredStatePlanner.refresh(enabled.getId());
        assertEquals("personal-item-1", afterPublish.getExternalItemId());
        assertEquals(SalesChannelListingStatus.ERROR, afterPublish.getStatus());
        assertEquals("UNSUPPORTED_ACCOUNT_CAPABILITY", afterPublish.getLastErrorCode());
        assertTrue(operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(enabled.getId(), 2).isEmpty());
    }

    @Test
    void exhaustedUnknownReconciliationCanBeRestartedAsReadOnlyWork() {
        Fixture fixture = fixture(false);
        listingService.setDesiredState(fixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        SalesChannelOperation target = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of("snapshot", "fixed"));
        assertTrue(operationService.markRunning(target.getId()));
        assertTrue(operationService.markUnknown(target.getId(), "TIMEOUT", "provider result is unknown"));

        SalesChannelOperation first = operationService.requestReconciliation(target.getId());
        assertEquals(first.getId(), operationService.requestReconciliation(target.getId()).getId());
        assertTrue(operationService.markRunning(first.getId()));
        assertTrue(operationService.markFailed(first.getId(), "RECONCILE_MAX_ATTEMPTS",
                "target outcome remains unknown"));

        SalesChannelOperation second = operationService.requestReconciliation(target.getId());
        assertNotEquals(first.getId(), second.getId());
        assertEquals(SalesChannelOperationType.RECONCILE, second.getOperationType());
        assertEquals(target.getId().toString(), second.getPayload().get("target_operation_id"));
        assertEquals(target.getIdempotencyKey(), second.getPayload().get("target_request_id"));
        assertFalse(operationService.markRunning(target.getId()));
        assertTrue(operationService.markRunning(second.getId()));
    }

    @Test
    void unknownPublishBlocksNewPublishUntilReconciliationBindsTheSingleExternalItem() {
        Fixture fixture = fixture(false);
        accountService.upsert(SalesChannelProvider.XIANYU, fixture.account().getProviderAccountId(),
                fixture.account().getDisplayName(), SalesChannelAccountStatus.CONNECTED,
                Map.of("account_type", "FISH_SHOP"), LocalDateTime.now());
        savePublishConfigWithoutPlanning(fixture, true, true);
        SalesChannelListing enabled = desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        SalesChannelOperation firstPublish = operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(enabled.getId(), 1).getFirst();
        assertEquals(SalesChannelOperationType.PUBLISH, firstPublish.getOperationType());
        assertTrue(operationService.markRunning(firstPublish.getId()));
        assertTrue(operationService.markUnknown(firstPublish.getId(), "TIMEOUT", "publish result is unknown"));

        Product product = productRepository.findById(fixture.product().getId()).orElseThrow();
        product.setTitle(product.getTitle() + " newer");
        productRepository.saveAndFlush(product);
        SalesChannelListing revisionTwo = desiredStatePlanner.refresh(enabled.getId());
        assertEquals(2, revisionTwo.getDesiredRevision());
        assertTrue(operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(enabled.getId(), 2).isEmpty());

        SalesChannelOperation reconciliation = operationService.requestReconciliation(firstPublish.getId());
        assertTrue(operationService.markRunning(reconciliation.getId()));
        assertTrue(operationService.resolveUnknownAsSucceeded(reconciliation.getId(), firstPublish.getId(),
                Map.of("item_id", "one-confirmed-item"), Map.of("target_outcome", "SUCCEEDED"),
                "revision-one-fingerprint"));
        SalesChannelListing reconciled = desiredStatePlanner.refresh(enabled.getId());
        assertEquals("one-confirmed-item", reconciled.getExternalItemId());

        List<SalesChannelOperation> revisionTwoOperations = operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(enabled.getId(), 2);
        List<SalesChannelOperation> revisionTwoSideEffects = revisionTwoOperations.stream()
                .filter(operation -> operation.getOperationType() != SalesChannelOperationType.RECONCILE)
                .toList();
        assertEquals(1, revisionTwoSideEffects.size());
        assertEquals(SalesChannelOperationType.SYNC, revisionTwoSideEffects.getFirst().getOperationType());
        SalesChannelOperation erroneousPublish = operationService.request(enabled.getId(),
                SalesChannelOperationType.PUBLISH, Map.of("unexpected", true));
        assertFalse(operationService.markRunning(erroneousPublish.getId()));
        assertEquals(SalesChannelOperationStatus.SUPERSEDED,
                operationRepository.findById(erroneousPublish.getId()).orElseThrow().getStatus());
        assertEquals("one-confirmed-item",
                listingRepository.findById(enabled.getId()).orElseThrow().getExternalItemId());
    }

    @Test
    void conflictingSyncIdentityIsPreservedAsUnknownAndCannotReplaceExistingItem() {
        Fixture fixture = fixture(false);
        listingService.setDesiredState(fixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        SalesChannelOperation firstPublish = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of("revision", 1));
        assertTrue(operationService.markRunning(firstPublish.getId()));
        assertTrue(operationService.markSucceeded(firstPublish.getId(), Map.of("item_id", "remote-123"),
                "first-fingerprint"));

        listingService.updateProviderConfig(fixture.listing().getId(), Map.of("title", "revision two"));
        SalesChannelOperation conflictingSync = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of("revision", 2));
        assertTrue(operationService.markRunning(conflictingSync.getId()));
        assertTrue(operationService.markSucceeded(conflictingSync.getId(), Map.of("item_id", "remote-456"),
                "second-fingerprint"));

        SalesChannelListing conflicted = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals("remote-123", conflicted.getExternalItemId());
        assertEquals(SalesChannelListingStatus.UNKNOWN, conflicted.getStatus());
        assertEquals("EXTERNAL_ITEM_ID_CONFLICT", conflicted.getLastErrorCode());
        assertEquals(1, conflicted.getAppliedRevision());
        assertEquals("first-fingerprint", conflicted.getAppliedFingerprint());
        SalesChannelOperation conflictReconciliation = operationService.requestReconciliation(conflictingSync.getId());
        assertTrue(operationService.markRunning(conflictReconciliation.getId()));
        assertTrue(operationService.resolveUnknownAsSucceeded(conflictReconciliation.getId(), conflictingSync.getId(),
                Map.of("item_id", "remote-456"), Map.of("target_outcome", "SUCCEEDED"), null));
        SalesChannelListing stillConflicted = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(SalesChannelListingStatus.UNKNOWN, stillConflicted.getStatus());
        assertEquals("EXTERNAL_ITEM_ID_CONFLICT", stillConflicted.getLastErrorCode());
        desiredStatePlanner.retry(fixture.listing().getId());
        SalesChannelOperation repeatedConflictReconciliation = operationRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(fixture.listing().getId()).stream()
                .filter(operation -> operation.getOperationType() == SalesChannelOperationType.RECONCILE)
                .reduce((earlier, later) -> later)
                .orElseThrow();
        assertEquals(conflictingSync.getIdempotencyKey(),
                repeatedConflictReconciliation.getPayload().get("target_request_id"));
        listingService.updateProviderConfig(fixture.listing().getId(), Map.of("title", "revision three"));
        assertThrows(IllegalStateException.class, () -> operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of("revision", "must not be queued")));
    }

    @Test
    void syncResultCannotSilentlyRebindAnExistingExternalItem() {
        Fixture fixture = fixture(false);
        listingService.setDesiredState(fixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        SalesChannelOperation publish = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of("revision", 1));
        assertTrue(operationService.markRunning(publish.getId()));
        assertTrue(operationService.markSucceeded(publish.getId(), Map.of("item_id", "remote-123"),
                "publish-fingerprint"));

        listingService.updateProviderConfig(fixture.listing().getId(), Map.of("title", "revision two"));
        SalesChannelOperation sync = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of("revision", 2));
        assertTrue(operationService.markRunning(sync.getId()));
        assertTrue(operationService.markSucceeded(sync.getId(), Map.of("external_item_id", "remote-456"),
                "sync-fingerprint"));

        SalesChannelListing conflicted = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals("remote-123", conflicted.getExternalItemId());
        assertEquals(SalesChannelListingStatus.UNKNOWN, conflicted.getStatus());
        assertEquals("EXTERNAL_ITEM_ID_CONFLICT", conflicted.getLastErrorCode());
        assertEquals(1, conflicted.getAppliedRevision());
        assertEquals("publish-fingerprint", conflicted.getAppliedFingerprint());
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(sync.getId()).orElseThrow().getStatus());
    }

    @Test
    void accountExternalItemIdIsUniqueWhileUnboundListingsCanShareNull() {
        Fixture fixture = fixture(false);
        Product secondProduct = new Product();
        secondProduct.setProductNo(productRepository.nextProductNoValue());
        secondProduct.setTitle("sales-channel-test-" + UUID.randomUUID());
        secondProduct.setRetailPrice(new BigDecimal("10.00"));
        secondProduct.setCostPrice(new BigDecimal("5.00"));
        secondProduct.setCategoryId(fixture.product().getCategoryId());
        secondProduct.setEnabled(true);
        secondProduct = productRepository.saveAndFlush(secondProduct);
        SalesChannelListing secondListing = listingService.ensureDraft(fixture.account().getId(), secondProduct.getId());

        assertNull(listingRepository.findById(fixture.listing().getId()).orElseThrow().getExternalItemId());
        assertNull(listingRepository.findById(secondListing.getId()).orElseThrow().getExternalItemId());
        jdbcTemplate.update("UPDATE sales_channel_listings SET external_item_id = 'remote-shared' WHERE id = ?",
                fixture.listing().getId());
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE sales_channel_listings SET external_item_id = 'remote-shared' WHERE id = ?",
                secondListing.getId()));

        Fixture anotherAccount = fixture(false);
        jdbcTemplate.update("UPDATE sales_channel_listings SET external_item_id = 'remote-shared' WHERE id = ?",
                anotherAccount.listing().getId());
        assertEquals("remote-shared", listingRepository.findById(anotherAccount.listing().getId())
                .orElseThrow().getExternalItemId());
    }

    @Test
    void reconcileCanResolveUnknownPublishSuccessWithoutApplyingStaleRevision() {
        Fixture fixture = fixture(false);
        listingService.setDesiredState(fixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        SalesChannelOperation target = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of("immutable", "revision-one"));
        assertTrue(operationService.markRunning(target.getId()));
        listingService.updateProviderConfig(fixture.listing().getId(), Map.of("title", "revision two"));
        SalesChannelOperation newerDesired = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of("immutable", "revision-two"));
        assertTrue(operationService.markUnknown(target.getId(), "HTTP_TIMEOUT", "publish result uncertain"));

        SalesChannelOperation reconciliation = operationService.requestReconciliation(target.getId());
        assertEquals(target.getDesiredRevision(), reconciliation.getDesiredRevision());
        assertTrue(operationService.markRunning(reconciliation.getId()));
        assertTrue(operationService.resolveUnknownAsSucceeded(reconciliation.getId(), target.getId(),
                Map.of("item_id", "remote-published"),
                Map.of("target_outcome", "SUCCEEDED", "item_id", "remote-published"),
                "revision-one-fingerprint"));

        SalesChannelOperation resolvedTarget = operationRepository.findById(target.getId()).orElseThrow();
        SalesChannelOperation resolvedReconciliation = operationRepository.findById(reconciliation.getId()).orElseThrow();
        SalesChannelListing listing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(SalesChannelOperationStatus.SUCCEEDED, resolvedTarget.getStatus());
        assertEquals(Map.of("item_id", "remote-published"), resolvedTarget.getResult());
        assertEquals(SalesChannelOperationStatus.SUCCEEDED, resolvedReconciliation.getStatus());
        assertEquals("SUCCEEDED", resolvedReconciliation.getResult().get("target_outcome"));
        assertEquals("remote-published", listing.getExternalItemId());
        assertEquals(0, listing.getAppliedRevision());
        assertNull(listing.getAppliedFingerprint());
        assertEquals(SalesChannelListingStatus.PUBLISHING, listing.getStatus());
        assertEquals(SalesChannelOperationStatus.PENDING,
                operationRepository.findById(newerDesired.getId()).orElseThrow().getStatus());
    }

    @Test
    void reconcileAppliesResolvedSuccessWhenTargetRevisionIsStillCurrent() {
        Fixture fixture = fixture(false);
        listingService.setDesiredState(fixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        bindExistingItem(fixture);
        SalesChannelOperation target = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of("snapshot", "current-revision"));
        assertTrue(operationService.markRunning(target.getId()));
        assertTrue(operationService.markUnknown(target.getId(), "RESET", "sync result uncertain"));
        SalesChannelOperation reconciliation = operationService.requestReconciliation(target.getId());
        assertEquals(target.getDesiredRevision(), reconciliation.getDesiredRevision());
        assertTrue(operationService.markRunning(reconciliation.getId()));

        assertTrue(operationService.resolveUnknownAsSucceeded(reconciliation.getId(), target.getId(),
                Map.of("confirmed", true), Map.of("target_outcome", "SUCCEEDED"), "resolved-fingerprint"));
        SalesChannelListing listing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(1, listing.getAppliedRevision());
        assertEquals("resolved-fingerprint", listing.getAppliedFingerprint());
        assertEquals(SalesChannelListingStatus.ONLINE, listing.getStatus());
        assertNotNull(listing.getLastSyncedAt());
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(target.getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(reconciliation.getId()).orElseThrow().getStatus());
    }

    @Test
    void queuedReconciliationStillRunsAfterDesiredRevisionAdvances() {
        Fixture fixture = fixture(false);
        listingService.setDesiredState(fixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        bindExistingItem(fixture);
        SalesChannelOperation target = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of("snapshot", "revision-one"));
        assertTrue(operationService.markRunning(target.getId()));
        assertTrue(operationService.markUnknown(target.getId(), "RESET", "sync result uncertain"));
        SalesChannelOperation reconciliation = operationService.requestReconciliation(target.getId());
        assertEquals(1, reconciliation.getDesiredRevision());

        listingService.updateProviderConfig(fixture.listing().getId(), Map.of("title", "revision-two"));
        assertEquals(SalesChannelOperationStatus.PENDING,
                operationRepository.findById(reconciliation.getId()).orElseThrow().getStatus());
        assertTrue(operationService.markRunning(reconciliation.getId()));
        assertTrue(operationService.resolveUnknownAsSucceeded(reconciliation.getId(), target.getId(),
                Map.of("confirmed", true), Map.of("target_outcome", "SUCCEEDED"), null));
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(reconciliation.getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(target.getId()).orElseThrow().getStatus());
    }

    @Test
    void reconcileCanReopenUnknownOnlyAfterExplicitRetryableFailedOutcome() {
        Fixture fixture = fixture(false);
        listingService.setDesiredState(fixture.listing().getId(), SalesChannelListingDesiredState.ONLINE);
        SalesChannelOperation target = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, Map.of("snapshot", "same-payload"));
        assertTrue(operationService.markRunning(target.getId()));
        assertTrue(operationService.markUnknown(target.getId(), "HTTP_RESET", "publish result uncertain"));
        SalesChannelOperation unknownTarget = operationRepository.findById(target.getId()).orElseThrow();
        String originalKey = unknownTarget.getIdempotencyKey();
        Map<String, Object> originalPayload = unknownTarget.getPayload();
        int originalAttempts = unknownTarget.getAttemptCount();
        long originalRevision = unknownTarget.getDesiredRevision();
        LocalDateTime nextAttempt = LocalDateTime.now().plusMinutes(1).truncatedTo(ChronoUnit.MICROS);

        SalesChannelOperation reconciliation = operationService.requestReconciliation(target.getId());
        assertTrue(operationService.markRunning(reconciliation.getId()));
        assertFalse(operationService.reschedule(target.getId(), "BAD_RETRY", "must not retry UNKNOWN", nextAttempt));
        assertThrows(IllegalArgumentException.class, () -> operationService.resolveUnknownAsRetryable(
                reconciliation.getId(), target.getId(), Map.of("target_outcome", "UNKNOWN", "target_retryable", true),
                "PROVEN_NOT_PUBLISHED", "not published", nextAttempt));

        assertTrue(operationService.resolveUnknownAsRetryable(reconciliation.getId(), target.getId(),
                Map.of("target_outcome", "FAILED", "target_retryable", true),
                "PROVEN_NOT_PUBLISHED", "Agent proved no item was created", nextAttempt));
        SalesChannelOperation reopened = operationRepository.findById(target.getId()).orElseThrow();
        SalesChannelOperation completedReconciliation = operationRepository.findById(reconciliation.getId()).orElseThrow();
        assertEquals(SalesChannelOperationStatus.PENDING, reopened.getStatus());
        assertEquals(target.getId(), reopened.getId());
        assertEquals(originalKey, reopened.getIdempotencyKey());
        assertEquals(originalPayload, reopened.getPayload());
        assertEquals(originalRevision, reopened.getDesiredRevision());
        assertEquals(originalAttempts, reopened.getAttemptCount());
        assertEquals(nextAttempt, reopened.getNextAttemptAt());
        assertNull(reopened.getStartedAt());
        assertNull(reopened.getFinishedAt());
        assertEquals(SalesChannelOperationStatus.SUCCEEDED, completedReconciliation.getStatus());
        assertEquals("FAILED", completedReconciliation.getResult().get("target_outcome"));
        assertFalse(operationService.markRunning(target.getId()));

        reopened.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        operationRepository.saveAndFlush(reopened);
        assertTrue(operationService.markRunning(target.getId()));
        assertEquals(originalAttempts + 1,
                operationRepository.findById(target.getId()).orElseThrow().getAttemptCount());
    }

    @Test
    void reconcilePermanentlyFailsUnknownTargetWhenAgentConfirmsNonRetryableFailure() {
        Fixture fixture = fixture(false);
        bindExistingItem(fixture);
        SalesChannelOperation target = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, Map.of("snapshot", "immutable"));
        assertTrue(operationService.markRunning(target.getId()));
        assertTrue(operationService.markUnknown(target.getId(), "RESET", "result uncertain"));
        SalesChannelOperation reconciliation = operationService.requestReconciliation(target.getId());
        assertTrue(operationService.markRunning(reconciliation.getId()));

        assertTrue(operationService.resolveUnknownAsFailed(reconciliation.getId(), target.getId(),
                Map.of("error", "provider rejected"),
                Map.of("target_outcome", "FAILED", "target_retryable", false),
                "PROVIDER_REJECTED", "Agent confirmed the operation did not occur and is permanent"));
        SalesChannelOperation failedTarget = operationRepository.findById(target.getId()).orElseThrow();
        SalesChannelListing listing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(SalesChannelOperationStatus.FAILED, failedTarget.getStatus());
        assertEquals(Map.of("error", "provider rejected"), failedTarget.getResult());
        assertEquals("PROVIDER_REJECTED", failedTarget.getLastErrorCode());
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(reconciliation.getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelListingStatus.ERROR, listing.getStatus());
        assertEquals("PROVIDER_REJECTED", listing.getLastErrorCode());
    }

    @Test
    void operationPayloadAndResultAreDeepCopiedAcrossQueueAndReadBoundaries() {
        Fixture fixture = fixture(false);
        bindExistingItem(fixture);
        Map<String, Object> nestedInput = new LinkedHashMap<>();
        nestedInput.put("title", "original");
        List<Object> nestedList = new java.util.ArrayList<>();
        nestedList.add(nestedInput);
        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("snapshot", nestedList);

        SalesChannelOperation operation = operationService.request(fixture.listing().getId(),
                SalesChannelOperationType.SYNC, inputPayload);
        nestedInput.put("title", "mutated after enqueue");
        nestedList.add("mutated after enqueue");
        assertEquals(List.of(Map.of("title", "original")), operation.getPayload().get("snapshot"));

        Map<String, Object> exposedPayload = operation.getPayload();
        @SuppressWarnings("unchecked")
        List<Object> exposedNestedList = (List<Object>) exposedPayload.get("snapshot");
        @SuppressWarnings("unchecked")
        Map<String, Object> exposedNestedMap = (Map<String, Object>) exposedNestedList.get(0);
        exposedNestedMap.put("title", "mutated through getter");
        assertEquals(List.of(Map.of("title", "original")), operation.getPayload().get("snapshot"));

        assertTrue(operationService.markRunning(operation.getId()));
        Map<String, Object> nestedResult = new LinkedHashMap<>();
        nestedResult.put("confirmed", true);
        List<Object> resultList = new java.util.ArrayList<>(List.of(nestedResult));
        Map<String, Object> inputResult = new LinkedHashMap<>();
        inputResult.put("details", resultList);
        assertTrue(operationService.markSucceeded(operation.getId(), inputResult, null));
        nestedResult.put("confirmed", false);
        resultList.add("late mutation");
        assertEquals(List.of(Map.of("confirmed", true)),
                operationRepository.findById(operation.getId()).orElseThrow().getResult().get("details"));
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

    private List<UUID> addStockCapSpecs(Fixture fixture) {
        List<UUID> specIds = List.of(
                addSpec(fixture.product().getId(), "A", true, 0),
                addSpec(fixture.product().getId(), "B", true, 1),
                addSpec(fixture.product().getId(), "C", true, 2));
        listingService.ensureDraft(fixture.account().getId(), fixture.product().getId());
        return specIds;
    }

    private void addAvailableCardKeys(UUID productId, UUID specId, int count) {
        List<CardKey> cardKeys = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            CardKey cardKey = new CardKey();
            cardKey.setProductId(productId);
            cardKey.setSpecId(specId);
            cardKey.setContentParts(List.of("integration-only-card-" + UUID.randomUUID()));
            cardKey.setContentFingerprint(UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", ""));
            cardKey.setStatus(CardKeyStatus.AVAILABLE);
            cardKeys.add(cardKey);
        }
        cardKeyRepository.saveAllAndFlush(cardKeys);
    }

    private void assertAvailableCardKeys(UUID productId, List<UUID> specIds, List<Integer> expected) {
        List<Long> actual = specIds.stream()
                .map(specId -> cardKeyRepository.countByProductIdAndSpecIdAndStatus(
                        productId, specId, CardKeyStatus.AVAILABLE))
                .toList();
        assertEquals(expected.stream().map(Integer::longValue).toList(), actual);
    }

    private SalesChannelOperation operationAtRevision(Fixture fixture, long revision,
                                                       SalesChannelOperationType operationType) {
        return operationRepository.findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(
                        fixture.listing().getId(), revision).stream()
                .filter(operation -> operation.getOperationType() == operationType)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected " + operationType + " at revision " + revision));
    }

    @SuppressWarnings("unchecked")
    private void assertSkuSnapshot(SalesChannelOperation operation, List<Integer> expectedStocks, int expectedTotal) {
        Map<String, Object> snapshot = (Map<String, Object>) operation.getPayload().get("desired_snapshot");
        assertNotNull(snapshot);
        assertEquals(expectedTotal, ((Number) snapshot.get("quantity")).intValue());
        Object rawRows = snapshot.containsKey("skuRows") ? snapshot.get("skuRows") : snapshot.get("sku_rows");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rawRows;
        assertNotNull(rows);
        assertEquals(expectedStocks.size(), rows.size());
        assertEquals(expectedStocks, rows.stream()
                .map(row -> ((Number) row.get("stock")).intValue())
                .toList());
    }

    private com.myshop.saleschannel.xianyu.model.XianyuProviderConfig publishConfigWithStockCap(
            Fixture fixture, Integer stockCapPerSku) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("descriptionOverride", "Test listing description");
        config.put("titleOverride", fixture.product().getTitle());
        config.put("imageUrls", List.of("https://images.example.invalid/source.jpg"));
        config.put("priceMode", "PRODUCT_RETAIL");
        config.put("address", "East Lake");
        config.put("platformCategoryId", "category-test-id");
        config.put("platformCategoryName", "Software service");
        config.put("platformChannelCategoryId", "channel-category-test-id");
        config.put("platformChannelCategoryName", "Virtual goods");
        config.put("platformTbCategoryId", "tb-category-test-id");
        config.put("unlimitedStockQuantity", 999);
        if (stockCapPerSku != null) config.put("stockCapPerSku", stockCapPerSku);
        return objectMapper.convertValue(config,
                com.myshop.saleschannel.xianyu.model.XianyuProviderConfig.class);
    }

    private void bindExistingItem(Fixture fixture) {
        SalesChannelListing listing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        listing.bindExternalItemId("existing-item-" + UUID.randomUUID());
        listingRepository.saveAndFlush(listing);
    }

    private void verifiedAccount(Fixture fixture, String accountType) {
        accountService.upsert(SalesChannelProvider.XIANYU, fixture.account().getProviderAccountId(),
                fixture.account().getDisplayName(), SalesChannelAccountStatus.CONNECTED,
                Map.of("account_type", accountType), LocalDateTime.now());
    }

    private void savePublishConfig(Fixture fixture, boolean includeAddress, boolean includeCategory) {
        salesChannelAdminService.updateXianyuListing(
                fixture.product().getId(),
                fixture.account().getId(),
                publishConfig(fixture, includeAddress, includeCategory));
    }

    private void savePublishConfigWithoutPlanning(Fixture fixture, boolean includeAddress, boolean includeCategory) {
        desiredStatePlanner.saveConfig(fixture.listing().getId(),
                publishConfig(fixture, includeAddress, includeCategory));
    }

    private com.myshop.saleschannel.xianyu.model.XianyuProviderConfig publishConfig(
            Fixture fixture, boolean includeAddress, boolean includeCategory) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("descriptionOverride", "Test listing description");
        config.put("imageUrls", List.of("https://images.example.invalid/source.jpg"));
        config.put("priceMode", "PRODUCT_RETAIL");
        if (includeAddress) config.put("address", "East Lake");
        if (includeCategory) {
            config.put("platformCategoryId", "category-test-id");
            config.put("platformCategoryName", "Software service");
            config.put("platformChannelCategoryId", "channel-category-test-id");
            config.put("platformChannelCategoryName", "Virtual goods");
            config.put("platformTbCategoryId", "tb-category-test-id");
        }
        return objectMapper.convertValue(config,
                com.myshop.saleschannel.xianyu.model.XianyuProviderConfig.class);
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
