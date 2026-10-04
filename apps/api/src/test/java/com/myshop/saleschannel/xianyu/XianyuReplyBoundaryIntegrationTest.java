package com.myshop.saleschannel.xianyu;

import com.myshop.config.JwtAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"xianyu.agent.key-id=MYSHOP", "xianyu.agent.secret=boundary-test-secret"})
class XianyuReplyBoundaryIntegrationTest {
    private static final String BODY = """
            {"message_id":"m","account_id":"a","conversation_id":"c","sender_id":"s",
             "sender_name":"S","external_item_id":"i","listing_key":"l","order_id":null,
             "message_type":"TEXT","text":null,"timestamp":"2026-10-02T00:00:00Z"}
            """;
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy security;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    @Test
    void hmacPrecedesJwtAndAuthenticatedResolveRemainsNoReplyIncludingReplay() throws Exception {
        var filters = security.getFilterChains().getFirst().getFilters();
        int hmac = -1, jwt = -1;
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i) instanceof XianyuReplyRequestSignatureFilter) hmac = i;
            if (filters.get(i) instanceof JwtAuthenticationFilter) jwt = i;
        }
        assertTrue(hmac >= 0 && jwt > hmac);
        for (int i = 0; i < 2; i++) {
            mvc.perform(signed(BODY, MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("NO_REPLY"))
                    .andExpect(jsonPath("$.message_id").value("m"))
                    .andExpect(jsonPath("$.text").doesNotExist()).andExpect(jsonPath("$.image").doesNotExist());
        }
    }

    @Test
    void unsignedResolveIsRejectedAndOtherRoutesKeepTheirAuthorization() throws Exception {
        mvc.perform(post("/api/internal/xianyu/reply/resolve").contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(java.net.URI.create("/api/internal/xianyu/reply/%72esolve")).contextPath("/api")
                .with(request -> { request.setServletPath(XianyuReplyRequestSignatureFilter.PATH); return request; })
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/xianyu/reply/resolve").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/operation-logs").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signedInvalidBodiesAndUnsupportedContentTypesDoNotReachResolution() throws Exception {
        mvc.perform(signed(BODY.replace("\"account_id\":\"a\"", "\"account_id\":\"" + "a".repeat(81) + "\""),
                MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
        mvc.perform(signed(BODY.replace("\"order_id\":null,", ""), MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mvc.perform(signed(BODY.replace("\"message_id\":\"m\"", "\"unexpected\":true,\"message_id\":\"m\""), MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mvc.perform(signed(BODY, MediaType.TEXT_PLAIN)).andExpect(status().isUnsupportedMediaType());
    }

    private MockHttpServletRequestBuilder signed(String body, MediaType contentType) {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = "read-only-replay";
        return post("/api/internal/xianyu/reply/resolve").contextPath("/api")
                .contentType(contentType).content(body)
                .header("X-MyShop-Key-Id", "MYSHOP").header("X-MyShop-Timestamp", timestamp)
                .header("X-MyShop-Nonce", nonce)
                .header("X-MyShop-Signature", XianyuRequestSigner.signature("boundary-test-secret",
                        XianyuRequestSigner.canonicalString("POST", XianyuReplyRequestSignatureFilter.PATH,
                                timestamp, nonce, body.getBytes(StandardCharsets.UTF_8))));
    }
}
