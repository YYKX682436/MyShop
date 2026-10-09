package com.myshop.saleschannel.xianyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountCapabilities;
import com.myshop.saleschannel.model.SalesChannelDtos.AccountsData;
import com.myshop.saleschannel.model.SalesChannelDtos.CategoryRecommendCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.CapabilityData;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingOperationData;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSpecMappingsData;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryData;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsSyncCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsSyncData;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSnapshot;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaUploadData;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaType;
import com.myshop.saleschannel.model.SalesChannelDtos.MediaUploadCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.model.SalesChannelDtos.OwnershipClaimData;
import com.myshop.saleschannel.model.SalesChannelDtos.PlatformAttribute;
import com.myshop.saleschannel.model.SalesChannelDtos.PublishCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStartCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginReauthenticationStartCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStatusCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.QrLoginVerificationCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ReconcileCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.ReconcileData;
import com.myshop.saleschannel.model.SalesChannelDtos.SalesChannelAccount;
import com.myshop.saleschannel.model.SalesChannelDtos.SkuRow;
import com.myshop.saleschannel.model.SalesChannelDtos.Specification;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecificationValue;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingsCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.UpdateCommand;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class XianyuSalesChannelClientTest {

    private static final String SECRET = "unit-test-only-secret";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalStringUsesMethodPathUnixTimestampNonceAndRawBodySha256() throws Exception {
        byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        String canonical = XianyuRequestSigner.canonicalString(
                "post", "/api/integration/v1/listings/publish", "1700000000", "nonce-123", body);

        assertEquals("POST\n/api/integration/v1/listings/publish\n1700000000\nnonce-123\n"
                        + XianyuRequestSigner.sha256Hex(body), canonical);
        assertEquals(independentHmac(SECRET, canonical), XianyuRequestSigner.signature(SECRET, canonical));
        assertNotEquals(XianyuRequestSigner.signature(SECRET, canonical),
                XianyuRequestSigner.signature(SECRET,
                        XianyuRequestSigner.canonicalString("POST", "/api/integration/v1/listings/publish",
                                "1700000000", "nonce-123", "{\"x\":2}".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void qrLoginUsesTheExistingSignedIntegrationClientAndMapsVerificationStatus() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.SUCCESS)) {
            XianyuSalesChannelClient client = client(fixture.baseUrl());
            String loginIntentId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            String loginSessionId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
            String operator = "codex-admin";
            String startRequestId = UUID.randomUUID().toString();
            var started = client.startQrLogin(new QrLoginStartCommand(startRequestId, loginIntentId, operator));
            assertEquals(Outcome.SUCCEEDED, started.outcome());
            assertEquals(loginIntentId, started.data().loginIntentId());
            assertEquals(loginSessionId, started.data().loginSessionId());
            assertEquals("data:image/png;base64,AA==", started.data().qrCodeDataUrl());

            String statusRequestId = UUID.randomUUID().toString();
            var status = client.getQrLoginStatus(
                    new QrLoginStatusCommand(statusRequestId, loginSessionId, loginIntentId, operator));
            assertEquals(Outcome.SUCCEEDED, status.outcome());
            assertEquals("VERIFICATION_REQUIRED", status.data().status());
            assertEquals(loginIntentId, status.data().loginIntentId());
            assertEquals(loginSessionId, status.data().loginSessionId());
            assertEquals("闲鱼要求进行安全验证，请按手机端提示完成验证。",
                    status.data().verificationMessage());
            assertEquals("data:image/png;base64,iVBORw0KGgo=",
                    status.data().verificationQrCodeDataUrl());

            String reauthRequestId = UUID.randomUUID().toString();
            var reauthStarted = client.startQrReauthentication(new QrLoginReauthenticationStartCommand(
                    reauthRequestId, loginIntentId, "2215747465381", operator));
            assertEquals(loginIntentId, reauthStarted.data().loginIntentId());

            String verificationRequestId = UUID.randomUUID().toString();
            var completed = client.completeQrLoginVerification(new QrLoginVerificationCommand(
                    verificationRequestId, loginSessionId, loginIntentId, operator, true, null));
            assertEquals("PROVIDER_AUTH_VERIFIED", completed.data().status());

            List<CapturedRequest> requests = fixture.requests();
            assertEquals(List.of(
                    "POST /api/integration/v1/accounts/login/qr/start",
                    "POST /api/integration/v1/accounts/login/qr/status",
                    "POST /api/integration/v1/accounts/login/qr/reauthenticate/start",
                    "POST /api/integration/v1/accounts/login/qr/verification"),
                    requests.stream().map(r -> r.method() + " " + r.path()).toList());
            JsonNode startBody = objectMapper.readTree(requests.get(0).body());
            assertEquals(startRequestId, startBody.path("request_id").asText());
            assertEquals(loginIntentId, startBody.path("login_intent_id").asText());
            assertEquals(operator, startBody.path("operator_identity").asText());
            JsonNode statusBody = objectMapper.readTree(requests.get(1).body());
            assertEquals(statusRequestId, statusBody.path("request_id").asText());
            assertEquals(loginSessionId, statusBody.path("login_session_id").asText());
            assertEquals(loginIntentId, statusBody.path("login_intent_id").asText());
            JsonNode reauthBody = objectMapper.readTree(requests.get(2).body());
            assertEquals("2215747465381", reauthBody.path("target_account_id").asText());
            JsonNode verificationBody = objectMapper.readTree(requests.get(3).body());
            assertEquals(true, verificationBody.path("verified").asBoolean());
            assertEquals(loginIntentId, verificationBody.path("login_intent_id").asText());
            for (CapturedRequest request : requests) {
                String canonical = XianyuRequestSigner.canonicalString(request.method(), request.path(),
                        request.header("X-MyShop-Timestamp"), request.header("X-MyShop-Nonce"), request.body());
                assertEquals(independentHmac(SECRET, canonical), request.header("X-MyShop-Signature"));
            }
        }
    }

    @Test
    void deterministicMediaRequestIdsStayWithinDatabaseLimitForLongAccountIds() {
        String accountId = "acct-" + "x".repeat(500);
        byte[] bytes = {1, 2, 3};

        for (MediaType mediaType : MediaType.values()) {
            MediaUploadCommand command = new MediaUploadCommand(accountId, mediaType,
                    "same-name.jpg", "image/jpeg", bytes);
            MediaUploadCommand replay = new MediaUploadCommand(accountId, mediaType,
                    "renamed.jpg", "image/jpeg", bytes);

            assertTrue(command.requestId().length() <= 255);
            assertEquals(command.requestId(), replay.requestId());
        }

        assertNotEquals(new MediaUploadCommand(accountId, MediaType.IMAGE, "x", "image/jpeg", bytes).requestId(),
                new MediaUploadCommand(accountId + "-different", MediaType.IMAGE,
                        "x", "image/jpeg", bytes).requestId());
        assertNotEquals(new MediaUploadCommand(accountId, MediaType.IMAGE, "x", "image/jpeg", bytes).requestId(),
                new MediaUploadCommand(accountId, MediaType.VIDEO, "x", "image/jpeg", bytes).requestId());
    }

    @Test
    void signsRequestsAndUsesTypedSnakeCaseContractForEveryEndpoint() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.SUCCESS)) {
            XianyuSalesChannelClient client = client(fixture.baseUrl());

            IntegrationEnvelope<AccountsData> accounts = client.listAccounts();
            assertEquals(Outcome.SUCCEEDED, accounts.outcome());
            assertEquals("acct-1", accounts.data().accounts().getFirst().providerAccountId());

            IntegrationEnvelope<CapabilityData> verified = client.verifyAccount("acct-1");
            assertEquals("FISH_SHOP", verified.data().capabilities().accountType());
            MediaUploadCommand mediaCommand = new MediaUploadCommand("acct-1", MediaType.IMAGE, "front.jpg",
                    "image/jpeg", new byte[]{1, 2, 3});
            IntegrationEnvelope<MediaUploadData> uploaded = client.uploadMedia(mediaCommand);
            assertEquals("media-1", uploaded.data().mediaId());
            assertTrue(mediaCommand.requestId().startsWith("XIANYU_MEDIA:acct-1:image:"));
            assertEquals(mediaCommand.requestId(), new MediaUploadCommand("acct-1", MediaType.IMAGE,
                    "renamed.jpg", "image/jpeg", new byte[]{1, 2, 3}).requestId());
            assertTrue(mediaCommand.requestId().length() <= 255);

            ListingSnapshot listing = listing();
            IntegrationEnvelope<ListingOperationData> published = client.publish(
                    new PublishCommand("op-publish", "acct-1", 11, listing));
            assertEquals(Outcome.SUCCEEDED, published.outcome());
            assertEquals("item-123", published.data().itemId());
            client.update("item/123", new UpdateCommand("op-edit", "acct-1", 12, listing));
            client.offline("item-123", new UpdateCommand("op-offline", "acct-1", 13, null));
            client.delete("item-123", new UpdateCommand("op-delete", "acct-1", 14, null));
            IntegrationEnvelope<ReconcileData> reconciled = client.reconcile(
                    new ReconcileCommand("op-reconcile", "acct-1", "target-operation", null, 15));
            assertEquals("target-operation", reconciled.data().targetRequestId());

            List<CapturedRequest> requests = fixture.requests();
            assertEquals(List.of("GET /api/integration/v1/accounts",
                            "POST /api/integration/v1/accounts/acct-1/verify",
                            "POST /api/integration/v1/media",
                            "POST /api/integration/v1/listings/publish",
                            "PUT /api/integration/v1/listings/item%2F123",
                            "POST /api/integration/v1/listings/item-123/offline",
                            "DELETE /api/integration/v1/listings/item-123",
                            "POST /api/integration/v1/listings/reconcile"),
                    requests.stream().map(r -> r.method() + " " + r.path()).toList());

            for (CapturedRequest request : requests) {
                assertEquals("test-key", request.header("X-MyShop-Key-Id"));
                assertEquals("1800000000", request.header("X-MyShop-Timestamp"));
                assertNotNull(request.header("X-MyShop-Nonce"));
                assertTrue(request.header("X-MyShop-Signature").matches("[0-9a-f]{64}"));
                String canonical = XianyuRequestSigner.canonicalString(request.method(), request.path(),
                        request.header("X-MyShop-Timestamp"), request.header("X-MyShop-Nonce"), request.body());
                assertEquals(independentHmac(SECRET, canonical), request.header("X-MyShop-Signature"));
            }

            JsonNode publish = objectMapper.readTree(requests.get(3).body());
            assertEquals("op-publish", publish.path("request_id").asText());
            assertEquals("acct-1", publish.path("account_id").asText());
            assertEquals(11, publish.path("desired_revision").asLong());
            assertEquals("A carefully prepared listing", publish.path("title").asText());
            assertTrue(publish.has("platform_attributes"));
            assertTrue(publish.has("sku_rows"));
            assertFalse(publish.has("secret_key"));
            assertFalse(new String(requests.get(3).body(), StandardCharsets.UTF_8).contains(SECRET));

            JsonNode offline = objectMapper.readTree(requests.get(5).body());
            assertEquals("op-offline", offline.path("request_id").asText());
            assertEquals(13, offline.path("desired_revision").asLong());
            JsonNode remove = objectMapper.readTree(requests.get(6).body());
            assertEquals("op-delete", remove.path("request_id").asText());
            JsonNode reconcile = objectMapper.readTree(requests.get(7).body());
            assertEquals("target-operation", reconcile.path("target_request_id").asText());
            assertTrue(reconcile.path("expected_item_id").isMissingNode());

            String multipart = new String(requests.get(2).body(), StandardCharsets.ISO_8859_1);
            assertTrue(multipart.contains("name=\"account_id\""));
            assertTrue(multipart.contains("name=\"media_type\""));
            assertTrue(multipart.contains("name=\"request_id\""));
            assertTrue(multipart.contains("XIANYU_MEDIA:acct-1:image:"));
            assertTrue(multipart.contains("name=\"file\"; filename=\"front.jpg\""));
            assertTrue(multipart.contains("image/jpeg"));
        }
    }

    @Test
    void metadataEndpointsReuseHmacSignerAndMapAddressAndCategoryData() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.SUCCESS)) {
            XianyuSalesChannelClient client = client(fixture.baseUrl());
            var address = client.searchAddress("上海市徐汇区", "全国");
            assertEquals("上海市徐汇区", address.keywords());
            assertEquals("徐家汇", address.tips().getFirst().name());
            assertEquals("上海市徐汇区虹桥路1号", address.tips().getFirst().expectedText());
            var category = client.recommendCategory(new CategoryRecommendCommand("category-request", "acct-1",
                    "软件服务", "正版授权", List.of(), null));
            assertEquals("category-request", category.requestId());
            assertEquals("cat-real-1", category.candidates().getFirst().get("cat_id"));
            assertEquals("property-1", category.properties().getFirst().get("property_id"));
            String metadataJson = objectMapper.writeValueAsString(category);
            assertFalse(metadataJson.toLowerCase().contains("cookie"));
            assertFalse(metadataJson.toLowerCase().contains("token"));
            assertFalse(metadataJson.toLowerCase().contains("password"));
            assertFalse(metadataJson.toLowerCase().contains("proxy"));
            client.recommendCategory(new CategoryRecommendCommand("category-select", "acct-1",
                    "软件服务", "正版授权", List.of(Map.of("seed", "service-card")),
                    Map.of("cat_id", "cat-real-1", "cat_name", "软件服务")));

            List<CapturedRequest> requests = fixture.requests();
            assertEquals(List.of("POST /api/integration/v1/metadata/address/search",
                            "POST /api/integration/v1/metadata/category/recommend",
                            "POST /api/integration/v1/metadata/category/recommend"),
                    requests.stream().map(r -> r.method() + " " + r.path()).toList());
            for (CapturedRequest request : requests) {
                String canonical = XianyuRequestSigner.canonicalString(request.method(), request.path(),
                        request.header("X-MyShop-Timestamp"), request.header("X-MyShop-Nonce"), request.body());
                assertEquals(independentHmac(SECRET, canonical), request.header("X-MyShop-Signature"));
            }
            JsonNode addressRequest = objectMapper.readTree(requests.get(0).body());
            assertEquals("上海市徐汇区", addressRequest.path("keywords").asText());
            assertEquals("全国", addressRequest.path("city").asText());
            JsonNode categoryRequest = objectMapper.readTree(requests.get(1).body());
            assertEquals("acct-1", categoryRequest.path("account_id").asText());
            assertTrue(categoryRequest.path("card_list").isArray());
            assertTrue(categoryRequest.path("selected_category").isNull());
            JsonNode selectedRequest = objectMapper.readTree(requests.get(2).body());
            assertEquals("service-card", selectedRequest.path("card_list").get(0).path("seed").asText());
            assertEquals("cat-real-1", selectedRequest.path("selected_category").path("cat_id").asText());
        }
    }

    @Test
    void specMappingReadUsesReadOnlyHmacEndpointAndPreservesProviderSkuIdentity() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.SUCCESS)) {
            XianyuSalesChannelClient client = client(fixture.baseUrl());
            IntegrationEnvelope<ListingSpecMappingsData> response = client.getListingSpecMappings(
                    new SpecMappingsCommand("spec-read-1", "acct-1", "item-123"));

            assertEquals(Outcome.SUCCEEDED, response.outcome());
            assertEquals("item-123", response.data().itemId());
            assertEquals("规格", response.data().specifications().getFirst().name());
            assertEquals("sku-month", response.data().skuRows().getFirst().externalSkuId());
            CapturedRequest request = fixture.requests().getFirst();
            assertEquals("POST /api/integration/v1/listings/spec-mappings",
                    request.method() + " " + request.path());
            JsonNode body = objectMapper.readTree(request.body());
            assertEquals("spec-read-1", body.path("request_id").asText());
            assertEquals("acct-1", body.path("account_id").asText());
            assertEquals("item-123", body.path("item_id").asText());
            String canonical = XianyuRequestSigner.canonicalString(request.method(), request.path(),
                    request.header("X-MyShop-Timestamp"), request.header("X-MyShop-Nonce"), request.body());
            assertEquals(independentHmac(SECRET, canonical), request.header("X-MyShop-Signature"));
        }
    }

    @Test
    void takeoverPreflightAndOwnershipClaimUseSeparateSignedSafeRepeatableEndpoints() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.SUCCESS)) {
            XianyuSalesChannelClient client = client(fixture.baseUrl());

            IntegrationEnvelope<ListingSpecMappingsData> inspection = client.inspectItemSpecMappings(
                    new SpecMappingsCommand("spec-inspect-1", "acct-1", "item-123"));
            IntegrationEnvelope<OwnershipClaimData> claim = client.claimOwnership(
                    new com.myshop.saleschannel.model.SalesChannelDtos.OwnershipClaimCommand(
                            "claim-1", "acct-1", "item-123"));

            assertEquals(Outcome.SUCCEEDED, inspection.outcome());
            assertTrue(inspection.data().specDimensionsVerified());
            assertEquals(Outcome.SUCCEEDED, claim.outcome());
            assertEquals("ALREADY_CLAIMED", claim.data().result());
            assertEquals(List.of("POST /api/integration/v1/items/spec-mappings",
                            "POST /api/integration/v1/items/ownership/claim"),
                    fixture.requests().stream().map(r -> r.method() + " " + r.path()).toList());

            JsonNode inspectionBody = objectMapper.readTree(fixture.requests().get(0).body());
            assertEquals("spec-inspect-1", inspectionBody.path("request_id").asText());
            assertEquals("acct-1", inspectionBody.path("account_id").asText());
            assertEquals("item-123", inspectionBody.path("item_id").asText());
            JsonNode claimBody = objectMapper.readTree(fixture.requests().get(1).body());
            assertEquals("claim-1", claimBody.path("request_id").asText());
            assertEquals("acct-1", claimBody.path("account_id").asText());
            assertEquals("item-123", claimBody.path("item_id").asText());
            assertFalse(claimBody.has("managed_by"));
            for (CapturedRequest request : fixture.requests()) {
                String canonical = XianyuRequestSigner.canonicalString(request.method(), request.path(),
                        request.header("X-MyShop-Timestamp"), request.header("X-MyShop-Nonce"), request.body());
                assertEquals(independentHmac(SECRET, canonical), request.header("X-MyShop-Signature"));
            }
        }
    }

    @Test
    void catalogQueryAndManualSyncUseTheExistingSignedClientAndSafeSemantics() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.SUCCESS)) {
            XianyuSalesChannelClient client = client(fixture.baseUrl());
            IntegrationEnvelope<ItemsQueryData> query = client.queryItems(
                    new ItemsQueryCommand("acct-1", 2, 50, "安卓"));
            String requestId = UUID.randomUUID().toString();
            IntegrationEnvelope<ItemsSyncData> sync = client.syncItems(
                    new ItemsSyncCommand(requestId, "acct-1"));

            assertEquals(Outcome.SUCCEEDED, query.outcome());
            assertEquals(1L, query.data().total());
            assertEquals("item-123", query.data().items().getFirst().itemId());
            assertEquals(Outcome.SUCCEEDED, sync.outcome());
            assertEquals(requestId, sync.requestId());
            assertEquals(3, sync.data().pages());
            assertEquals(List.of(
                    "POST /api/integration/v1/items/query",
                    "POST /api/integration/v1/items/sync"),
                    fixture.requests().stream().map(r -> r.method() + " " + r.path()).toList());

            JsonNode queryBody = objectMapper.readTree(fixture.requests().get(0).body());
            assertEquals("acct-1", queryBody.path("account_id").asText());
            assertEquals(2, queryBody.path("page").asInt());
            assertEquals(50, queryBody.path("page_size").asInt());
            assertEquals("安卓", queryBody.path("keyword").asText());
            JsonNode syncBody = objectMapper.readTree(fixture.requests().get(1).body());
            assertEquals(requestId, syncBody.path("request_id").asText());
            assertEquals("acct-1", syncBody.path("account_id").asText());
            for (CapturedRequest request : fixture.requests()) {
                String canonical = XianyuRequestSigner.canonicalString(request.method(), request.path(),
                        request.header("X-MyShop-Timestamp"), request.header("X-MyShop-Nonce"), request.body());
                assertEquals(independentHmac(SECRET, canonical), request.header("X-MyShop-Signature"));
            }
            String safeResponse = objectMapper.writeValueAsString(query.data().items().getFirst());
            assertFalse(safeResponse.toLowerCase().contains("cookie"));
            assertFalse(safeResponse.toLowerCase().contains("token"));
            assertFalse(safeResponse.toLowerCase().contains("password"));
            assertFalse(safeResponse.toLowerCase().contains("metadata"));
        }
    }

    @Test
    void manualCatalogSyncTransportFailureRemainsSafeRepeatableAndNeverUnknown() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.RESET_AFTER_READ)) {
            String requestId = UUID.randomUUID().toString();
            SalesChannelProviderClientException error = assertThrows(SalesChannelProviderClientException.class,
                    () -> client(fixture.baseUrl()).syncItems(new ItemsSyncCommand(requestId, "acct-1")));
            assertEquals(SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE, error.getDisposition());
            assertNotEquals("UNKNOWN", error.getDisposition().name());
            assertEquals("/api/integration/v1/items/sync", fixture.requests().getFirst().path());
        }
    }

    @Test
    void metadataTimeoutIsSafeRetryableAndNeverUnknown() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.TIMEOUT_AFTER_READ)) {
            XianyuSalesChannelClient client = client(fixture.baseUrl(), 250);
            SalesChannelProviderClientException failure = assertThrows(SalesChannelProviderClientException.class,
                    () -> client.searchAddress("Hangzhou", "全国"));
            assertEquals(SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE,
                    failure.getDisposition());
            assertNotEquals(Outcome.UNKNOWN.name(), failure.getDisposition().name());
        }
    }

    @Test
    void deserializesSeparateAgentOperationalAndAuthenticationState() throws Exception {
        SalesChannelAccount account = objectMapper.readValue("""
                {"provider_account_id":"acct-1","status":"active","enabled":true,
                 "operational_status":"active","status_source":"AUTH_RECOVERY",
                 "auth_state":"AUTH_REQUIRED","auth_state_source":"SESSION_EXPIRED",
                 "auth_state_observed_at":"2026-10-06T14:40:30Z",
                 "last_login_at":"2026-10-06T14:40:29Z","last_refresh_at":"2026-10-06T14:40:30Z"}
                """, SalesChannelAccount.class);

        assertEquals("active", account.operationalStatus());
        assertEquals("AUTH_RECOVERY", account.statusSource());
        assertEquals("AUTH_REQUIRED", account.authState());
        assertEquals("SESSION_EXPIRED", account.authStateSource());
        assertEquals("2026-10-06T14:40:30Z", account.authStateObservedAt());
        assertEquals("2026-10-06T14:40:30Z", account.lastRefreshAt());
    }

    @Test
    void ignoresSensitiveUnknownAccountFieldsAndDoesNotExposeSecretInErrorEnvelope() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.SENSITIVE_RESPONSE)) {
            XianyuSalesChannelClient client = client(fixture.baseUrl());
            IntegrationEnvelope<AccountsData> result = client.listAccounts();
            SalesChannelAccount account = result.data().accounts().getFirst();
            String serialized = objectMapper.writeValueAsString(account);
            assertFalse(serialized.toLowerCase().contains("cookie"));
            assertFalse(serialized.toLowerCase().contains("password"));
            assertFalse(serialized.toLowerCase().contains("proxy"));
            assertFalse(serialized.toLowerCase().contains("token"));

        }
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.FAILED_RESPONSE)) {
            IntegrationEnvelope<AccountsData> failed = client(fixture.baseUrl()).listAccounts();
            assertEquals(Outcome.FAILED, failed.outcome());
            assertFalse(failed.error().message().contains(SECRET));
            assertFalse(failed.error().message().contains(fixture.requests().getFirst()
                    .header("X-MyShop-Signature")));
        }
    }

    @Test
    void explicitAgentFailureForPublishRemainsFailedAndSafeToRetry() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.FAILED_OPERATION)) {
            IntegrationEnvelope<?> result = client(fixture.baseUrl()).publish(
                    new PublishCommand("op-rejected", "acct-1", 8, listing()));
            assertEquals(Outcome.FAILED, result.outcome());
            assertEquals("PLATFORM_REJECTED", result.error().code());
            assertEquals("op-rejected", result.requestId());
            assertEquals(1, fixture.requests().size());
        }
    }

    @Test
    void explicitPreReservationHttpFailureWithoutRequestIdIsCorrelatedAndFailed() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.PRE_RESERVATION_REJECTION)) {
            IntegrationEnvelope<?> result = client(fixture.baseUrl()).publish(
                    new PublishCommand("op-auth-rejected", "acct-1", 8, listing()));
            assertEquals(Outcome.FAILED, result.outcome());
            assertEquals("op-auth-rejected", result.requestId());
            assertEquals("INVALID_SIGNATURE", result.error().code());
            assertEquals(1, fixture.requests().size());
        }
    }

    @Test
    void requestConstructionAndConfigurationErrorsAreSafeFailuresBeforeNetwork() {
        XianyuSalesChannelClient unconfigured = new XianyuSalesChannelClient(objectMapper,
                "", "", "", 1000, CLOCK, () -> "fixed-nonce");
        SalesChannelProviderClientException error = assertThrows(SalesChannelProviderClientException.class,
                () -> unconfigured.publish(new PublishCommand("operation-1", "account-1", 1, listing())));
        assertEquals(SalesChannelProviderClientException.Disposition.SAFE_PERMANENT, error.getDisposition());
        assertEquals("AGENT_CONFIGURATION_MISSING", error.getErrorCode());
        assertFalse(error.getMessage().contains(SECRET));

        SalesChannelProviderClientException invalid = assertThrows(SalesChannelProviderClientException.class,
                () -> client("http://127.0.0.1:1").publish(
                        new PublishCommand("operation-1", "account-1", 1,
                                new ListingSnapshot("", "", null, null, List.of(), List.of(), null, null,
                                        null, null, null, null, List.of(), 1, List.of(), List.of(), "", null,
                                        null, null, false, null))));
        assertEquals(SalesChannelProviderClientException.Disposition.SAFE_PERMANENT, invalid.getDisposition());
        assertEquals("INVALID_LISTING", invalid.getErrorCode());
    }

    @Test
    void connectionFailureIsExplicitlySafeToRetry() {
        SalesChannelProviderClientException error = assertThrows(SalesChannelProviderClientException.class,
                () -> client("http://127.0.0.1:1").listAccounts());
        assertEquals(SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE, error.getDisposition());
        assertEquals("AGENT_UNREACHABLE", error.getErrorCode());
    }

    @Test
    void mediaUploadTransportFailureIsSafeToRetryWithItsStableRequestId() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.RESET_AFTER_READ)) {
            MediaUploadCommand command = new MediaUploadCommand("acct-1", MediaType.IMAGE, "front.jpg",
                    "image/jpeg", new byte[]{1, 2, 3});
            SalesChannelProviderClientException error = assertThrows(SalesChannelProviderClientException.class,
                    () -> client(fixture.baseUrl()).uploadMedia(command));
            assertEquals(SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE, error.getDisposition());
            String multipart = new String(fixture.requests().getFirst().body(), StandardCharsets.ISO_8859_1);
            assertTrue(multipart.contains(command.requestId()));
        }
    }

    @Test
    void reconcileTimeoutIsExplicitlySafeToRetry() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.RESET_AFTER_READ)) {
            SalesChannelProviderClientException error = assertThrows(SalesChannelProviderClientException.class,
                    () -> client(fixture.baseUrl()).reconcile(
                            new ReconcileCommand("reconcile-1", "acct-1", "target-1", null, 1)));
            assertEquals(SalesChannelProviderClientException.Disposition.SAFE_RETRYABLE, error.getDisposition());
        }
    }

    @Test
    void timeoutAfterAgentReceivesPublishReturnsUnknownInsteadOfRetrying() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.TIMEOUT_AFTER_READ)) {
            XianyuSalesChannelClient client = client(fixture.baseUrl(), 250);
            IntegrationEnvelope<?> result = client.publish(new PublishCommand("op-timeout", "acct-1", 5, listing()));
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertEquals("op-timeout", result.requestId());
            assertEquals("AGENT_OUTCOME_UNKNOWN", result.error().code());
            assertEquals(1, fixture.requests().size());
            assertTrue(result.error().message().contains("reconcile"));
        }
    }

    @Test
    void connectionResetAfterRequestBodyWasReadReturnsUnknown() throws Exception {
        try (Fixture fixture = new Fixture(objectMapper, FixtureMode.RESET_AFTER_READ)) {
            XianyuSalesChannelClient client = client(fixture.baseUrl());
            IntegrationEnvelope<?> result = client.publish(new PublishCommand("op-reset", "acct-1", 6, listing()));
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertEquals("op-reset", result.requestId());
            assertEquals(1, fixture.requests().size());
        }
    }

    private XianyuSalesChannelClient client(String baseUrl) {
        return client(baseUrl, 3000);
    }

    private XianyuSalesChannelClient client(String baseUrl, long timeoutMillis) {
        AtomicInteger nonce = new AtomicInteger();
        return new XianyuSalesChannelClient(objectMapper, baseUrl, "test-key", SECRET,
                timeoutMillis, CLOCK, () -> "test-nonce-" + nonce.incrementAndGet());
    }

    private ListingSnapshot listing() {
        return new ListingSnapshot("A carefully prepared listing", "A detailed description",
                new BigDecimal("12.50"), new BigDecimal("18.00"), List.of("media-cover"), List.of(),
                "category-1", "Category", "channel-1", "Channel", "leaf-1", "tb-1",
                List.of(new PlatformAttribute("property", "Color", "blue", "Blue", null, Map.of())),
                3, List.of(new Specification("Color", List.of(new SpecificationValue("Blue", null)), false)),
                List.of(new SkuRow(Map.of("Color", "Blue"), new BigDecimal("12.50"), 3)),
                "Warehouse 1", "Warehouse", "express", "free", false, BigDecimal.ZERO);
    }

    private String independentHmac(String key, String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private enum FixtureMode {
        SUCCESS,
        SENSITIVE_RESPONSE,
        FAILED_RESPONSE,
        FAILED_OPERATION,
        PRE_RESERVATION_REJECTION,
        TIMEOUT_AFTER_READ,
        RESET_AFTER_READ
    }

    private record CapturedRequest(String method, String path, byte[] body, Map<String, List<String>> headers) {
        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
        }
    }

    private final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final FixtureMode mode;
        private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

        Fixture(ObjectMapper mapper, FixtureMode mode) throws IOException {
            this.mode = mode;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handle(mapper, exchange));
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<CapturedRequest> requests() {
            return List.copyOf(requests);
        }

        private void handle(ObjectMapper mapper, HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    body, exchange.getRequestHeaders().entrySet().stream().collect(
                    java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
            if (mode == FixtureMode.TIMEOUT_AFTER_READ) {
                try {
                    Thread.sleep(1000);
                    send(exchange, 200, successResponse(mapper, exchange, body));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                } catch (IOException ignored) {
                    exchange.close();
                }
                return;
            }
            if (mode == FixtureMode.RESET_AFTER_READ) {
                exchange.close();
                return;
            }
            if (mode == FixtureMode.SENSITIVE_RESPONSE) {
                send(exchange, 200, ("{\"request_id\":\"fixture\",\"outcome\":\"SUCCEEDED\","
                        + "\"data\":{\"accounts\":[{\"provider_account_id\":\"acct-1\","
                        + "\"display_name\":\"Account\",\"status\":\"ACTIVE\",\"enabled\":true,"
                        + "\"last_login_at\":null,\"last_refresh_at\":null,\"capabilities\":null,"
                        + "\"cookie\":\"should-not-escape\",\"login_password\":\"should-not-escape\","
                        + "\"proxy_user\":\"should-not-escape\",\"proxy_pass\":\"should-not-escape\","
                        + "\"unb\":\"should-not-escape\",\"token\":\"should-not-escape\"}]},"
                        + "\"error\":null}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (mode == FixtureMode.FAILED_RESPONSE) {
                String echoedSignature = exchange.getRequestHeaders().getFirst("X-MyShop-Signature");
                send(exchange, 400, ("{\"request_id\":\"fixture\",\"outcome\":\"FAILED\","
                        + "\"data\":null,\"error\":{\"code\":\"TEST_ERROR\",\"message\":\"remote echoed "
                        + SECRET + " and " + echoedSignature + "\"}}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (mode == FixtureMode.FAILED_OPERATION) {
                JsonNode request = mapper.readTree(body);
                send(exchange, 409, ("{\"request_id\":\"" + request.path("request_id").asText()
                        + "\",\"outcome\":\"FAILED\",\"data\":null,\"error\":{"
                        + "\"code\":\"PLATFORM_REJECTED\",\"message\":\"platform rejected\"}}")
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (mode == FixtureMode.PRE_RESERVATION_REJECTION) {
                send(exchange, 401, ("{\"request_id\":null,\"outcome\":\"FAILED\","
                        + "\"data\":null,\"error\":{\"code\":\"INVALID_SIGNATURE\","
                        + "\"message\":\"Integration authentication failed\"}}")
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, successResponse(mapper, exchange, body));
        }

        private byte[] successResponse(ObjectMapper mapper, HttpExchange exchange, byte[] requestBody)
                throws IOException {
            String requestId = "fixture-request";
            if (requestBody.length > 0 && exchange.getRequestHeaders().getFirst("Content-Type") != null
                    && exchange.getRequestHeaders().getFirst("Content-Type").startsWith("application/json")) {
                JsonNode request = mapper.readTree(requestBody);
                if (request.hasNonNull("request_id")) requestId = request.path("request_id").asText();
            } else if (exchange.getRequestURI().getRawPath().equals("/api/integration/v1/media")) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("name=\"request_id\"\\r\\n\\r\\n([^\\r\\n]+)")
                        .matcher(new String(requestBody, StandardCharsets.ISO_8859_1));
                if (matcher.find()) requestId = matcher.group(1);
            }
            Object data = switch (exchange.getRequestURI().getRawPath()) {
                case "/api/integration/v1/accounts/login/qr/start" -> Map.of(
                        "login_intent_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                        "login_session_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                        "qr_code_data_url", "data:image/png;base64,AA==",
                        "expires_in_seconds", 300);
                case "/api/integration/v1/accounts/login/qr/status" -> Map.of(
                        "status", "VERIFICATION_REQUIRED",
                        "login_intent_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                        "login_session_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                        "verification_message", "闲鱼要求进行安全验证，请按手机端提示完成验证。",
                        "verification_qr_code_data_url", "data:image/png;base64,iVBORw0KGgo=");
                case "/api/integration/v1/accounts/login/qr/reauthenticate/start" -> Map.of(
                        "login_intent_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                        "login_session_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                        "qr_code_data_url", "data:image/png;base64,AA==", "expires_in_seconds", 300);
                case "/api/integration/v1/accounts/login/qr/verification" -> Map.of(
                        "status", "PROVIDER_AUTH_VERIFIED",
                        "login_intent_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                        "login_session_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                        "account_id", "2215747465381", "is_new_account", false);
                case "/api/integration/v1/accounts" -> Map.of("accounts", List.of(Map.of(
                        "provider_account_id", "acct-1", "display_name", "Fixture account",
                        "status", "ACTIVE", "enabled", true,
                        "capabilities", Map.of("account_type", "FISH_SHOP", "publish", true,
                                "edit", true, "stock_sync", true, "sku", true, "video", true))));
                case "/api/integration/v1/accounts/acct-1/verify" -> Map.of("capabilities", Map.of(
                        "account_type", "FISH_SHOP", "publish", true, "edit", true,
                        "stock_sync", true, "sku", true, "video", true));
                case "/api/integration/v1/items/query" -> Map.of(
                        "items", List.of(Map.ofEntries(
                                Map.entry("account_id", "acct-1"), Map.entry("item_id", "item-123"),
                                Map.entry("title", "安卓定制V"), Map.entry("price", "20"),
                                Map.entry("quantity", 5), Map.entry("status_desc", "在售"),
                                Map.entry("sku_count", 3), Map.entry("is_multi_spec", true),
                                Map.entry("is_seller_item", true),
                                Map.entry("catalog_created_at", "2026-09-24T00:00:00Z"),
                                Map.entry("catalog_updated_at", "2026-09-24T01:00:00Z"),
                                Map.entry("cookie", "must-not-escape"), Map.entry("token", "must-not-escape"),
                                Map.entry("metadata_json", Map.of("ai_prompt", "private")))),
                        "page", 2, "page_size", 50, "total", 1, "total_pages", 1);
                case "/api/integration/v1/items/sync" -> Map.of(
                        "account_id", "acct-1", "fetched_count", 25, "saved_count", 20,
                        "pages", 3, "skipped", false);
                case "/api/integration/v1/metadata/address/search" -> Map.of("keywords", "上海市徐汇区",
                        "tips", List.of(Map.of("id", "poi-1", "name", "徐家汇", "district", "徐汇区",
                                "adcode", "310104", "location", "121.4365,31.1885", "address", "虹桥路1号",
                                "city", "上海市", "search_keyword", "上海市徐汇区徐家汇",
                                "expected_text", "上海市徐汇区虹桥路1号")));
                case "/api/integration/v1/metadata/category/recommend" -> Map.of(
                        "candidates", List.of(Map.of("cat_id", "cat-real-1", "cat_name", "软件服务",
                                "channel_cat_id", "channel-1", "channel_cat_name", "虚拟商品",
                                "tb_cat_id", "tb-real-1", "is_selected", true,
                                "cookie", "do-not-leak", "token", "do-not-leak")),
                        "properties", List.of(Map.of("property_id", "property-1", "property_name", "授权类型",
                        "options", List.of(Map.of("value_id", "personal", "value_name", "个人版")),
                        "is_multiple", false)), "card_list", List.of(Map.of("id", "card-1")));
                case "/api/integration/v1/listings/spec-mappings" -> Map.of(
                        "item_id", "item-123",
                        "specifications", List.of(Map.of("name", "规格", "values", List.of(
                                Map.of("name", "月卡"), Map.of("name", "年卡"), Map.of("name", "永久卡")))),
                        "sku_rows", List.of(Map.of("external_sku_id", "sku-month", "specs", Map.of("规格", "月卡")),
                                Map.of("external_sku_id", "sku-year", "specs", Map.of("规格", "年卡")),
                                Map.of("external_sku_id", "sku-life", "specs", Map.of("规格", "永久卡"))));
                case "/api/integration/v1/items/spec-mappings" -> Map.of(
                        "item_id", "item-123", "specifications", List.of(), "sku_rows", List.of(),
                        "spec_dimensions_verified", true);
                case "/api/integration/v1/items/ownership/claim" -> Map.of(
                        "account_id", "acct-1", "item_id", "item-123", "managed_by", "MYSHOP",
                        "result", "ALREADY_CLAIMED");
                case "/api/integration/v1/media" -> Map.of("media_id", "media-1", "media_type", "image");
                case "/api/integration/v1/listings/reconcile" -> Map.of(
                        "target_request_id", "target-operation", "operation_type", "PUBLISH",
                        "item_id", "item-123", "desired_revision", 15);
                default -> Map.of("item_id", "item-123", "item_url", "https://agent.invalid/item-123",
                        "capability", Map.of("account_type", "FISH_SHOP"),
                        "idempotent_reused", false, "desired_revision", 1);
            };
            java.util.LinkedHashMap<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("request_id", requestId);
            envelope.put("outcome", "SUCCEEDED");
            envelope.put("data", data);
            envelope.put("error", null);
            return mapper.writeValueAsBytes(envelope);
        }

        private void send(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
