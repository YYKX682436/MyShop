package com.myshop.saleschannel.convergence;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;
import java.time.Instant;

/**
 * Phase 1 contracts for the converged MyShop/Xianyu Agent boundary.
 * These types are intentionally not wired to the existing listing workers.
 */
public final class XianyuConvergenceContracts {

    private XianyuConvergenceContracts() { }

    public enum DesiredListingState { ONLINE, OFFLINE }

    /** The only state vocabulary MyShop observes from Agent commands. */
    public enum OperationState { APPLYING, SUCCEEDED, FAILED }

    public enum MessageAction { NO_REPLY, SEND_TEXT, SEND_IMAGE, SEND_TEXT_AND_IMAGE }

    public record EnsureListingRequest(
            @JsonProperty("request_id") @NotBlank String requestId,
            @JsonProperty("listing_key") @NotBlank String listingKey,
            @JsonProperty("account_id") @NotBlank String accountId,
            @JsonProperty("revision") @NotNull @Min(0) Long revision,
            @JsonProperty("desired_state") @NotNull DesiredListingState desiredState,
            @JsonProperty("desired_snapshot") @NotNull @Valid ListingSnapshot desiredSnapshot) { }

    public record ListingSnapshot(
            @NotBlank String title,
            String description,
            @NotBlank @Pattern(regexp = "^[0-9]{1,8}(\\.[0-9]{1,2})?$") String price,
            @NotNull @Min(0) Integer stock,
            @NotEmpty List<@Valid ListingImage> images,
            @NotNull @Valid ListingCategory category,
            @NotNull @Valid ListingAddress address,
            @NotNull @JsonProperty("platform_attributes") List<@Valid PlatformAttribute> platformAttributes,
            @NotNull List<@Valid ListingSpecification> specifications,
            @NotNull List<@Valid ListingSku> skus) { }

    /** Agent fetches the image and handles provider media preparation. */
    public record ListingImage(
            @NotBlank @JsonProperty("asset_key") String assetKey,
            @NotBlank @JsonProperty("source_url") String sourceUrl,
            @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256) { }

    public record ListingCategory(
            @JsonProperty("category_id") String categoryId,
            @JsonProperty("category_name") String categoryName,
            @JsonProperty("channel_category_id") String channelCategoryId,
            @JsonProperty("channel_category_name") String channelCategoryName,
            @JsonProperty("leaf_id") String leafId) { }

    public record ListingAddress(
            String id,
            String name,
            String district,
            String adcode,
            String location,
            String address,
            String city,
            @JsonProperty("expected_text") String expectedText) { }

    public record PlatformAttribute(
            @JsonProperty("property_id") String propertyId,
            @JsonProperty("property_name") String propertyName,
            @JsonProperty("value_id") String valueId,
            @JsonProperty("value_name") String valueName,
            String text,
            Map<String, Object> properties) { }

    public record ListingSpecification(
            @NotBlank String name,
            @NotEmpty List<@NotBlank String> values) { }

    /** sku_key is an opaque MyShop key copied through Agent item history. */
    public record ListingSku(
            @NotBlank @JsonProperty("sku_key") String skuKey,
            @NotEmpty @JsonProperty("spec_values") Map<@NotBlank String, @NotBlank String> specValues,
            @NotBlank @Pattern(regexp = "^[0-9]{1,8}(\\.[0-9]{1,2})?$") String price,
            @NotNull @Min(0) Integer stock) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OperationObservation(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("listing_key") String listingKey,
            @NotNull OperationState state,
            @Min(0) Long revision,
            @JsonProperty("external_item_id") String externalItemId,
            @JsonProperty("external_order_id") String externalOrderId,
            @JsonProperty("unit_index") Integer unitIndex,
            @Valid OperationError error) { }

    public record OperationError(String code, String message) { }

    public record AdoptExistingItemRequest(
            @JsonProperty("request_id") @NotBlank String requestId,
            @JsonProperty("listing_key") @NotBlank String listingKey,
            @JsonProperty("account_id") @NotBlank String accountId,
            @JsonProperty("external_item_id") @NotBlank String externalItemId) { }

    public record SendDeliveryRequest(
            @JsonProperty("request_id") @NotBlank String requestId,
            @JsonProperty("listing_key") @NotBlank String listingKey,
            @JsonProperty("account_id") @NotBlank String accountId,
            @JsonProperty("external_item_id") @NotBlank String externalItemId,
            @JsonProperty("external_order_id") @NotBlank String externalOrderId,
            @JsonProperty("unit_index") @NotNull @Min(0) Integer unitIndex,
            @JsonProperty("content_parts") @NotEmpty List<@NotBlank String> contentParts) { }

    public record ConfirmDeliveryRequest(
            @JsonProperty("request_id") @NotBlank String requestId,
            @JsonProperty("listing_key") @NotBlank String listingKey,
            @JsonProperty("account_id") @NotBlank String accountId,
            @JsonProperty("external_item_id") @NotBlank String externalItemId,
            @JsonProperty("external_order_id") @NotBlank String externalOrderId) { }

    /** Normalized event received at POST /internal/xianyu/reply/resolve. */
    public record MessageEvent(
            @JsonProperty("message_id") @NotBlank String messageId,
            @JsonProperty("account_id") @NotBlank String accountId,
            @JsonProperty("conversation_id") @NotBlank String conversationId,
            @JsonProperty("sender_id") @NotBlank String senderId,
            @JsonProperty("sender_name") @NotBlank String senderName,
            @JsonProperty("external_item_id") @NotBlank String externalItemId,
            @JsonProperty("listing_key") @NotBlank String listingKey,
            @JsonProperty("order_id") String orderId,
            @JsonProperty("message_type") @NotBlank String messageType,
            String text,
            @NotNull Instant timestamp) { }

    public record ReplyImage(
            @NotBlank String url,
            @JsonProperty("content_type") @NotBlank String contentType,
            @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{64}$") String sha256) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplyResolution(
            @JsonProperty("message_id") @NotBlank String messageId,
            @NotNull MessageAction action,
            String text,
            @Valid ReplyImage image) { }
}
