package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Builds provider callback URLs from the configured canonical public site URL.
 * Request Host headers are intentionally never consulted because the API is
 * normally behind a reverse proxy.
 */
@Service
public class PaymentUrlService {

    private final String publicSiteUrl;

    public PaymentUrlService(
            @Value("${app.public-site-url:http://localhost:3000}") String publicSiteUrl) {
        this.publicSiteUrl = normalizePublicSiteUrl(publicSiteUrl);
    }

    public String webhookUrl(String providerId) {
        String provider = requiredPathSegment(providerId, "provider");
        return publicSiteUrl + "/api/payments/webhook/" + provider;
    }

    public String paymentReturnUrl(String orderId) {
        return publicSiteUrl + "/order/" + requiredPathSegment(orderId, "orderId")
                + "?paymentReturn=1";
    }

    public String publicSiteUrl() {
        return publicSiteUrl;
    }

    public static String normalizePublicSiteUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.endsWith("/") && !path.isEmpty()) {
                path = path.substring(0, path.length() - 1);
            }
            String authority = uri.getRawAuthority();
            return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://"
                    + authority + path;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "公开站点根地址必须是合法的 http/https URL");
        }
    }

    private String requiredPathSegment(String value, String field) {
        String text = requiredText(value, field);
        if (text.contains("/") || text.contains("\\") || text.contains("?")
                || text.contains("#") || text.contains("%")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付回跳参数无效: " + field);
        }
        return encodePath(text);
    }

    private String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付回跳参数不能为空: " + field);
        }
        return value.trim();
    }

    private String encodePath(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
