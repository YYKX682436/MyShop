package com.myshop.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentNextActionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void nestedActionsKeepTheirDiscriminatorAtTheUntypedApiResponseBoundary() throws Exception {
        List<PaymentNextAction> actions = List.of(
                new PaymentNextAction.QrCode("weixin://qr/demo"),
                new PaymentNextAction.Redirect("https://pay.example/checkout"),
                new PaymentNextAction.OpenInClient("https://pay.example/open", "wechat"),
                new PaymentNextAction.DeepLink("weixin://pay/demo", "wechat"),
                new PaymentNextAction.FormPost("https://pay.example/submit.php",
                        Map.of("pid", "demo", "sign", "signed")));
        List<String> expectedTypes = List.of("qr_code", "redirect", "open_in_client", "deep_link", "form_post");

        for (int index = 0; index < actions.size(); index++) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("next_action", actions.get(index));

            JsonNode action = objectMapper.valueToTree(response).get("next_action");

            assertEquals(expectedTypes.get(index), action.get("type").asText(), expectedTypes.get(index));
        }
    }
}
