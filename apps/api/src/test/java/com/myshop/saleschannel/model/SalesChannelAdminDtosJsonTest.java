package com.myshop.saleschannel.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.AccountSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.ListingSummary;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuQrLoginStartResponse;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.XianyuQrLoginStatusResponse;
import com.myshop.saleschannel.model.SalesChannelAdminDtos.UpdateXianyuListingRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesChannelAdminDtosJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void accountSummaryUsesTheCamelCaseContractExpectedByTheAdminUi() {
        AccountSummary summary = new AccountSummary("local-id", "provider-id", "Test shop",
                "FISH_SHOP", "CONNECTED", true, false, Map.of("account_type", "FISH_SHOP"),
                LocalDateTime.of(2026, 9, 21, 12, 0), null);

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
                "FISH_SHOP", false, false, "PENDING", "item-1", 3L,
                LocalDateTime.of(2026, 9, 21, 12, 0), "", "", null, java.util.List.of());

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
    void xianyuQrLoginResponsesUseCamelCaseForTheBrowserContract() {
        JsonNode start = objectMapper.valueToTree(new XianyuQrLoginStartResponse(
                "session-1", "data:image/png;base64,AA==", 300));
        assertEquals("session-1", start.path("loginSessionId").asText());
        assertEquals("data:image/png;base64,AA==", start.path("qrCodeDataUrl").asText());
        assertEquals(300, start.path("expiresInSeconds").asInt());
        assertFalse(start.has("login_session_id"));

        JsonNode status = objectMapper.valueToTree(new XianyuQrLoginStatusResponse(
                "SUCCESS", "account-1", false, null));
        assertEquals("account-1", status.path("accountId").asText());
        assertFalse(status.path("isNewAccount").asBoolean());
        assertTrue(status.has("verificationMessage"));
        assertFalse(status.has("verification_message"));
    }

    @Test
    void xianyuConfigAcceptsAndReturnsTheCamelCaseAdminApiContract() throws Exception {
        String body = """
                {"accountId":"7eb0fbc8-51cd-42e3-a343-79af60c721d7","config":{
                  "titleOverride":"Test title",
                  "addressExpectedText":"Test address check",
                  "platformChannelCategoryId":"channel-1",
                  "unlimitedStockQuantity":0,
                  "stockCapPerSku":0,
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
        assertEquals(0, request.config().stockCapPerSku());
        assertEquals("prop-1", request.config().platformAttributes().getFirst().propertyId());

        JsonNode json = objectMapper.valueToTree(request);
        assertTrue(json.has("accountId"));
        assertTrue(json.path("config").has("addressExpectedText"));
        assertTrue(json.path("config").has("platformChannelCategoryId"));
        assertTrue(json.path("config").has("unlimitedStockQuantity"));
        assertTrue(json.path("config").has("stockCapPerSku"));
        assertFalse(json.path("config").has("address_expected_text"));
    }

    @Test
    void stockCapPerSkuAllowsNullAndZeroButRejectsValuesOutsideTheSupportedRange() {
        var noCap = objectMapper.convertValue(Map.of(),
                com.myshop.saleschannel.xianyu.model.XianyuProviderConfig.class);
        assertNull(noCap.stockCapPerSku());
        noCap.validateForSave();

        var zeroCap = objectMapper.convertValue(Map.of("stockCapPerSku", 0),
                com.myshop.saleschannel.xianyu.model.XianyuProviderConfig.class);
        assertEquals(0, zeroCap.stockCapPerSku());
        zeroCap.validateForSave();

        var maxCap = objectMapper.convertValue(Map.of("stockCapPerSku", 999999),
                com.myshop.saleschannel.xianyu.model.XianyuProviderConfig.class);
        assertEquals(999999, maxCap.stockCapPerSku());
        maxCap.validateForSave();

        var negativeCap = objectMapper.convertValue(Map.of("stockCapPerSku", -1),
                com.myshop.saleschannel.xianyu.model.XianyuProviderConfig.class);
        assertThrows(IllegalArgumentException.class, negativeCap::validateForSave);

        var excessiveCap = objectMapper.convertValue(Map.of("stockCapPerSku", 1000000),
                com.myshop.saleschannel.xianyu.model.XianyuProviderConfig.class);
        assertThrows(IllegalArgumentException.class, excessiveCap::validateForSave);
    }
}
