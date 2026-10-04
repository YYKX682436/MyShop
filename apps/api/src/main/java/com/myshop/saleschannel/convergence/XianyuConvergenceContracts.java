package com.myshop.saleschannel.convergence;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

import java.util.List;
import java.util.Map;
import java.time.Instant;

/**
 * Phase 1 contracts for the converged MyShop/Xianyu Agent boundary.
 * These types are intentionally not wired to the existing listing workers.
 */
public final class XianyuConvergenceContracts {

    private XianyuConvergenceContracts() { }

    private static void requireUri(String value) {
        if (value == null) return; // Bean validation rejects null required fields.
        try {
            if (!new java.net.URI(value).isAbsolute()) throw new IllegalArgumentException("URI must be absolute");
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid URI", exception);
        }
    }

    /** Reject unknown wire properties even when the application mapper ignores them. */
    public interface ClosedContract {
        @JsonAnySetter
        default void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown convergence property: " + name);
        }
    }

    public enum DesiredListingState { ONLINE, OFFLINE }

    /** The only state vocabulary MyShop observes from Agent commands. */
    public enum OperationState { APPLYING, SUCCEEDED, FAILED }

    public enum MessageAction { NO_REPLY, SEND_TEXT, SEND_IMAGE, SEND_TEXT_AND_IMAGE }

    public record EnsureListingRequest(
            @JsonProperty(value = "request_id", required = true) @NotNull @CodePointLength(min = 1, max = 255) String requestId,
            @JsonProperty(value = "listing_key", required = true) @NotNull @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "account_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String accountId,
            @JsonProperty(value = "revision", required = true) @NotNull @Min(0) Long revision,
            @JsonProperty(value = "desired_state", required = true) @NotNull DesiredListingState desiredState,
            @JsonProperty(value = "desired_snapshot", required = true) @NotNull @Valid ListingSnapshot desiredSnapshot) implements ClosedContract { }

    public record ListingSnapshot(
            @JsonProperty(value = "title", required = true) @NotNull @CodePointLength(min = 1) String title,
            @JsonProperty(value = "description", required = true) String description,
            @JsonProperty(value = "price", required = true) @NotNull @CodePointLength(min = 1) @Pattern(regexp = "^[0-9]{1,8}(\\.[0-9]{1,2})?(?![\\s\\S])") String price,
            @JsonProperty(value = "stock", required = true) @NotNull @Min(0) Integer stock,
            @JsonProperty(value = "images", required = true) @NotEmpty List<@NotNull @Valid ListingImage> images,
            @JsonProperty(value = "category", required = true) @NotNull @Valid ListingCategory category,
            @JsonProperty(value = "address", required = true) @NotNull @Valid ListingAddress address,
            @NotNull @JsonProperty(value = "platform_attributes", required = true) List<@NotNull @Valid PlatformAttribute> platformAttributes,
            @JsonProperty(value = "specifications", required = true) @NotNull List<@NotNull @Valid ListingSpecification> specifications,
            @JsonProperty(value = "skus", required = true) @NotNull List<@NotNull @Valid ListingSku> skus) implements ClosedContract { }

    /** Agent fetches the image and handles provider media preparation. */
    public record ListingImage(
            @NotNull @CodePointLength(min = 1) @JsonProperty(value = "asset_key", required = true) @CodePointLength(max = 128) String assetKey,
            @NotNull @CodePointLength(min = 1) @JsonProperty(value = "source_url", required = true) String sourceUrl,
            @JsonProperty(value = "sha256", required = true) @NotNull @CodePointLength(min = 1) @Pattern(regexp = "^[a-fA-F0-9]{64}(?![\\s\\S])") String sha256) implements ClosedContract {
        public ListingImage { requireUri(sourceUrl); }
    }

    public record ListingCategory(
            @JsonProperty("category_id") @CodePointLength(max = 64) String categoryId,
            @JsonProperty("category_name") @CodePointLength(max = 100) String categoryName,
            @JsonProperty("channel_category_id") @CodePointLength(max = 64) String channelCategoryId,
            @JsonProperty("channel_category_name") @CodePointLength(max = 100) String channelCategoryName,
            @JsonProperty("leaf_id") @CodePointLength(max = 64) String leafId) implements ClosedContract { }

    public record ListingAddress(
            @CodePointLength(max = 128) String id,
            @CodePointLength(max = 200) String name,
            @CodePointLength(max = 200) String district,
            @CodePointLength(max = 32) String adcode,
            @CodePointLength(max = 128) String location,
            @CodePointLength(max = 500) String address,
            @CodePointLength(max = 100) String city,
            @JsonProperty("expected_text") @CodePointLength(max = 200) String expectedText) implements ClosedContract { }

    public record PlatformAttribute(
            @JsonProperty("property_id") @CodePointLength(max = 64) String propertyId,
            @JsonProperty("property_name") @CodePointLength(max = 100) String propertyName,
            @JsonProperty("value_id") @CodePointLength(max = 64) String valueId,
            @JsonProperty("value_name") @CodePointLength(max = 200) String valueName,
            @CodePointLength(max = 200) String text,
            Map<String, Object> properties) implements ClosedContract { }

    public record ListingSpecification(
            @JsonProperty(value = "name", required = true) @NotNull @CodePointLength(min = 1, max = 100) String name,
            @JsonProperty(value = "values", required = true) @NotEmpty @Size(max = 50) List<@NotNull @CodePointLength(min = 1, max = 100) String> values) implements ClosedContract { }

    /** sku_key is an opaque MyShop key copied through Agent item history. */
    public record ListingSku(
            @NotNull @CodePointLength(min = 1) @JsonProperty(value = "sku_key", required = true) @CodePointLength(max = 128) String skuKey,
            @NotEmpty @JsonProperty(value = "spec_values", required = true) Map<@NotNull @CodePointLength(min = 1) String, @NotNull @CodePointLength(min = 1) String> specValues,
            @JsonProperty(value = "price", required = true) @NotNull @CodePointLength(min = 1) @Pattern(regexp = "^[0-9]{1,8}(\\.[0-9]{1,2})?(?![\\s\\S])") String price,
            @JsonProperty(value = "stock", required = true) @NotNull @Min(0) Integer stock) implements ClosedContract { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OperationObservation(
            @JsonProperty(value = "request_id", required = true) @NotNull @CodePointLength(min = 1, max = 255) String requestId,
            @JsonProperty(value = "listing_key", required = true) @NotNull @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "state", required = true) @NotNull OperationState state,
            @Min(0) Long revision,
            @JsonProperty("external_item_id") @CodePointLength(max = 128) String externalItemId,
            @JsonProperty("external_order_id") @CodePointLength(max = 128) String externalOrderId,
            @JsonProperty("unit_index") @Min(0) Integer unitIndex,
            @Valid OperationError error) implements ClosedContract { }

    public record OperationError(
            @JsonProperty(value = "code", required = true) @NotNull @CodePointLength(min = 1, max = 128) String code,
            @JsonProperty(value = "message", required = true) @NotNull @CodePointLength(min = 1, max = 1000) String message) implements ClosedContract { }

    public record AdoptExistingItemRequest(
            @JsonProperty(value = "request_id", required = true) @NotNull @CodePointLength(min = 1, max = 255) String requestId,
            @JsonProperty(value = "listing_key", required = true) @NotNull @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "account_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String accountId,
            @JsonProperty(value = "external_item_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String externalItemId) implements ClosedContract { }

    public record SendDeliveryRequest(
            @JsonProperty(value = "request_id", required = true) @NotNull @CodePointLength(min = 1, max = 255) String requestId,
            @JsonProperty(value = "listing_key", required = true) @NotNull @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "account_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String accountId,
            @JsonProperty(value = "external_item_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String externalItemId,
            @JsonProperty(value = "external_order_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String externalOrderId,
            @JsonProperty(value = "unit_index", required = true) @NotNull @Min(0) Integer unitIndex,
            @JsonProperty(value = "content_parts", required = true) @NotEmpty List<@NotNull @CodePointLength(min = 1) String> contentParts) implements ClosedContract { }

    public record ConfirmDeliveryRequest(
            @JsonProperty(value = "request_id", required = true) @NotNull @CodePointLength(min = 1, max = 255) String requestId,
            @JsonProperty(value = "listing_key", required = true) @NotNull @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "account_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String accountId,
            @JsonProperty(value = "external_item_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String externalItemId,
            @JsonProperty(value = "external_order_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String externalOrderId) implements ClosedContract { }

    /** Normalized event received at POST /internal/xianyu/reply/resolve. */
    public record MessageEvent(
            @JsonProperty(value = "message_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String messageId,
            @JsonProperty(value = "account_id", required = true) @NotNull @CodePointLength(min = 1, max = 80) String accountId,
            @JsonProperty(value = "conversation_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String conversationId,
            @JsonProperty(value = "sender_id", required = true) @NotNull @CodePointLength(min = 1, max = 128) String senderId,
            @JsonProperty(value = "sender_name", required = true) @NotNull @CodePointLength(min = 1, max = 255) String senderName,
            @JsonProperty(value = "external_item_id", required = true) @NotNull @CodePointLength(min = 1, max = 64) String externalItemId,
            @JsonProperty(value = "listing_key", required = true) @NotNull @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "order_id", required = true) @CodePointLength(max = 128) String orderId,
            @JsonProperty(value = "message_type", required = true) @NotNull @CodePointLength(min = 1, max = 64) String messageType,
            @JsonProperty(value = "text", required = true) @CodePointLength(max = 20000) String text,
            @JsonProperty(value = "timestamp", required = true) @JsonFormat(shape = JsonFormat.Shape.STRING) @NotNull Instant timestamp) implements ClosedContract { }

    public record ReplyImage(
            @JsonProperty(value = "url", required = true) @NotNull @CodePointLength(min = 1, max = 2048) String url,
            @JsonProperty(value = "content_type", required = true) @NotNull @CodePointLength(min = 1, max = 100) String contentType,
            @JsonProperty(value = "sha256", required = true) @NotNull @CodePointLength(min = 1) @Pattern(regexp = "^[a-fA-F0-9]{64}(?![\\s\\S])") String sha256) implements ClosedContract {
        public ReplyImage { requireUri(url); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplyResolution(
            @JsonProperty("message_id") @NotNull @CodePointLength(min = 1) String messageId,
            @NotNull MessageAction action,
            @CodePointLength(min = 1, max = 2000) String text,
            @Valid ReplyImage image) implements ClosedContract {
        public ReplyResolution {
            if (messageId == null || messageId.isEmpty() || action == null) {
                throw new IllegalArgumentException("Reply message_id and action are required");
            }
            boolean needsText = action == MessageAction.SEND_TEXT || action == MessageAction.SEND_TEXT_AND_IMAGE;
            boolean needsImage = action == MessageAction.SEND_IMAGE || action == MessageAction.SEND_TEXT_AND_IMAGE;
            if (needsText != (text != null) || needsImage != (image != null)
                    || (text != null && (text.isEmpty() || text.codePointCount(0, text.length()) > 2000))) {
                throw new IllegalArgumentException("Reply payload must match action");
            }
        }
    }
}
