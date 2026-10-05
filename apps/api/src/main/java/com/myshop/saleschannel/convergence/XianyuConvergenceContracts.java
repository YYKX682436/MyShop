package com.myshop.saleschannel.convergence;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
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

    /** Money on this wire is a canonical JSON string; reject Jackson's numeric coercion. */
    public static final class StrictMoneyWireDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws java.io.IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw JsonMappingException.from(parser, "money must be a canonical two-decimal JSON string");
            }
            return parser.getText();
        }
    }

    private static void requireHttpUri(String value) {
        if (value == null) return; // Bean validation rejects null required fields.
        try {
            java.net.URI uri = new java.net.URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("URI must be an absolute HTTP(S) URL without credentials");
            }
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid URI", exception);
        }
    }

    private static void requireUri(String value) {
        if (value == null) return;
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

    public enum AgentBindingState { CURRENT, TRANSITION_PENDING, RETIRED }

    public enum AgentProviderStatus { ACTIVE, OFFLINE, MISSING, UNAVAILABLE }

    public enum MessageAction { NO_REPLY, SEND_TEXT, SEND_IMAGE, SEND_TEXT_AND_IMAGE }

    public record EnsureListingRequest(
            @JsonProperty(value = "request_id", required = true) @NotNull @CodePointLength(min = 1, max = 255) @Pattern(regexp = "^[A-Za-z0-9._~-]{1,255}$") String requestId,
            @JsonProperty(value = "listing_key", required = true) @NotNull @NotBlank @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "account_id", required = true) @NotNull @CodePointLength(min = 1, max = 80) String accountId,
            @JsonProperty(value = "revision", required = true) @NotNull @Min(0) Long revision,
            @JsonProperty(value = "desired_state", required = true) @NotNull DesiredListingState desiredState,
            @JsonProperty(value = "desired_snapshot", required = true)
            @JsonInclude(JsonInclude.Include.ALWAYS) @Valid ListingSnapshot desiredSnapshot) implements ClosedContract {
        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isAccountIdExact() {
            return accountId == null || (!accountId.isBlank() && accountId.equals(accountId.strip()));
        }

        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isSnapshotIdentityConsistent() {
            return listingKey == null || desiredSnapshot == null || listingKey.equals(desiredSnapshot.listingKey());
        }

        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isListingKeyCanonical() {
            return listingKey == null || canonicalListingKey(listingKey);
        }

        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isDesiredSnapshotConsistentWithState() {
            if (desiredState == null) return true;
            return desiredState == DesiredListingState.ONLINE
                    ? desiredSnapshot != null
                    : desiredSnapshot == null;
        }
    }

    public record ListingSnapshot(
            @JsonProperty(value = "schema_version", required = true) @NotNull @Min(1) Integer schemaVersion,
            @JsonProperty(value = "listing_key", required = true) @NotNull @NotBlank @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "title", required = true) @NotNull @NotBlank @CodePointLength(min = 1, max = 200) String title,
            @JsonProperty(value = "description", required = true) @NotNull @NotBlank @CodePointLength(min = 1, max = 1500) String description,
            @JsonProperty(value = "price", required = true) @JsonDeserialize(using = StrictMoneyWireDeserializer.class) @NotNull @CodePointLength(min = 1) @Pattern(regexp = "^[0-9]+\\.[0-9]{2}(?![\\s\\S])") @DecimalMin(value = "0", inclusive = false) String price,
            @JsonProperty(value = "stock", required = true) @NotNull @Min(0) @Max(999999) Integer stock,
            @JsonProperty(value = "images", required = true) @NotEmpty List<@NotNull @Valid ListingImage> images,
            @JsonProperty(value = "category", required = true) @NotNull @Valid ListingCategory category,
            @JsonProperty(value = "address", required = true) @NotNull @Valid ListingAddress address,
            @NotNull @JsonProperty(value = "platform_attributes", required = true) List<@NotNull @Valid PlatformAttribute> platformAttributes,
            @NotNull @JsonProperty(value = "shipping", required = true) @Valid ListingShipping shipping,
            @JsonProperty(value = "specifications", required = true) @NotNull List<@NotNull @Valid ListingSpecification> specifications,
            @JsonProperty(value = "skus", required = true) @NotNull List<@NotNull @Valid ListingSku> skus) implements ClosedContract {
        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isSchemaVersionSupported() { return schemaVersion == null || schemaVersion == 1; }

        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isTextCanonical() {
            if (title != null && (!title.equals(title.strip())
                    || !java.text.Normalizer.isNormalized(title, java.text.Normalizer.Form.NFC))) return false;
            return description == null || (description.equals(description.strip())
                    && !description.contains("\r")
                    && java.text.Normalizer.isNormalized(description, java.text.Normalizer.Form.NFC));
        }

        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isListingKeyCanonical() {
            return listingKey == null || canonicalListingKey(listingKey);
        }

        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isSkuMatrixValid() {
            if (specifications == null || skus == null) return true;
            if (specifications.size() > 2) return false;
            java.util.Map<String, java.util.Set<String>> dimensions = new java.util.LinkedHashMap<>();
            for (ListingSpecification specification : specifications) {
                if (specification == null || specification.name() == null || specification.values() == null
                        || dimensions.putIfAbsent(specification.name(), new java.util.LinkedHashSet<>(specification.values())) != null) return false;
                if (dimensions.get(specification.name()).size() != specification.values().size()) return false;
            }
            if (dimensions.isEmpty()) return skus.isEmpty();
            long expected = 1;
            try {
                for (var values : dimensions.values()) expected = Math.multiplyExact(expected, values.size());
            } catch (ArithmeticException overflow) {
                return false;
            }
            if (skus.size() != expected) return false;
            java.util.Set<String> keys = new java.util.HashSet<>();
            java.util.Set<java.util.List<String>> combinations = new java.util.HashSet<>();
            long aggregate = 0;
            for (ListingSku sku : skus) {
                if (sku == null || sku.skuKey() == null || !keys.add(sku.skuKey())
                        || sku.specValues() == null || !sku.specValues().keySet().equals(dimensions.keySet())) return false;
                java.util.List<String> combination = new java.util.ArrayList<>();
                for (var dimension : dimensions.entrySet()) {
                    String value = sku.specValues().get(dimension.getKey());
                    if (!dimension.getValue().contains(value)) return false;
                    combination.add(value);
                }
                if (!combinations.add(combination)) return false;
                aggregate += sku.stock() == null ? 0 : sku.stock();
            }
            if (aggregate != (stock == null ? -1 : stock) || aggregate > 999_999) return false;
            if (skus.isEmpty()) return true;
            try {
                java.math.BigDecimal minimum = skus.stream()
                        .map(sku -> new java.math.BigDecimal(sku.price()))
                        .min(java.math.BigDecimal::compareTo).orElseThrow();
                return new java.math.BigDecimal(price).compareTo(minimum) == 0;
            } catch (RuntimeException invalidPrice) {
                return false;
            }
        }

        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isPlatformAttributesValid() {
            if (platformAttributes == null) return true;
            java.util.Map<String, String> propertyNames = new java.util.HashMap<>();
            java.util.Set<java.util.List<String>> selections = new java.util.HashSet<>();
            for (PlatformAttribute attribute : platformAttributes) {
                if (attribute == null || attribute.propertyId() == null || attribute.propertyName() == null
                        || "分类".equals(attribute.propertyName())) return false;
                String priorName = propertyNames.putIfAbsent(attribute.propertyId(), attribute.propertyName());
                if (priorName != null && !priorName.equals(attribute.propertyName())) return false;
                java.util.List<String> identity = java.util.List.of(
                        attribute.propertyId(), nullToEmpty(attribute.valueId()),
                        nullToEmpty(attribute.valueName()), nullToEmpty(attribute.text()));
                if (!selections.add(identity)) return false;
            }
            return true;
        }

        private static String nullToEmpty(String value) { return value == null ? "" : value; }
    }

    /** Agent fetches the image and handles provider media preparation. */
    public record ListingImage(
            @NotNull @NotBlank @CodePointLength(min = 1) @JsonProperty(value = "asset_key", required = true) @Pattern(regexp = "^sha256:[0-9a-f]{64}(?![\\s\\S])") String assetKey,
            @NotNull @NotBlank @CodePointLength(min = 1) @JsonProperty(value = "source_url", required = true) String sourceUrl,
            @JsonProperty(value = "sha256", required = true) @NotNull @CodePointLength(min = 1) @Pattern(regexp = "^[0-9a-f]{64}(?![\\s\\S])") String sha256) implements ClosedContract {
        public ListingImage { requireHttpUri(sourceUrl); }

        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isAssetKeyBoundToDigest() {
            return assetKey == null || sha256 == null || assetKey.equals("sha256:" + sha256);
        }
    }

    public record ListingShipping(
            @JsonProperty(value = "method", required = true) @NotNull @Pattern(regexp = "free|distance|fixed|template|none") String method,
            @JsonProperty(value = "support_pickup", required = true) @NotNull Boolean supportPickup,
            @JsonProperty(value = "postage", required = true) @JsonInclude(JsonInclude.Include.ALWAYS) @JsonDeserialize(using = StrictMoneyWireDeserializer.class) String postage) implements ClosedContract {
        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isPostageConsistentWithMethod() {
            if (method == null) return true;
            if (!"fixed".equals(method)) return postage == null;
            if (postage == null || !postage.matches("^[0-9]+\\.[0-9]{2}$")) return false;
            try {
                java.math.BigDecimal value = new java.math.BigDecimal(postage);
                return value.compareTo(java.math.BigDecimal.ZERO) >= 0
                        && value.compareTo(new java.math.BigDecimal("1000.00")) <= 0;
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
    }

    public record ListingCategory(
            @JsonProperty(value = "category_id", required = true) @NotNull @NotBlank @CodePointLength(min = 1) String categoryId,
            @JsonProperty("category_name") String categoryName,
            @JsonProperty(value = "channel_category_id", required = true) @NotNull @NotBlank @CodePointLength(min = 1) String channelCategoryId,
            @JsonProperty(value = "channel_category_name", required = true) @NotNull @NotBlank @CodePointLength(min = 1) String channelCategoryName,
            @JsonProperty("leaf_id") String leafId,
            @JsonProperty(value = "tb_category_id", required = true) @NotNull @NotBlank @CodePointLength(min = 1) String tbCategoryId) implements ClosedContract { }

    public record ListingAddress(
            String id,
            String name,
            String district,
            String adcode,
            String location,
            @JsonProperty(value = "address", required = true) @NotNull @NotBlank @CodePointLength(min = 1) String address,
            String city,
            @JsonProperty(value = "expected_text", required = true) @NotNull @NotBlank @CodePointLength(min = 1) String expectedText) implements ClosedContract { }

    public record PlatformAttribute(
            @JsonProperty(value = "property_id", required = true) @NotNull @NotBlank @CodePointLength(min = 1) String propertyId,
            @JsonProperty(value = "property_name", required = true) @NotNull @NotBlank @CodePointLength(min = 1) String propertyName,
            @JsonProperty("value_id") String valueId,
            @JsonProperty("value_name") String valueName,
            String text,
            Map<String, Object> properties) implements ClosedContract {
        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isValuePresentAndPropertiesClosed() {
            return (propertyName != null && !propertyName.equals("分类"))
                    && (valueName != null && !valueName.isBlank() || text != null && !text.isBlank())
                    && properties == null;
        }
    }

    public record ListingSpecification(
            @JsonProperty(value = "name", required = true) @NotNull @NotBlank @CodePointLength(min = 1) String name,
            @JsonProperty(value = "values", required = true) @NotEmpty List<@NotNull @NotBlank @CodePointLength(min = 1) String> values) implements ClosedContract { }

    /** sku_key is an opaque MyShop key copied through Agent item history. */
    public record ListingSku(
            @NotNull @NotBlank @CodePointLength(min = 1) @JsonProperty(value = "sku_key", required = true) @CodePointLength(max = 128) String skuKey,
            @NotEmpty @JsonProperty(value = "spec_values", required = true) Map<@NotNull @NotBlank @CodePointLength(min = 1) String, @NotNull @NotBlank @CodePointLength(min = 1) String> specValues,
            @JsonProperty(value = "price", required = true) @JsonDeserialize(using = StrictMoneyWireDeserializer.class) @NotNull @CodePointLength(min = 1) @Pattern(regexp = "^[0-9]+\\.[0-9]{2}(?![\\s\\S])") @DecimalMin(value = "0", inclusive = false) String price,
            @JsonProperty(value = "stock", required = true) @NotNull @Min(0) @Max(999999) Integer stock) implements ClosedContract { }

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

    /** Exact v2 observation for durable ENSURE_LISTING commands only. */
    public record EnsureListingOperationObservation(
            @JsonProperty(value = "request_id", required = true) @NotNull @CodePointLength(min = 1, max = 255) @Pattern(regexp = "^[A-Za-z0-9._~-]{1,255}$") String requestId,
            @JsonProperty(value = "listing_key", required = true) @NotNull @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "revision", required = true) @NotNull @Min(0) Long revision,
            @JsonProperty(value = "state", required = true) @NotNull OperationState state,
            @JsonProperty(value = "external_item_id", required = true) @CodePointLength(max = 128) String externalItemId,
            @JsonProperty(value = "error", required = true) @Valid OperationError error) implements ClosedContract { }

    private static boolean canonicalListingKey(String value) {
        return !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= 128
                && java.text.Normalizer.isNormalized(value, java.text.Normalizer.Form.NFC);
    }

    public record OperationError(
            @JsonProperty(value = "code", required = true) @NotNull @CodePointLength(min = 1, max = 128) String code,
            @JsonProperty(value = "message", required = true) @NotNull @CodePointLength(min = 1, max = 1000) String message) implements ClosedContract { }

    public record AdoptExistingItemRequest(
            @JsonProperty(value = "request_id", required = true) @NotNull @CodePointLength(min = 1, max = 255) @Pattern(regexp = "^[A-Za-z0-9._~-]{1,255}$") String requestId,
            @JsonProperty(value = "listing_key", required = true) @NotNull @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "account_id", required = true) @NotNull @CodePointLength(min = 1, max = 80) String accountId,
            @JsonProperty(value = "external_item_id", required = true) @NotNull @CodePointLength(min = 1, max = 64) String externalItemId) implements ClosedContract {
        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isAccountIdExact() {
            return accountId == null || (!accountId.isBlank() && accountId.equals(accountId.strip()));
        }

        @AssertTrue
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isListingKeyCanonical() {
            return listingKey == null || canonicalListingKey(listingKey);
        }
    }

    /** Agent runtime gate report. No credentials or environment values cross this contract. */
    public record CutoverReadiness(
            @JsonProperty(value = "schema_version", required = true) @NotNull Integer schemaVersion,
            @JsonProperty(value = "v2_writes_enabled", required = true) @NotNull Boolean v2WritesEnabled,
            @JsonProperty(value = "v2_adoption_enabled", required = true) @NotNull Boolean v2AdoptionEnabled)
            implements ClosedContract {
        @AssertTrue @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isSupportedSchema() { return schemaVersion == null || schemaVersion == 1; }
    }

    public record CutoverInspectionRequest(
            @JsonProperty(value = "request_id", required = true) @NotNull
            @Pattern(regexp = "^[A-Za-z0-9._~-]{1,255}$") String requestId,
            @JsonProperty(value = "account_id", required = true) @NotNull
            @CodePointLength(min = 1, max = 80) String accountId,
            @JsonProperty(value = "listing_key", required = true) @NotNull
            @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "expected_external_item_id", required = true)
            @JsonInclude(JsonInclude.Include.ALWAYS) @CodePointLength(max = 64) String expectedExternalItemId)
            implements ClosedContract {
        @AssertTrue @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isIdentityExact() {
            return exact(accountId) && canonicalListingKey(listingKey)
                    && (expectedExternalItemId == null || exact(expectedExternalItemId));
        }
    }

    public record CutoverManagedItem(
            @JsonProperty(value = "external_item_id", required = true) @NotNull @NotBlank
            @CodePointLength(min = 1, max = 64) String externalItemId,
            @JsonProperty(value = "managed_by", required = true) @NotNull @NotBlank
            @CodePointLength(min = 1, max = 32) String managedBy) implements ClosedContract { }

    public record CutoverBinding(
            @JsonProperty(value = "listing_key", required = true) @NotNull @NotBlank
            @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "external_item_id", required = true) @NotNull @NotBlank
            @CodePointLength(min = 1, max = 64) String externalItemId,
            @JsonProperty(value = "state", required = true) @NotNull AgentBindingState state,
            @JsonProperty(value = "desired_revision", required = true) @NotNull @Min(0) Long desiredRevision,
            @JsonProperty(value = "desired_fingerprint", required = true)
            @JsonInclude(JsonInclude.Include.ALWAYS) @Pattern(regexp = "^[a-f0-9]{64}$") String desiredFingerprint,
            @JsonProperty(value = "lifecycle_revision", required = true) @NotNull @Min(0) Integer lifecycleRevision)
            implements ClosedContract { }

    public record CutoverLifecycleHead(
            @JsonProperty(value = "desired_revision", required = true) @NotNull @Min(0) Long desiredRevision,
            @JsonProperty(value = "desired_fingerprint", required = true) @NotNull
            @Pattern(regexp = "^[a-f0-9]{64}$") String desiredFingerprint,
            @JsonProperty(value = "desired_active", required = true) @NotNull Boolean desiredActive)
            implements ClosedContract { }

    public record CutoverNonterminalOperation(
            @JsonProperty(value = "request_id", required = true) @NotNull
            @Pattern(regexp = "^[A-Za-z0-9._~-]{1,255}$") String requestId,
            @JsonProperty(value = "operation_type", required = true) @NotNull @NotBlank String operationType,
            @JsonProperty(value = "status", required = true) @NotNull
            @Pattern(regexp = "PENDING|RUNNING|UNKNOWN") String status,
            @JsonProperty(value = "desired_revision", required = true) Long desiredRevision,
            @JsonProperty(value = "external_item_id", required = true)
            @JsonInclude(JsonInclude.Include.ALWAYS) String externalItemId,
            @JsonProperty(value = "external_call_started", required = true) @NotNull Boolean externalCallStarted)
            implements ClosedContract { }

    public record CutoverProviderObservation(
            @JsonProperty(value = "external_item_id", required = true)
            @JsonInclude(JsonInclude.Include.ALWAYS) String externalItemId,
            @JsonProperty(value = "status", required = true) @NotNull AgentProviderStatus status,
            @JsonProperty(value = "complete", required = true) @NotNull Boolean complete,
            @JsonProperty("observed_at") String observedAt) implements ClosedContract { }

    public record CutoverInspection(
            @JsonProperty(value = "schema_version", required = true) @NotNull Integer schemaVersion,
            @JsonProperty(value = "request_id", required = true) @NotNull
            @Pattern(regexp = "^[A-Za-z0-9._~-]{1,255}$") String requestId,
            @JsonProperty(value = "account_id", required = true) @NotNull @CodePointLength(min = 1, max = 80) String accountId,
            @JsonProperty(value = "listing_key", required = true) @NotNull @CodePointLength(min = 1, max = 128) String listingKey,
            @JsonProperty(value = "expected_external_item_id", required = true)
            @JsonInclude(JsonInclude.Include.ALWAYS) String expectedExternalItemId,
            @JsonProperty(value = "v2_writes_enabled", required = true) @NotNull Boolean v2WritesEnabled,
            @JsonProperty(value = "v2_adoption_enabled", required = true) @NotNull Boolean v2AdoptionEnabled,
            @JsonProperty(value = "managed_item", required = true) @JsonInclude(JsonInclude.Include.ALWAYS) @Valid CutoverManagedItem managedItem,
            @JsonProperty(value = "bindings", required = true) @NotNull List<@NotNull @Valid CutoverBinding> bindings,
            @JsonProperty(value = "lifecycle_head", required = true) @JsonInclude(JsonInclude.Include.ALWAYS) @Valid CutoverLifecycleHead lifecycleHead,
            @JsonProperty(value = "nonterminal_operations", required = true) @NotNull List<@NotNull @Valid CutoverNonterminalOperation> nonterminalOperations,
            @JsonProperty(value = "provider_observation", required = true) @JsonInclude(JsonInclude.Include.ALWAYS) @Valid CutoverProviderObservation providerObservation,
            @JsonProperty(value = "provider_observation_error_code", required = true) @JsonInclude(JsonInclude.Include.ALWAYS) String providerObservationErrorCode,
            @JsonProperty(value = "inspection_digest", required = true) @NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String inspectionDigest)
            implements ClosedContract {
        @AssertTrue @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isExactRequestIdentity() {
            return schemaVersion == null || schemaVersion == 1;
        }
    }

    private static boolean exact(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip());
    }

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
