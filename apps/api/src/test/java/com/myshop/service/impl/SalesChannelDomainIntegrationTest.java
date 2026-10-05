package com.myshop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelGenerationState;
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
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelListingGenerationSpec;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.exception.BusinessException;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingGenerationSpecRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.service.ManualStockService;
import com.myshop.service.SalesChannelAdminService;
import com.myshop.service.SalesChannelDesiredStatePlanner;
import com.myshop.service.UploadStorage;
import com.myshop.saleschannel.xianyu.XianyuMediaMaterializer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    @Autowired private SalesChannelListingGenerationRepository generationRepository;
    @Autowired private SalesChannelListingGenerationSpecRepository generationSpecRepository;
    @Autowired private SalesChannelOperationRepository operationRepository;
    @Autowired private SalesChannelAccountServiceImpl accountService;
    @Autowired private SalesChannelListingServiceImpl listingService;
    @Autowired private SalesChannelOperationServiceImpl operationService;
    @Autowired private SalesChannelDesiredStatePlanner desiredStatePlanner;
    @Autowired private ManualStockService manualStockService;
    @Autowired private SalesChannelAdminService salesChannelAdminService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UploadStorage uploadStorage;
    @Autowired private XianyuMediaMaterializer mediaMaterializer;

    private static final String PREFLIGHT_IMAGE_KEY = "sales-channel-test/preflight.jpg";
    private static final String PREFLIGHT_IMAGE_URL = "/api/uploads/" + PREFLIGHT_IMAGE_KEY;

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
    void resetSalesChannelFixtures() throws IOException {
        jdbcTemplate.update("DELETE FROM sales_channel_operations");
        jdbcTemplate.update("DELETE FROM sales_channel_listing_generation_specs");
        jdbcTemplate.update("DELETE FROM sales_channel_listing_generations");
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
        Files.write(uploadStorage.prepareManagedFile(PREFLIGHT_IMAGE_KEY),
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02});
        mediaMaterializer.readImage(PREFLIGHT_IMAGE_URL);
        entityManager.clear();
    }

    @AfterEach
    void removePreflightImage() {
        uploadStorage.deleteManagedFile(PREFLIGHT_IMAGE_KEY);
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

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE sales_channel_listings SET status = 'RELIST_REQUIRED' WHERE id = ?",
                fixture.listing().getId()));

        SalesChannelListing secondCall = listingService.ensureDraft(fixture.account().getId(), fixture.product().getId());
        assertEquals(fixture.listing().getId(), secondCall.getId());
        assertTrue(generationRepository.findCurrentByListingId(secondCall.getId()).isEmpty());

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
        List<SalesChannelOperation> operations = operationRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(UUID.fromString(saved.id()));
        assertEquals(1, operations.size());
        assertEquals(SalesChannelOperationType.ENSURE_LISTING, operations.getFirst().getOperationType());
        assertEquals("OFFLINE", operations.getFirst().getPayload().get("desired_state"));
        assertNull(operations.getFirst().getPayload().get("desired_snapshot"));
        assertEquals(com.myshop.saleschannel.convergence.DesiredListingSnapshotFingerprints.offline(
                        objectMapper, UUID.fromString(saved.id()).toString().toLowerCase()),
                operations.getFirst().getPayload().get("desired_fingerprint"));
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
        assertOnlyDraftOfflineCommandRemains(fixture.listing().getId());
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
        assertOnlyDraftOfflineCommandRemains(fixture.listing().getId());
    }

    @Test
    void completePreflightAllowsPublishWithExplicitSelectedAddressText() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        savePublishConfig(fixture, true, true);

        SalesChannelListing enabled = desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        List<SalesChannelOperation> operations = operationRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(enabled.getId());

        assertTrue(enabled.isEnabled());
        assertEquals(2, operations.size());
        assertEquals(SalesChannelOperationType.ENSURE_LISTING, operations.get(0).getOperationType());
        assertEquals("OFFLINE", operations.get(0).getPayload().get("desired_state"));
        assertNull(operations.get(0).getPayload().get("desired_snapshot"));
        assertEquals(SalesChannelOperationStatus.SUPERSEDED, operations.get(0).getStatus());
        assertEquals(SalesChannelOperationType.ENSURE_LISTING, operations.get(1).getOperationType());
        assertEquals("ONLINE", operations.get(1).getPayload().get("desired_state"));
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) operations.get(1).getPayload().get("desired_snapshot");
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) snapshot.get("address");
        assertEquals("East Lake", address.get("address"));
        assertEquals("East Lake POI", address.get("expected_text"));
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
        assertTrue(generationRepository.findCurrentByListingId(noSpecs.listing().getId()).isEmpty());

        Fixture withSpecs = fixture(true);
        UUID visibleSpecId = addSpec(withSpecs.product().getId(), "visible", true, 0);
        UUID hiddenButActiveSpecId = addSpec(withSpecs.product().getId(), "hidden", false, 1);
        UUID retiredSpecId = addSpec(withSpecs.product().getId(), "retired", true, 2);
        ProductSpec retired = productSpecRepository.findById(retiredSpecId).orElseThrow();
        retired.setIsDeleted(1);
        productSpecRepository.saveAndFlush(retired);

        SalesChannelListingGeneration generation = createGeneration(withSpecs, "active-spec-item");
        insertGenerationSpec(generation, visibleSpecId);
        insertGenerationSpec(generation, hiddenButActiveSpecId);
        List<SalesChannelListingGenerationSpec> mappings = generationSpecRepository
                .findBySalesChannelListingGenerationIdOrderByCreatedAtAsc(generation.getId());
        Set<UUID> mappedSpecIds = mappings.stream().map(SalesChannelListingGenerationSpec::getProductSpecId)
                .collect(Collectors.toSet());
        assertEquals(Set.of(visibleSpecId, hiddenButActiveSpecId), mappedSpecIds);
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO sales_channel_listing_generation_specs(id, sales_channel_listing_generation_id, product_spec_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), generation.getId(), visibleSpecId));
        entityManager.clear();
        assertTrue(generationSpecRepository.findBySalesChannelListingGenerationIdOrderByCreatedAtAsc(
                generation.getId()).stream().anyMatch(mapping -> visibleSpecId.equals(mapping.getProductSpecId())));
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
        assertEquals(SalesChannelOperationType.ENSURE_LISTING, publish.getOperationType());
        assertEquals("ONLINE", publish.getPayload().get("desired_state"));
        @SuppressWarnings("unchecked")
        Map<String, Object> initialSnapshot = (Map<String, Object>) publish.getPayload().get("desired_snapshot");
        assertEquals(10, ((Number) initialSnapshot.get("stock")).intValue());

        completeEnsure(publish, "fish-shop-item-1");

        manualStockService.setAvailable(product.getId(), null, 0);
        SalesChannelListing zeroPlan = desiredStatePlanner.refresh(fixture.listing().getId());
        assertEquals(2, zeroPlan.getDesiredRevision());
        SalesChannelOperation zeroSync = operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(fixture.listing().getId(), 2)
                .stream().filter(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> zeroSnapshot = (Map<String, Object>) zeroSync.getPayload().get("desired_snapshot");
        assertEquals(0, ((Number) zeroSnapshot.get("stock")).intValue());

        manualStockService.setAvailable(product.getId(), null, 5);
        SalesChannelListing fivePlan = desiredStatePlanner.refresh(fixture.listing().getId());
        assertEquals(3, fivePlan.getDesiredRevision());
        assertEquals(SalesChannelOperationStatus.SUPERSEDED,
                operationRepository.findById(zeroSync.getId()).orElseThrow().getStatus());
        List<SalesChannelOperation> currentOperations = operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(fixture.listing().getId(), 3);
        assertEquals(1, currentOperations.size());
        assertEquals(SalesChannelOperationType.ENSURE_LISTING, currentOperations.get(0).getOperationType());
        @SuppressWarnings("unchecked")
        Map<String, Object> fiveSnapshot = (Map<String, Object>) currentOperations.get(0)
                .getPayload().get("desired_snapshot");
        assertEquals(5, ((Number) fiveSnapshot.get("stock")).intValue());

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
        saveConfigWithoutPlanning(fixture, publishConfigWithStockCap(fixture, null));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);

        SalesChannelOperation publish = operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING);
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

        completeEnsure(publish, "stock-cap-test-item");

        salesChannelAdminService.updateXianyuListing(fixture.product().getId(), fixture.account().getId(),
                publishConfigWithStockCap(fixture, 1));
        SalesChannelListing capOneListing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(2, capOneListing.getDesiredRevision());
        assertEquals(1, ((Number) capOneListing.getProviderConfig().get("stockCapPerSku")).intValue());
        assertNotEquals(nullCapFingerprint, capOneListing.getDesiredFingerprint());
        assertSkuSnapshot(operationAtRevision(fixture, 2, SalesChannelOperationType.ENSURE_LISTING),
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
        assertSkuSnapshot(operationAtRevision(fixture, 3, SalesChannelOperationType.ENSURE_LISTING),
                List.of(0, 0, 0), 0);
        assertAvailableCardKeys(product.getId(), specIds, List.of(5, 4, 4));

        String capZeroFingerprint = capZeroListing.getDesiredFingerprint();
        salesChannelAdminService.updateXianyuListing(fixture.product().getId(), fixture.account().getId(),
                publishConfigWithStockCap(fixture, 10));
        SalesChannelListing capTenListing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(4, capTenListing.getDesiredRevision());
        assertNotEquals(capZeroFingerprint, capTenListing.getDesiredFingerprint());
        assertSkuSnapshot(operationAtRevision(fixture, 4, SalesChannelOperationType.ENSURE_LISTING),
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
        saveConfigWithoutPlanning(fixture, publishConfigWithStockCap(fixture, 1));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);

        SalesChannelOperation publish = operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        assertSkuSnapshot(publish, List.of(1, 1, 1), 3);
        completeEnsure(publish, "stock-drift-test-item");
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
        assertSkuSnapshot(operationAtRevision(fixture, 2, SalesChannelOperationType.ENSURE_LISTING),
                List.of(0, 1, 1), 2);

        jdbcTemplate.update("UPDATE card_keys SET status = 'AVAILABLE' "
                        + "WHERE product_id = ? AND spec_id = ? AND status = 'INVALID'",
                product.getId(), specIds.get(0));
        assertAvailableCardKeys(product.getId(), specIds, List.of(5, 4, 4));
        SalesChannelListing restoredAStock = desiredStatePlanner.refresh(fixture.listing().getId());
        assertEquals(3, restoredAStock.getDesiredRevision());
        assertEquals(fullStockFingerprint, restoredAStock.getDesiredFingerprint());
        assertSkuSnapshot(operationAtRevision(fixture, 3, SalesChannelOperationType.ENSURE_LISTING),
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
        saveConfigWithoutPlanning(fixture, publishConfigWithStockCap(fixture, 1));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);

        assertSkuSnapshot(operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING),
                List.of(1, 1, 1), 3);
        assertEquals(5, manualStockService.getAvailable(product.getId(), specIds.get(0)));
        assertEquals(4, manualStockService.getAvailable(product.getId(), specIds.get(1)));
        assertEquals(4, manualStockService.getAvailable(product.getId(), specIds.get(2)));
    }

    @Test
    void unlimitedSkuStockUsesCanonicalAssemblerCapSemantics() {
        Fixture fixture = fixture(true);
        List<UUID> specIds = addStockCapSpecs(fixture);
        Product product = productRepository.findById(fixture.product().getId()).orElseThrow();
        product.setStockMode(StockMode.UNLIMITED);
        productRepository.saveAndFlush(product);
        verifiedAccount(fixture, "FISH_SHOP");
        saveConfigWithoutPlanning(fixture, publishConfigWithStockCap(fixture, 0));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);

        assertSkuSnapshot(operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING),
                List.of(0, 0, 0), 0);
        assertTrue(specIds.stream().allMatch(specId -> cardKeyRepository
                .countByProductIdAndSpecIdAndStatus(product.getId(), specId, CardKeyStatus.AVAILABLE) == 0));
    }

    @Test
    void aPossiblySentEnsureMustFinishBeforeANewerRevisionCanRun() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        saveConfigWithoutPlanning(fixture, publishConfig(fixture, true, true));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        SalesChannelOperation first = operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        assertTrue(operationService.markRunning(first.getId()));
        Product product = productRepository.findById(fixture.product().getId()).orElseThrow();
        product.setTitle(product.getTitle() + " newer");
        productRepository.saveAndFlush(product);
        desiredStatePlanner.refresh(fixture.listing().getId());
        assertEquals(2, listingRepository.findById(fixture.listing().getId()).orElseThrow().getDesiredRevision());
        assertTrue(operationRepository.findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(
                fixture.listing().getId(), 2).isEmpty());

        assertTrue(operationService.markSucceeded(first.getId(),
                Map.of("state", "SUCCEEDED", "external_item_id", "same-provider-item"),
                Objects.toString(first.getPayload().get("desired_fingerprint"), null)));
        desiredStatePlanner.refresh(fixture.listing().getId());
        SalesChannelOperation second = operationAtRevision(fixture, 2, SalesChannelOperationType.ENSURE_LISTING);
        LocalDateTime readyAt = LocalDateTime.now();
        List<UUID> readyIds = new TransactionTemplate(transactionManager).execute(status ->
                operationRepository.findReadyPendingForUpdate(readyAt, 10).stream()
                        .map(SalesChannelOperation::getId).toList());
        assertEquals(List.of(second.getId()), readyIds);
        assertTrue(operationService.markRunning(second.getId()));
        assertTrue(operationService.markSucceeded(second.getId(),
                Map.of("state", "SUCCEEDED", "external_item_id", "same-provider-item"),
                Objects.toString(second.getPayload().get("desired_fingerprint"), null)));
    }

    @Test
    void staleCompletionCannotApplyOldRevisionAndLaterQueueCanApplyCurrent() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        saveConfigWithoutPlanning(fixture, publishConfig(fixture, true, true));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        SalesChannelOperation stale = operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        assertTrue(operationService.markRunning(stale.getId()));
        Product product = productRepository.findById(fixture.product().getId()).orElseThrow();
        product.setTitle(product.getTitle() + " changed");
        productRepository.saveAndFlush(product);
        desiredStatePlanner.refresh(fixture.listing().getId());
        assertTrue(operationService.markSucceeded(stale.getId(),
                Map.of("state", "SUCCEEDED", "external_item_id", "provider-item-1"),
                Objects.toString(stale.getPayload().get("desired_fingerprint"), null)));
        SalesChannelListing afterStaleCompletion = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(0, afterStaleCompletion.getAppliedRevision());
        assertNull(afterStaleCompletion.getAppliedFingerprint());
        assertEquals(SalesChannelListingStatus.APPLYING, afterStaleCompletion.getStatus());
        assertNull(candidateItem(fixture.listing().getId()));
        assertEquals("provider-item-1", currentItem(fixture.listing().getId()));

        desiredStatePlanner.refresh(fixture.listing().getId());
        SalesChannelOperation current = operationAtRevision(fixture, 2, SalesChannelOperationType.ENSURE_LISTING);
        assertTrue(operationService.markRunning(current.getId()));
        assertTrue(operationService.markSucceeded(current.getId(),
                Map.of("state", "SUCCEEDED", "external_item_id", "provider-item-1"),
                Objects.toString(current.getPayload().get("desired_fingerprint"), null)));
        SalesChannelListing applied = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(2, applied.getAppliedRevision());
        assertEquals(current.getPayload().get("desired_fingerprint"), applied.getAppliedFingerprint());
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                UPDATE sales_channel_listings SET applied_revision = 0 WHERE id = ?
                """, fixture.listing().getId()));
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(stale.getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelOperationType.ENSURE_LISTING, current.getOperationType());
    }

    @Test
    void ensureProjectionReplacesCurrentGenerationDirectlyWithoutCandidate() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        saveConfigWithoutPlanning(fixture, publishConfig(fixture, true, true));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        SalesChannelListingGeneration previous = createGeneration(fixture, "provider-item-a");
        assertTrue(operationService.markConvergenceAdopted(fixture.listing().getId(), "provider-item-a"));
        SalesChannelOperation ensure = operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        assertTrue(operationService.markRunning(ensure.getId()));

        assertTrue(operationService.markSucceeded(ensure.getId(),
                Map.of("state", "SUCCEEDED", "external_item_id", "provider-item-b"),
                Objects.toString(ensure.getPayload().get("desired_fingerprint"), null)));

        SalesChannelListingGeneration retired = generationRepository.findById(previous.getId()).orElseThrow();
        SalesChannelListingGeneration current = generationRepository.findCurrentByListingId(fixture.listing().getId())
                .orElseThrow();
        assertEquals(SalesChannelGenerationState.RETIRED, retired.getGenerationState());
        assertEquals("provider-item-b", current.getExternalItemId());
        assertEquals("CONVERGENCE", current.getOrigin());
        assertEquals(previous.getId(), current.getReplacesGenerationId());
        assertEquals("provider-item-b",
                listingRepository.findById(fixture.listing().getId()).orElseThrow()
                        .getConvergenceAdoptedExternalItemId());
        assertNull(candidateItem(fixture.listing().getId()));
    }

    @Test
    void sameProviderIdentityKeepsExistingGenerationProjection() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        saveConfigWithoutPlanning(fixture, publishConfig(fixture, true, true));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        SalesChannelListingGeneration current = createGeneration(fixture, "same-provider-item");
        assertTrue(operationService.markConvergenceAdopted(fixture.listing().getId(), "same-provider-item"));
        long generationCount = generationRepository.count();
        SalesChannelOperation ensure = operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        assertTrue(operationService.markRunning(ensure.getId()));

        assertTrue(operationService.markSucceeded(ensure.getId(),
                Map.of("state", "SUCCEEDED", "external_item_id", "same-provider-item"),
                Objects.toString(ensure.getPayload().get("desired_fingerprint"), null)));

        assertEquals(generationCount, generationRepository.count());
        assertEquals(current.getId(), generationRepository.findCurrentByListingId(fixture.listing().getId())
                .orElseThrow().getId());
        assertNull(candidateItem(fixture.listing().getId()));
    }

    @Test
    void crossListingProviderIdentityConflictRollsBackOperationAndProjection() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        saveConfigWithoutPlanning(fixture, publishConfig(fixture, true, true));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);

        Product otherProduct = new Product();
        otherProduct.setProductNo(productRepository.nextProductNoValue());
        otherProduct.setTitle("sales-channel-test-" + UUID.randomUUID());
        otherProduct.setRetailPrice(new BigDecimal("10.00"));
        otherProduct.setCostPrice(new BigDecimal("5.00"));
        otherProduct.setCategoryId(fixture.product().getCategoryId());
        otherProduct.setEnabled(true);
        Product savedOtherProduct = productRepository.saveAndFlush(otherProduct);
        SalesChannelListing otherListing = listingService.ensureDraft(fixture.account().getId(), savedOtherProduct.getId());
        SalesChannelListingGeneration otherCurrent = createGeneration(otherListing, "provider-item-owned-elsewhere");

        SalesChannelOperation ensure = operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        assertTrue(operationService.markRunning(ensure.getId()));
        assertThrows(IllegalStateException.class, () -> operationService.markSucceeded(ensure.getId(),
                Map.of("state", "SUCCEEDED", "external_item_id", "provider-item-owned-elsewhere"),
                Objects.toString(ensure.getPayload().get("desired_fingerprint"), null)));

        entityManager.clear();
        assertEquals(SalesChannelOperationStatus.RUNNING,
                operationRepository.findById(ensure.getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelGenerationState.CURRENT,
                generationRepository.findById(otherCurrent.getId()).orElseThrow().getGenerationState());
        assertNull(currentItem(fixture.listing().getId()));
        assertNull(candidateItem(fixture.listing().getId()));
        assertEquals(SalesChannelListingStatus.APPLYING,
                listingRepository.findById(fixture.listing().getId()).orElseThrow().getStatus());
    }

    @Test
    void accountTypeDoesNotChangeDesiredSnapshotOrCreateLegacyOperation() {
        Fixture fixture = fixture(false);
        accountService.upsert(SalesChannelProvider.XIANYU, fixture.account().getProviderAccountId(),
                fixture.account().getDisplayName(), SalesChannelAccountStatus.CONNECTED,
                Map.of("account_type", "PERSONAL"), LocalDateTime.now());
        savePublishConfigWithoutPlanning(fixture, true, true);
        SalesChannelListing enabled = desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        SalesChannelOperation ensure = operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        Map<String, Object> personalSnapshot = ensure.getPayload();
        String fingerprint = Objects.toString(personalSnapshot.get("desired_fingerprint"));

        accountService.upsert(SalesChannelProvider.XIANYU, fixture.account().getProviderAccountId(),
                fixture.account().getDisplayName(), SalesChannelAccountStatus.CONNECTED,
                Map.of("account_type", "FISH_SHOP"), LocalDateTime.now());
        SalesChannelListing refreshed = desiredStatePlanner.refresh(enabled.getId());

        assertEquals(1, refreshed.getDesiredRevision());
        assertEquals(fingerprint, refreshed.getDesiredFingerprint());
        assertEquals(1, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(enabled.getId()).size());
        assertEquals(SalesChannelOperationType.ENSURE_LISTING, ensure.getOperationType());
        assertEquals("ONLINE", ensure.getPayload().get("desired_state"));
        assertNull(candidateItem(enabled.getId()));
    }

    @Test
    void failedEnsureRetryKeepsRevisionAndFingerprintButGetsANewRequestId() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        saveConfigWithoutPlanning(fixture, publishConfig(fixture, true, true));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        SalesChannelOperation first = operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        String fingerprint = Objects.toString(first.getPayload().get("desired_fingerprint"));
        String firstRequestId = first.getIdempotencyKey();
        assertTrue(operationService.markRunning(first.getId()));
        assertTrue(operationService.markFailed(first.getId(), "AGENT_REJECTED", "snapshot was rejected"));

        desiredStatePlanner.refresh(fixture.listing().getId());
        assertEquals(1, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(
                fixture.listing().getId()).size());
        desiredStatePlanner.retry(fixture.listing().getId());

        List<SalesChannelOperation> sameRevision = operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(fixture.listing().getId(), 1);
        assertEquals(2, sameRevision.size());
        SalesChannelOperation retry = sameRevision.getLast();
        assertNotEquals(firstRequestId, retry.getIdempotencyKey());
        assertEquals(fingerprint, retry.getPayload().get("desired_fingerprint"));
        assertEquals(1, listingRepository.findById(fixture.listing().getId()).orElseThrow().getDesiredRevision());
        assertTrue(sameRevision.stream().allMatch(op -> op.getOperationType() == SalesChannelOperationType.ENSURE_LISTING));
    }

    @Test
    void staleEnsureLeaseRecoversByAgentReplayWithoutLocalUnknownState() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        saveConfigWithoutPlanning(fixture, publishConfig(fixture, true, true));
        desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        SalesChannelOperation ensure = operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        assertTrue(operationService.markRunning(ensure.getId()));

        jdbcTemplate.update("UPDATE sales_channel_operations SET started_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(10), ensure.getId());
        assertTrue(operationService.recoverStaleEnsureAsReplay(ensure.getId(), LocalDateTime.now().minusMinutes(5)));

        SalesChannelOperation recovered = operationRepository.findById(ensure.getId()).orElseThrow();
        SalesChannelListing listing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        assertEquals(SalesChannelOperationStatus.PENDING, recovered.getStatus());
        assertEquals("ENSURE_REPLAY_REQUIRED", recovered.getLastErrorCode());
        assertEquals(1, recovered.getAttemptCount());
        assertNull(recovered.getStartedAt());
        assertEquals(SalesChannelListingStatus.APPLYING, listing.getStatus());
        assertNotEquals(SalesChannelListingStatus.UNKNOWN, listing.getStatus());
    }

    @Test
    void invalidCurrentOnlineIntentCancelsOnlyNeverSentEnsureAndPreservesSentRecovery() {
        Fixture neverSentFixture = fixture(false);
        verifiedAccount(neverSentFixture, "FISH_SHOP");
        savePublishConfigWithoutPlanning(neverSentFixture, true, true);
        desiredStatePlanner.setEnabled(neverSentFixture.listing().getId(), true);
        SalesChannelOperation neverSent = operationAtRevision(
                neverSentFixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        savePublishConfigWithoutPlanning(neverSentFixture, false, false);

        SalesChannelListing invalidNeverSent = desiredStatePlanner.refresh(neverSentFixture.listing().getId());

        assertEquals(SalesChannelOperationStatus.SUPERSEDED,
                operationRepository.findById(neverSent.getId()).orElseThrow().getStatus());
        assertEquals("DESIRED_LISTING_INVALID", invalidNeverSent.getLastErrorCode());
        assertEquals(SalesChannelListingStatus.ERROR, invalidNeverSent.getStatus());
        assertEquals(1, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(
                neverSentFixture.listing().getId()).size());

        Fixture attemptedFixture = fixture(false);
        verifiedAccount(attemptedFixture, "FISH_SHOP");
        savePublishConfigWithoutPlanning(attemptedFixture, true, true);
        desiredStatePlanner.setEnabled(attemptedFixture.listing().getId(), true);
        SalesChannelOperation attempted = operationAtRevision(
                attemptedFixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        assertTrue(operationService.markRunning(attempted.getId()));
        assertTrue(operationService.reschedule(attempted.getId(), "AGENT_APPLYING", "same request replays",
                LocalDateTime.now().minusSeconds(1)));
        savePublishConfigWithoutPlanning(attemptedFixture, false, false);

        SalesChannelListing invalidAttempted = desiredStatePlanner.refresh(attemptedFixture.listing().getId());
        assertEquals("DESIRED_LISTING_INVALID", invalidAttempted.getLastErrorCode());
        assertEquals(SalesChannelOperationStatus.PENDING,
                operationRepository.findById(attempted.getId()).orElseThrow().getStatus());
        assertEquals(1, operationRepository.findById(attempted.getId()).orElseThrow().getAttemptCount());
        assertEquals(1, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(
                attemptedFixture.listing().getId()).size());

        assertTrue(operationService.markRunning(attempted.getId()));
        assertTrue(operationService.markSucceeded(attempted.getId(),
                Map.of("state", "SUCCEEDED", "external_item_id", "stale-valid-snapshot-item"),
                Objects.toString(attempted.getPayload().get("desired_fingerprint"), null)));
        SalesChannelListing afterStaleTerminal = desiredStatePlanner.refresh(attemptedFixture.listing().getId());
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(attempted.getId()).orElseThrow().getStatus());
        assertEquals(1, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(
                attemptedFixture.listing().getId()).size());
        assertEquals(0, afterStaleTerminal.getAppliedRevision());
        assertEquals("DESIRED_LISTING_INVALID", afterStaleTerminal.getLastErrorCode());
        assertEquals(SalesChannelListingStatus.ERROR, afterStaleTerminal.getStatus());
    }

    @Test
    void cutoverSupersedesOnlyUnstartedLegacyPendingCommands() {
        Fixture fixture = fixture(false);
        verifiedAccount(fixture, "FISH_SHOP");
        saveConfigWithoutPlanning(fixture, publishConfig(fixture, true, true));
        setOnlineIntentWithoutPlanning(fixture);
        SalesChannelOperation legacyPending = operationRepository.saveAndFlush(new SalesChannelOperation(
                fixture.listing().getId(), SalesChannelOperationType.PUBLISH,
                fixture.listing().getDesiredRevision(), "legacy-publish-unstarted-" + UUID.randomUUID(),
                Map.of("legacy", true)));

        desiredStatePlanner.refresh(fixture.listing().getId());

        assertEquals(SalesChannelOperationStatus.SUPERSEDED,
                operationRepository.findById(legacyPending.getId()).orElseThrow().getStatus());
        assertEquals(1, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(
                fixture.listing().getId()).stream()
                .filter(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING).count());
    }

    @Test
    void cutoverBlocksLegacyRunningReconcileAndCandidateHistory() {
        Fixture runningFixture = fixture(false);
        verifiedAccount(runningFixture, "FISH_SHOP");
        saveConfigWithoutPlanning(runningFixture, publishConfig(runningFixture, true, true));
        setOnlineIntentWithoutPlanning(runningFixture);
        SalesChannelOperation running = new SalesChannelOperation(runningFixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, runningFixture.listing().getDesiredRevision(),
                "legacy-publish-running-" + UUID.randomUUID(), Map.of());
        assertTrue(running.start(LocalDateTime.now()));
        operationRepository.saveAndFlush(running);
        desiredStatePlanner.refresh(runningFixture.listing().getId());
        SalesChannelListing blockedRunning = listingRepository.findById(runningFixture.listing().getId()).orElseThrow();
        assertEquals("LEGACY_OPERATION_UNRESOLVED", blockedRunning.getLastErrorCode());
        assertTrue(operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(runningFixture.listing().getId())
                .stream().noneMatch(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING));

        Fixture reconcileFixture = fixture(false);
        verifiedAccount(reconcileFixture, "FISH_SHOP");
        saveConfigWithoutPlanning(reconcileFixture, publishConfig(reconcileFixture, true, true));
        setOnlineIntentWithoutPlanning(reconcileFixture);
        operationRepository.saveAndFlush(new SalesChannelOperation(reconcileFixture.listing().getId(),
                SalesChannelOperationType.RECONCILE, reconcileFixture.listing().getDesiredRevision(),
                "legacy-reconcile-pending", Map.of()));
        desiredStatePlanner.refresh(reconcileFixture.listing().getId());
        assertEquals("LEGACY_OPERATION_UNRESOLVED",
                listingRepository.findById(reconcileFixture.listing().getId()).orElseThrow().getLastErrorCode());
        assertTrue(operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(reconcileFixture.listing().getId())
                .stream().noneMatch(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING));

        Fixture unknownFixture = fixture(false);
        verifiedAccount(unknownFixture, "FISH_SHOP");
        saveConfigWithoutPlanning(unknownFixture, publishConfig(unknownFixture, true, true));
        setOnlineIntentWithoutPlanning(unknownFixture);
        SalesChannelOperation unknown = new SalesChannelOperation(unknownFixture.listing().getId(),
                SalesChannelOperationType.PUBLISH, unknownFixture.listing().getDesiredRevision(),
                "legacy-publish-unknown-" + UUID.randomUUID(), Map.of());
        operationRepository.saveAndFlush(unknown);
        jdbcTemplate.update("UPDATE sales_channel_operations SET status = 'UNKNOWN' WHERE id = ?", unknown.getId());
        entityManager.clear();
        desiredStatePlanner.refresh(unknownFixture.listing().getId());
        assertEquals("LEGACY_OPERATION_UNRESOLVED",
                listingRepository.findById(unknownFixture.listing().getId()).orElseThrow().getLastErrorCode());
        assertTrue(operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(unknownFixture.listing().getId())
                .stream().noneMatch(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING));

        Fixture candidateFixture = fixture(false);
        verifiedAccount(candidateFixture, "FISH_SHOP");
        saveConfigWithoutPlanning(candidateFixture, publishConfig(candidateFixture, true, true));
        setOnlineIntentWithoutPlanning(candidateFixture);
        SalesChannelListingGeneration source = createGeneration(candidateFixture, "candidate-source-item");
        SalesChannelListingGeneration candidate = new SalesChannelListingGeneration(
                candidateFixture.listing().getId(), candidateFixture.account().getId(), 2L,
                "candidate-target-item", SalesChannelGenerationState.CANDIDATE, "REPUBLISH");
        candidate.setReplacesGenerationId(source.getId());
        generationRepository.saveAndFlush(candidate);
        desiredStatePlanner.refresh(candidateFixture.listing().getId());
        assertEquals("LEGACY_CANDIDATE_UNRESOLVED",
                listingRepository.findById(candidateFixture.listing().getId()).orElseThrow().getLastErrorCode());
        assertTrue(operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(candidateFixture.listing().getId())
                .stream().noneMatch(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING));
    }

    @Test
    void possiblySentEnsureBlocksNewerRevisionWithoutCreatingReconcileOrCandidate() {
        Fixture fixture = fixture(false);
        accountService.upsert(SalesChannelProvider.XIANYU, fixture.account().getProviderAccountId(),
                fixture.account().getDisplayName(), SalesChannelAccountStatus.CONNECTED,
                Map.of("account_type", "FISH_SHOP"), LocalDateTime.now());
        savePublishConfigWithoutPlanning(fixture, true, true);
        SalesChannelListing enabled = desiredStatePlanner.setEnabled(fixture.listing().getId(), true);
        SalesChannelOperation firstEnsure = operationAtRevision(fixture, 1, SalesChannelOperationType.ENSURE_LISTING);
        assertTrue(operationService.markRunning(firstEnsure.getId()));

        SalesChannelListing waitingForFirstPublish = desiredStatePlanner.refresh(enabled.getId());
        assertEquals(SalesChannelListingStatus.APPLYING, waitingForFirstPublish.getStatus());
        assertEquals(1, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(enabled.getId()).size());

        Product product = productRepository.findById(fixture.product().getId()).orElseThrow();
        product.setTitle(product.getTitle() + " newer");
        productRepository.saveAndFlush(product);
        SalesChannelListing revisionTwo = desiredStatePlanner.refresh(enabled.getId());
        assertEquals(2, revisionTwo.getDesiredRevision());
        assertTrue(operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(enabled.getId(), 2).isEmpty());

        assertTrue(operationService.reschedule(firstEnsure.getId(), "AGENT_APPLYING",
                "same request is replayable", LocalDateTime.now().plusSeconds(2)));
        assertEquals(SalesChannelOperationStatus.PENDING,
                operationRepository.findById(firstEnsure.getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelListingStatus.APPLYING,
                listingRepository.findById(enabled.getId()).orElseThrow().getStatus());
        desiredStatePlanner.refresh(enabled.getId());
        assertEquals(1, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(enabled.getId()).stream()
                .filter(operation -> operation.getOperationType() == SalesChannelOperationType.ENSURE_LISTING).count());
        assertEquals(0, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(enabled.getId()).stream()
                .filter(operation -> operation.getOperationType() == SalesChannelOperationType.RECONCILE).count());
        SalesChannelOperation replay = operationRepository.findById(firstEnsure.getId()).orElseThrow();
        replay.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        operationRepository.saveAndFlush(replay);
        assertTrue(operationService.markRunning(firstEnsure.getId()));
        assertTrue(operationService.markSucceeded(firstEnsure.getId(),
                Map.of("state", "SUCCEEDED", "external_item_id", "one-confirmed-item"),
                Objects.toString(firstEnsure.getPayload().get("desired_fingerprint"), null)));
        desiredStatePlanner.refresh(enabled.getId());
        SalesChannelOperation secondEnsure = operationAtRevision(fixture, 2, SalesChannelOperationType.ENSURE_LISTING);
        assertEquals(SalesChannelOperationStatus.PENDING, secondEnsure.getStatus());
        assertNull(candidateItem(enabled.getId()));
        assertEquals("one-confirmed-item", currentItem(enabled.getId()));
        assertEquals(0, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(enabled.getId()).stream()
                .filter(operation -> operation.getOperationType() == SalesChannelOperationType.RECONCILE).count());
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

        assertNull(currentItem(fixture.listing().getId()));
        assertNull(currentItem(secondListing.getId()));
        createGeneration(fixture, "remote-shared");
        assertThrows(DataAccessException.class, () -> createGeneration(secondListing, "remote-shared"));

        Fixture anotherAccount = fixture(false);
        createGeneration(anotherAccount, "remote-shared");
        assertEquals("remote-shared", currentItem(anotherAccount.listing().getId()));
    }

    @Test
    void operationPayloadAndResultAreDeepCopiedAcrossQueueAndReadBoundaries() {
        Fixture fixture = fixture(false);
        Map<String, Object> nestedInput = new LinkedHashMap<>();
        nestedInput.put("title", "original");
        List<Object> nestedList = new java.util.ArrayList<>();
        nestedList.add(nestedInput);
        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("listing_key", fixture.listing().getId().toString().toLowerCase());
        inputPayload.put("account_id", fixture.account().getProviderAccountId());
        inputPayload.put("revision", 0L);
        inputPayload.put("desired_state", "ONLINE");
        inputPayload.put("desired_snapshot", nestedList);
        inputPayload.put("desired_fingerprint", "a".repeat(64));
        SalesChannelOperation operation = operationRepository.saveAndFlush(new SalesChannelOperation(
                fixture.listing().getId(), SalesChannelOperationType.ENSURE_LISTING, 0,
                UUID.randomUUID().toString().toLowerCase(), inputPayload));
        nestedInput.put("title", "mutated after enqueue");
        nestedList.add("mutated after enqueue");
        assertEquals(List.of(Map.of("title", "original")), operation.getPayload().get("desired_snapshot"));

        Map<String, Object> exposedPayload = operation.getPayload();
        @SuppressWarnings("unchecked")
        List<Object> exposedNestedList = (List<Object>) exposedPayload.get("desired_snapshot");
        @SuppressWarnings("unchecked")
        Map<String, Object> exposedNestedMap = (Map<String, Object>) exposedNestedList.get(0);
        exposedNestedMap.put("title", "mutated through getter");
        assertEquals(List.of(Map.of("title", "original")), operation.getPayload().get("desired_snapshot"));

        assertTrue(operation.start(LocalDateTime.now()));
        Map<String, Object> nestedResult = new LinkedHashMap<>();
        nestedResult.put("confirmed", true);
        List<Object> resultList = new java.util.ArrayList<>(List.of(nestedResult));
        Map<String, Object> inputResult = new LinkedHashMap<>();
        inputResult.put("state", "SUCCEEDED");
        inputResult.put("external_item_id", "copy-test-item");
        inputResult.put("details", resultList);
        assertTrue(operation.succeed(inputResult, LocalDateTime.now()));
        operationRepository.saveAndFlush(operation);
        nestedResult.put("confirmed", false);
        resultList.add("late mutation");
        assertEquals(List.of(Map.of("confirmed", true)),
                operationRepository.findById(operation.getId()).orElseThrow().getResult().get("details"));
    }

    @Test
    void durableQueueSelectsDuePendingOperationsInOrderAndSupportsSkipLockedClaims() {
        Fixture firstFixture = fixture(false);
        Fixture secondFixture = fixture(false);
        Fixture futureFixture = fixture(false);
        SalesChannelOperation first = queuedEnsure(firstFixture, "queue-ensure-first");
        SalesChannelOperation second = queuedEnsure(secondFixture, "queue-ensure-second");
        SalesChannelOperation future = queuedEnsure(futureFixture, "queue-ensure-future");
        LocalDateTime now = LocalDateTime.now();
        first.setNextAttemptAt(now.minusMinutes(2));
        second.setNextAttemptAt(now.minusMinutes(1));
        future.setNextAttemptAt(now.plusHours(1));
        operationRepository.saveAllAndFlush(List.of(first, second, future));

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

    private SalesChannelOperation queuedEnsure(Fixture fixture, String requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account_id", fixture.account().getProviderAccountId());
        payload.put("listing_key", fixture.listing().getId().toString().toLowerCase());
        payload.put("revision", fixture.listing().getDesiredRevision());
        payload.put("desired_state", "OFFLINE");
        payload.put("desired_snapshot", null);
        payload.put("desired_fingerprint", "a".repeat(64));
        return operationRepository.saveAndFlush(new SalesChannelOperation(
                fixture.listing().getId(), SalesChannelOperationType.ENSURE_LISTING,
                fixture.listing().getDesiredRevision(), requestId, payload));
    }

    @SuppressWarnings("unchecked")
    private void assertSkuSnapshot(SalesChannelOperation operation, List<Integer> expectedStocks, int expectedTotal) {
        Map<String, Object> snapshot = (Map<String, Object>) operation.getPayload().get("desired_snapshot");
        assertNotNull(snapshot);
        assertEquals(expectedTotal, ((Number) snapshot.get("stock")).intValue());
        Object rawRows = snapshot.get("skus");
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
        config.put("imageUrls", List.of(PREFLIGHT_IMAGE_URL));
        config.put("priceMode", "PRODUCT_RETAIL");
        config.put("address", "East Lake");
        config.put("addressExpectedText", "East Lake POI");
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
        createGeneration(listing, "existing-item-" + UUID.randomUUID());
    }

    private SalesChannelListingGeneration createGeneration(Fixture fixture, String itemId) {
        return createGeneration(fixture.listing(), itemId);
    }

    private SalesChannelListingGeneration createGeneration(SalesChannelListing listing, String itemId) {
        SalesChannelListingGeneration generation = new SalesChannelListingGeneration(
                listing.getId(), listing.getSalesChannelAccountId(), 1L, itemId,
                SalesChannelGenerationState.CURRENT, "LEGACY_MIGRATION");
        generation.setActivatedAt(LocalDateTime.now());
        return generationRepository.saveAndFlush(generation);
    }

    private void insertGenerationSpec(SalesChannelListingGeneration generation, UUID productSpecId) {
        SalesChannelListingGenerationSpec mapping = new SalesChannelListingGenerationSpec();
        mapping.setSalesChannelListingGenerationId(generation.getId());
        mapping.setProductSpecId(productSpecId);
        generationSpecRepository.saveAndFlush(mapping);
    }

    private String currentItem(UUID listingId) {
        return generationRepository.findCurrentByListingId(listingId)
                .map(SalesChannelListingGeneration::getExternalItemId)
                .orElse(null);
    }

    private String candidateItem(UUID listingId) {
        return generationRepository.findCandidateByListingId(listingId)
                .map(SalesChannelListingGeneration::getExternalItemId)
                .orElse(null);
    }

    private void completeEnsure(SalesChannelOperation operation, String itemId) {
        assertEquals(SalesChannelOperationType.ENSURE_LISTING, operation.getOperationType());
        assertTrue(operationService.markRunning(operation.getId()));
        assertTrue(operationService.markSucceeded(operation.getId(),
                Map.of("state", "SUCCEEDED", "external_item_id", itemId),
                Objects.toString(operation.getPayload().get("desired_fingerprint"), null)));
        assertNull(candidateItem(operation.getSalesChannelListingId()));
        assertEquals(itemId, currentItem(operation.getSalesChannelListingId()));
        SalesChannelListingGeneration current = generationRepository
                .findCurrentByListingId(operation.getSalesChannelListingId()).orElseThrow();
        assertEquals("CONVERGENCE", current.getOrigin());
        SalesChannelListing listing = listingRepository.findById(operation.getSalesChannelListingId()).orElseThrow();
        assertEquals(itemId, listing.getConvergenceAdoptedExternalItemId());
        assertNotNull(listing.getConvergenceAdoptedAt());
    }

    private void verifiedAccount(Fixture fixture, String accountType) {
        accountService.upsert(SalesChannelProvider.XIANYU, fixture.account().getProviderAccountId(),
                fixture.account().getDisplayName(), SalesChannelAccountStatus.CONNECTED,
                Map.of("account_type", accountType), LocalDateTime.now());
    }

    private SalesChannelListing setOnlineIntentWithoutPlanning(Fixture fixture) {
        SalesChannelListing listing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        listing.setEnabled(true);
        listing.setDesiredState(SalesChannelListingDesiredState.ONLINE);
        return listingRepository.saveAndFlush(listing);
    }

    private void savePublishConfig(Fixture fixture, boolean includeAddress, boolean includeCategory) {
        salesChannelAdminService.updateXianyuListing(
                fixture.product().getId(),
                fixture.account().getId(),
                publishConfig(fixture, includeAddress, includeCategory));
    }

    private void savePublishConfigWithoutPlanning(Fixture fixture, boolean includeAddress, boolean includeCategory) {
        saveConfigWithoutPlanning(fixture, publishConfig(fixture, includeAddress, includeCategory));
    }

    private void saveConfigWithoutPlanning(Fixture fixture,
            com.myshop.saleschannel.xianyu.model.XianyuProviderConfig config) {
        SalesChannelListing listing = listingRepository.findById(fixture.listing().getId()).orElseThrow();
        listing.setProviderConfig(objectMapper.convertValue(config, Map.class));
        listingRepository.saveAndFlush(listing);
    }

    private void assertOnlyDraftOfflineCommandRemains(UUID listingId) {
        List<SalesChannelOperation> operations = operationRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(listingId);
        assertEquals(1, operations.size());
        SalesChannelOperation operation = operations.getFirst();
        assertEquals(SalesChannelOperationType.ENSURE_LISTING, operation.getOperationType());
        assertEquals("OFFLINE", operation.getPayload().get("desired_state"));
        assertNull(operation.getPayload().get("desired_snapshot"));
        assertEquals(SalesChannelOperationStatus.PENDING, operation.getStatus());
    }

    private com.myshop.saleschannel.xianyu.model.XianyuProviderConfig publishConfig(
            Fixture fixture, boolean includeAddress, boolean includeCategory) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("descriptionOverride", "Test listing description");
        config.put("imageUrls", List.of(PREFLIGHT_IMAGE_URL));
        config.put("priceMode", "PRODUCT_RETAIL");
        if (includeAddress) {
            config.put("address", "East Lake");
            config.put("addressExpectedText", "East Lake POI");
        }
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
