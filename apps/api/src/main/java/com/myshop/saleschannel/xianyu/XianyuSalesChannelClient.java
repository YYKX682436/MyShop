package com.myshop.saleschannel.xianyu;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountCapabilities;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountsData;
import com.myshop.saleschannel.model.SalesChannelDtos.AddressMetadataResult;
import com.myshop.saleschannel.model.SalesChannelDtos.AddressSearchCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.AddressSearchData;
import com.myshop.saleschannel.model.SalesChannelDtos.CapabilityData;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendData;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendResult;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationError;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingOperationData;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaUploadCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaUploadData;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaType;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.model.SalesChannelDtos.PublishCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ReconcileCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ReconcileData;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSnapshot;
import com.myshop.saleschannel.model.SalesChannelDtos.PlatformAttribute;
import com.myshop.saleschannel.model.SalesChannelDtos.UpdateCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.Specification;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecificationValue;
import com.myshop.saleschannel.model.SalesChannelDtos.SkuRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** HMAC-authenticated MyShop client for the separately deployed Xianyu Agent. */
@Component
public final class XianyuSalesChannelClient implements SalesChannelProviderClient {

    private static final String API_ROOT = "/api/integration/v1";
    private static final int MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_VIDEO_BYTES = 100 * 1024 * 1024;
    private static final Set<String> SENSITIVE_METADATA_KEY_PARTS = Set.of(
            "cookie", "token", "password", "proxy", "credential", "secret",
            "auth");

    private final ObjectMapper objectMapper;
    private final String configuredBaseUrl;
    private final String keyId;
    private final String secret;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;

    @Autowired
    public XianyuSalesChannelClient(
            ObjectMapper objectMapper,
            @Value("${xianyu.agent.base-url:}") String configuredBaseUrl,
            @Value("${xianyu.agent.key-id:}") String keyId,
            @Value("${xianyu.agent.secret:}") String secret,
            @Value("${xianyu.agent.timeout-ms:10000}") long timeoutMillis) {
        this(objectMapper, configuredBaseUrl, keyId, secret, timeoutMillis,
                Clock.systemUTC(), secureNonceSupplier());
    }

    XianyuSalesChannelClient(ObjectMapper objectMapper, String configuredBaseUrl, String keyId,
                             String secret, long timeoutMillis, Clock clock,
                             Supplier<String> nonceSupplier) {
        this.objectMapper = objectMapper;
        this.configuredBaseUrl = configuredBaseUrl;
        this.keyId = keyId;
        this.secret = secret;
        long boundedTimeout = Math.max(250, Math.min(timeoutMillis, 60_000));
        this.requestTimeout = Duration.ofMillis(boundedTimeout);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.clock = clock;
        this.nonceSupplier = nonceSupplier;
    }

    @Override
    public IntegrationEnvelope<AccountsData> listAccounts() {
        return exchange("GET", "/accounts", new byte[0], "application/json",
                responseType(AccountsData.class), RequestSemantics.READ_ONLY, null, null);
    }

    @Override
    public IntegrationEnvelope<CapabilityData> verifyAccount(String accountId) {
        String path = "/accounts/" + pathSegment(accountId) + "/verify";
        return exchange("POST", path, new byte[0], "application/json",
                responseType(CapabilityData.class), RequestSemantics.READ_ONLY, UUID.randomUUID().toString(), null);
    }

    @Override
    public IntegrationEnvelope<MediaUploadData> uploadMedia(MediaUploadCommand command) {
        validateMediaCommand(command);
        String boundary = "----MyShopXianyu" + nonceSupplier.get();
        byte[] body = multipartBody(boundary, command);
        return exchange("POST", "/media", body, "multipart/form-data; boundary=" + boundary,
                responseType(MediaUploadData.class), RequestSemantics.SAFE_REPEATABLE,
                command.requestId(), command.requestId());
    }

    @Override
    public IntegrationEnvelope<ListingOperationData> publish(PublishCommand command) {
        validateOperation(command == null ? null : command.requestId(),
                command == null ? null : command.accountId(),
                command == null ? -1 : command.desiredRevision());
        validateListing(command.listing());
        byte[] body = serialize(new XianyuPublishRequest(command.requestId(), command.accountId(),
                command.desiredRevision(), command.listing()));
        return exchange("POST", "/listings/publish", body, "application/json",
                responseType(ListingOperationData.class), RequestSemantics.SIDE_EFFECTING,
                command.requestId(), command.requestId());
    }

    @Override
    public IntegrationEnvelope<ListingOperationData> update(String itemId, UpdateCommand command) {
        validateOperation(command == null ? null : command.requestId(),
                command == null ? null : command.accountId(),
                command == null ? -1 : command.desiredRevision());
        validateListing(command.listing());
        byte[] body = serialize(new XianyuPublishRequest(command.requestId(), command.accountId(),
                command.desiredRevision(), command.listing()));
        return exchange("PUT", "/listings/" + pathSegment(itemId), body, "application/json",
                responseType(ListingOperationData.class), RequestSemantics.SIDE_EFFECTING,
                command.requestId(), command.requestId());
    }

    @Override
    public IntegrationEnvelope<ListingOperationData> offline(String itemId, UpdateCommand command) {
        validateOperation(command == null ? null : command.requestId(),
                command == null ? null : command.accountId(),
                command == null ? -1 : command.desiredRevision());
        byte[] body = serialize(new OperationRequest(command.requestId(), command.accountId(),
                command.desiredRevision()));
        return exchange("POST", "/listings/" + pathSegment(itemId) + "/offline", body,
                "application/json", responseType(ListingOperationData.class), RequestSemantics.SIDE_EFFECTING,
                command.requestId(), command.requestId());
    }

    @Override
    public IntegrationEnvelope<ListingOperationData> delete(String itemId, UpdateCommand command) {
        validateOperation(command == null ? null : command.requestId(),
                command == null ? null : command.accountId(),
                command == null ? -1 : command.desiredRevision());
        byte[] body = serialize(new OperationRequest(command.requestId(), command.accountId(),
                command.desiredRevision()));
        return exchange("DELETE", "/listings/" + pathSegment(itemId), body, "application/json",
                responseType(ListingOperationData.class), RequestSemantics.SIDE_EFFECTING,
                command.requestId(), command.requestId());
    }

    @Override
    public IntegrationEnvelope<ReconcileData> reconcile(ReconcileCommand command) {
        validateOperation(command == null ? null : command.requestId(),
                command == null ? null : command.accountId(),
                command == null ? -1 : command.desiredRevision());
        if (command.targetRequestId() == null || command.targetRequestId().isBlank()) {
            throw safeFailure("INVALID_REQUEST", "target_request_id is required");
        }
        byte[] body = serialize(new ReconcileRequest(command.requestId(), command.accountId(),
                command.targetRequestId(), blankToNull(command.expectedItemId()), command.desiredRevision()));
        return exchange("POST", "/listings/reconcile", body, "application/json",
                responseType(ReconcileData.class), RequestSemantics.READ_ONLY,
                command.requestId(), command.requestId());
    }

    @Override
    public AddressMetadataResult searchAddress(String keywords, String city) {
        if (blank(keywords) || keywords.length() > 100 || (city != null && city.length() > 100)) {
            throw safeFailure("INVALID_METADATA_REQUEST", "Address search keywords or city are invalid");
        }
        AddressSearchCommand command = new AddressSearchCommand(UUID.randomUUID().toString(),
                keywords.trim(), blank(city) ? "全国" : city.trim());
        byte[] body = serialize(new XianyuAddressSearchRequest(command.requestId(),
                command.keywords(), command.city()));
        IntegrationEnvelope<AddressSearchData> response = exchange("POST", "/metadata/address/search", body,
                "application/json", responseType(AddressSearchData.class), RequestSemantics.READ_ONLY,
                command.requestId(), command.requestId());
        if (response.outcome() != Outcome.SUCCEEDED || response.data() == null) {
            throw metadataFailure(response.error());
        }
        return new AddressMetadataResult(response.requestId(), response.data().keywords(),
                response.data().tips() == null ? List.of() : response.data().tips());
    }

    @Override
    public CategoryRecommendResult recommendCategory(CategoryRecommendCommand command) {
        if (command == null || blank(command.accountId()) || blank(command.title())
                || command.title().length() > 200 || (command.description() != null
                && command.description().length() > 1500) || command.cardList() == null
                || command.cardList().size() > 100 || command.accountId().length() > 128) {
            throw safeFailure("INVALID_METADATA_REQUEST", "Category recommendation fields are invalid");
        }
        String requestId = blank(command.requestId()) ? UUID.randomUUID().toString() : command.requestId();
        if (requestId.length() > 255) {
            throw safeFailure("INVALID_METADATA_REQUEST", "Category recommendation request id is too long");
        }
        byte[] body = serialize(new XianyuCategoryRecommendRequest(requestId, command.accountId(),
                command.title(), command.description(), command.cardList(), command.selectedCategory()));
        IntegrationEnvelope<CategoryRecommendData> response = exchange("POST", "/metadata/category/recommend", body,
                "application/json", responseType(CategoryRecommendData.class), RequestSemantics.READ_ONLY,
                requestId, requestId);
        if (response.outcome() != Outcome.SUCCEEDED || response.data() == null) {
            throw metadataFailure(response.error());
        }
        CategoryRecommendData data = response.data();
        return new CategoryRecommendResult(response.requestId(), sanitizeMetadata(data.candidates()),
                sanitizeMetadata(data.properties()), sanitizeMetadata(data.cardList()));
    }

    private SalesChannelProviderClientException metadataFailure(IntegrationError error) {
        if (error != null && Boolean.TRUE.equals(error.retryable())) {
            return retryableFailure(error.code() == null ? "AGENT_METADATA_FAILED" : error.code(),
                    "Xianyu metadata request failed and can be retried safely");
        }
        return permanentFailure(error == null || error.code() == null ? "AGENT_METADATA_FAILED" : error.code(),
                "Xianyu metadata request failed");
    }

    private static List<Map<String, Object>> sanitizeMetadata(List<Map<String, Object>> values) {
        if (values == null) return List.of();
        List<Map<String, Object>> sanitized = new ArrayList<>(values.size());
        for (Map<String, Object> value : values) {
            Object result = sanitizeMetadataValue(value);
            if (result instanceof Map<?, ?> map) {
                Map<String, Object> typed = new LinkedHashMap<>();
                map.forEach((key, item) -> typed.put(String.valueOf(key), item));
                sanitized.add(Collections.unmodifiableMap(typed));
            }
        }
        return List.copyOf(sanitized);
    }

    private static Object sanitizeMetadataValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String normalizedKey = String.valueOf(key).toLowerCase(Locale.ROOT);
                boolean sensitive = normalizedKey.equals("auth")
                        || SENSITIVE_METADATA_KEY_PARTS.stream().anyMatch(normalizedKey::contains);
                if (!sensitive) sanitized.put(String.valueOf(key), sanitizeMetadataValue(item));
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(XianyuSalesChannelClient::sanitizeMetadataValue).toList();
        }
        return value;
    }

    private <T> IntegrationEnvelope<T> exchange(String method, String endpoint, byte[] rawBody,
                                                 String contentType, JavaType responseType,
                                                 RequestSemantics semantics, String correlationId,
                                                 String expectedRequestId) {
        URI uri = configuredUri(endpoint);
        String actualCorrelationId = correlationId == null ? UUID.randomUUID().toString() : correlationId;
        final String timestamp;
        final String nonce;
        final String signature;
        final HttpRequest request;
        try {
            timestamp = Long.toString(clock.instant().getEpochSecond());
            nonce = nonceSupplier.get();
            String canonical = XianyuRequestSigner.canonicalString(
                    method, uri.getRawPath(), timestamp, nonce, rawBody);
            signature = XianyuRequestSigner.signature(secret, canonical);
            HttpRequest.BodyPublisher publisher = method.equals("GET")
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(rawBody);
            request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", contentType)
                    .header("X-MyShop-Key-Id", keyId)
                    .header("X-MyShop-Timestamp", timestamp)
                    .header("X-MyShop-Nonce", nonce)
                    .header("X-MyShop-Signature", signature)
                    .method(method, publisher)
                    .build();
        } catch (RuntimeException e) {
            throw permanentFailure("REQUEST_BUILD_FAILED", "Unable to construct Xianyu Agent request");
        }

        final HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (semantics == RequestSemantics.SIDE_EFFECTING) {
                return unknown(actualCorrelationId, "AGENT_OUTCOME_UNKNOWN");
            }
            throw retryableFailure("AGENT_REQUEST_INTERRUPTED", "Xianyu Agent request was interrupted");
        } catch (IOException | RuntimeException e) {
            if (definitelyBeforeConnection(e)) {
                throw retryableFailure("AGENT_UNREACHABLE", "Xianyu Agent connection could not be established");
            }
            if (semantics == RequestSemantics.SIDE_EFFECTING) {
                return unknown(actualCorrelationId, "AGENT_OUTCOME_UNKNOWN");
            }
            throw retryableFailure("AGENT_UNAVAILABLE", "Xianyu Agent request failed before a usable response");
        }

        byte[] responseBody = response.body() == null ? new byte[0] : response.body();
        if (responseBody.length > MAX_JSON_RESPONSE_BYTES) {
            if (semantics == RequestSemantics.SIDE_EFFECTING) {
                return unknown(actualCorrelationId, "AGENT_RESPONSE_TOO_LARGE");
            }
            throw retryableFailure("AGENT_RESPONSE_TOO_LARGE", "Xianyu Agent response exceeded the size limit");
        }

        final IntegrationEnvelope<T> envelope;
        try {
            envelope = objectMapper.readValue(responseBody, responseType);
        } catch (IOException | RuntimeException e) {
            if (semantics == RequestSemantics.SIDE_EFFECTING) {
                return unknown(actualCorrelationId, "AGENT_RESPONSE_UNREADABLE");
            }
            throw retryableFailure("AGENT_RESPONSE_UNREADABLE", "Xianyu Agent returned an unreadable response");
        }
        if (envelope == null || envelope.outcome() == null) {
            if (semantics == RequestSemantics.SIDE_EFFECTING) {
                return unknown(actualCorrelationId, "AGENT_RESPONSE_INVALID");
            }
            throw retryableFailure("AGENT_RESPONSE_INVALID", "Xianyu Agent returned an invalid response envelope");
        }
        if (semantics == RequestSemantics.SIDE_EFFECTING && response.statusCode() >= 500) {
            return unknown(actualCorrelationId, "AGENT_HTTP_OUTCOME_UNKNOWN");
        }
        if (response.statusCode() >= 400 && response.statusCode() < 500
                && envelope.outcome() == Outcome.FAILED) {
            // Agent authentication/validation failures happen before an operation log is reserved,
            // so their null request_id is still a definite safe failure for this local request.
            IntegrationError error = envelope.error() == null
                    ? new IntegrationError("AGENT_REQUEST_REJECTED", "Xianyu Agent rejected the request")
                    : envelope.error();
            IntegrationEnvelope<T> correlatedFailure = new IntegrationEnvelope<>(
                    expectedRequestId == null ? envelope.requestId() : expectedRequestId,
                    Outcome.FAILED, null, error);
            return redactSensitiveResponse(correlatedFailure, signature);
        }
        if (expectedRequestId != null && !expectedRequestId.equals(envelope.requestId())) {
            if (semantics == RequestSemantics.SIDE_EFFECTING) {
                return unknown(actualCorrelationId, "AGENT_REQUEST_ID_MISMATCH");
            }
            throw permanentFailure("AGENT_REQUEST_ID_MISMATCH", "Xianyu Agent response request_id did not match");
        }
        if (envelope.outcome() == Outcome.SUCCEEDED && (response.statusCode() < 200 || response.statusCode() >= 300)) {
            if (semantics == RequestSemantics.SIDE_EFFECTING) {
                return unknown(actualCorrelationId, "AGENT_HTTP_OUTCOME_MISMATCH");
            }
            throw retryableFailure("AGENT_HTTP_OUTCOME_MISMATCH", "Xianyu Agent HTTP status conflicts with its outcome");
        }
        return redactSensitiveResponse(envelope, signature);
    }

    private URI configuredUri(String endpoint) {
        if (keyId == null || keyId.isBlank() || secret == null || secret.isBlank()) {
            throw safeFailure("AGENT_CONFIGURATION_MISSING", "Xianyu Agent credentials are not configured");
        }
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            throw safeFailure("AGENT_CONFIGURATION_MISSING", "Xianyu Agent base URL is not configured");
        }
        try {
            URI base = URI.create(configuredBaseUrl.trim());
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("invalid agent base URL");
            }
            String prefix = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
            return URI.create(base.getScheme() + "://" + base.getRawAuthority() + prefix + API_ROOT + endpoint);
        } catch (RuntimeException e) {
            throw permanentFailure("AGENT_CONFIGURATION_INVALID", "Xianyu Agent base URL is invalid");
        }
    }

    private <T> IntegrationEnvelope<T> redactSensitiveResponse(IntegrationEnvelope<T> envelope, String signature) {
        IntegrationError error = envelope.error();
        if (error == null) return envelope;
        String code = redact(error.code(), signature);
        String message = redact(error.message(), signature);
        if (java.util.Objects.equals(code, error.code()) && java.util.Objects.equals(message, error.message())) {
            return envelope;
        }
        return new IntegrationEnvelope<>(envelope.requestId(), envelope.outcome(), envelope.data(),
                new IntegrationError(code, message, error.retryable()));
    }

    private String redact(String value, String signature) {
        if (value == null) return null;
        String result = value;
        if (secret != null && !secret.isBlank()) result = result.replace(secret, "[redacted]");
        if (signature != null && !signature.isBlank()) result = result.replace(signature, "[redacted]");
        return result;
    }

    private static <T> IntegrationEnvelope<T> unknown(String requestId, String code) {
        return new IntegrationEnvelope<>(requestId, Outcome.UNKNOWN, null,
                new IntegrationError(code, "The Agent may have processed the request; reconcile before retrying", false));
    }

    private byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (IOException | RuntimeException e) {
            throw permanentFailure("REQUEST_SERIALIZATION_FAILED", "Unable to serialize Xianyu Agent request");
        }
    }

    private JavaType responseType(Class<?> dataClass) {
        return objectMapper.getTypeFactory().constructParametricType(IntegrationEnvelope.class, dataClass);
    }

    private static void validateOperation(String requestId, String accountId, long desiredRevision) {
        if (requestId == null || requestId.isBlank()) {
            throw safeFailure("INVALID_REQUEST", "request_id is required");
        }
        if (requestId.length() > 255) throw safeFailure("INVALID_REQUEST", "request_id is too long");
        if (accountId == null || accountId.isBlank()) {
            throw safeFailure("INVALID_REQUEST", "account_id is required");
        }
        if (desiredRevision < 0) throw safeFailure("INVALID_REQUEST", "desired_revision cannot be negative");
    }

    private static void validateListing(ListingSnapshot listing) {
        if (listing == null || blank(listing.title()) || listing.title().length() > 200
                || blank(listing.description()) || listing.description().length() > 1500
                || listing.price() == null || listing.price().signum() <= 0
                || blank(listing.address()) || listing.address().length() > 200) {
            throw safeFailure("INVALID_LISTING", "Listing title, description, price, and address are required");
        }
        if (listing.imageMediaIds() == null || listing.imageMediaIds().isEmpty()
                || listing.imageMediaIds().size() > 9) {
            throw safeFailure("INVALID_LISTING", "One to nine image_media_ids are required");
        }
        if (listing.videoMediaIds() != null && listing.videoMediaIds().size() > 3) {
            throw safeFailure("INVALID_LISTING", "At most three video_media_ids are allowed");
        }
        if (listing.originalPrice() != null && listing.originalPrice().signum() <= 0) {
            throw safeFailure("INVALID_LISTING", "original_price must be greater than zero");
        }
        if (listing.quantity() != null && (listing.quantity() < 0 || listing.quantity() > 999_999)) {
            throw safeFailure("INVALID_LISTING", "quantity is out of range");
        }
        if (listing.platformAttributes() != null && listing.platformAttributes().size() > 30) {
            throw safeFailure("INVALID_LISTING", "At most thirty platform_attributes are allowed");
        }
        if (listing.specifications() != null && listing.specifications().size() > 2) {
            throw safeFailure("INVALID_LISTING", "At most two specifications are allowed");
        }
        if (listing.skuRows() != null && listing.skuRows().size() > 200) {
            throw safeFailure("INVALID_LISTING", "At most two hundred sku_rows are allowed");
        }
        if (listing.deliveryMethod() != null && !List.of("express", "pickup").contains(listing.deliveryMethod())) {
            throw safeFailure("INVALID_LISTING", "delivery_method is invalid");
        }
        if (listing.shippingMethod() != null
                && !List.of("free", "distance", "fixed", "template", "none").contains(listing.shippingMethod())) {
            throw safeFailure("INVALID_LISTING", "shipping_method is invalid");
        }
    }

    private static void validateMediaCommand(MediaUploadCommand command) {
        if (command == null || blank(command.requestId()) || command.requestId().length() > 255
                || blank(command.accountId()) || command.mediaType() == null
                || command.accountId().length() > 128 || hasHeaderControls(command.accountId())
                || blank(command.filename()) || hasHeaderControls(command.filename()) || blank(command.contentType())
                || !command.contentType().matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")
                || command.content() == null || command.content().length == 0
                || command.content().length > (command.mediaType() == MediaType.VIDEO
                        ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES)) {
            throw safeFailure("INVALID_MEDIA", "Media upload fields or file size are invalid");
        }
    }

    private static byte[] multipartBody(String boundary, MediaUploadCommand command) {
        byte[] file = command.content();
        String filename = command.filename().replace("\\", "/");
        filename = filename.substring(filename.lastIndexOf('/') + 1).replace("\"", "_");
        String start = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"account_id\"\r\n\r\n" + command.accountId() + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"media_type\"\r\n\r\n"
                + command.mediaType().wireValue() + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"request_id\"\r\n\r\n"
                + command.requestId() + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + command.contentType() + "\r\n\r\n";
        byte[] prefix = start.getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + file.length + suffix.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(file, 0, result, prefix.length, file.length);
        System.arraycopy(suffix, 0, result, prefix.length + file.length, suffix.length);
        return result;
    }

    private static String pathSegment(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw permanentFailure("INVALID_REQUEST", "Path identifier is required");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean hasHeaderControls(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }

    private static boolean definitelyBeforeConnection(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof HttpConnectTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof UnresolvedAddressException) return true;
        }
        return false;
    }

    private static SalesChannelProviderClientException safeFailure(String code, String message) {
        return permanentFailure(code, message);
    }

    private static SalesChannelProviderClientException retryableFailure(String code, String message) {
        return new SalesChannelProviderClientException(
                SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE, code, message);
    }

    private static SalesChannelProviderClientException permanentFailure(String code, String message) {
        return new SalesChannelProviderClientException(
                SalesChannelProviderClientException.Disposition.SAFE_PERMANENT, code, message);
    }

    private enum RequestSemantics { READ_ONLY, SAFE_REPEATABLE, SIDE_EFFECTING }

    private static Supplier<String> secureNonceSupplier() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            return HexFormat.of().formatHex(nonce);
        };
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record XianyuPublishRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("desired_revision") long desiredRevision,
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("price") java.math.BigDecimal price,
            @JsonProperty("original_price") java.math.BigDecimal originalPrice,
            @JsonProperty("image_media_ids") List<String> imageMediaIds,
            @JsonProperty("video_media_ids") List<String> videoMediaIds,
            @JsonProperty("platform_category_id") String platformCategoryId,
            @JsonProperty("platform_category_name") String platformCategoryName,
            @JsonProperty("platform_channel_category_id") String platformChannelCategoryId,
            @JsonProperty("platform_channel_category_name") String platformChannelCategoryName,
            @JsonProperty("platform_leaf_id") String platformLeafId,
            @JsonProperty("platform_tb_category_id") String platformTbCategoryId,
            @JsonProperty("platform_attributes") List<XianyuPlatformAttribute> platformAttributes,
            @JsonProperty("quantity") Integer quantity,
            @JsonProperty("specifications") List<XianyuSpecification> specifications,
            @JsonProperty("sku_rows") List<XianyuSkuRow> skuRows,
            @JsonProperty("address") String address,
            @JsonProperty("address_expected_text") String addressExpectedText,
            @JsonProperty("delivery_method") String deliveryMethod,
            @JsonProperty("shipping_method") String shippingMethod,
            @JsonProperty("support_pickup") Boolean supportPickup,
            @JsonProperty("postage") java.math.BigDecimal postage) {
        XianyuPublishRequest(String requestId, String accountId, long desiredRevision, ListingSnapshot listing) {
            this(requestId, accountId, desiredRevision, listing.title(), listing.description(), listing.price(),
                    listing.originalPrice(), listing.imageMediaIds(), listing.videoMediaIds(),
                    listing.platformCategoryId(), listing.platformCategoryName(),
                    listing.platformChannelCategoryId(), listing.platformChannelCategoryName(),
                    listing.platformLeafId(), listing.platformTbCategoryId(),
                    mapAttributes(listing.platformAttributes()),
                    listing.quantity() == null ? 1 : listing.quantity(), mapSpecifications(listing.specifications()),
                    mapSkuRows(listing.skuRows()), listing.address(), listing.addressExpectedText(),
                    listing.deliveryMethod() == null ? "express" : listing.deliveryMethod(),
                    listing.shippingMethod() == null ? "free" : listing.shippingMethod(),
                    listing.supportPickup() == null ? false : listing.supportPickup(), listing.postage());
        }

        private static List<XianyuPlatformAttribute> mapAttributes(List<PlatformAttribute> values) {
            return values == null ? List.of() : values.stream().map(value -> new XianyuPlatformAttribute(
                    value.propertyId(), value.propertyName(), value.valueId(), value.valueName(), value.text(),
                    value.properties())).toList();
        }

        private static List<XianyuSpecification> mapSpecifications(List<Specification> values) {
            return values == null ? List.of() : values.stream().map(value -> new XianyuSpecification(
                    value.name(), value.values() == null ? List.of() : value.values().stream()
                    .map(item -> new XianyuSpecificationValue(item.name(), item.imageMediaId())).toList(),
                    value.supportImage() == null ? false : value.supportImage())).toList();
        }

        private static List<XianyuSkuRow> mapSkuRows(List<SkuRow> values) {
            return values == null ? List.of() : values.stream().map(value -> new XianyuSkuRow(value.specs(),
                    value.price(), value.stock() == null ? 0 : value.stock())).toList();
        }
    }

    private record XianyuPlatformAttribute(
            @JsonProperty("property_id") String propertyId,
            @JsonProperty("property_name") String propertyName,
            @JsonProperty("value_id") String valueId,
            @JsonProperty("value_name") String valueName,
            @JsonProperty("text") String text,
            @JsonProperty("properties") java.util.Map<String, Object> properties) { }

    private record XianyuSpecification(
            @JsonProperty("name") String name,
            @JsonProperty("values") List<XianyuSpecificationValue> values,
            @JsonProperty("support_image") boolean supportImage) { }

    private record XianyuSpecificationValue(
            @JsonProperty("name") String name,
            @JsonProperty("image_media_id") String imageMediaId) { }

    private record XianyuSkuRow(
            @JsonProperty("specs") java.util.Map<String, String> specs,
            @JsonProperty("price") java.math.BigDecimal price,
            @JsonProperty("stock") int stock) { }

    private record OperationRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("desired_revision") long desiredRevision) { }

    private record XianyuAddressSearchRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("keywords") String keywords,
            @JsonProperty("city") String city) { }

    private record XianyuCategoryRecommendRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("card_list") List<java.util.Map<String, Object>> cardList,
            @JsonProperty("selected_category") java.util.Map<String, Object> selectedCategory) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ReconcileRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("target_request_id") String targetRequestId,
            @JsonProperty("expected_item_id") String expectedItemId,
            @JsonProperty("desired_revision") long desiredRevision) { }
}
