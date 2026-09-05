package com.myshop.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import com.myshop.payment.PaymentQueryUnavailableException;
import com.myshop.service.EpayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Slf4j
@Service
public class EpayServiceImpl implements EpayService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final boolean pageRedirectFallbackEnabled;

    @Autowired
    public EpayServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper,
                           @Value("${app.epay.page-redirect-fallback-enabled:false}")
                           boolean pageRedirectFallbackEnabled) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.pageRedirectFallbackEnabled = pageRedirectFallbackEnabled;
    }

    /** Test-friendly constructor keeps the fail-closed default. */
    public EpayServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this(restTemplate, objectMapper, false);
    }

    @Override
    public EpayResult createPayment(ChannelConfig config, String outTradeNo, String type, String name,
                                    BigDecimal money, String clientIp, String device,
                                    String notifyUrl, String returnUrl) {
        String url = normalizeApiUrl(config.apiUrl());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", config.pid());
        params.put("type", type);
        params.put("out_trade_no", outTradeNo);
        params.put("notify_url", notifyUrl);
        params.put("return_url", returnUrl);
        params.put("name", name);
        params.put("money", money.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        params.put("clientip", clientIp != null ? clientIp : "127.0.0.1");
        params.put("device", device != null && !device.isBlank() ? device : "pc");

        String sign = buildSign(config.key(), params);
        params.put("sign", sign);
        params.put("sign_type", "MD5");

        log.info("Epay createPayment: outTradeNo={}, type={}, money={}, apiUrl={}", outTradeNo, type, money, config.apiUrl());

        // Build form-urlencoded body
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        params.forEach(formData::add);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(formData, headers);

        // 带重试的网络调用（最多重试 2 次，间隔 1 秒）
        int maxRetries = 2;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    log.info("Epay API retry attempt {}/{}", attempt, maxRetries);
                    Thread.sleep(1000);
                }

                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
                String responseBody = response.getBody();

                if (responseBody == null || responseBody.isBlank()) {
                    log.error("Epay API response: orderId={}, paymentType={}, responseCode=unknown, responseFields=[], selectedCarrier=none",
                            outTradeNo, type);
                    throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付创建失败：响应为空");
                }

                Map<String, Object> body;
                try {
                    body = objectMapper.readValue(responseBody, new TypeReference<>() {});
                } catch (Exception parseEx) {
                    log.error("Epay API response: orderId={}, paymentType={}, responseCode=unknown, responseFields=unavailable, selectedCarrier=none; invalidJson",
                            outTradeNo, type);
                    throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付创建失败：响应格式异常");
                }

                Object rawCode = body.get("code");
                int code = isSuccessCode(rawCode) ? 1 : -1;
                String responseCode = responseCodeForLog(rawCode);
                String msg = presentValue(body.get("msg"));
                String tradeNo = presentValue(body.get("trade_no"));
                if (msg == null) msg = "";
                if (tradeNo == null) tradeNo = "";

                String gatewayOrigin = origin(parseHttpUrl(config.apiUrl()));
                Carrier qrcodeCarrier = firstCarrier(body, gatewayOrigin,
                        "qrcode", "qr_code", "urlscheme");
                Carrier payUrlCarrier = firstCarrier(body, gatewayOrigin,
                        "payurl", "payurl2", "url", "pay_action_url");
                String responseFields = responseFieldNames(body);
                String selectedCarrier = selectedCarrier(qrcodeCarrier, payUrlCarrier);
                log.info("Epay API response: orderId={}, paymentType={}, responseCode={}, responseFields=[{}], selectedCarrier={}",
                        outTradeNo, type, responseCode, responseFields, selectedCarrier);

                if (!isSuccessCode(rawCode)) {
                    // 业务错误不重试
                    log.error("Epay API error: orderId={}, paymentType={}, responseCode={}, selectedCarrier=none",
                            outTradeNo, type, responseCode);
                    throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付创建失败：" + msg);
                }

                String resultQrcode = qrcodeCarrier == null ? null : qrcodeCarrier.value();
                String payUrl = payUrlCarrier == null ? null : payUrlCarrier.value();

                if (payUrl == null && resultQrcode == null) {
                    if (pageRedirectFallbackEnabled && tradeNo != null && !tradeNo.isBlank()) {
                        String submitUrl = buildSubmitPaymentUrl(config.apiUrl(), params);
                        log.info("Epay API page redirect fallback: orderId={}, paymentType={}, "
                                        + "responseCode={}, selectedCarrier=submit.php",
                                outTradeNo, type, responseCode);
                        return new EpayResult(code, msg, tradeNo, submitUrl, null);
                    }
                    log.error("Epay API error: orderId={}, paymentType={}, responseCode={}, selectedCarrier=none; noPaymentCarrier",
                            outTradeNo, type, responseCode);
                    throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL,
                            "支付创建失败：网关未返回支付链接或二维码");
                }

                // 网关未返回 payUrl 且为移动端请求时，将 qrcode（收银台页面 URL）作为 H5 跳转入口
                String effectivePayUrl = payUrl;
                if (effectivePayUrl == null && device != null && !"pc".equals(device) && resultQrcode != null) {
                    effectivePayUrl = resultQrcode;
                    log.info("Epay mobile fallback: orderId={}, paymentType={}, selectedCarrier={}",
                            outTradeNo, type, qrcodeCarrier.fieldName());
                }

                return new EpayResult(code, msg, tradeNo, effectivePayUrl, resultQrcode);

            } catch (BusinessException e) {
                throw e; // 业务异常直接抛出，不重试
            } catch (Exception e) {
                lastException = e;
                log.warn("Epay API attempt {} failed: orderId={}, paymentType={}, errorType={}",
                        attempt + 1, outTradeNo, type, e.getClass().getSimpleName());
            }
        }

        log.error("Epay API call failed after {} retries: orderId={}, paymentType={}, errorType={}",
                maxRetries + 1, outTradeNo, type,
                lastException == null ? "unknown" : lastException.getClass().getSimpleName());
        throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付创建失败：网络超时，请重试");
    }

    private Carrier firstCarrier(Map<String, Object> body, String gatewayOrigin, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = normalizeResponseField(fieldName, body.get(fieldName), gatewayOrigin);
            if (value != null) return new Carrier(fieldName, value);
        }
        return null;
    }

    private String normalizeResponseField(String fieldName, Object rawValue, String gatewayOrigin) {
        String value = presentValue(rawValue);
        if (value == null) return null;
        if (isRelativeUrlField(fieldName) && value.startsWith("/")) {
            return gatewayOrigin + value;
        }
        return value;
    }

    private boolean isRelativeUrlField(String fieldName) {
        return switch (fieldName) {
            case "payurl", "payurl2", "url", "pay_action_url", "qrcode", "qr_code" -> true;
            default -> false;
        };
    }

    private String presentValue(Object rawValue) {
        if (rawValue == null) return null;
        String value = rawValue.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private boolean isSuccessCode(Object rawCode) {
        if (rawCode instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).compareTo(BigDecimal.ONE) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return rawCode instanceof CharSequence sequence
                && "1".equals(sequence.toString().trim());
    }

    private String responseCodeForLog(Object rawCode) {
        String value = presentValue(rawCode);
        return value == null ? "null" : value;
    }

    private String responseFieldNames(Map<String, Object> body) {
        List<String> fields = body.keySet().stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .sorted()
                .toList();
        return String.join(",", fields);
    }

    private String selectedCarrier(Carrier qrcodeCarrier, Carrier payUrlCarrier) {
        List<String> selected = new ArrayList<>();
        if (qrcodeCarrier != null) selected.add(qrcodeCarrier.fieldName());
        if (payUrlCarrier != null) selected.add(payUrlCarrier.fieldName());
        return selected.isEmpty() ? "none" : String.join(",", selected);
    }

    /**
     * Some Epay-compatible gateways create the order through mapi.php but do
     * not return a payurl/qrcode. When that behavior has been explicitly
     * enabled for a verified gateway, the signed submit.php page is the
     * provider's payment carrier. The merchant key is deliberately not part
     * of params and therefore can never enter this URL.
     */
    private String buildSubmitPaymentUrl(String rawApiUrl, Map<String, String> params) {
        String endpoint = gatewayBaseUrl(rawApiUrl) + "/submit.php";
        String query = params.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(entry -> encodeQueryParam(entry.getKey()) + "=" + encodeQueryParam(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        return endpoint + (query.isEmpty() ? "" : "?" + query);
    }

    private record Carrier(String fieldName, String value) { }

    @Override
    public String buildSign(String merchantKey, Map<String, String> params) {
        // 1. Filter out sign, sign_type, and empty values
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("sign".equals(key) || "sign_type".equals(key)) continue;
            if (value == null || value.isEmpty()) continue;
            sorted.put(key, value);
        }

        // 2. Concatenate sorted params: a=b&c=d&e=f
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }

        // 3. Append merchant key directly
        sb.append(merchantKey);

        // 4. MD5 hash
        return md5(sb.toString());
    }

    @Override
    public OrderQueryResult queryOrder(ChannelConfig config, String outTradeNo) {
        String url = gatewayBaseUrl(config.apiUrl()) + "/api.php?act=order"
                + "&pid=" + encodeQueryParam(config.pid())
                + "&key=" + encodeQueryParam(config.key())
                + "&out_trade_no=" + encodeQueryParam(outTradeNo);

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                log.warn("Epay order query returned empty body: outTradeNo={}", outTradeNo);
                return null;
            }
            Map<String, Object> result;
            try {
                result = objectMapper.readValue(body, new TypeReference<>() {});
            } catch (Exception parseException) {
                if (isHtmlResponse(response, body)) {
                    log.warn("Epay order query endpoint unavailable: outTradeNo={}, responseContentType={}",
                            outTradeNo, response.getHeaders().getContentType());
                    throw new PaymentQueryUnavailableException("易支付网关未提供可用的订单查询接口");
                }
                log.warn("Epay order query returned an invalid response: outTradeNo={}, errorType={}",
                        outTradeNo, parseException.getClass().getSimpleName());
                return null;
            }

            // 网关返回错误码时（如订单不存在），返回 null
            if (result.containsKey("code") && !isSuccessCode(result.get("code"))) {
                log.warn("Epay order query error: outTradeNo={}, responseCode={}",
                        outTradeNo, responseCodeForLog(result.get("code")));
                return null;
            }

            String tradeStatus = result.get("status") != null ? result.get("status").toString() : null;
            String money = result.get("money") != null ? result.get("money").toString() : null;
            String tradeNo = result.get("trade_no") != null ? result.get("trade_no").toString() : null;
            return new OrderQueryResult(tradeStatus, money, tradeNo);
        } catch (PaymentQueryUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Epay order query failed: outTradeNo={}, errorType={}",
                    outTradeNo, e.getClass().getSimpleName());
            return null;
        }
    }

    private boolean isHtmlResponse(ResponseEntity<String> response, String body) {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && MediaType.TEXT_HTML.isCompatibleWith(contentType)) return true;
        String value = body == null ? "" : body.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("<!doctype html") || value.startsWith("<html");
    }

    @Override
    public boolean verifySign(String merchantKey, Map<String, String> params, String sign) {
        if (sign == null || sign.isEmpty()) return false;
        String expected = buildSign(merchantKey, params);
        return expected.equalsIgnoreCase(sign);
    }

    /**
     * Accept either a gateway root or the complete mapi.php endpoint and
     * produce exactly one canonical mapi.php URL.
     */
    public static String normalizeApiUrl(String raw) {
        URI uri = parseHttpUrl(raw);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/") && !path.isEmpty()) {
            path = path.substring(0, path.length() - 1);
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        int mapiMarker = lowerPath.indexOf("/mapi.php");
        if (mapiMarker >= 0
                && (lowerPath.length() == mapiMarker + "/mapi.php".length()
                || lowerPath.charAt(mapiMarker + "/mapi.php".length()) == '/')) {
            // Truncate a repeated or suffixed endpoint to its first mapi.php
            // segment so the request URL can never contain mapi.php twice.
            path = path.substring(0, mapiMarker) + "/mapi.php";
        } else {
            path = (path.isEmpty() ? "" : path) + "/mapi.php";
        }
        return origin(uri) + path;
    }

    private static String gatewayBaseUrl(String raw) {
        String mapiUrl = normalizeApiUrl(raw);
        return mapiUrl.substring(0, mapiUrl.length() - "/mapi.php".length());
    }

    private static URI parseHttpUrl(String raw) {
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
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "易支付接口地址必须是合法的 http/https URL");
        }
    }

    private static String origin(URI uri) {
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
    }

    private static String encodeQueryParam(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
}
