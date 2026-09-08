package com.myshop.controller;

import com.myshop.payment.PaymentProvider;
import com.myshop.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final WebhookService webhookService;

    @RequestMapping(value = "/{providerId}", method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> handleWebhook(@PathVariable String providerId,
                                                HttpServletRequest request) throws IOException {
        PaymentProvider.WebhookAcknowledgement acknowledgement = webhookService.handleWebhook(
                providerId, buildWebhookRequest(request));
        MediaType contentType = MediaType.parseMediaType(acknowledgement.contentType());
        return ResponseEntity.status(acknowledgement.statusCode())
                .contentType(contentType)
                .body(acknowledgement.body());
    }

    private PaymentProvider.WebhookRequest buildWebhookRequest(HttpServletRequest request)
            throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, request.getHeader(name));
            }
        }

        Map<String, List<String>> queryParams = parsePairs(request.getQueryString());
        String rawBody = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, List<String>> formParams = isFormEncoded(request)
                ? parsePairs(rawBody) : Map.of();
        return new PaymentProvider.WebhookRequest(
                request.getMethod(), headers, queryParams, formParams, rawBody);
    }

    private boolean isFormEncoded(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase(java.util.Locale.ROOT)
                .startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
    }

    private Map<String, List<String>> parsePairs(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) continue;
            int separator = pair.indexOf('=');
            String rawKey = separator < 0 ? pair : pair.substring(0, separator);
            String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
            String key = decode(rawKey);
            String value = decode(rawValue);
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
