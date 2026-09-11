package com.myshop.repository;

import com.myshop.entity.Product;
import com.myshop.entity.ProductCategory;
import com.myshop.service.ProductListSort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class ProductRepositoryIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductCategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void adminKeywordFindsProductByBusinessNumber() {
        Product product = product(4321L, "Searchable title");
        productRepository.saveAndFlush(product);

        Page<Product> result = productRepository.findAdminProductsByKeyword(
                null, "%4321%", 4321L, null,
                PageRequest.of(0, 20, ProductListSort.resolve(null, null)));

        assertEquals(1, result.getTotalElements());
        assertEquals(4321L, result.getContent().get(0).getProductNo());
        assertEquals(product.getId(), productRepository.findByProductNo(4321L).orElseThrow().getId());
    }

    @Test
    void productNumberSortIsNumericInsteadOfLexicographic() {
        insertProduct(1L, 0, LocalDateTime.of(2026, 1, 1, 0, 0), "00000000-0000-0000-0000-000000000001");
        insertProduct(2L, 0, LocalDateTime.of(2026, 1, 1, 0, 0), "00000000-0000-0000-0000-000000000002");
        insertProduct(10L, 0, LocalDateTime.of(2026, 1, 1, 0, 0), "00000000-0000-0000-0000-000000000010");
        insertProduct(11L, 0, LocalDateTime.of(2026, 1, 1, 0, 0), "00000000-0000-0000-0000-000000000011");

        Page<Product> result = productRepository.findAdminProducts(null, null,
                PageRequest.of(0, 20, ProductListSort.resolve("product_no", "asc")));

        assertEquals(List.of(1L, 2L, 10L, 11L),
                result.getContent().stream().map(Product::getProductNo).toList());
    }

    @Test
    void defaultSortUsesWeightThenCreatedAtThenId() {
        insertProduct(1L, 10, LocalDateTime.of(2026, 1, 1, 0, 0), "00000000-0000-0000-0000-000000000001");
        insertProduct(2L, 10, LocalDateTime.of(2026, 1, 2, 0, 0), "00000000-0000-0000-0000-000000000002");
        insertProduct(10L, 10, LocalDateTime.of(2026, 1, 2, 0, 0), "00000000-0000-0000-0000-000000000003");
        insertProduct(11L, 9, LocalDateTime.of(2026, 1, 3, 0, 0), "00000000-0000-0000-0000-000000000004");

        Page<Product> result = productRepository.findAdminProducts(null, null,
                PageRequest.of(0, 20, ProductListSort.resolve(null, null)));

        assertEquals(List.of(2L, 10L, 1L, 11L),
                result.getContent().stream().map(Product::getProductNo).toList());

        Page<Product> publicResult = productRepository.findPublicProducts(null,
                PageRequest.of(0, 20, ProductListSort.resolve(null, null)));
        assertEquals(List.of(2L, 10L, 1L, 11L),
                publicResult.getContent().stream().map(Product::getProductNo).toList());
    }

    @Test
    void categoryQueriesKeepRootAndChildSemanticsThroughKeywordStatusAndPagination() {
        ProductCategory rootA = category("Root A", null, 1);
        categoryRepository.saveAndFlush(rootA);
        ProductCategory childA1 = category("Child A1", rootA.getId(), 1);
        ProductCategory childA2 = category("Child A2", rootA.getId(), 2);
        ProductCategory rootB = category("Root B", null, 2);
        categoryRepository.saveAndFlush(rootB);
        ProductCategory childB1 = category("Child B1", rootB.getId(), 1);
        categoryRepository.saveAllAndFlush(List.of(childA1, childA2, childB1));

        Product direct = product(100L, "Promo direct");
        direct.setCategoryId(rootA.getId());
        Product firstChild = product(101L, "Promo child one");
        firstChild.setCategoryId(childA1.getId());
        Product secondChild = product(102L, "Promo child two");
        secondChild.setCategoryId(childA2.getId());
        Product otherRoot = product(103L, "Promo other root");
        otherRoot.setCategoryId(rootB.getId());
        Product otherChild = product(104L, "Promo other child");
        otherChild.setCategoryId(childB1.getId());
        Product disabled = product(105L, "Promo disabled");
        disabled.setCategoryId(childA1.getId());
        disabled.setEnabled(false);
        productRepository.saveAllAndFlush(List.of(direct, firstChild, secondChild, otherRoot, otherChild, disabled));

        Page<Product> rootPage = productRepository.findAdminProducts(rootA.getId(), true,
                PageRequest.of(0, 20, ProductListSort.resolve(null, null)));
        assertEquals(3, rootPage.getTotalElements());
        assertIterableEquals(List.of(100L, 101L, 102L),
                rootPage.getContent().stream().map(Product::getProductNo).sorted().toList());

        Page<Product> childPage = productRepository.findAdminProducts(childA1.getId(), true,
                PageRequest.of(0, 20, ProductListSort.resolve(null, null)));
        assertEquals(1, childPage.getTotalElements());
        assertEquals(101L, childPage.getContent().get(0).getProductNo());

        Page<Product> keywordPage = productRepository.findAdminProductsByKeyword(
                rootA.getId(), "%promo%", null, true,
                PageRequest.of(1, 1, ProductListSort.resolve("product_no", "desc")));
        assertEquals(3, keywordPage.getTotalElements());
        assertEquals(101L, keywordPage.getContent().get(0).getProductNo());

        Page<Product> publicRootPage = productRepository.findPublicProducts(rootA.getId(),
                PageRequest.of(0, 20, ProductListSort.resolve(null, null)));
        assertEquals(3, publicRootPage.getTotalElements());
        assertTrue(publicRootPage.getContent().stream()
                .noneMatch(product -> product.getProductNo().equals(105L)));

        Page<Product> publicChildPage = productRepository.findPublicProducts(childA1.getId(),
                PageRequest.of(0, 20, ProductListSort.resolve(null, null)));
        assertEquals(1, publicChildPage.getTotalElements());
        assertEquals(101L, publicChildPage.getContent().get(0).getProductNo());
    }

    private void insertProduct(long productNo, int sortWeight, LocalDateTime createdAt, String id) {
        jdbcTemplate.update("""
                INSERT INTO products(id, created_at, updated_at, title, product_no, retail_price, cost_price,
                                     category_id, delivery_type, customer_input_fields, stock_mode, low_stock_threshold,
                                     spec_enabled, is_enabled, initial_sales, sort_weight, is_deleted)
                VALUES (?::uuid, ?, ?, ?, ?, 10.00, 5.00, ?::uuid, 'AUTO', JSON_ARRAY(), 'CARD_KEY', 10,
                        false, true, 0, ?, 0)
                """, id, createdAt, createdAt, "Product " + productNo, productNo,
                "00000000-0000-0000-0000-000000000099", sortWeight);
    }

    private Product product(Long productNo, String title) {
        Product product = new Product();
        product.setProductNo(productNo);
        product.setTitle(title);
        product.setDescription(null);
        product.setRetailPrice(new BigDecimal("10.00"));
        product.setCostPrice(new BigDecimal("5.00"));
        product.setCategoryId(UUID.randomUUID());
        product.setIsDeleted(0);
        product.setEnabled(true);
        return product;
    }

    private ProductCategory category(String name, UUID parentId, int sortOrder) {
        ProductCategory category = new ProductCategory();
        category.setName(name);
        category.setParentId(parentId);
        category.setSortOrder(sortOrder);
        category.setIsDeleted(0);
        return category;
    }
}
