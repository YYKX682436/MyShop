package com.myshop.saleschannel.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
            @JsonProperty("message") String message,
            @JsonProperty("retryable") Boolean retryable) {
        public IntegrationError(String code, String message) {
            this(code, message, false);
        }
    }

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

    public record AddressSearchCommand(String requestId, String keywords, String city) { }

    public record AddressTip(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("district") String district,
            @JsonProperty("adcode") String adcode,
            @JsonProperty("location") String location,
            @JsonProperty("address") String address,
            @JsonProperty("city") String city,
            @JsonProperty("search_keyword") String searchKeyword,
            @JsonProperty("expected_text") String expectedText) { }

    public record AddressSearchData(
            @JsonProperty("keywords") String keywords,
            @JsonProperty("tips") List<AddressTip> tips) { }

    public record AddressMetadataResult(
            String requestId,
            String keywords,
            List<AddressTip> tips) { }

    public record CategoryRecommendCommand(
            String requestId,
            String accountId,
            String title,
            String description,
            List<Map<String, Object>> cardList,
            Map<String, Object> selectedCategory) { }

    public record CategoryRecommendData(
            @JsonProperty("candidates") List<Map<String, Object>> candidates,
            @JsonProperty("properties") List<Map<String, Object>> properties,
            @JsonProperty("card_list") List<Map<String, Object>> cardList) { }

    public record CategoryRecommendResult(
            String requestId,
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> properties,
            List<Map<String, Object>> cardList) { }

    public record MediaUploadCommand(
            String requestId,
            String accountId,
            MediaType mediaType,
            String filename,
            String contentType,
            byte[] content) {
        public MediaUploadCommand {
            content = content == null ? null : content.clone();
            if (requestId == null || requestId.isBlank()) {
                requestId = deterministicRequestId(accountId, mediaType, content);
            }
            if (requestId != null && requestId.length() > 255) {
                throw new IllegalArgumentException("media request id must not exceed 255 characters");
            }
        }

        public MediaUploadCommand(String accountId, MediaType mediaType, String filename,
                                  String contentType, byte[] content) {
            this(deterministicRequestId(accountId, mediaType, content), accountId, mediaType,
                    filename, contentType, content);
        }

        private static String deterministicRequestId(String accountId, MediaType mediaType, byte[] content) {
            if (accountId == null || accountId.isBlank() || mediaType == null || content == null) {
                return null;
            }
            try {
                String digest = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content));
                String accountToken = accountId.length() <= 166 ? accountId
                        : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(accountId.getBytes(StandardCharsets.UTF_8)));
                return "XIANYU_MEDIA:" + accountToken + ":" + mediaType.wireValue() + ":" + digest;
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
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

    public record SpecMappingsCommand(String requestId, String accountId, String itemId) { }

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
            @JsonProperty("desired_revision") Long desiredRevision,
            @JsonProperty("sku_mappings") List<SkuMapping> skuMappings) { }

    public record SkuMapping(
            @JsonProperty("external_sku_id") String externalSkuId,
            @JsonProperty("spec_name") String specName,
            @JsonProperty("spec_value") String specValue) { }

    public record ListingSpecMappingsData(
            @JsonProperty("item_id") String itemId,
            @JsonProperty("specifications") List<SpecMappingSpecification> specifications,
            @JsonProperty("sku_rows") List<SpecMappingSkuRow> skuRows) { }

    public record SpecMappingSpecification(
            @JsonProperty("name") String name,
            @JsonProperty("values") List<SpecMappingSpecificationValue> values) { }

    public record SpecMappingSpecificationValue(
            @JsonProperty("name") String name) { }

    public record SpecMappingSkuRow(
            @JsonProperty("external_sku_id") String externalSkuId,
            @JsonProperty("specs") Map<String, String> specs) { }

    public record ReconcileData(
            @JsonProperty("target_request_id") String targetRequestId,
            @JsonProperty("target_outcome") Outcome targetOutcome,
            @JsonProperty("operation_type") String operationType,
            @JsonProperty("item_id") String itemId,
            @JsonProperty("desired_revision") Long desiredRevision,
            @JsonProperty("result") Map<String, Object> result,
            @JsonProperty("target_retryable") Boolean targetRetryable) { }

    public record OrderCursor(
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("id") Long id) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentOrder(
            @JsonProperty("account_id") String accountId,
            @JsonProperty("external_order_no") String externalOrderNo,
            @JsonProperty("status") String status,
            @JsonProperty("item_id") String itemId,
            @JsonProperty("external_sku_id") String externalSkuId,
            @JsonProperty("spec_name") String specName,
            @JsonProperty("spec_value") String specValue,
            @JsonProperty("quantity") Integer quantity,
            @JsonProperty("amount") String amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("placed_at") String placedAt,
            @JsonProperty("updated_at") String updatedAt) { }

    public record OrderChangesData(
            @JsonProperty("orders") List<AgentOrder> orders,
            @JsonProperty("next_cursor") OrderCursor nextCursor,
            @JsonProperty("has_more") Boolean hasMore) { }

    public record WatermarkData(
            @JsonProperty("cursor") OrderCursor cursor,
            @JsonProperty("empty") Boolean empty) { }

    public record OrderChangesCommand(
            String requestId,
            List<String> accountIds,
            OrderCursor cursor,
            Integer limit) { }

    public record WatermarkCommand(String requestId, List<String> accountIds) { }

    public record DeliverySendCommand(
            String requestId,
            String accountId,
            String externalOrderNo,
            Integer unitIndex,
            List<String> contentParts) { }

    public record DeliveryReconcileCommand(
            String requestId,
            String accountId,
            String targetRequestId,
            String externalOrderNo,
            Integer unitIndex) { }

    public record ConfirmDeliveryCommand(
            String requestId,
            String accountId,
            String externalOrderNo) { }

    public record DeliveryData(
            @JsonProperty("external_order_no") String externalOrderNo,
            @JsonProperty("unit_index") Integer unitIndex,
            @JsonProperty("status") String status,
            @JsonProperty("message_id") String messageId) { }

    public record DeliveryReconcileData(
            @JsonProperty("target_request_id") String targetRequestId,
            @JsonProperty("target_outcome") Outcome targetOutcome,
            @JsonProperty("operation_type") String operationType,
            @JsonProperty("target_retryable") Boolean targetRetryable,
            @JsonProperty("result") Map<String, Object> result) { }
}
