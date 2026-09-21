package com.myshop.saleschannel.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.AccountSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.ListingSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.UpdateXianyuListingRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesChannelAdminDtosJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void accountSummaryUsesTheCamelCaseContractExpectedByTheAdminUi() {
        AccountSummary summary = new AccountSummary("local-id", "provider-id", "Test shop",
                "FISH_SHOP", "CONNECTED", true, Map.of("account_type", "FISH_SHOP"),
                LocalDateTime.of(2026, 9, 21, 12, 0));

        JsonNode json = objectMapper.valueToTree(summary);

        assertEquals("provider-id", json.path("providerAccountId").asText());
        assertEquals("FISH_SHOP", json.path("accountType").asText());
        assertEquals("Test shop", json.path("displayName").asText());
        assertTrue(json.has("lastVerifiedAt"));
        assertFalse(json.has("provider_account_id"));
        assertFalse(json.has("account_type"));
    }

    @Test
    void listingSummaryUsesCamelCaseForFieldsConsumedByTheAdminUi() {
        ListingSummary summary = new ListingSummary("listing-id", "provider-id", "Test shop",
                "FISH_SHOP", false, "PENDING", "item-1", 3L,
                LocalDateTime.of(2026, 9, 21, 12, 0), "", "", null);

        JsonNode json = objectMapper.valueToTree(summary);

        assertEquals("provider-id", json.path("providerAccountId").asText());
        assertEquals("FISH_SHOP", json.path("accountType").asText());
        assertEquals("item-1", json.path("externalItemId").asText());
        assertEquals(3L, json.path("desiredRevision").asLong());
        assertFalse(json.path("isEnabled").asBoolean());
        assertFalse(json.has("provider_account_id"));
        assertFalse(json.has("external_item_id"));
    }

    @Test
    void xianyuConfigAcceptsAndReturnsTheCamelCaseAdminApiContract() throws Exception {
        String body = """
                {"accountId":"7eb0fbc8-51cd-42e3-a343-79af60c721d7","config":{
                  "titleOverride":"Test title",
                  "addressExpectedText":"Test address check",
                  "platformChannelCategoryId":"channel-1",
                  "unlimitedStockQuantity":0,
                  "platformAttributes":[{"propertyId":"prop-1","propertyName":"Size",
                    "valueId":"value-1","valueName":"Small","text":"Small","properties":{"unit":"one"}}]
                }}
                """;

        UpdateXianyuListingRequest request = objectMapper.readValue(body, UpdateXianyuListingRequest.class);
        assertEquals("7eb0fbc8-51cd-42e3-a343-79af60c721d7", request.accountId().toString());
        assertEquals("Test title", request.config().titleOverride());
        assertEquals("Test address check", request.config().addressExpectedText());
        assertEquals("channel-1", request.config().platformChannelCategoryId());
        assertEquals(0, request.config().unlimitedStockQuantity());
        assertEquals("prop-1", request.config().platformAttributes().getFirst().propertyId());

        JsonNode json = objectMapper.valueToTree(request);
        assertTrue(json.has("accountId"));
        assertTrue(json.path("config").has("addressExpectedText"));
        assertTrue(json.path("config").has("platformChannelCategoryId"));
        assertTrue(json.path("config").has("unlimitedStockQuantity"));
        assertFalse(json.path("config").has("address_expected_text"));
    }
}
