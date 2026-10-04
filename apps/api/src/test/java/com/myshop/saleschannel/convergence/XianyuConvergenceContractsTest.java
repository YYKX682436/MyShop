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
    void listingSnapshotUsesSnakeCaseFieldsAndDecimalStrings() throws Exception {
        ListingSnapshot snapshot = new ListingSnapshot("Item", null, "12345678.99", 1,
                java.util.List.of(new ListingImage("asset-1", "https://example.invalid/image", "a".repeat(64))),
                new ListingCategory("cat", null, null, null, null),
                new ListingAddress(null, null, null, null, null, null, null, null),
                java.util.List.of(new PlatformAttribute("p", "Property", "v", "Value", null, null)),
                java.util.List.of(new ListingSpecification("Type", java.util.List.of("A"))),
                java.util.List.of(new ListingSku("sku-1", java.util.Map.of("Type", "A"), "12345678.99", 1)));

        var json = objectMapper.readTree(objectMapper.writeValueAsString(snapshot));
        assertTrue(json.has("platform_attributes"));
        assertEquals("12345678.99", json.get("price").asText());
    }

    private boolean valid(Object value) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(value).isEmpty();
        }
    }

    @Test
    void moneyStockRevisionImagesSkuAndDeliveryConstraintsRejectInvalidInputs() {
        for (String price : java.util.List.of("1.001", "123456789.00", "-1", "1e2", "NaN", "1\n")) {
            assertTrue(!valid(new ListingSku("sku", java.util.Map.of("Type", "A"), price, 0)));
        }
        assertTrue(valid(new ListingSku("opaque:key", java.util.Map.of("Type", "A"), "12345678.99", 0)));
        assertTrue(!valid(new ListingSku("sku", java.util.Map.of("Type", "A"), "1", -1)));
        assertTrue(!valid(new ListingSku("sku", java.util.Map.of(), "1", 0)));
        var snapshot = new ListingSnapshot("T", null, "1", 0,
                java.util.List.of(new ListingImage("asset", "https://example.invalid/i", "a".repeat(64))),
                new ListingCategory(null, null, null, null, null),
                new ListingAddress(null, null, null, null, null, null, null, null),
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        assertTrue(valid(new EnsureListingRequest("r", "l", "a", 0L, DesiredListingState.ONLINE, snapshot)));
        assertTrue(!valid(new EnsureListingRequest("r", "l", "a", -1L, DesiredListingState.ONLINE, snapshot)));
        assertTrue(!valid(new ListingSnapshot("T", null, "1", 0, java.util.List.of(),
                new ListingCategory(null, null, null, null, null),
                new ListingAddress(null, null, null, null, null, null, null, null),
                java.util.List.of(), java.util.List.of(), java.util.List.of())));
        assertTrue(!valid(new SendDeliveryRequest("r", "l", "a", "i", "o", 0, java.util.List.of())));
        assertTrue(!valid(new OperationObservation(null, "l", OperationState.APPLYING, null, null, null, -1, null)));
        assertTrue(!valid(new OperationError("", "error")));
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
        assertTrue(valid(new ListingSku("😀".repeat(128), java.util.Map.of("Type", "A"), "1", 0)));
        assertTrue(!valid(new ListingSku("😀".repeat(129), java.util.Map.of("Type", "A"), "1", 0)));
        var permissive = objectMapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> permissive.readValue("{\"message_id\":\"m\",\"action\":\"NO_REPLY\",\"extra\":true}", ReplyResolution.class));
        assertTrue(valid(image));
    }
}
