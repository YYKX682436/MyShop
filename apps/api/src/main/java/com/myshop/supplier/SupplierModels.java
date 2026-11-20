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
    public record Attachment(String name, String value) {
        @Override public String toString() { return "Attachment[redacted]"; }
    }
    public record PurchaseRequest(String goodsId, int count, String notifyUrl, String outerNumber,
                                  BigDecimal safePrice, String sku, List<Attachment> attach) {
        @Override public String toString() { return "PurchaseRequest[redacted]"; }
    }
    public record Card(String id, String number, String password, int status, int cancelRequest) {
        @Override public String toString() { return "Card[redacted]"; }
    }
    public record CreatedPurchase(String orderNumber, List<Card> cards) {
        @Override public String toString() { return "CreatedPurchase[redacted]"; }
    }
    public record PurchaseDetail(String orderNumber, String outerNumber, int status, int count,
                                 BigDecimal money, BigDecimal refunded, String result,
                                 String startCount, String nowCount, String endCount, List<Card> cards) {
        @Override public String toString() { return "PurchaseDetail[redacted]"; }
    }
    public record RefundResult(int status) {}
    public record ComplaintRequest(String orderNumber,String topic,String content,String notifyUrl,List<String> images) {
        @Override public String toString() { return "ComplaintRequest[redacted]"; }
    }
    public record ComplaintResult(long requestId) {}
    public record CardDetail(String id,int status,int cancelRequest,String result) {
        @Override public String toString() { return "CardDetail[redacted]"; }
    }
    public record CardVoidResult(int status,String result) {
        @Override public String toString() { return "CardVoidResult[redacted]"; }
    }
}
