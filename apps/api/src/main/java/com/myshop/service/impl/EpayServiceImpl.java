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
import java.util.ArrayList;
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
                                          String notifyUrl, String returnUrl,
                                          boolean submitFallbackEnabled, String carrierMode) {
        String url = normalizeApiUrl(config.apiUrl());
        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("pid", config.pid());
        params.put("type", type);
        params.put("out_trade_no", merchantTradeNo);
        params.put("notify_url", notifyUrl);
        params.put("return_url", returnUrl);
        params.put("name", name);
        params.put("money", money.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        params.put("clientip", clientIp != null ? clientIp : "127.0.0.1");
        params.put("device", device != null && !device.isBlank() ? device : "pc");

        params.put("sign", buildSign(config.key(), params));
        params.put("sign_type", "MD5");

        log.info("Epay payment create: merchantTradeNo={}, type={}, money={}, apiUrl={}",
                merchantTradeNo, type, money, config.apiUrl());

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        params.forEach(formData::add);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(formData, headers);

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
                    log.error("Epay API response: merchantTradeNo={}, paymentType={}, responseCode=unknown, responseFields=[], selectedCarrier=none",
                            merchantTradeNo, type);
                    throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付创建失败：响应为空");
                }

                Map<String, Object> body;
                try {
                    body = objectMapper.readValue(responseBody, new TypeReference<>() { });
                } catch (Exception parseException) {
                    log.error("Epay API response: merchantTradeNo={}, paymentType={}, responseCode=unknown, responseFields=unavailable, selectedCarrier=none; invalidJson",
                            merchantTradeNo, type);
                    throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付创建失败：响应格式异常");
                }

                Object rawCode = body.get("code");
                String responseCode = responseCodeForLog(rawCode);
                String message = presentValue(body.get("msg"));
                String providerTradeNo = presentValue(body.get("trade_no"));
                String gatewayOrigin = origin(parseHttpUrl(config.apiUrl()));
                String responseFields = responseFieldNames(body);
                CarrierCandidates candidates = extractCarrierCandidates(body, gatewayOrigin);
                Carrier selected = firstAvailableCarrier(candidates);
                log.info("Epay API response: merchantTradeNo={}, paymentType={}, responseCode={}, responseFields=[{}], selectedCarrier={}",
                        merchantTradeNo, type, responseCode, responseFields,
                        selected == null ? "none" : selected.fieldName());

                if (!isSuccessCode(rawCode)) {
                    log.error("Epay API error: merchantTradeNo={}, paymentType={}, responseCode={}, selectedCarrier=none",
                            merchantTradeNo, type, responseCode);
                    throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL,
                            "支付创建失败：" + (message == null ? "" : message));
                }

                PaymentNextAction nextAction = selectNextAction(
                        candidates, type, submitFallbackEnabled, carrierMode,
                        params, config.apiUrl());
                return new EpayCreateResult(providerTradeNo, nextAction);
            } catch (BusinessException exception) {
                throw exception;
            } catch (Exception exception) {
                lastException = exception;
                log.warn("Epay API attempt {} failed: merchantTradeNo={}, paymentType={}, errorType={}",
                        attempt + 1, merchantTradeNo, type, exception.getClass().getSimpleName());
            }
        }

        log.error("Epay API call failed after {} retries: merchantTradeNo={}, paymentType={}, errorType={}",
                maxRetries + 1, merchantTradeNo, type,
                lastException == null ? "unknown" : lastException.getClass().getSimpleName());
        throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付创建失败：网络超时，请重试");
    }

    private PaymentNextAction selectNextAction(CarrierCandidates candidates,
                                                String paymentType, boolean submitFallbackEnabled,
                                                String rawCarrierMode, Map<String, String> signedParams,
                                                String apiUrl) {
        String carrierMode = normalizeCarrierMode(rawCarrierMode);

        return switch (carrierMode) {
            case "auto" -> {
                if (candidates.scheme() != null && isAppScheme(candidates.scheme().value())) {
                    yield new PaymentNextAction.DeepLink(candidates.scheme().value(), clientFor(paymentType));
                }
                if (candidates.qrCode() != null) {
                    yield new PaymentNextAction.QrCode(candidates.qrCode().value());
                }
                Carrier redirect = firstHttpUrlCarrier(candidates.redirectCandidates());
                if (redirect != null) yield new PaymentNextAction.Redirect(redirect.value());
                if (!candidates.redirectCandidates().isEmpty()) throw noCompatibleCarrier();
                yield fallbackAction(submitFallbackEnabled, signedParams,
                        paymentType, carrierMode, apiUrl);
            }
            case "qr_code" -> {
                if (candidates.qrCode() != null) {
                    yield new PaymentNextAction.QrCode(candidates.qrCode().value());
                }
                Carrier fallbackQr = firstCarrier(candidates.qrCodeFallbackCandidates());
                if (fallbackQr != null) yield new PaymentNextAction.QrCode(fallbackQr.value());
                if (candidates.scheme() != null && isAppScheme(candidates.scheme().value())) {
                    yield new PaymentNextAction.QrCode(candidates.scheme().value());
                }
                throw noCompatibleCarrier();
            }
            case "redirect" -> {
                Carrier redirect = firstHttpUrlCarrier(candidates.redirectCandidates());
                if (redirect == null) {
                    if (candidates.hasAnyCarrier()) throw noCompatibleCarrier();
                    yield fallbackAction(submitFallbackEnabled, signedParams,
                            paymentType, carrierMode, apiUrl);
                }
                yield new PaymentNextAction.Redirect(redirect.value());
            }
            case "open_in_client" -> {
                Carrier redirect = firstHttpUrlCarrier(candidates.redirectCandidates());
                if (redirect == null) {
                    if (candidates.hasAnyCarrier()) throw noCompatibleCarrier();
                    yield fallbackAction(submitFallbackEnabled, signedParams,
                            paymentType, carrierMode, apiUrl);
                }
                yield new PaymentNextAction.OpenInClient(redirect.value(), clientFor(paymentType));
            }
            default -> throw noCompatibleCarrier();
        };
    }

    private PaymentNextAction fallbackAction(boolean enabled, Map<String, String> signedParams,
                                             String paymentType,
                                             String carrierMode, String apiUrl) {
        if (!enabled || "qr_code".equals(carrierMode)) {
            throw noCompatibleCarrier();
        }
        String submitUrl = buildSubmitPaymentUrl(apiUrl, signedParams);
        if ("open_in_client".equals(carrierMode)) {
            return new PaymentNextAction.OpenInClient(submitUrl, clientFor(paymentType));
        }
        return new PaymentNextAction.Redirect(submitUrl);
    }

    private BusinessException noCompatibleCarrier() {
        return new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL,
                "支付创建失败：网关未返回与渠道载体模式兼容的载体");
    }

    private CarrierCandidates extractCarrierCandidates(Map<String, Object> body, String gatewayOrigin) {
        return new CarrierCandidates(
                firstCarrier(body, gatewayOrigin, "urlscheme"),
                firstCarrier(body, gatewayOrigin, "qrcode", "qr_code"),
                carriers(body, gatewayOrigin,
                        "payurl", "payurl2", "url", "pay_action_url", "qrcode", "qr_code"),
                carriers(body, gatewayOrigin, "payurl", "payurl2", "url", "pay_action_url"));
    }

    private Carrier firstAvailableCarrier(CarrierCandidates candidates) {
        if (candidates.scheme() != null && isAppScheme(candidates.scheme().value())) {
            return candidates.scheme();
        }
        if (candidates.qrCode() != null) return candidates.qrCode();
        return firstHttpUrlCarrier(candidates.redirectCandidates());
    }

    private Carrier firstCarrier(Map<String, Object> body, String gatewayOrigin, String... fieldNames) {
        return firstCarrier(carriers(body, gatewayOrigin, fieldNames));
    }

    private Carrier firstCarrier(List<Carrier> carriers) {
        return carriers.isEmpty() ? null : carriers.get(0);
    }

    private List<Carrier> carriers(Map<String, Object> body, String gatewayOrigin, String... fieldNames) {
        List<Carrier> carriers = new ArrayList<>();
        for (String fieldName : fieldNames) {
            String value = normalizeResponseField(fieldName, body.get(fieldName), gatewayOrigin);
            if (value != null) carriers.add(new Carrier(fieldName, value));
        }
        return List.copyOf(carriers);
    }

    private Carrier firstHttpUrlCarrier(List<Carrier> candidates) {
        return candidates.stream()
                .filter(candidate -> isHttpUrl(candidate.value()))
                .findFirst()
                .orElse(null);
    }

    private String normalizeResponseField(String fieldName, Object rawValue, String gatewayOrigin) {
        String value = presentValue(rawValue);
        if (value == null) return null;
        if (isRelativeUrlField(fieldName) && value.startsWith("/")) return gatewayOrigin + value;
        return value;
    }

    private boolean isRelativeUrlField(String fieldName) {
        return switch (fieldName) {
            case "payurl", "payurl2", "url", "pay_action_url", "qrcode", "qr_code" -> true;
            default -> false;
        };
    }

    private boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isAppScheme(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return scheme != null && !"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme) && uri.getSchemeSpecificPart() != null
                    && !uri.getSchemeSpecificPart().isBlank()
                    && !List.of("javascript", "data", "file").contains(scheme.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String clientFor(String paymentType) {
        if ("wxpay".equalsIgnoreCase(paymentType)) return "wechat";
        if ("alipay".equalsIgnoreCase(paymentType)) return "alipay";
        throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                "易支付兼容协议不支持支付类型: " + paymentType);
    }

    private String normalizeCarrierMode(String raw) {
        String mode = raw == null || raw.isBlank() ? "auto" : raw.trim().toLowerCase(Locale.ROOT);
        if (List.of("auto", "qr_code", "redirect", "open_in_client").contains(mode)) return mode;
        throw new BusinessException(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付创建失败：渠道载体模式无效");
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
        return rawCode instanceof CharSequence sequence && "1".equals(sequence.toString().trim());
    }

    private String responseCodeForLog(Object rawCode) {
        String value = presentValue(rawCode);
        return value == null ? "null" : value;
    }

    private String responseFieldNames(Map<String, Object> body) {
        return String.join(",", body.keySet().stream()
                .filter(Objects::nonNull).map(Object::toString).sorted().toList());
    }

    /** The submit page receives the same signed request, without the merchant secret. */
    private String buildSubmitPaymentUrl(String apiUrl, Map<String, String> params) {
        String endpoint = gatewayBaseUrl(apiUrl) + "/submit.php";
        List<String> encoded = new ArrayList<>();
        params.forEach((key, value) -> {
            if (value != null && !value.isBlank() && !"api_url".equals(key)) {
                encoded.add(encodeQueryParam(key) + "=" + encodeQueryParam(value));
            }
        });
        return endpoint + (encoded.isEmpty() ? "" : "?" + String.join("&", encoded));
    }

    private record Carrier(String fieldName, String value) { }

    private record CarrierCandidates(Carrier scheme, Carrier qrCode,
                                     List<Carrier> redirectCandidates,
                                     List<Carrier> qrCodeFallbackCandidates) {
        private CarrierCandidates {
            redirectCandidates = List.copyOf(redirectCandidates);
            qrCodeFallbackCandidates = List.copyOf(qrCodeFallbackCandidates);
        }

        private boolean hasAnyCarrier() {
            return scheme != null || qrCode != null || !redirectCandidates.isEmpty();
        }
    }

    @Override
    public String buildSign(String merchantKey, Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("sign".equals(key) || "sign_type".equals(key)) continue;
            if (value == null || value.isEmpty()) continue;
            sorted.put(key, value);
        }

        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!content.isEmpty()) content.append('&');
            content.append(entry.getKey()).append('=').append(entry.getValue());
        }
        content.append(merchantKey);
        return md5(content.toString());
    }

    @Override
    public OrderQueryResult queryOrder(ChannelConfig config, String merchantTradeNo) {
        String url = gatewayBaseUrl(config.apiUrl()) + "/api.php?act=order"
                + "&pid=" + encodeQueryParam(config.pid())
                + "&key=" + encodeQueryParam(config.key())
                + "&out_trade_no=" + encodeQueryParam(merchantTradeNo);
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                log.warn("Epay order query returned empty body: merchantTradeNo={}", merchantTradeNo);
                return null;
            }
            Map<String, Object> result;
            try {
                result = objectMapper.readValue(body, new TypeReference<>() { });
            } catch (Exception parseException) {
                if (isHtmlResponse(response, body)) {
                    log.warn("Epay order query endpoint unavailable: merchantTradeNo={}, responseContentType={}",
                            merchantTradeNo, response.getHeaders().getContentType());
                    throw new PaymentQueryUnavailableException("易支付网关未提供可用的订单查询接口");
                }
                log.warn("Epay order query returned an invalid response: merchantTradeNo={}, errorType={}",
                        merchantTradeNo, parseException.getClass().getSimpleName());
                return null;
            }

            if (result.containsKey("code") && !isSuccessCode(result.get("code"))) {
                log.warn("Epay order query error: merchantTradeNo={}, responseCode={}",
                        merchantTradeNo, responseCodeForLog(result.get("code")));
                return null;
            }
            String rawStatus = result.get("status") == null ? null : result.get("status").toString();
            BigDecimal amount = parseAmount(result.get("money"));
            String providerTradeNo = result.get("trade_no") == null
                    ? null : result.get("trade_no").toString();
            return new OrderQueryResult(mapStatus(rawStatus), amount, presentValue(providerTradeNo));
        } catch (PaymentQueryUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Epay order query failed: merchantTradeNo={}, errorType={}",
                    merchantTradeNo, exception.getClass().getSimpleName());
            return null;
        }
    }

    private PaymentStatus mapStatus(String rawStatus) {
        String status = presentValue(rawStatus);
        if ("TRADE_SUCCESS".equalsIgnoreCase(status) || "1".equals(status)) {
            return PaymentStatus.PAID;
        }
        if ("TRADE_CLOSED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status)
                || "4".equals(status)) {
            return PaymentStatus.CLOSED;
        }
        if (status == null || "TRADE_CREATED".equalsIgnoreCase(status)
                || "WAIT_BUYER_PAY".equalsIgnoreCase(status) || "0".equals(status)) {
            return PaymentStatus.PENDING;
        }
        return PaymentStatus.FAILED;
    }

    private BigDecimal parseAmount(Object rawAmount) {
        String value = presentValue(rawAmount);
        if (value == null) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
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
        return buildSign(merchantKey, params).equalsIgnoreCase(sign);
    }

    public static String normalizeApiUrl(String raw) {
        URI uri = parseHttpUrl(raw);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/") && !path.isEmpty()) path = path.substring(0, path.length() - 1);
        String lowerPath = path.toLowerCase(Locale.ROOT);
        int mapiMarker = lowerPath.indexOf("/mapi.php");
        if (mapiMarker >= 0 && (lowerPath.length() == mapiMarker + "/mapi.php".length()
                || lowerPath.charAt(mapiMarker + "/mapi.php".length()) == '/')) {
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
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
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
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new RuntimeException("MD5 algorithm not available", exception);
        }
    }
}
