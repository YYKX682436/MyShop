package com.myshop.service;

import com.myshop.constant.CustomerInputType;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductRulesTest {

    @Test
    void customerInputTypesValidateAndTrimValues() {
        Product qq = product(CustomerInputType.QQ);
        Product phone = product(CustomerInputType.PHONE);
        Product either = product(CustomerInputType.QQ_OR_PHONE);

        assertEquals("123456", ProductRules.validateCustomerInput(qq, " 123456 "));
        assertEquals("13800138000", ProductRules.validateCustomerInput(phone, "13800138000"));
        assertEquals("13800138000", ProductRules.validateCustomerInput(either, "13800138000"));
        assertNull(ProductRules.validateCustomerInput(product(CustomerInputType.NONE), "ignored"));
    }

    @Test
    void invalidCustomerInputIsRejectedByTheSharedBackendRule() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> ProductRules.validateCustomerInput(product(CustomerInputType.QQ), "not-a-qq"));

        assertEquals("请填写有效的客户信息", exception.getMessage());
    }

    @Test
    void deliveryAndStockModesHaveCompatibleDefaults() {
        assertEquals(StockMode.CARD_KEY, ProductRules.parseStockMode(null, StockMode.CARD_KEY));
        assertEquals(StockMode.MANUAL, ProductRules.parseStockMode("manual", StockMode.CARD_KEY));
        assertEquals(StockMode.CARD_KEY, ProductRules.compatibleStockMode("AUTO", StockMode.CARD_KEY));
        assertEquals(StockMode.UNLIMITED, ProductRules.compatibleStockMode("MANUAL", StockMode.UNLIMITED));
        assertThrows(BusinessException.class, () -> ProductRules.compatibleStockMode("AUTO", StockMode.MANUAL));
        assertThrows(BusinessException.class, () -> ProductRules.compatibleStockMode("MANUAL", StockMode.CARD_KEY));
    }

    private Product product(CustomerInputType type) {
        Product product = new Product();
        product.setCustomerInputType(type);
        return product;
    }
}
