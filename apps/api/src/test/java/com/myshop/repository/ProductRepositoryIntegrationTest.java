package com.myshop.repository;

import com.myshop.entity.Product;
import com.myshop.service.ProductListSort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class ProductRepositoryIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Test
    void adminKeywordFindsProductByBusinessCode() {
        Product product = product("P004321", "Searchable title");
        productRepository.saveAndFlush(product);

        Page<Product> result = productRepository.findAdminProductsByKeyword(
                null, "%p004321%", null, PageRequest.of(0, 20, ProductListSort.resolve(null, null)));

        assertEquals(1, result.getTotalElements());
        assertEquals("P004321", result.getContent().get(0).getProductCode());
    }

    private Product product(String code, String title) {
        Product product = new Product();
        product.setProductCode(code);
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
