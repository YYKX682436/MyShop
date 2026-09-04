package com.myshop.service.impl;

import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.StockMode;
import com.myshop.entity.CardKey;
import com.myshop.entity.ManualStock;
import com.myshop.entity.Product;
import com.myshop.entity.ProductCategory;
import com.myshop.entity.ProductLevelPriceOverride;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.AdminProductOverrideRequest;
import com.myshop.model.request.AdminProductSpecRequest;
import com.myshop.model.request.AdminProductUpsertRequest;
import com.myshop.model.response.AdminProductResponse;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.ManualStockRepository;
import com.myshop.repository.ProductCategoryRepository;
import com.myshop.repository.ProductLevelPriceOverrideRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.service.AdminProductService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises assigned ProductSpec UUIDs against the real PostgreSQL/JPA stack.
 * These tests intentionally flush and clear before reloading rows so a
 * first-level persistence-context hit cannot hide an incorrect insert/update
 * decision.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@SpringBootTest
class AdminProductAssignedUuidIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductSpecRepository productSpecRepository;

    @Autowired
    private ProductCategoryRepository categoryRepository;

    @Autowired
    private UserLevelRepository userLevelRepository;

    @Autowired
    private ProductLevelPriceOverrideRepository overrideRepository;

    @Autowired
    private ManualStockRepository manualStockRepository;

    @Autowired
    private CardKeyRepository cardKeyRepository;

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
                "assigned-uuid-integration-secret-with-at-least-64-bytes-and-random-looking-value-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
    }

    @Test
    void explicitlyAssignedProductSpecUuidSurvivesFlushClearAndReload() {
        Product product = persistStandaloneProduct();
        UUID clientSpecId = UUID.randomUUID();

        ProductSpec spec = productSpec(clientSpecId, product.getId(), "explicit-client-id");
        ProductSpec saved = productSpecRepository.saveAndFlush(spec);

        assertEquals(clientSpecId, saved.getId());
        assertFalse(saved.isNew());

        entityManager.clear();

        ProductSpec reloaded = productSpecRepository.findById(clientSpecId).orElseThrow();
        assertEquals(clientSpecId, reloaded.getId());
        assertEquals(product.getId(), reloaded.getProductId());
        assertFalse(reloaded.isNew());
    }

    @Test
    void newProductWithClientUuidSpecCommitsTheSameSpecId() {
        UUID categoryId = persistCategory().getId();
        UUID clientSpecId = UUID.randomUUID();

        AdminProductResponse response = adminProductService.createProduct(
                productRequest(categoryId, "new-product-with-client-uuid", "AUTO", StockMode.CARD_KEY,
                        null, List.of(specRequest(clientSpecId, "one month", "5.00", "10.00", true, 0, null)),
                        List.of()));

        assertEquals(clientSpecId, response.specs().get(0).id());
        assertEquals(1L, count("SELECT count(*) FROM product_specs WHERE product_id = ? AND id = ?",
                response.id(), clientSpecId));

        entityManager.clear();

        ProductSpec persisted = productSpecRepository.findById(clientSpecId).orElseThrow();
        assertEquals(response.id(), persisted.getProductId());
        assertEquals(clientSpecId, persisted.getId());
    }

    @Test
    void updatingExistingProductCanAddAClientUuidSpec() {
        UUID categoryId = persistCategory().getId();
        UUID existingSpecId = UUID.randomUUID();
        UUID newSpecId = UUID.randomUUID();
        AdminProductResponse created = adminProductService.createProduct(
                productRequest(categoryId, "product-before-spec-add", "AUTO", StockMode.CARD_KEY,
                        null, List.of(specRequest(existingSpecId, "one month", "5.00", "10.00", true, 0, null)),
                        List.of()));

        AdminProductResponse updated = adminProductService.updateProduct(created.id(),
                productRequest(categoryId, "product-after-spec-add", "AUTO", StockMode.CARD_KEY,
                        null, List.of(
                                specRequest(existingSpecId, "one month", "5.00", "10.00", true, 0, null),
                                specRequest(newSpecId, "six months", "8.00", "18.00", true, 1, null)),
                        List.of()));

        assertEquals(2, updated.specs().size());
        assertEquals(newSpecId, updated.specs().stream()
                .filter(spec -> "six months".equals(spec.name()))
                .findFirst().orElseThrow().id());

        entityManager.clear();

        ProductSpec persisted = productSpecRepository.findById(newSpecId).orElseThrow();
        assertEquals(created.id(), persisted.getProductId());
        assertEquals(newSpecId, persisted.getId());
        assertEquals(2L, count("SELECT count(*) FROM product_specs WHERE product_id = ? AND is_deleted = 0",
                created.id()));
    }

    @Test
    void newSpecKeepsOneUuidAcrossProductOverrideManualStockAndCardKeyReferences() {
        UUID categoryId = persistCategory().getId();
        UUID clientSpecId = UUID.randomUUID();
        UUID levelId = enabledUserLevelId();

        AdminProductResponse response = adminProductService.createProduct(
                productRequest(categoryId, "manual-product-with-override", "MANUAL", StockMode.MANUAL,
                        null, List.of(specRequest(clientSpecId, "premium", "5.00", "10.00", true, 0, 7)),
                        List.of(new AdminProductOverrideRequest(levelId, clientSpecId, new BigDecimal("8.00")))));

        CardKey cardKey = new CardKey();
        cardKey.setProductId(response.id());
        cardKey.setSpecId(clientSpecId);
        cardKey.setContentParts(List.of("assigned-uuid-integration-card"));
        cardKey.setContentFingerprint(UUID.randomUUID().toString().replace("-", ""));
        cardKey.setStatus(CardKeyStatus.AVAILABLE);
        CardKey savedCardKey = cardKeyRepository.saveAndFlush(cardKey);

        entityManager.clear();

        ProductSpec persistedSpec = productSpecRepository.findById(clientSpecId).orElseThrow();
        ProductLevelPriceOverride persistedOverride = overrideRepository.findByProductId(response.id()).stream()
                .filter(override -> clientSpecId.equals(override.getSpecId()))
                .findFirst().orElseThrow();
        ManualStock persistedManualStock = manualStockRepository.findPool(response.id(), clientSpecId).orElseThrow();
        CardKey persistedCardKey = cardKeyRepository.findById(savedCardKey.getId()).orElseThrow();

        assertEquals(clientSpecId, persistedSpec.getId());
        assertEquals(clientSpecId, persistedOverride.getSpecId());
        assertEquals(clientSpecId, persistedManualStock.getSpecId());
        assertEquals(clientSpecId, persistedCardKey.getSpecId());
        assertEquals(7, persistedManualStock.getAvailableQuantity());
        assertEquals(1L, count("SELECT count(*) FROM product_level_price_overrides "
                        + "WHERE product_id = ? AND spec_id = ?", response.id(), clientSpecId));
        assertEquals(1L, count("SELECT count(*) FROM manual_stocks WHERE product_id = ? AND spec_id = ?",
                response.id(), clientSpecId));
        assertEquals(1L, count("SELECT count(*) FROM card_keys WHERE product_id = ? AND spec_id = ?",
                response.id(), clientSpecId));
    }

    @Test
    void clientUuidAlreadyOwnedByAnotherSpecIsStillRejected() {
        UUID categoryId = persistCategory().getId();
        UUID occupiedSpecId = UUID.randomUUID();
        UUID otherSpecId = UUID.randomUUID();
        AdminProductResponse owner = adminProductService.createProduct(
                productRequest(categoryId, "uuid-owner-product", "AUTO", StockMode.CARD_KEY,
                        null, List.of(specRequest(occupiedSpecId, "occupied", "5.00", "10.00", true, 0, null)),
                        List.of()));
        AdminProductResponse target = adminProductService.createProduct(
                productRequest(categoryId, "uuid-target-product", "AUTO", StockMode.CARD_KEY,
                        null, List.of(specRequest(otherSpecId, "target", "5.00", "10.00", true, 0, null)),
                        List.of()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminProductService.updateProduct(target.id(), productRequest(
                        categoryId, "uuid-target-product", "AUTO", StockMode.CARD_KEY, null,
                        List.of(
                                specRequest(otherSpecId, "target", "5.00", "10.00", true, 0, null),
                                specRequest(occupiedSpecId, "illegal-reuse", "6.00", "12.00", true, 1, null)),
                        List.of())));

        assertEquals(ErrorCode.SPEC_NOT_FOUND, exception.getCode());
        assertEquals(1L, count("SELECT count(*) FROM product_specs WHERE product_id = ? AND id = ?",
                owner.id(), occupiedSpecId));
        assertEquals(1L, count("SELECT count(*) FROM product_specs WHERE product_id = ? AND id = ?",
                target.id(), otherSpecId));
        assertEquals(0L, count("SELECT count(*) FROM product_specs WHERE product_id = ? AND id = ?",
                target.id(), occupiedSpecId));
    }

    @Test
    void missingSpecUuidIsGeneratedByTheServiceAndPersistsNormally() {
        UUID categoryId = persistCategory().getId();

        AdminProductResponse response = adminProductService.createProduct(
                productRequest(categoryId, "new-product-without-spec-uuid", "AUTO", StockMode.CARD_KEY,
                        null, List.of(specRequest(null, "generated", "5.00", "10.00", true, 0, null)),
                        List.of()));

        UUID generatedSpecId = response.specs().get(0).id();
        assertNotNull(generatedSpecId);

        entityManager.clear();

        ProductSpec persisted = productSpecRepository.findById(generatedSpecId).orElseThrow();
        assertEquals(generatedSpecId, persisted.getId());
        assertEquals(response.id(), persisted.getProductId());
    }

    private Product persistStandaloneProduct() {
        Product product = new Product();
        product.setProductNo(productRepository.nextProductNoValue());
        product.setTitle("standalone-product-" + UUID.randomUUID());
        product.setRetailPrice(new BigDecimal("10.00"));
        product.setCostPrice(new BigDecimal("5.00"));
        product.setCategoryId(UUID.randomUUID());
        product.setDeliveryType("AUTO");
        product.setStockMode(StockMode.CARD_KEY);
        product.setSpecEnabled(false);
        product.setEnabled(true);
        return productRepository.saveAndFlush(product);
    }

    private ProductCategory persistCategory() {
        ProductCategory category = new ProductCategory();
        category.setName("assigned-uuid-category-" + UUID.randomUUID());
        return categoryRepository.saveAndFlush(category);
    }

    private UUID enabledUserLevelId() {
        return userLevelRepository.findAllOrdered().stream()
                .filter(UserLevel::isEnabled)
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private ProductSpec productSpec(UUID id, UUID productId, String name) {
        ProductSpec spec = new ProductSpec();
        spec.setId(id);
        spec.setProductId(productId);
        spec.setName(name);
        spec.setCostPrice(new BigDecimal("5.00"));
        spec.setRetailPrice(new BigDecimal("10.00"));
        spec.setVisible(true);
        spec.setSortOrder(0);
        spec.setIsDeleted(0);
        return spec;
    }

    private AdminProductSpecRequest specRequest(UUID id, String name, String cost, String retail,
                                                boolean visible, int sortOrder, Integer manualStock) {
        return new AdminProductSpecRequest(id, name, new BigDecimal(cost), new BigDecimal(retail),
                visible, sortOrder, manualStock);
    }

    private AdminProductUpsertRequest productRequest(UUID categoryId, String title, String deliveryType,
                                                     StockMode stockMode, Integer manualStock,
                                                     List<AdminProductSpecRequest> specs,
                                                     List<AdminProductOverrideRequest> overrides) {
        return new AdminProductUpsertRequest(title, null, null, null, null, null, categoryId, 10,
                true, true, 0, 0, deliveryType, List.of(), stockMode, manualStock, specs, overrides);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
