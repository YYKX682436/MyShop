package com.myshop.saleschannel.convergence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingAddress;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingCategory;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingImage;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingSnapshot;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingSpecification;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingSku;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.MessageAction;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.MessageEvent;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.PlatformAttribute;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ReplyResolution;
import org.junit.jupiter.api.Test;
import jakarta.validation.Validation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;

class XianyuConvergenceContractsTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void normalizedMessageEventUsesTheSpecifiedJsonFieldNames() throws Exception {
        String json = """
                {"message_id":"msg-1","account_id":"acc-1","conversation_id":"chat-1",
                 "sender_id":"buyer-1","sender_name":"Buyer","external_item_id":"item-1",
                 "listing_key":"listing-1","order_id":null,"message_type":"TEXT",
                 "text":"hello","timestamp":"2026-10-02T00:00:00Z"}
                """;

        MessageEvent event = objectMapper.readValue(json, MessageEvent.class);
        String normalized = objectMapper.writeValueAsString(event);

        assertEquals("msg-1", event.messageId());
        assertEquals("listing-1", event.listingKey());
        assertEquals("item-1", event.externalItemId());
        assertEquals("2026-10-02T00:00:00Z", objectMapper.readTree(normalized).get("timestamp").asText());
        assertEquals("null", objectMapper.readTree(normalized).get("order_id").toString());
    }

    @Test
    void replyActionVocabularyAndNamesAreStable() throws Exception {
        ReplyResolution response = new ReplyResolution("msg-1", MessageAction.NO_REPLY, null, null);

        var json = objectMapper.readTree(objectMapper.writeValueAsString(response));
        assertEquals("NO_REPLY", json.get("action").asText());
        assertEquals(2, json.size());
    }

    @Test
    void sharedAgentV1FixtureRoundTripsAsTheExactWireContract() throws Exception {
        byte[] fixture;
        try (InputStream input = getClass().getResourceAsStream("/contracts/ensure_listing_v2.json")) {
            fixture = input.readAllBytes();
        }
        EnsureListingRequest request = objectMapper.readValue(fixture, EnsureListingRequest.class);
        var expected = objectMapper.readTree(fixture);
        var serialized = objectMapper.readTree(objectMapper.writeValueAsBytes(request));

        assertEquals(expected, serialized);
        assertTrue(valid(request));
        assertEquals(1, request.desiredSnapshot().schemaVersion());
        assertEquals(request.listingKey(), request.desiredSnapshot().listingKey());
        assertEquals("tb-cat-1", request.desiredSnapshot().category().tbCategoryId());
        assertEquals("sku-black", request.desiredSnapshot().skus().getFirst().skuKey());
        assertEquals(5, request.desiredSnapshot().skus().stream().mapToInt(ListingSku::stock).sum());
    }

    @Test
    void onlineAndOfflineFingerprintsMatchTheSharedCrossLanguageFixture() throws Exception {
        var fixture = objectMapper.readTree(getClass().getResourceAsStream(
                "/contracts/desired_listing_v1_fingerprints.json"));
        ListingSnapshot online = objectMapper.treeToValue(fixture.path("online_snapshot"), ListingSnapshot.class);
        assertEquals(fixture.path("online_fingerprint").asText(),
                DesiredListingSnapshotFingerprints.online(objectMapper, online));
        assertEquals(fixture.path("offline_fingerprint").asText(),
                DesiredListingSnapshotFingerprints.offline(objectMapper, fixture.path("listing_key").asText()));

        var offlineNode = objectMapper.readTree(getClass().getResourceAsStream(
                "/contracts/ensure_listing_v2_offline.json"));
        EnsureListingRequest offline = objectMapper.treeToValue(offlineNode, EnsureListingRequest.class);
        var serialized = objectMapper.readTree(objectMapper.writeValueAsBytes(offline));
        assertTrue(serialized.has("desired_snapshot"));
        assertTrue(serialized.get("desired_snapshot").isNull());
        assertEquals(offlineNode, serialized);
        assertTrue(valid(offline));

        var invalidOnline = objectMapper.readTree(getClass().getResourceAsStream(
                "/contracts/ensure_listing_v2.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalidOnline).putNull("desired_snapshot");
        assertTrue(!valid(objectMapper.treeToValue(invalidOnline, EnsureListingRequest.class)));
        var invalidOffline = (com.fasterxml.jackson.databind.node.ObjectNode) offlineNode.deepCopy();
        invalidOffline.set("desired_snapshot", fixture.path("online_snapshot").deepCopy());
        assertTrue(!valid(objectMapper.treeToValue(invalidOffline, EnsureListingRequest.class)));
    }

    @Test
    void sharedAgentOperationResponseFixtureDeserializesWithoutExtraFields() throws Exception {
        byte[] fixture;
        try (InputStream input = getClass().getResourceAsStream("/contracts/operation_observation_v2.json")) {
            fixture = input.readAllBytes();
        }
        var response = objectMapper.readValue(fixture, EnsureListingOperationObservation.class);
        assertEquals("myshop-command-20261005-0001", response.requestId());
        assertEquals(OperationState.SUCCEEDED, response.state());
        assertEquals(objectMapper.readTree(fixture), objectMapper.readTree(objectMapper.writeValueAsBytes(response)));

        var withUnknown = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(fixture);
        withUnknown.put("provider_action", "PUBLISH");
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> objectMapper.treeToValue(withUnknown, EnsureListingOperationObservation.class));
    }

    private boolean valid(Object value) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(value).isEmpty();
        }
    }

    @Test
    void v1SnapshotConstraintsRejectUnknownFieldsAndUnsafeValues() throws Exception {
        var fixture = objectMapper.readTree(getClass().getResourceAsStream("/contracts/ensure_listing_v2.json"));
        var unknownNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknownNode.path("desired_snapshot")).put("unknown", true);
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> objectMapper.treeToValue(unknownNode, EnsureListingRequest.class));

        var unsafeImageNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsafeImageNode.path("desired_snapshot").path("images").get(0))
                .put("source_url", "file:///tmp/image.png");
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> objectMapper.treeToValue(unsafeImageNode, EnsureListingRequest.class));

        var requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot")).put("stock", 1_000_000);
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot")).put("description", " ");
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot")).put("title", " Desk Lamp");
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot")).put("description", "one\r\ntwo");
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot")).put("title", "😀".repeat(201));
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot").path("skus").get(0))
                .put("price", "0");
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot")).put("price", "10.00");
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot").path("images").get(0))
                .put("sha256", "A".repeat(64));
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        requestNode.put("listing_key", "different-listing");
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot")).put("schema_version", 2);
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        var attributes = (com.fasterxml.jackson.databind.node.ArrayNode) requestNode.path("desired_snapshot").path("platform_attributes");
        attributes.add(attributes.get(0).deepCopy());
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot").path("platform_attributes").get(1))
                .put("property_name", "Maker");
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot").path("platform_attributes").get(0))
                .set("properties", objectMapper.createObjectNode().put("opaque", true));
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot").path("platform_attributes").get(0))
                .put("property_name", "分类");
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot").path("skus").get(0))
                .put("sku_key", " ");
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));

        requestNode = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        var shipping = (com.fasterxml.jackson.databind.node.ObjectNode) requestNode.path("desired_snapshot").path("shipping");
        shipping.put("method", "free");
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));
        shipping.putNull("postage");
        assertTrue(valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));
        shipping.put("method", "fixed").put("postage", "1000.01");
        assertTrue(!valid(objectMapper.treeToValue(requestNode, EnsureListingRequest.class)));
        assertTrue(!valid(new SendDeliveryRequest("r", "l", "a", "i", "o", 0, java.util.List.of())));
        assertTrue(!valid(new EnsureListingOperationObservation(null, "l", -1L, OperationState.APPLYING, null, null)));
        assertTrue(valid(new OperationObservation("r", "l", OperationState.APPLYING,
                null, null, null, null, null)));
        assertTrue(!valid(new OperationError("", "error")));
    }

    @Test
    void desiredListingMoneyWireRequiresCanonicalPositivePriceAndBoundedPostage() throws Exception {
        var fixture = objectMapper.readTree(getClass().getResourceAsStream("/contracts/ensure_listing_v2.json"));
        for (String value : java.util.List.of("10", "10.0", "10.001", "0.00")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("desired_snapshot")).put("price", value);
            assertTrue(!valid(objectMapper.treeToValue(node, EnsureListingRequest.class)), value);
        }
        for (String value : java.util.List.of("10", "10.0", "10.001", "0.00")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
            var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) node.path("desired_snapshot");
            ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.path("skus").get(0)).put("price", value);
            snapshot.put("price", value);
            assertTrue(!valid(objectMapper.treeToValue(node, EnsureListingRequest.class)), value);
        }
        var canonical = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        var canonicalSnapshot = (com.fasterxml.jackson.databind.node.ObjectNode) canonical.path("desired_snapshot");
        canonicalSnapshot.put("price", "10.00");
        for (var sku : canonicalSnapshot.path("skus")) ((com.fasterxml.jackson.databind.node.ObjectNode) sku).put("price", "10.00");
        assertTrue(valid(objectMapper.treeToValue(canonical, EnsureListingRequest.class)));

        var numericPrice = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) numericPrice.path("desired_snapshot")).put("price", 10);
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> objectMapper.treeToValue(numericPrice, EnsureListingRequest.class));
        var numericSkuPrice = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) numericSkuPrice.path("desired_snapshot").path("skus").get(0)).put("price", 10);
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> objectMapper.treeToValue(numericSkuPrice, EnsureListingRequest.class));

        for (String value : java.util.List.of("12", "12.3", "12.345", "1000.01", "1000.99")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
            var shipping = (com.fasterxml.jackson.databind.node.ObjectNode) node.path("desired_snapshot").path("shipping");
            shipping.put("method", "fixed").put("postage", value);
            assertTrue(!valid(objectMapper.treeToValue(node, EnsureListingRequest.class)), value);
        }
        for (String value : java.util.List.of("12.30", "1000.00")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("desired_snapshot").path("shipping"))
                    .put("method", "fixed").put("postage", value);
            assertTrue(valid(objectMapper.treeToValue(node, EnsureListingRequest.class)), value);
        }
        var numericPostage = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) numericPostage.path("desired_snapshot").path("shipping"))
                .put("method", "fixed").put("postage", 12);
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> objectMapper.treeToValue(numericPostage, EnsureListingRequest.class));
        var missingPostage = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingPostage.path("desired_snapshot").path("shipping")).remove("postage");
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> objectMapper.treeToValue(missingPostage, EnsureListingRequest.class));
    }

    @Test
    void requestAndListingIdentitiesAreExactAndCanonical() throws Exception {
        var fixture = objectMapper.readTree(getClass().getResourceAsStream("/contracts/ensure_listing_v2.json"));
        for (String invalid : java.util.List.of(" leading", "trailing ", "a/b", "a:b", "a%b", "商品", "x".repeat(256))) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
            node.put("request_id", invalid);
            assertTrue(!valid(objectMapper.treeToValue(node, EnsureListingRequest.class)), invalid);
        }
        for (String validId : java.util.List.of("0f684102-e1f7-4df3-b03c-0acfc9f052f1", "Az09", "a.b_c~d-e")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
            node.put("request_id", validId);
            assertTrue(valid(objectMapper.treeToValue(node, EnsureListingRequest.class)), validId);
        }

        for (String invalidKey : java.util.List.of(" listing ", "e\u0301", "x".repeat(129))) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
            node.put("listing_key", invalidKey);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("desired_snapshot")).put("listing_key", invalidKey);
            assertTrue(!valid(objectMapper.treeToValue(node, EnsureListingRequest.class)), invalidKey);
        }
        var nfc = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.deepCopy();
        nfc.put("listing_key", "é");
        ((com.fasterxml.jackson.databind.node.ObjectNode) nfc.path("desired_snapshot")).put("listing_key", "é");
        assertTrue(valid(objectMapper.treeToValue(nfc, EnsureListingRequest.class)));
    }

    @Test
    void messageRequiredNullableAndLengthConstraintsMatchSchema() throws Exception {
        String json = """
                {"message_id":"m","account_id":"a","conversation_id":"c","sender_id":"s",
                 "sender_name":"S","external_item_id":"i","listing_key":"l","order_id":null,
                 "message_type":"TEXT","text":null,"timestamp":"2026-10-02T00:00:00Z"}
                """;
        assertTrue(valid(objectMapper.readValue(json, MessageEvent.class)));
        for (String field : java.util.List.of("message_id", "account_id", "conversation_id", "sender_id",
                "sender_name", "external_item_id", "listing_key", "order_id", "message_type", "text", "timestamp")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(json);
            node.remove(field);
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> objectMapper.treeToValue(node, MessageEvent.class), field);
        }
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(json);
        node.put("account_id", "a".repeat(81));
        assertTrue(!valid(objectMapper.treeToValue(node, MessageEvent.class)));
        node.put("account_id", "a");
        node.put("text", "t".repeat(20001));
        assertTrue(!valid(objectMapper.treeToValue(node, MessageEvent.class)));
    }

    @Test
    void replyUnionAcceptsExactlyTheFourLegalPayloadCombinations() throws Exception {
        var image = new ReplyImage("https://example.invalid/i", "image/png", "a".repeat(64));
        for (MessageAction action : MessageAction.values()) {
            for (String text : new String[] {null, "hello"}) {
                for (ReplyImage candidate : new ReplyImage[] {null, image}) {
                    boolean legal = (text != null) == (action == MessageAction.SEND_TEXT || action == MessageAction.SEND_TEXT_AND_IMAGE)
                            && (candidate != null) == (action == MessageAction.SEND_IMAGE || action == MessageAction.SEND_TEXT_AND_IMAGE);
                    if (legal) {
                        var reply = new ReplyResolution("m", action, text, candidate);
                        assertTrue(valid(reply));
                        assertEquals(reply, objectMapper.readValue(objectMapper.writeValueAsString(reply), ReplyResolution.class));
                    } else {
                        assertThrows(IllegalArgumentException.class, () -> new ReplyResolution("m", action, text, candidate));
                    }
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new ReplyResolution("m", MessageAction.SEND_TEXT, "", null));
        assertThrows(IllegalArgumentException.class, () -> new ReplyResolution("m", MessageAction.SEND_TEXT, "x".repeat(2001), null));
        assertThrows(IllegalArgumentException.class, () -> new ReplyImage("relative", "image/png", "a".repeat(64)));
    }

    @Test
    void stringLimitsCountUnicodeCodePointsAndUnknownPropertiesStayClosed() throws Exception {
        var image = new ReplyImage("https://example.invalid/i", "image/png", "a".repeat(64));
        assertTrue(valid(new ReplyResolution("m", MessageAction.SEND_TEXT, "😀".repeat(2000), null)));
        assertTrue(valid(new ListingSku("😀".repeat(128), java.util.Map.of("Type", "A"), "1.00", 0)));
        assertTrue(!valid(new ListingSku("😀".repeat(129), java.util.Map.of("Type", "A"), "1.00", 0)));
        var permissive = objectMapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> permissive.readValue("{\"message_id\":\"m\",\"action\":\"NO_REPLY\",\"extra\":true}", ReplyResolution.class));
        assertTrue(valid(image));
    }
}
