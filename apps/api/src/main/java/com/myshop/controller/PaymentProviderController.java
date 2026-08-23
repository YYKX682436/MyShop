package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.payment.PaymentProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/payment-providers")
@RequiredArgsConstructor
public class PaymentProviderController {

    private final List<PaymentProvider> providers;

    @GetMapping
    public ApiResponse<?> listProviders() {
        return ApiResponse.success(providers.stream().map(provider -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider_id", provider.id());
            result.put("display_name", provider.displayName());
            result.put("payment_types", provider.paymentTypes());
            result.put("config_schema", provider.configSchema());
            return result;
        }).toList());
    }
}
