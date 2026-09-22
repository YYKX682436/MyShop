package com.myshop.service;

import com.myshop.exception.SalesChannelSpecMappingException;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSpecMappingsData;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSkuRow;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSpecification;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSpecificationValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SalesChannelSpecMappingValidatorTest {

    private final SalesChannelSpecMappingValidator validator = new SalesChannelSpecMappingValidator();

    @Test
    void exactThreeToThreeMappingUsesNamesNotProviderOrderAndAllowsAbsentSkuIds() {
        UUID month = UUID.randomUUID();
        UUID year = UUID.randomUUID();
        UUID lifetime = UUID.randomUUID();
        var resolved = validator.resolve(
                List.of(new SalesChannelSpecMappingValidator.ProductSpecSnapshot(month, "月卡"),
                        new SalesChannelSpecMappingValidator.ProductSpecSnapshot(year, "年卡"),
                        new SalesChannelSpecMappingValidator.ProductSpecSnapshot(lifetime, "永久卡")),
                List.of(emptyMapping(month), emptyMapping(year), emptyMapping(lifetime)),
                provider(List.of("永久卡", "月卡", "年卡"), Arrays.asList(null, null, null)));

        assertEquals(List.of("月卡", "年卡", "永久卡"),
                resolved.stream().map(SalesChannelSpecMappingValidator.ResolvedSpecMapping::providerSpecValue).toList());
        assertEquals(Arrays.asList(null, null, null),
                resolved.stream().map(SalesChannelSpecMappingValidator.ResolvedSpecMapping::externalSkuId).toList());
    }

    @Test
    void missingOrExtraProviderValueFailsAsAWholeSet() {
        UUID month = UUID.randomUUID();
        UUID year = UUID.randomUUID();
        UUID lifetime = UUID.randomUUID();
        var specs = List.of(new SalesChannelSpecMappingValidator.ProductSpecSnapshot(month, "月卡"),
                new SalesChannelSpecMappingValidator.ProductSpecSnapshot(year, "年卡"),
                new SalesChannelSpecMappingValidator.ProductSpecSnapshot(lifetime, "永久卡"));
        assertCode("SPEC_MAPPING_INCOMPLETE", () -> validator.resolve(specs,
                List.of(emptyMapping(month), emptyMapping(year), emptyMapping(lifetime)),
                provider(List.of("月卡", "年卡"), Arrays.asList(null, null))));
        assertCode("SPEC_MAPPING_INCOMPLETE", () -> validator.resolve(specs,
                List.of(emptyMapping(month), emptyMapping(year), emptyMapping(lifetime)),
                provider(List.of("月卡", "年卡", "永久卡", "测试卡"), Arrays.asList(null, null, null, null))));
    }

    @Test
    void duplicateProviderValueDuplicateProductNameAndDuplicateSkuAllFail() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertCode("SPEC_MAPPING_INCOMPLETE", () -> validator.resolve(
                List.of(new SalesChannelSpecMappingValidator.ProductSpecSnapshot(first, "月卡"),
                        new SalesChannelSpecMappingValidator.ProductSpecSnapshot(second, "月卡")),
                List.of(emptyMapping(first), emptyMapping(second)),
                provider(List.of("月卡", "年卡"), List.of("sku-1", "sku-2"))));

        assertCode("SPEC_MAPPING_INCOMPLETE", () -> validator.resolve(
                List.of(new SalesChannelSpecMappingValidator.ProductSpecSnapshot(first, "月卡"),
                        new SalesChannelSpecMappingValidator.ProductSpecSnapshot(second, "年卡")),
                List.of(emptyMapping(first), emptyMapping(second)),
                provider(List.of("月卡", "月卡"), List.of("sku-1", "sku-2"))));

        assertCode("AMBIGUOUS_EXTERNAL_SKU", () -> validator.resolve(
                List.of(new SalesChannelSpecMappingValidator.ProductSpecSnapshot(first, "月卡"),
                        new SalesChannelSpecMappingValidator.ProductSpecSnapshot(second, "年卡")),
                List.of(emptyMapping(first), emptyMapping(second)),
                provider(List.of("月卡", "年卡"), List.of("same-sku", "same-sku"))));
    }

    @Test
    void existingMappingAndSkuConflictsAreNotOverwritten() {
        UUID month = UUID.randomUUID();
        assertCode("PROVIDER_SPEC_MAPPING_CONFLICT", () -> validator.resolve(
                List.of(new SalesChannelSpecMappingValidator.ProductSpecSnapshot(month, "月卡")),
                List.of(new SalesChannelSpecMappingValidator.ExistingMappingSnapshot(month, null,
                        Map.of("provider_spec_name", "规格", "provider_spec_value", "旧月卡"))),
                provider(List.of("月卡"), List.of("sku-month"))));

        assertCode("EXTERNAL_SKU_ID_CONFLICT", () -> validator.resolve(
                List.of(new SalesChannelSpecMappingValidator.ProductSpecSnapshot(month, "月卡")),
                List.of(new SalesChannelSpecMappingValidator.ExistingMappingSnapshot(month, "old-sku",
                        Map.of("provider_spec_name", "规格", "provider_spec_value", "月卡"))),
                provider(List.of("月卡"), List.of("new-sku"))));
    }

    @Test
    void autoFulfillmentGateAcceptsEitherReliableSkuOrCompleteProviderMapping() {
        UUID month = UUID.randomUUID();
        UUID year = UUID.randomUUID();
        var specs = List.of(new SalesChannelSpecMappingValidator.ProductSpecSnapshot(month, "月卡"),
                new SalesChannelSpecMappingValidator.ProductSpecSnapshot(year, "年卡"));
        validator.assertCompleteForAutoFulfillment(specs, List.of(
                new SalesChannelSpecMappingValidator.ExistingMappingSnapshot(month, "sku-month", Map.of()),
                new SalesChannelSpecMappingValidator.ExistingMappingSnapshot(year, null,
                        Map.of("provider_spec_name", "规格", "provider_spec_value", "年卡"))));

        assertCode("SPEC_MAPPING_INCOMPLETE", () -> validator.assertCompleteForAutoFulfillment(
                specs, List.of(emptyMapping(month), emptyMapping(year))));
    }

    private SalesChannelSpecMappingValidator.ExistingMappingSnapshot emptyMapping(UUID id) {
        return new SalesChannelSpecMappingValidator.ExistingMappingSnapshot(id, null, Map.of());
    }

    private ListingSpecMappingsData provider(List<String> values, List<String> skuIds) {
        List<SpecMappingSpecificationValue> specificationValues = values.stream()
                .map(SpecMappingSpecificationValue::new).toList();
        List<SpecMappingSkuRow> rows = values.stream().map(value -> {
            int index = values.indexOf(value);
            return new SpecMappingSkuRow(skuIds.get(index), Map.of("规格", value));
        }).toList();
        return new ListingSpecMappingsData("item-1",
                List.of(new SpecMappingSpecification("规格", specificationValues)), rows);
    }

    private void assertCode(String expected, org.junit.jupiter.api.function.Executable executable) {
        SalesChannelSpecMappingException failure = assertThrows(SalesChannelSpecMappingException.class, executable);
        assertEquals(expected, failure.getCode());
    }
}
