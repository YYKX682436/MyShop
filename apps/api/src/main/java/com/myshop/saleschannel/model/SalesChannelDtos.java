package com.myshop.saleschannel.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Typed provider-neutral commands and typed Xianyu integration response data. */
public final class SalesChannelDtos {

    private SalesChannelDtos() { }

    public enum Outcome {
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    public enum MediaType {
        IMAGE("image"),
        SPEC_IMAGE("spec_image"),
        VIDEO("video");

        private final String wireValue;

        MediaType(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public record IntegrationError(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message) { }

    public record IntegrationEnvelope<T>(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("outcome") Outcome outcome,
            @JsonProperty("data") T data,
            @JsonProperty("error") IntegrationError error) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountCapabilities(
            @JsonProperty("account_type") String accountType,
            @JsonProperty("publish") Boolean publish,
            @JsonProperty("edit") Boolean edit,
            @JsonProperty("stock_sync") Boolean stockSync,
            @JsonProperty("sku") Boolean sku,
            @JsonProperty("video") Boolean video) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SalesChannelAccount(
            @JsonProperty("provider_account_id") String providerAccountId,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("status") String status,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("last_login_at") String lastLoginAt,
            @JsonProperty("last_refresh_at") String lastRefreshAt,
            @JsonProperty("capabilities") AccountCapabilities capabilities) { }

    public record AccountsData(
            @JsonProperty("accounts") List<SalesChannelAccount> accounts) { }

    public record CapabilityData(
            @JsonProperty("capabilities") AccountCapabilities capabilities) { }

    public record MediaUploadCommand(
            String accountId,
            MediaType mediaType,
            String filename,
            String contentType,
            byte[] content) {
        public MediaUploadCommand {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    public record MediaUploadData(
            @JsonProperty("media_id") String mediaId,
            @JsonProperty("media_type") String mediaType) { }

    public record PublishCommand(String requestId, String accountId, long desiredRevision,
                                 ListingSnapshot listing) { }

    public record UpdateCommand(String requestId, String accountId, long desiredRevision,
                                ListingSnapshot listing) { }

    public record ReconcileCommand(String requestId, String accountId, String targetRequestId,
                                   String expectedItemId, long desiredRevision) { }

    /** Target product data needed by the Xianyu publish/edit wire contract. */
    public record ListingSnapshot(
            String title,
            String description,
            BigDecimal price,
            BigDecimal originalPrice,
            List<String> imageMediaIds,
            List<String> videoMediaIds,
            String platformCategoryId,
            String platformCategoryName,
            String platformChannelCategoryId,
            String platformChannelCategoryName,
            String platformLeafId,
            String platformTbCategoryId,
            List<PlatformAttribute> platformAttributes,
            Integer quantity,
            List<Specification> specifications,
            List<SkuRow> skuRows,
            String address,
            String addressExpectedText,
            String deliveryMethod,
            String shippingMethod,
            Boolean supportPickup,
            BigDecimal postage) { }

    public record PlatformAttribute(String propertyId, String propertyName, String valueId,
                                    String valueName, String text, Map<String, Object> properties) { }

    public record Specification(String name, List<SpecificationValue> values, Boolean supportImage) { }

    public record SpecificationValue(String name, String imageMediaId) { }

    public record SkuRow(Map<String, String> specs, BigDecimal price, Integer stock) { }

    public record ListingOperationData(
            @JsonProperty("item_id") String itemId,
            @JsonProperty("item_url") String itemUrl,
            @JsonProperty("capability") AccountCapabilities capability,
            @JsonProperty("idempotent_reused") Boolean idempotentReused,
            @JsonProperty("desired_revision") Long desiredRevision) { }

    public record ReconcileData(
            @JsonProperty("target_request_id") String targetRequestId,
            @JsonProperty("operation_type") String operationType,
            @JsonProperty("item_id") String itemId,
            @JsonProperty("desired_revision") Long desiredRevision) { }
}
