package com.myshop.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Test-only provider proving admin validation is not coupled to Epay. */
public final class FakePaymentProvider implements PaymentProvider {

    @Override public String id() { return "fakepay"; }
    @Override public String displayName() { return "Fake Pay (tests only)"; }
    @Override public List<String> paymentTypes() { return List.of("alipay"); }
    @Override public List<ConfigField> configSchema() {
        return List.of(
                new ConfigField("merchant", "Merchant", "text", false, true,
                        "merchant-id", null, List.of()),
                new ConfigField("timeout", "Timeout", "number", false, true,
                        "30", 30, List.of()),
                new ConfigField("sandbox", "Sandbox", "switch", false, true,
                        null, false, List.of()),
                new ConfigField("mode", "Mode", "select", false, true,
                        null, null, List.of(new ConfigOption("fast", "Fast"), new ConfigOption("safe", "Safe")))
        );
    }
    @Override public PaymentResult create(PaymentRequest request) {
        return new PaymentResult("fake-trade", "https://fake.example/pay", null);
    }
}
