package com.myshop.repository;

import com.myshop.entity.Product;
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

@SpringBootTest
@Transactional
class ProductRepositoryIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

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
}
