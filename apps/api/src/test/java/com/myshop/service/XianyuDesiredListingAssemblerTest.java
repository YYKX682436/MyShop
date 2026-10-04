package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelListing;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingSnapshot;
import com.myshop.saleschannel.xianyu.XianyuMediaMaterializer;
import com.myshop.saleschannel.xianyu.XianyuMediaMaterializer.MaterializedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class XianyuDesiredListingAssemblerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("a1b2c3d4-e5f6-4789-8abc-def012345678");
    private static final UUID LISTING_ID = UUID.fromString("b1b2c3d4-e5f6-4789-8abc-def012345678");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProductSpecRepository specs = mock(ProductSpecRepository.class);
    private final ProductInventoryBatchService inventory = mock(ProductInventoryBatchService.class);
    private final XianyuMediaMaterializer media = mock(XianyuMediaMaterializer.class);
    private final PaymentUrlService paymentUrls = new PaymentUrlService("https://shop.example.test/");
    private final byte[] imageBytes = "real image bytes".getBytes(StandardCharsets.UTF_8);
    private XianyuDesiredListingAssembler assembler;
    private Product product;
    private SalesChannelListing listing;

    @BeforeEach
    void setUp() {
        assembler = new XianyuDesiredListingAssembler(mapper, specs, inventory, media, paymentUrls);
        product = new Product();
        product.setId(PRODUCT_ID);
        product.setProductNo(123456L);
        product.setTitle("Product title");
        product.setDescription("Product description");
        product.setRetailPrice(new BigDecimal("10.00"));
        product.setStockMode(StockMode.MANUAL);
        product.setCoverUrl("/api/uploads/cover.png");
        listing = new SalesChannelListing();
        listing.setId(LISTING_ID);
        listing.setProductId(PRODUCT_ID);
        setConfig(baseConfig());
        when(media.readImage(anyString())).thenAnswer(call -> new MaterializedImage(
                "image.png", "image/png", imageBytes));
        when(inventory.load(anyCollection(), anyMap())).thenReturn(Map.of(
                new PriceTarget(PRODUCT_ID, null), new ProductInventoryBatchService.StockSnapshot(7L, false)));
    }

    @Test
    void listingAndManagedImagesUseStableIdentityAndActualBytesInConfiguredOrder() throws Exception {
        Map<String, Object> config = baseConfig();
        config.put("imageUrls", List.of("/api/uploads/first.png", "https://cdn.example.test/second.png"));
        setConfig(config);

        ListingSnapshot snapshot = assembler.assemble(listing, product);

        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(imageBytes));
        assertEquals(LISTING_ID.toString(), snapshot.listingKey());
        assertEquals("sha256:" + digest, snapshot.images().getFirst().assetKey());
        assertEquals(digest, snapshot.images().getFirst().sha256());
        assertEquals("https://shop.example.test/api/uploads/first.png", snapshot.images().getFirst().sourceUrl());
        assertEquals("https://cdn.example.test/second.png", snapshot.images().get(1).sourceUrl());
        verify(media).readImage("/api/uploads/first.png");
        verify(media).readImage("https://cdn.example.test/second.png");
    }

    @Test
    void visibleSpecsBecomeStableSingleDimensionSkusSortedBySortOrder() {
        product.setSpecEnabled(true);
        UUID blackId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID whiteId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ProductSpec black = spec(blackId, "Black", "12.00", 2);
        ProductSpec white = spec(whiteId, "White", "9.50", 1);
        when(specs.findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
                List.of(PRODUCT_ID), 0, true)).thenReturn(List.of(black, white));
        when(inventory.load(anyCollection(), anyMap())).thenReturn(Map.of(
                new PriceTarget(PRODUCT_ID, blackId), new ProductInventoryBatchService.StockSnapshot(2L, false),
                new PriceTarget(PRODUCT_ID, whiteId), new ProductInventoryBatchService.StockSnapshot(3L, false)));

        ListingSnapshot snapshot = assembler.assemble(listing, product);

        verify(specs).findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
                List.of(PRODUCT_ID), 0, true);
        assertEquals(List.of("White", "Black"), snapshot.specifications().getFirst().values());
        assertEquals(List.of(whiteId.toString(), blackId.toString()),
                snapshot.skus().stream().map(sku -> sku.skuKey()).toList());
        assertEquals("9.50", snapshot.price());
        assertEquals(5, snapshot.stock());
    }

    @Test
    void noSellableSpecsDuplicateNamesAndAggregateOverflowFailClosed() {
        product.setSpecEnabled(true);
        when(specs.findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
                List.of(PRODUCT_ID), 0, true)).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));

        ProductSpec one = spec(UUID.randomUUID(), "Same", "10.00", 1);
        ProductSpec duplicate = spec(UUID.randomUUID(), "Same", "11.00", 2);
        when(specs.findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
                List.of(PRODUCT_ID), 0, true)).thenReturn(List.of(one, duplicate));
        when(inventory.load(anyCollection(), anyMap())).thenReturn(Map.of(
                new PriceTarget(PRODUCT_ID, one.getId()), new ProductInventoryBatchService.StockSnapshot(600_000L, false),
                new PriceTarget(PRODUCT_ID, duplicate.getId()), new ProductInventoryBatchService.StockSnapshot(400_000L, false)));
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));

        ProductSpec largeOne = spec(UUID.randomUUID(), "One", "10.00", 1);
        ProductSpec largeTwo = spec(UUID.randomUUID(), "Two", "11.00", 2);
        when(specs.findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
                List.of(PRODUCT_ID), 0, true)).thenReturn(List.of(largeOne, largeTwo));
        when(inventory.load(anyCollection(), anyMap())).thenReturn(Map.of(
                new PriceTarget(PRODUCT_ID, largeOne.getId()), new ProductInventoryBatchService.StockSnapshot(600_000L, false),
                new PriceTarget(PRODUCT_ID, largeTwo.getId()), new ProductInventoryBatchService.StockSnapshot(400_000L, false)));
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));
    }

    @Test
    void priceModesRoundHalfUpAndRejectZero() {
        product.setRetailPrice(new BigDecimal("11.23"));
        assertEquals("11.23", assembler.assemble(listing, product).price());
        Map<String, Object> fixed = baseConfig();
        fixed.put("priceMode", "FIXED");
        fixed.put("fixedPrice", "15.255");
        setConfig(fixed);
        assertEquals("15.26", assembler.assemble(listing, product).price());
        Map<String, Object> markup = baseConfig();
        markup.put("priceMode", "MARKUP_PERCENT");
        markup.put("markupPercent", "10.00");
        setConfig(markup);
        assertEquals("12.35", assembler.assemble(listing, product).price());
        fixed.put("fixedPrice", "0.00");
        setConfig(fixed);
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));
    }

    @Test
    void stockUsesRealPoolUnlimitedQuantityAndPerSkuCap() {
        Map<String, Object> config = baseConfig();
        config.put("stockCapPerSku", 4);
        setConfig(config);
        assertEquals(4, assembler.assemble(listing, product).stock());

        product.setStockMode(StockMode.UNLIMITED);
        config.put("unlimitedStockQuantity", 20);
        setConfig(config);
        assertEquals(4, assembler.assemble(listing, product).stock());
    }

    @Test
    void titleAndDescriptionAreNormalizedButNeverSilentlyTruncated() {
        product.setTitle("  Cafe\u0301  ");
        product.setDescription("one\r\ntwo");
        ListingSnapshot normalized = assembler.assemble(listing, product);
        assertEquals("Café", normalized.title());
        assertEquals("one\ntwo", normalized.description());

        product.setTitle("😀".repeat(201));
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));
        product.setTitle("Short");
        product.setDescription("x".repeat(1501));
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));
    }

    @Test
    void attributesAllowSortedMultiselectButRejectDuplicatesOpaqueValuesAndCategory() {
        Map<String, Object> config = baseConfig();
        config.put("platformAttributes", List.of(
                Map.of("propertyId", "brand", "propertyName", "Brand", "valueId", "b", "valueName", "Blue"),
                Map.of("propertyId", "brand", "propertyName", "Brand", "valueId", "a", "valueName", "Red")));
        setConfig(config);
        ListingSnapshot result = assembler.assemble(listing, product);
        assertEquals(List.of("a", "b"), result.platformAttributes().stream().map(row -> row.valueId()).toList());
        config.put("platformAttributes", List.of(
                Map.of("propertyId", "brand", "propertyName", "Brand", "valueName", "A"),
                Map.of("propertyId", "brand", "propertyName", "Brand", "valueName", "A")));
        setConfig(config);
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));
        config.put("platformAttributes", List.of(Map.of(
                "propertyId", "brand", "propertyName", "Brand", "valueName", "A", "properties", Map.of("opaque", true))));
        setConfig(config);
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));
        config.put("platformAttributes", List.of(Map.of(
                "propertyId", "category", "propertyName", "分类", "valueName", "A")));
        setConfig(config);
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));
    }

    @Test
    void allShippingMethodsCarryExplicitPickupAndFixedPostageOnly() {
        for (String method : List.of("free", "distance", "fixed", "template", "none")) {
            Map<String, Object> config = baseConfig();
            config.put("shippingMethod", method);
            config.put("supportPickup", true);
            if ("fixed".equals(method)) config.put("postage", "12.34");
            setConfig(config);
            ListingSnapshot snapshot = assembler.assemble(listing, product);
            assertEquals(method, snapshot.shipping().method());
            assertEquals(Boolean.TRUE, snapshot.shipping().supportPickup());
            assertEquals("fixed".equals(method) ? "12.34" : null, snapshot.shipping().postage());
        }
        Map<String, Object> invalidFixed = baseConfig();
        invalidFixed.put("shippingMethod", "fixed");
        setConfig(invalidFixed);
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));
    }

    @Test
    void categoryAndSelectedAddressTextAreRequiredWithoutGuessing() {
        Map<String, Object> config = baseConfig();
        config.remove("addressExpectedText");
        setConfig(config);
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));
        config = baseConfig();
        config.remove("platformCategoryId");
        setConfig(config);
        assertThrows(IllegalArgumentException.class, () -> assembler.assemble(listing, product));
    }

    private ProductSpec spec(UUID id, String name, String price, int order) {
        ProductSpec spec = new ProductSpec();
        spec.setId(id);
        spec.setProductId(PRODUCT_ID);
        spec.setName(name);
        spec.setRetailPrice(new BigDecimal(price));
        spec.setSortOrder(order);
        spec.setVisible(true);
        spec.setIsDeleted(0);
        return spec;
    }

    private void setConfig(Map<String, Object> config) {
        ReflectionTestUtils.setField(listing, "providerConfig", config);
    }

    private Map<String, Object> baseConfig() {
        return new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("address", "1 Example Road"),
                Map.entry("addressExpectedText", "Pickup at Example Road"),
                Map.entry("platformCategoryId", "cat-1"),
                Map.entry("platformCategoryName", "Lighting"),
                Map.entry("platformChannelCategoryId", "channel-1"),
                Map.entry("platformChannelCategoryName", "Desk lamps"),
                Map.entry("platformTbCategoryId", "tb-cat-1")));
    }
}
