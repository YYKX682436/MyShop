package com.myshop.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.List;

/** Provider-neutral catalog data; provider-specific details remain in the snapshot. */
public final class SupplierModels {
    private SupplierModels() {}
    public record Connection(String baseUrl, String appId, String secret) {
        @Override public String toString() { return "SupplierConnection[redacted]"; }
    }
    public record Account(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance) {}
    public record Category(String id, String name, List<Category> children) {}
    public record Summary(String id, String name, int goodsType, int status, int skuType, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cost) {}
    public record Page(List<Summary> items, Integer reportedCount, Integer reportedPages) {}
    public record Sku(String id, String name, String group, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cost, long stock, int status) {}
    public record Product(String id, String name, int goodsType, int status, int skuType,
                          int multiple, int minQuantity, int maxQuantity, List<Sku> skus,
                          JsonNode snapshot) {}
}
