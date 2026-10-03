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
}
