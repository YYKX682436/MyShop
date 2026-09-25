package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.Product;
import com.myshop.entity.ProductCategory;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.exception.BusinessException;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuItemBindingResponse;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuLinkCandidate;
import com.myshop.saleschannel.model.SalesChannelDtos.CatalogItem;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryData;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.service.impl.SalesChannelAccountServiceImpl;
import com.myshop.service.impl.SalesChannelListingServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class XianyuItemBindingPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private ProductCategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private SalesChannelAccountRepository accountRepository;
    @Autowired private SalesChannelListingRepository listingRepository;
    @Autowired private SalesChannelOperationRepository operationRepository;
    @Autowired private SalesChannelAccountServiceImpl accountService;
    @Autowired private SalesChannelListingServiceImpl listingService;
    @Autowired private SalesChannelDesiredStatePlanner desiredStatePlanner;
    @Autowired private SalesChannelAdminService adminService;
    @Autowired private XianyuItemBindingService bindingService;

    @MockitoBean
    private SalesChannelProviderClient providerClient;

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
                "xianyu-item-binding-integration-secret-with-at-least-64-bytes-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
        registry.add("sales-channel.worker.enabled", () -> false);
        registry.add("sales-channel.order-sync.enabled", () -> false);
        registry.add("sales-channel.delivery.worker-enabled", () -> false);
    }

    @BeforeEach
    void cleanFixtures() {
        reset(providerClient);
        jdbcTemplate.update("DELETE FROM sales_channel_delivery_operations");
        jdbcTemplate.update("DELETE FROM sales_channel_orders");
        jdbcTemplate.update("DELETE FROM sales_channel_order_cursors");
        jdbcTemplate.update("DELETE FROM sales_channel_operations");
        jdbcTemplate.update("DELETE FROM sales_channel_listing_specs");
        jdbcTemplate.update("DELETE FROM sales_channel_listings");
        jdbcTemplate.update("DELETE FROM sales_channel_accounts");
        jdbcTemplate.update("DELETE FROM products WHERE title LIKE 'phase2b-binding-%'");
        jdbcTemplate.update("DELETE FROM product_categories WHERE name LIKE 'phase2b-binding-%'");
        entityManager.clear();
    }

    @Test
    void unlinkedItemCreatesLinkedOnlyListingWithoutOperations() {
        AccountProduct fixture = accountAndProduct("phase2b-binding-new");
        String itemId = "phase2b-item-new";
        stubExactCatalog();

        XianyuItemBindingResponse result = bindingService.link(
                fixture.account().getId(), itemId, fixture.product().getId());

        SalesChannelListing listing = listingRepository
                .findBySalesChannelAccountIdAndProductId(fixture.account().getId(), fixture.product().getId())
                .orElseThrow();
        assertEquals("LINKED", result.result());
        assertEquals(itemId, result.itemId());
        assertEquals(SalesChannelListingManagementMode.LINKED_ONLY.name(), result.managementMode());
        assertEquals(SalesChannelListingManagementMode.LINKED_ONLY, listing.getManagementMode());
        assertEquals(itemId, listing.getExternalItemId());
        assertFalse(listing.isEnabled());
        assertFalse(listing.isAutoFulfillmentEnabled());
        assertEquals("OFFLINE", listing.getDesiredState().name());
        assertEquals(SalesChannelListingStatus.DRAFT, listing.getStatus());
        assertEquals(0, listing.getDesiredRevision());
        assertEquals(0, listing.getAppliedRevision());
        assertNull(listing.getDesiredFingerprint());
        assertNull(listing.getAppliedFingerprint());
        assertEquals(0, count("SELECT count(*) FROM sales_channel_operations"));
        verify(providerClient).queryItems(new ItemsQueryCommand(
                fixture.account().getProviderAccountId(), 1, 100, itemId));
        verify(providerClient, never()).syncItems(any());
    }

    @Test
    void repeatedSameBindingIsIdempotentAndLeavesOneRow() {
        AccountProduct fixture = accountAndProduct("phase2b-binding-idempotent");
        String itemId = "phase2b-item-idempotent";
        stubExactCatalog();

        XianyuItemBindingResponse first = bindingService.link(
                fixture.account().getId(), itemId, fixture.product().getId());
        XianyuItemBindingResponse second = bindingService.link(
                fixture.account().getId(), itemId, fixture.product().getId());

        assertEquals("LINKED", first.result());
        assertEquals("ALREADY_LINKED", second.result());
        assertEquals(1, count("SELECT count(*) FROM sales_channel_listings WHERE sales_channel_account_id = ?",
                fixture.account().getId()));
        assertEquals(0, count("SELECT count(*) FROM sales_channel_operations"));
        SalesChannelListing listing = listingRepository
                .findBySalesChannelAccountIdAndProductId(fixture.account().getId(), fixture.product().getId())
                .orElseThrow();
        assertEquals(SalesChannelListingManagementMode.LINKED_ONLY, listing.getManagementMode());
    }

    @Test
    void itemAlreadyLinkedToOtherProductIsConflictWithoutMutation() {
        SalesChannelAccount account = account("phase2b-item-conflict-account");
        Product firstProduct = product("phase2b-binding-item-first");
        Product secondProduct = product("phase2b-binding-item-second");
        String itemId = "phase2b-item-conflict";
        stubExactCatalog();
        bindingService.link(account.getId(), itemId, firstProduct.getId());
        int beforeListings = count("SELECT count(*) FROM sales_channel_listings");
        int beforeOperations = count("SELECT count(*) FROM sales_channel_operations");

        BusinessException error = assertThrows(BusinessException.class,
                () -> bindingService.link(account.getId(), itemId, secondProduct.getId()));

        assertEquals(ErrorCode.ITEM_ALREADY_LINKED, error.getCode());
        assertEquals(409, error.getHttpStatus().value());
        assertEquals(beforeListings, count("SELECT count(*) FROM sales_channel_listings"));
        assertEquals(beforeOperations, count("SELECT count(*) FROM sales_channel_operations"));
        assertEquals(1, count("SELECT count(*) FROM sales_channel_listings WHERE external_item_id = ?", itemId));
    }

    @Test
    void productAlreadyLinkedToOtherItemIsConflictWithoutMutation() {
        AccountProduct fixture = accountAndProduct("phase2b-binding-product-conflict");
        String firstItem = "phase2b-item-product-first";
        String secondItem = "phase2b-item-product-second";
        stubExactCatalog();
        bindingService.link(fixture.account().getId(), firstItem, fixture.product().getId());

        BusinessException error = assertThrows(BusinessException.class,
                () -> bindingService.link(fixture.account().getId(), secondItem, fixture.product().getId()));

        assertEquals(ErrorCode.PRODUCT_ALREADY_LINKED, error.getCode());
        assertEquals(409, error.getHttpStatus().value());
        assertEquals(1, count("SELECT count(*) FROM sales_channel_listings"));
        assertEquals(firstItem, listingRepository
                .findBySalesChannelAccountIdAndProductId(fixture.account().getId(), fixture.product().getId())
                .orElseThrow().getExternalItemId());
    }

    @Test
    void safeNullIdentityDraftReusesTheSameListingAndPreservesState() {
        AccountProduct fixture = accountAndProduct("phase2b-binding-reuse");
        fixture.product().setEnabled(false);
        productRepository.saveAndFlush(fixture.product());
        SalesChannelListing existing = listingService.ensureDraft(
                fixture.account().getId(), fixture.product().getId());
        existing.setProviderConfig(Map.of("local", "keep"));
        existing.setStatus(SalesChannelListingStatus.OFFLINE);
        existing = listingRepository.saveAndFlush(existing);
        UUID listingId = existing.getId();
        long desiredRevision = existing.getDesiredRevision();
        long appliedRevision = existing.getAppliedRevision();
        String desiredFingerprint = existing.getDesiredFingerprint();
        String appliedFingerprint = existing.getAppliedFingerprint();
        stubExactCatalog();

        XianyuItemBindingResponse result = bindingService.link(
                fixture.account().getId(), "phase2b-item-reuse", fixture.product().getId());

        SalesChannelListing linked = listingRepository.findById(listingId).orElseThrow();
        assertEquals("LINKED", result.result());
        assertEquals(listingId, linked.getId());
        assertEquals(SalesChannelListingManagementMode.LINKED_ONLY, linked.getManagementMode());
        assertEquals(desiredRevision, linked.getDesiredRevision());
        assertEquals(appliedRevision, linked.getAppliedRevision());
        assertEquals(desiredFingerprint, linked.getDesiredFingerprint());
        assertEquals(appliedFingerprint, linked.getAppliedFingerprint());
        assertEquals(Map.of("local", "keep"), linked.getProviderConfig());
        assertEquals(SalesChannelListingStatus.OFFLINE, linked.getStatus());
        assertEquals(0, count("SELECT count(*) FROM sales_channel_operations"));
    }

    @Test
    void nullIdentityListingWithOperationHistoryIsNotLinkable() {
        AccountProduct fixture = accountAndProduct("phase2b-binding-operation-history");
        fixture.product().setEnabled(false);
        productRepository.saveAndFlush(fixture.product());
        SalesChannelListing listing = listingService.ensureDraft(
                fixture.account().getId(), fixture.product().getId());
        operationRepository.saveAndFlush(new SalesChannelOperation(listing.getId(),
                SalesChannelOperationType.PUBLISH, 0, "phase2b-history-" + UUID.randomUUID(), Map.of()));
        stubExactCatalog();

        BusinessException error = assertThrows(BusinessException.class,
                () -> bindingService.link(fixture.account().getId(), "phase2b-item-history",
                        fixture.product().getId()));

        assertEquals(ErrorCode.LISTING_NOT_LINKABLE, error.getCode());
        assertNull(listingRepository.findById(listing.getId()).orElseThrow().getExternalItemId());
    }

    @Test
    void nullIdentityListingEnabledOrAutoFulfillmentOrUnsafeStatusIsNotLinkable() {
        assertNullListingRejected(true, false, SalesChannelListingStatus.DRAFT);
        assertNullListingRejected(false, true, SalesChannelListingStatus.DRAFT);
        assertNullListingRejected(false, false, SalesChannelListingStatus.ONLINE);
    }

    @Test
    void missingProductReturnsProductNotFoundAndExactCatalogMissReturnsXianyuNotFound() {
        AccountProduct fixture = accountAndProduct("phase2b-binding-missing-product");
        UUID missingProductId = UUID.randomUUID();
        stubExactCatalog();

        BusinessException productError = assertThrows(BusinessException.class,
                () -> bindingService.link(fixture.account().getId(), "phase2b-item-product-missing", missingProductId));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, productError.getCode());
        assertEquals(404, productError.getHttpStatus().value());
        assertEquals(0, count("SELECT count(*) FROM sales_channel_listings"));

        doAnswer(invocation -> {
            ItemsQueryCommand command = invocation.getArgument(0);
            CatalogItem other = catalogItem(command.accountId(), "different-item");
            return new IntegrationEnvelope<>("not-found", Outcome.SUCCEEDED,
                    new ItemsQueryData(List.of(other), 1, 100, 1L, 1), null);
        }).when(providerClient).queryItems(any(ItemsQueryCommand.class));
        BusinessException itemError = assertThrows(BusinessException.class,
                () -> bindingService.link(fixture.account().getId(), "phase2b-item-not-found", fixture.product().getId()));
        assertEquals(ErrorCode.XIANYU_ITEM_NOT_FOUND, itemError.getCode());
        assertEquals(404, itemError.getHttpStatus().value());
        assertEquals(0, count("SELECT count(*) FROM sales_channel_listings"));
    }

    @Test
    void candidatePageUsesBatchEligibilityAndDoesNotLoadWholeProductDetails() {
        SalesChannelAccount account = account("phase2b-candidate-account");
        Product available = product("phase2b-binding-available");
        Product current = product("phase2b-binding-current");
        Product other = product("phase2b-binding-other");
        Product unsafe = product("phase2b-binding-unsafe");
        listingService.ensureDraft(account.getId(), current.getId());
        SalesChannelListing currentListing = listingRepository
                .findBySalesChannelAccountIdAndProductId(account.getId(), current.getId()).orElseThrow();
        currentListing.bindExternalItemId("candidate-current");
        listingRepository.saveAndFlush(currentListing);
        SalesChannelListing otherListing = listingService.ensureDraft(account.getId(), other.getId());
        otherListing.bindExternalItemId("candidate-other");
        listingRepository.saveAndFlush(otherListing);
        SalesChannelListing unsafeListing = listingService.ensureDraft(account.getId(), unsafe.getId());
        unsafeListing.setEnabled(true);
        listingRepository.saveAndFlush(unsafeListing);

        var page = adminService.queryXianyuLinkCandidates(account.getId(), "candidate-current", 1, 10,
                "phase2b-binding-");

        Map<String, XianyuLinkCandidate> byTitle = page.candidates().stream()
                .collect(java.util.stream.Collectors.toMap(XianyuLinkCandidate::title, value -> value));
        assertEquals("AVAILABLE", byTitle.get(available.getTitle()).eligibility());
        assertEquals("ALREADY_LINKED_THIS_ITEM", byTitle.get(current.getTitle()).eligibility());
        assertEquals("LINKED_TO_OTHER_ITEM", byTitle.get(other.getTitle()).eligibility());
        assertEquals("LISTING_NOT_LINKABLE", byTitle.get(unsafe.getTitle()).eligibility());
        assertEquals(4, page.total());
    }

    @Test
    void linkedOnlyPlannerAndAutoFulfillmentHaveNoSideEffects() {
        AccountProduct fixture = accountAndProduct("phase2b-binding-safety");
        String itemId = "phase2b-item-safety";
        stubExactCatalog();
        bindingService.link(fixture.account().getId(), itemId, fixture.product().getId());
        SalesChannelListing before = listingRepository
                .findBySalesChannelAccountIdAndProductId(fixture.account().getId(), fixture.product().getId())
                .orElseThrow();
        long desiredRevision = before.getDesiredRevision();
        long appliedRevision = before.getAppliedRevision();
        String desiredFingerprint = before.getDesiredFingerprint();
        String appliedFingerprint = before.getAppliedFingerprint();
        int operationCount = count("SELECT count(*) FROM sales_channel_operations");

        desiredStatePlanner.refresh(before.getId());
        listingService.updateProviderConfig(before.getId(), Map.of("local", "config-only"));
        assertThrows(BusinessException.class, () -> desiredStatePlanner.setEnabled(before.getId(), true));
        BusinessException retryError = assertThrows(BusinessException.class,
                () -> desiredStatePlanner.retry(before.getId()));
        BusinessException fulfillmentError = assertThrows(BusinessException.class,
                () -> adminService.setAutoFulfillmentEnabled(before.getId(), true));

        assertEquals(ErrorCode.LINKED_ITEM_NOT_MANAGED, retryError.getCode());
        assertEquals(ErrorCode.LINKED_ITEM_NOT_MANAGED, fulfillmentError.getCode());
        SalesChannelListing after = listingRepository.findById(before.getId()).orElseThrow();
        assertEquals(SalesChannelListingManagementMode.LINKED_ONLY, after.getManagementMode());
        assertEquals(desiredRevision, after.getDesiredRevision());
        assertEquals(appliedRevision, after.getAppliedRevision());
        assertEquals(desiredFingerprint, after.getDesiredFingerprint());
        assertEquals(appliedFingerprint, after.getAppliedFingerprint());
        assertEquals(Map.of("local", "config-only"), after.getProviderConfig());
        assertEquals(operationCount, count("SELECT count(*) FROM sales_channel_operations"));
        assertFalse(after.isAutoFulfillmentEnabled());
    }

    @Test
    void existingManagedBindingIsIdempotentAndNeverDowngraded() {
        AccountProduct fixture = accountAndProduct("phase2b-binding-managed-regression");
        String itemId = "1084013851108";
        SalesChannelListing listing = listingService.ensureDraft(
                fixture.account().getId(), fixture.product().getId());
        listing.bindExternalItemId(itemId);
        listing.setManagementMode(SalesChannelListingManagementMode.MANAGED);
        listing.setStatus(SalesChannelListingStatus.OFFLINE);
        listing = listingRepository.saveAndFlush(listing);
        stubExactCatalog();

        XianyuItemBindingResponse result = bindingService.link(
                fixture.account().getId(), itemId, fixture.product().getId());

        assertEquals("ALREADY_LINKED", result.result());
        assertEquals("MANAGED", result.managementMode());
        assertEquals(SalesChannelListingManagementMode.MANAGED,
                listingRepository.findById(listing.getId()).orElseThrow().getManagementMode());
    }

    @Test
    void catalogVerificationRunsOutsideBindingTransaction() {
        AccountProduct fixture = accountAndProduct("phase2b-binding-network-boundary");
        java.util.concurrent.atomic.AtomicBoolean transactionActiveDuringQuery =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        when(providerClient.queryItems(any())).thenAnswer(invocation -> {
            transactionActiveDuringQuery.set(TransactionSynchronizationManager.isActualTransactionActive());
            ItemsQueryCommand command = invocation.getArgument(0);
            return new IntegrationEnvelope<>("boundary", Outcome.SUCCEEDED,
                    new ItemsQueryData(List.of(catalogItem(command.accountId(), command.keyword())),
                            1, 100, 1L, 1), null);
        });

        bindingService.link(fixture.account().getId(), "phase2b-item-boundary", fixture.product().getId());

        assertFalse(transactionActiveDuringQuery.get());
    }

    @Test
    void sameItemConcurrentProductsSerializeOnAccountAndReturnDomainConflict() throws Exception {
        SalesChannelAccount account = account("phase2b-concurrent-item-account");
        Product first = product("phase2b-binding-concurrent-first");
        Product second = product("phase2b-binding-concurrent-second");
        stubExactCatalog();
        String itemId = "phase2b-item-concurrent";

        List<Object> results = concurrentlyLink(account.getId(), itemId, first.getId(), second.getId());

        assertEquals(1, results.stream().filter(value -> value instanceof XianyuItemBindingResponse).count());
        assertEquals(1, results.stream().filter(value -> value instanceof BusinessException error
                && error.getCode() == ErrorCode.ITEM_ALREADY_LINKED).count());
        assertEquals(1, count("SELECT count(*) FROM sales_channel_listings WHERE sales_channel_account_id = ?",
                account.getId()));
        assertEquals(1, count("SELECT count(*) FROM sales_channel_listings WHERE sales_channel_account_id = ? AND external_item_id = ?",
                account.getId(), itemId));
        assertTrue(results.stream().noneMatch(value -> value instanceof DataIntegrityViolationException));
    }

    @Test
    void sameProductConcurrentItemsSerializeOnAccountAndReturnDomainConflict() throws Exception {
        SalesChannelAccount account = account("phase2b-concurrent-product-account");
        Product product = product("phase2b-binding-concurrent-product");
        stubExactCatalog();

        List<Object> results = concurrentlyLink(account.getId(), "phase2b-item-a", product.getId(),
                product.getId(), "phase2b-item-b");

        assertEquals(1, results.stream().filter(value -> value instanceof XianyuItemBindingResponse).count());
        assertEquals(1, results.stream().filter(value -> value instanceof BusinessException error
                && error.getCode() == ErrorCode.PRODUCT_ALREADY_LINKED).count());
        assertEquals(1, count("SELECT count(*) FROM sales_channel_listings WHERE sales_channel_account_id = ? AND product_id = ?",
                account.getId(), product.getId()));
        assertTrue(results.stream().noneMatch(value -> value instanceof DataIntegrityViolationException));
    }

    private void assertNullListingRejected(boolean enabled, boolean autoFulfillment,
                                           SalesChannelListingStatus status) {
        AccountProduct fixture = accountAndProduct("phase2b-binding-unsafe-" + UUID.randomUUID());
        fixture.product().setEnabled(false);
        productRepository.saveAndFlush(fixture.product());
        SalesChannelListing listing = listingService.ensureDraft(
                fixture.account().getId(), fixture.product().getId());
        listing.setEnabled(enabled);
        listing.setAutoFulfillmentEnabled(autoFulfillment);
        listing.setStatus(status);
        listingRepository.saveAndFlush(listing);
        stubExactCatalog();

        BusinessException error = assertThrows(BusinessException.class,
                () -> bindingService.link(fixture.account().getId(), "phase2b-item-unsafe-" + UUID.randomUUID(),
                        fixture.product().getId()));
        assertEquals(ErrorCode.LISTING_NOT_LINKABLE, error.getCode());
        assertNull(listingRepository.findById(listing.getId()).orElseThrow().getExternalItemId());
    }

    private List<Object> concurrentlyLink(UUID accountId, String firstItemId, UUID firstProductId,
                                          UUID secondProductId) throws Exception {
        return concurrentlyLink(accountId, firstItemId, firstProductId, secondProductId, firstItemId);
    }

    private List<Object> concurrentlyLink(UUID accountId, String firstItemId, UUID firstProductId,
                                          UUID secondProductId, String secondItemId) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> first = concurrentCall(ready, start, accountId, firstItemId, firstProductId);
            Callable<Object> second = concurrentCall(ready, start, accountId, secondItemId, secondProductId);
            Future<Object> firstResult = executor.submit(first);
            Future<Object> secondResult = executor.submit(second);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(get(firstResult), get(secondResult));
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<Object> concurrentCall(CountDownLatch ready, CountDownLatch start,
                                             UUID accountId, String itemId, UUID productId) {
        return () -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            try {
                return bindingService.link(accountId, itemId, productId);
            } catch (RuntimeException error) {
                return error;
            }
        };
    }

    private Object get(Future<Object> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(20, TimeUnit.SECONDS);
    }

    private void stubExactCatalog() {
        doAnswer(invocation -> {
            ItemsQueryCommand command = invocation.getArgument(0);
            return new IntegrationEnvelope<>("query-" + UUID.randomUUID(), Outcome.SUCCEEDED,
                    new ItemsQueryData(List.of(catalogItem(command.accountId(), command.keyword())),
                            1, 100, 1L, 1), null);
        }).when(providerClient).queryItems(any(ItemsQueryCommand.class));
    }

    private CatalogItem catalogItem(String accountId, String itemId) {
        return new CatalogItem(accountId, itemId, "remote title", "10.00", 1,
                "在售", 0, false, true, null, null);
    }

    private AccountProduct accountAndProduct(String title) {
        return new AccountProduct(account("account-" + UUID.randomUUID()), product(title));
    }

    private SalesChannelAccount account(String providerAccountId) {
        return accountService.upsert(SalesChannelProvider.XIANYU, providerAccountId,
                providerAccountId, SalesChannelAccountStatus.CONNECTED, Map.of("account_type", "FISH_SHOP"),
                LocalDateTime.now());
    }

    private Product product(String title) {
        ProductCategory category = new ProductCategory();
        category.setName("phase2b-binding-" + UUID.randomUUID());
        category = categoryRepository.saveAndFlush(category);
        Product product = new Product();
        product.setProductNo(productRepository.nextProductNoValue());
        product.setTitle(title);
        product.setRetailPrice(new BigDecimal("10.00"));
        product.setCostPrice(new BigDecimal("5.00"));
        product.setCategoryId(category.getId());
        product.setEnabled(true);
        return productRepository.saveAndFlush(product);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private record AccountProduct(SalesChannelAccount account, Product product) { }
}
