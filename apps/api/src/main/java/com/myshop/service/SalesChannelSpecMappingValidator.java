package com.myshop.service;

import com.myshop.entity.SalesChannelListingSpec;
import com.myshop.entity.ProductSpec;
import com.myshop.exception.SalesChannelSpecMappingException;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSpecMappingsData;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSkuRow;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSpecification;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSpecificationValue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exact, collection-level validation for one-dimensional provider mappings. */
@Component
public class SalesChannelSpecMappingValidator {

    public List<ResolvedSpecMapping> resolve(
            List<ProductSpecSnapshot> productSpecs,
            List<ExistingMappingSnapshot> existingMappings,
            ListingSpecMappingsData provider) {
        if (provider == null) fail("SPEC_MAPPING_INCOMPLETE", "The Agent returned no specification data");
        if (productSpecs == null || productSpecs.isEmpty()) {
            fail("SPEC_MAPPING_INCOMPLETE", "The product has no active specifications");
        }

        Map<String, ProductSpecSnapshot> productByName = activeProductSpecs(productSpecs);
        SpecDimension dimension = providerDimension(provider);
        Map<String, String> providerValues = providerValues(dimension.specification());
        Map<String, SpecMappingSkuRow> providerRows = providerRows(provider, dimension, providerValues);

        if (providerValues.size() != productByName.size()) {
            fail("SPEC_MAPPING_INCOMPLETE", "The MyShop and provider specification sets are not equal");
        }
        for (String productName : productByName.keySet()) {
            if (!providerValues.containsKey(productName)) {
                fail("SPEC_MAPPING_INCOMPLETE", "A MyShop specification has no exact provider value");
            }
        }
        for (String providerName : providerValues.keySet()) {
            if (!productByName.containsKey(providerName)) {
                fail("SPEC_MAPPING_INCOMPLETE", "The provider returned an extra specification value");
            }
        }

        Map<UUID, ExistingMappingSnapshot> existingBySpec = new LinkedHashMap<>();
        for (ExistingMappingSnapshot mapping : existingMappings == null ? List.<ExistingMappingSnapshot>of() : existingMappings) {
            if (existingBySpec.put(mapping.productSpecId(), mapping) != null) {
                fail("SPEC_MAPPING_INCOMPLETE", "A listing specification row is duplicated");
            }
            if (productSpecs.stream().noneMatch(spec -> spec.id().equals(mapping.productSpecId()))) {
                fail("SPEC_MAPPING_INCOMPLETE",
                        "A listing specification row does not belong to an active product specification");
            }
        }

        List<ResolvedSpecMapping> resolved = new ArrayList<>();
        for (ProductSpecSnapshot productSpec : productSpecs) {
            String productName = normalized(productSpec.name());
            String providerValue = providerValues.get(productName);
            SpecMappingSkuRow row = providerRows.get(normalized(providerValue));
            ExistingMappingSnapshot existing = existingBySpec.get(productSpec.id());
            if (row == null || existing == null) {
                fail("SPEC_MAPPING_INCOMPLETE", "Every active MyShop specification needs one provider SKU row");
            }
            validateExistingMapping(existing, dimension.name(), providerValue, row.externalSkuId());
            String externalSkuId = row.externalSkuId();
            if (isNonBlank(existing.externalSkuId())) {
                if (isNonBlank(externalSkuId) && !existing.externalSkuId().equals(externalSkuId)) {
                    fail("EXTERNAL_SKU_ID_CONFLICT", "An existing external SKU identity conflicts with the provider");
                }
                externalSkuId = existing.externalSkuId();
            }
            resolved.add(new ResolvedSpecMapping(productSpec.id(), productSpec.name(),
                    dimension.name(), providerValue, externalSkuId));
        }
        return List.copyOf(resolved);
    }

    /** Gate used before enabling auto fulfillment without contacting the provider. */
    public void assertCompleteForAutoFulfillment(
            List<ProductSpecSnapshot> productSpecs,
            List<ExistingMappingSnapshot> existingMappings) {
        if (productSpecs == null || productSpecs.isEmpty()) {
            fail("SPEC_MAPPING_INCOMPLETE", "All active product specifications must be mapped before auto fulfillment");
        }
        activeProductSpecs(productSpecs);
        Map<UUID, ExistingMappingSnapshot> bySpec = new LinkedHashMap<>();
        Set<String> externalIds = new HashSet<>();
        Set<String> providerValues = new HashSet<>();
        for (ExistingMappingSnapshot mapping : existingMappings == null ? List.<ExistingMappingSnapshot>of() : existingMappings) {
            if (bySpec.put(mapping.productSpecId(), mapping) != null) {
                fail("SPEC_MAPPING_INCOMPLETE", "A listing specification row is duplicated");
            }
        }
        for (ProductSpecSnapshot productSpec : productSpecs) {
            ExistingMappingSnapshot mapping = bySpec.get(productSpec.id());
            if (mapping == null) {
                fail("SPEC_MAPPING_INCOMPLETE", "Every active product specification must be mapped");
            }
            if (isNonBlank(mapping.externalSkuId())) {
                if (!externalIds.add(mapping.externalSkuId())) {
                    fail("SPEC_MAPPING_INCOMPLETE", "External SKU identities must be unique");
                }
            }
            String providerName = mappingProviderValue(mapping.providerMapping(), "provider_spec_name");
            String providerValue = mappingProviderValue(mapping.providerMapping(), "provider_spec_value");
            if (isNonBlank(providerName) != isNonBlank(providerValue)) {
                fail("SPEC_MAPPING_INCOMPLETE", "Provider specification mapping is incomplete");
            }
            if (isNonBlank(providerName)) {
                String identity = providerName + "\u0000" + providerValue;
                if (!providerValues.add(identity)) {
                    fail("SPEC_MAPPING_INCOMPLETE", "Provider specification mappings must be unique");
                }
            }
            if (!isNonBlank(mapping.externalSkuId()) && !isNonBlank(providerName)) {
                fail("SPEC_MAPPING_INCOMPLETE", "Every active product specification needs a provider identity");
            }
        }
    }

    public static ProductSpecSnapshot snapshot(ProductSpec productSpec) {
        return new ProductSpecSnapshot(productSpec.getId(), productSpec.getName());
    }

    public static ExistingMappingSnapshot snapshot(SalesChannelListingSpec mapping) {
        return new ExistingMappingSnapshot(mapping.getProductSpecId(), mapping.getExternalSkuId(),
                mapping.getProviderMapping());
    }

    private Map<String, ProductSpecSnapshot> activeProductSpecs(List<ProductSpecSnapshot> productSpecs) {
        Map<String, ProductSpecSnapshot> result = new LinkedHashMap<>();
        for (ProductSpecSnapshot productSpec : productSpecs) {
            String name = normalized(productSpec.name());
            if (name.isEmpty()) fail("SPEC_MAPPING_INCOMPLETE", "An active MyShop specification name is empty");
            if (result.put(name, productSpec) != null) {
                fail("SPEC_MAPPING_INCOMPLETE", "Active MyShop specification names must be unique");
            }
        }
        return result;
    }

    private SpecDimension providerDimension(ListingSpecMappingsData provider) {
        List<SpecMappingSpecification> specifications = provider.specifications() == null
                ? List.of() : provider.specifications();
        if (specifications.size() != 1) {
            fail("UNSUPPORTED_SPEC_DIMENSION", "Phase 4 backfill supports exactly one provider specification dimension");
        }
        SpecMappingSpecification specification = specifications.getFirst();
        if (!isNonBlank(specification.name())) {
            fail("UNSUPPORTED_SPEC_DIMENSION", "The provider specification dimension name is empty");
        }
        return new SpecDimension(specification.name(), specification);
    }

    private Map<String, String> providerValues(SpecMappingSpecification specification) {
        Map<String, String> values = new LinkedHashMap<>();
        List<SpecMappingSpecificationValue> rawValues = specification.values() == null
                ? List.of() : specification.values();
        for (SpecMappingSpecificationValue value : rawValues) {
            if (value == null || !isNonBlank(value.name())) {
                fail("SPEC_MAPPING_INCOMPLETE", "Provider specification values must not be empty");
            }
            String normalized = normalized(value.name());
            if (values.put(normalized, value.name()) != null) {
                fail("SPEC_MAPPING_INCOMPLETE", "Provider specification values must be unique");
            }
        }
        return values;
    }

    private Map<String, SpecMappingSkuRow> providerRows(
            ListingSpecMappingsData provider, SpecDimension dimension, Map<String, String> providerValues) {
        List<SpecMappingSkuRow> rawRows = provider.skuRows() == null ? List.of() : provider.skuRows();
        if (rawRows.size() != providerValues.size()) {
            fail("SPEC_MAPPING_INCOMPLETE", "Provider SKU rows must cover the complete specification set");
        }
        Map<String, SpecMappingSkuRow> rows = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        boolean hasSkuId = false;
        boolean hasMissingSkuId = false;
        for (SpecMappingSkuRow row : rawRows) {
            if (row == null || row.specs() == null || row.specs().size() != 1) {
                fail("SPEC_MAPPING_INCOMPLETE", "Each provider SKU row must contain one exact specification value");
            }
            Map.Entry<String, String> entry = row.specs().entrySet().iterator().next();
            if (!normalized(entry.getKey()).equals(normalized(dimension.name())) || !isNonBlank(entry.getValue())) {
                fail("SPEC_MAPPING_INCOMPLETE", "Each provider SKU row must use the returned specification dimension");
            }
            String value = normalized(entry.getValue());
            if (!providerValues.containsKey(value) || rows.put(value, row) != null) {
                fail("SPEC_MAPPING_INCOMPLETE", "Provider SKU rows must have unique exact specification values");
            }
            if (isNonBlank(row.externalSkuId())) {
                hasSkuId = true;
                if (!ids.add(row.externalSkuId())) {
                    fail("AMBIGUOUS_EXTERNAL_SKU", "Provider external SKU identities must be unique");
                }
            } else {
                hasMissingSkuId = true;
            }
        }
        if (hasSkuId && hasMissingSkuId) {
            fail("AMBIGUOUS_EXTERNAL_SKU", "Provider external SKU identities must be complete or entirely absent");
        }
        if (rows.size() != providerValues.size()) {
            fail("SPEC_MAPPING_INCOMPLETE", "Provider SKU rows must cover every returned specification value");
        }
        for (String providerValue : providerValues.keySet()) {
            if (!rows.containsKey(providerValue)) {
                fail("SPEC_MAPPING_INCOMPLETE", "Provider SKU rows must cover every returned specification value");
            }
        }
        return rows;
    }

    private void validateExistingMapping(ExistingMappingSnapshot existing,
                                         String providerName, String providerValue, String providerSkuId) {
        String existingName = mappingProviderValue(existing.providerMapping(), "provider_spec_name");
        String existingValue = mappingProviderValue(existing.providerMapping(), "provider_spec_value");
        if (isNonBlank(existingName) != isNonBlank(existingValue)) {
            fail("PROVIDER_SPEC_MAPPING_CONFLICT", "An existing provider mapping is incomplete");
        }
        if (isNonBlank(existingName)
                && (!existingName.equals(providerName) || !existingValue.equals(providerValue))) {
            fail("PROVIDER_SPEC_MAPPING_CONFLICT", "An existing provider mapping conflicts with the provider");
        }
        if (isNonBlank(existing.externalSkuId()) && isNonBlank(providerSkuId)
                && !existing.externalSkuId().equals(providerSkuId)) {
            fail("EXTERNAL_SKU_ID_CONFLICT", "An existing external SKU identity conflicts with the provider");
        }
    }

    private String mappingProviderValue(Map<String, Object> mapping, String key) {
        if (mapping == null) return null;
        Object value = mapping.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private void fail(String code, String message) {
        throw new SalesChannelSpecMappingException(code, message);
    }

    private record SpecDimension(String name, SpecMappingSpecification specification) { }

    public record ProductSpecSnapshot(UUID id, String name) { }

    public record ExistingMappingSnapshot(UUID productSpecId, String externalSkuId,
                                          Map<String, Object> providerMapping) {
        public ExistingMappingSnapshot {
            providerMapping = providerMapping == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(providerMapping));
        }
    }

    public record ResolvedSpecMapping(UUID productSpecId, String productSpecName,
                                      String providerSpecName, String providerSpecValue,
                                      String externalSkuId) { }
}
