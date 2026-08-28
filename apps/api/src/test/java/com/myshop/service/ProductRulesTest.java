package com.myshop.service;

import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.exception.BusinessException;
import com.myshop.model.CustomerInputField;
import com.myshop.model.CustomerInputValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductRulesTest {

    @Test
    void definitionsSupportZeroOneThreeAndTwentyFieldsWithStableIds() {
        assertEquals(List.of(), ProductRules.normalizeCustomerInputFields(List.of()));

        List<CustomerInputField> threeFields = ProductRules.normalizeCustomerInputFields(List.of(
                Map.of("id", "qq", "label", " QQ号 "),
                Map.of("id", "zone", "label", "游戏区服"),
                Map.of("id", "steam", "label", "Steam ID")));
        assertEquals(List.of(
                new CustomerInputField("qq", "QQ号"),
                new CustomerInputField("zone", "游戏区服"),
                new CustomerInputField("steam", "Steam ID")), threeFields);

        List<CustomerInputField> twentyFields = ProductRules.normalizeCustomerInputFields(
                java.util.stream.IntStream.range(0, 20)
                        .mapToObj(index -> Map.of("id", "field-" + index, "label", "字段" + index))
                        .toList());
        assertEquals(20, twentyFields.size());

        CustomerInputField generated = ProductRules.normalizeCustomerInputFields(
                List.of(Map.of("label", "新字段"))).get(0);
        assertNotNull(generated.id());
        assertTrue(!generated.id().isBlank());
    }

    @Test
    void arbitraryFieldsNormalizeInProductOrderAndValuesIgnoreSubmittedLabels() {
        Product product = product(List.of(
                new CustomerInputField("account", "收货账号"),
                new CustomerInputField("contact", "联系方式")));

        List<CustomerInputValue> values = ProductRules.normalizeCustomerInputs(product, Map.of(
                "customer_inputs", List.of(
                        Map.of("id", "contact", "label", "伪造标签", "value", " 123 "),
                        Map.of("id", "account", "value", " alice@example.com "))));

        assertEquals(List.of(
                new CustomerInputValue("account", "收货账号", "alice@example.com"),
                new CustomerInputValue("contact", "联系方式", "123")), values);
    }

    @Test
    void fieldsRejectEmptyDuplicateAndOverlongDefinitions() {
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputFields(List.of(
                Map.of("id", "a", "label", "重复"),
                Map.of("id", "b", "label", "重复"))));
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputFields(List.of(
                Map.of("id", "a", "label", " "))));
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputFields(
                java.util.stream.IntStream.range(0, 21)
                        .mapToObj(index -> Map.of("label", "字段" + index))
                        .toList()));
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputFields(List.of(
                Map.of("id", "a", "label", "x".repeat(51)))));
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputFields(List.of(
                Map.of("id", "a", "label", "字段\n名称"))));
    }

    @Test
    void missingUnknownDuplicateAndInvalidValuesAreRejected() {
        Product product = product(List.of(
                new CustomerInputField("contact", "联系方式"),
                new CustomerInputField("note", "备注")));
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputs(product, Map.of()));
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputs(product,
                Map.of("customer_inputs", List.of(Map.of("id", "contact", "value", "x")))));
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputs(product,
                Map.of("customer_inputs", List.of(
                        Map.of("id", "unknown", "value", "x"),
                        Map.of("id", "note", "value", "y")))));
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputs(product,
                Map.of("customer_inputs", List.of(
                        Map.of("id", "contact", "value", "a"),
                        Map.of("id", "contact", "value", "b")))));
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputs(product,
                Map.of("customer_inputs", List.of(
                        Map.of("id", "contact", "value", " "),
                        Map.of("id", "note", "value", "ok")))));
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputs(product,
                Map.of("customer_inputs", List.of(
                        Map.of("id", "contact", "value", "x".repeat(256)),
                        Map.of("id", "note", "value", "ok")))));
        Product oneField = product(List.of(new CustomerInputField("contact", "联系方式")));
        assertThrows(BusinessException.class, () -> ProductRules.normalizeCustomerInputs(oneField,
                Map.of("customer_inputs", List.of(Map.of("id", "contact", "value", "a\nb")))));
    }

    @Test
    void currentLabelsAreUsedAndCartCompletenessDetectsProductFieldChanges() {
        Product renamed = product(List.of(new CustomerInputField("account", "充值账号")));
        List<CustomerInputValue> renamedValue = ProductRules.normalizeCustomerInputs(renamed, Map.of(
                "customer_inputs", List.of(Map.of("id", "account", "label", "伪造标签", "value", "123"))));
        assertEquals(List.of(new CustomerInputValue("account", "充值账号", "123")), renamedValue);

        Product expanded = product(List.of(
                new CustomerInputField("account", "充值账号"),
                new CustomerInputField("zone", "游戏区服")));
        assertFalse(ProductRules.hasCompleteCustomerInputs(expanded, renamedValue));
        assertEquals(List.of(new CustomerInputValue("account", "充值账号", "123")),
                ProductRules.knownCustomerInputs(expanded, renamedValue));
    }

    @Test
    void inputEqualityUsesFieldIdAndValueInsteadOfJsonOrderOrSubmittedLabels() {
        List<CustomerInputValue> first = List.of(
                new CustomerInputValue("account", "账号", "123"),
                new CustomerInputValue("zone", "区服", "艾欧尼亚"));
        List<CustomerInputValue> sameValuesInDifferentOrder = List.of(
                new CustomerInputValue("zone", "改名后的区服", "艾欧尼亚"),
                new CustomerInputValue("account", "伪造标签", "123"));
        List<CustomerInputValue> differentValue = List.of(
                new CustomerInputValue("account", "账号", "456"),
                new CustomerInputValue("zone", "区服", "艾欧尼亚"));

        assertTrue(ProductRules.sameCustomerInputs(first, sameValuesInDifferentOrder));
        assertFalse(ProductRules.sameCustomerInputs(first, differentValue));
    }

    @Test
    void deliveryAndStockModesHaveValidDefaults() {
        assertEquals(StockMode.CARD_KEY, ProductRules.parseStockMode(null, StockMode.CARD_KEY));
        assertEquals(StockMode.MANUAL, ProductRules.parseStockMode("manual", StockMode.CARD_KEY));
        assertEquals(StockMode.CARD_KEY, ProductRules.validateStockMode("AUTO", StockMode.CARD_KEY));
        assertEquals(StockMode.UNLIMITED, ProductRules.validateStockMode("MANUAL", StockMode.UNLIMITED));
        assertThrows(BusinessException.class, () -> ProductRules.validateStockMode("AUTO", StockMode.MANUAL));
        assertThrows(BusinessException.class, () -> ProductRules.validateStockMode("MANUAL", StockMode.CARD_KEY));
    }

    private Product product(List<CustomerInputField> fields) {
        Product product = new Product();
        product.setCustomerInputFields(fields);
        return product;
    }
}
