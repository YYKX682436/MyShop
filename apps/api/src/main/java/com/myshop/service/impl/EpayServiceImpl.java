package com.myshop.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentNextAction;
import com.myshop.payment.PaymentQueryUnavailableException;
import com.myshop.payment.PaymentStatus;
import com.myshop.service.EpayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Slf4j
@Service
public class EpayServiceImpl implements EpayService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public EpayServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public EpayCreateResult createPayment(ChannelConfig config, String merchantTradeNo, String type,
                                          String name, BigDecimal money, String clientIp, String device,
                                          String notifyUrl, String returnUrl) {
        Map<String, String> fields = signedFields(config, merchantTradeNo, type, name, money,
                clientIp, device, notifyUrl, returnUrl);
        String gateway = normalizeGateway(config.gateway());
        if (isApiEndpoint(gateway)) return postApi(gateway, config, merchantTradeNo, type, fields);
        return new EpayCreateResult(null, new PaymentNextAction.FormPost(submitEndpoint(gateway), fields));
    }

    private Map<String, String> signedFields(ChannelConfig config, String tradeNo, String type, String name,
                                             BigDecimal money, String clientIp, String device,
                                             String notifyUrl, String returnUrl) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", config.pid());
        params.put("type", type);
        params.put("out_trade_no", tradeNo);
        params.put("notify_url", notifyUrl);
        params.put("return_url", returnUrl);
        params.put("name", name);
        params.put("money", money.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        params.put("clientip", clientIp == null ? "127.0.0.1" : clientIp);
        params.put("device", device == null || device.isBlank() ? "pc" : device);
        params.put("sign", buildSign(config.key(), params));
        params.put("sign_type", "MD5");
        return params;
    }

    private EpayCreateResult postApi(String endpoint, ChannelConfig config, String tradeNo, String type,
                                     Map<String, String> fields) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        fields.forEach(form::add);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(endpoint,
                    new HttpEntity<>(form, headers), String.class);
            String raw = response.getBody();
            if (raw == null || raw.isBlank()) throw createFailure("响应为空");
            Map<String, Object> body;
            try {
                body = objectMapper.readValue(raw, new TypeReference<>() { });
            } catch (Exception e) {
                throw createFailure("接口未返回 JSON；支付网关请填写站点根地址或 submit.php");
            }
            if (!isSuccessCode(body.get("code"))) {
                String msg = value(body.get("msg"));
                throw createFailure(msg == null ? "网关返回失败" : msg);
            }
            PaymentNextAction action = nextAction(body, gatewayBaseUrl(config.gateway()), type);
            log.info("Epay API created: merchantTradeNo={}, paymentType={}, endpoint={}, fields=[{}], action={}",
                    tradeNo, type, endpoint, fieldNames(body), action.type());
            return new EpayCreateResult(value(body.get("trade_no")), action);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Epay API create failed: merchantTradeNo={}, errorType={}", tradeNo, e.getClass().getSimpleName());
            throw createFailure("网络请求失败，请重试");
        }
    }

    private PaymentNextAction nextAction(Map<String, Object> body, String base, String paymentType) {
        String scheme = first(body, base, false, "urlscheme");
        if (scheme != null && isAppScheme(scheme)) return new PaymentNextAction.DeepLink(scheme, clientFor(paymentType));
        // qrcode/qr_code are QR payloads, including HTTP URLs that must be encoded locally.
        String qr = first(body, base, false, "qrcode", "qr_code");
        if (qr != null) return new PaymentNextAction.QrCode(qr);
        // payurl/url fields are cashier pages and must always be top-level browser redirects.
        String redirect = first(body, base, true, "payurl", "payurl2", "url", "pay_action_url");
        if (redirect != null && isHttpUrl(redirect)) return new PaymentNextAction.Redirect(redirect);
        throw createFailure("API 未返回可用支付载体");
    }

    private String first(Map<String, Object> body, String base, boolean relative, String... names) {
        for (String name : names) {
            String result = value(body.get(name));
            if (result == null) continue;
            return relative && result.startsWith("/") ? base + result : result;
        }
        return null;
    }

    private BusinessException createFailure(String detail) {
        return new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付创建失败：" + detail);
    }

    @Override
    public String buildSign(String merchantKey, Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        params.forEach((key, value) -> {
            if (!"sign".equals(key) && !"sign_type".equals(key) && value != null && !value.isEmpty()) sorted.put(key, value);
        });
        StringBuilder content = new StringBuilder();
        sorted.forEach((key, value) -> {
            if (!content.isEmpty()) content.append('&');
            content.append(key).append('=').append(value);
        });
        return md5(content.append(merchantKey).toString());
    }

    @Override
    public boolean verifySign(String merchantKey, Map<String, String> params, String sign) {
        return sign != null && !sign.isEmpty() && buildSign(merchantKey, params).equalsIgnoreCase(sign);
    }

    @Override
    public OrderQueryResult queryOrder(ChannelConfig config, String merchantTradeNo) {
        String url = gatewayBaseUrl(config.gateway()) + "/api.php?act=order"
                + "&pid=" + encode(config.pid()) + "&key=" + encode(config.key())
                + "&out_trade_no=" + encode(merchantTradeNo);
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            String raw = response.getBody();
            if (raw == null || raw.isBlank()) return null;
            Map<String, Object> body;
            try {
                body = objectMapper.readValue(raw, new TypeReference<>() { });
            } catch (Exception e) {
                if (isHtml(response, raw)) throw new PaymentQueryUnavailableException("易支付网关未提供可用的支付状态查询接口");
                return null;
            }
            if (body.containsKey("code") && !isSuccessCode(body.get("code"))) return null;
            return new OrderQueryResult(mapStatus(value(body.get("status"))), amount(body.get("money")), value(body.get("trade_no")));
        } catch (PaymentQueryUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Epay query failed: merchantTradeNo={}, errorType={}", merchantTradeNo, e.getClass().getSimpleName());
            return null;
        }
    }

    static String normalizeGateway(String raw) {
        URI uri = parseHttpUrl(raw);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (!path.isEmpty() && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return origin(uri) + path;
    }

    static String gatewayBaseUrl(String raw) {
        String gateway = normalizeGateway(raw);
        String lower = gateway.toLowerCase(Locale.ROOT);
        for (String suffix : List.of("/pay/apisubmit", "/mapi.php", "/submit.php")) {
            if (lower.endsWith(suffix)) return gateway.substring(0, gateway.length() - suffix.length());
        }
        return gateway;
    }

    private static boolean isApiEndpoint(String gateway) {
        String lower = gateway.toLowerCase(Locale.ROOT);
        return lower.endsWith("/mapi.php") || lower.endsWith("/pay/apisubmit");
    }

    private static String submitEndpoint(String gateway) {
        return gateway.toLowerCase(Locale.ROOT).endsWith("/submit.php") ? gateway : gatewayBaseUrl(gateway) + "/submit.php";
    }

    private static URI parseHttpUrl(String raw) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付网关必须是合法的 http/https URL");
        }
    }

    private static String origin(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
    }

    private boolean isHttpUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) { return false; }
    }

    private boolean isAppScheme(String raw) {
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            return scheme != null && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)
                    && !List.of("javascript", "data", "file").contains(scheme.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) { return false; }
    }

    private String clientFor(String type) {
        if ("wxpay".equalsIgnoreCase(type)) return "wechat";
        if ("alipay".equalsIgnoreCase(type)) return "alipay";
        throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "易支付兼容协议不支持支付类型: " + type);
    }

    private boolean isSuccessCode(Object raw) {
        if (raw instanceof Number n) {
            try { return new BigDecimal(n.toString()).compareTo(BigDecimal.ONE) == 0; } catch (NumberFormatException e) { return false; }
        }
        return raw instanceof CharSequence s && "1".equals(s.toString().trim());
    }

    private String value(Object raw) {
        if (raw == null) return null;
        String result = raw.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private String fieldNames(Map<String, Object> body) {
        return String.join(",", body.keySet().stream().filter(Objects::nonNull).map(Object::toString).sorted().toList());
    }

    private PaymentStatus mapStatus(String status) {
        if ("TRADE_SUCCESS".equalsIgnoreCase(status) || "1".equals(status)) return PaymentStatus.PAID;
        if ("TRADE_CLOSED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status) || "4".equals(status)) return PaymentStatus.CLOSED;
        if (status == null || "TRADE_CREATED".equalsIgnoreCase(status) || "WAIT_BUYER_PAY".equalsIgnoreCase(status) || "0".equals(status)) return PaymentStatus.PENDING;
        return PaymentStatus.FAILED;
    }

    private BigDecimal amount(Object raw) {
        String v = value(raw);
        if (v == null) return null;
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return null; }
    }

    private boolean isHtml(ResponseEntity<String> response, String raw) {
        MediaType type = response.getHeaders().getContentType();
        if (type != null && MediaType.TEXT_HTML.isCompatibleWith(type)) return true;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("<!doctype html") || value.startsWith("<html");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
}
