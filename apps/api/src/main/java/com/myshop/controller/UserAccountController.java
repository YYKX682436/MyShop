package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.context.RequestContext;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.payment.PaymentNextAction;
import com.myshop.service.PaymentService;
import com.myshop.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/user/account")
@RequiredArgsConstructor
public class UserAccountController {
    private final UserAccountService accounts;
    private final PaymentService payments;

    public record RechargeRequest(BigDecimal amount, String channelCode, String device) { }
    public record UpgradeRequest(UUID targetLevelId, OrderPaymentMethod paymentMethod,
                                 String channelCode, String device) { }

    @GetMapping("/overview")
    public ApiResponse<?> overview() {
        return ApiResponse.success(accounts.overview(RequestContext.getUserId()));
    }

    @GetMapping("/balance-transactions")
    public ApiResponse<?> balanceTransactions(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(accounts.balanceTransactions(RequestContext.getUserId(), page, pageSize));
    }

    @PostMapping("/recharges")
    public ApiResponse<?> recharge(@RequestBody RechargeRequest request) {
        var start = accounts.beginRecharge(RequestContext.getUserId(), request == null ? null : request.amount(),
                request == null ? null : request.channelCode(), request == null ? null : request.device());
        Map<String, Object> result = new LinkedHashMap<>(payments.dispatchPayment(start.paymentId()));
        result.put("transaction_id", start.transactionId());
        return ApiResponse.success(result);
    }

    @PostMapping("/level-upgrades")
    public ApiResponse<?> upgrade(@RequestBody UpgradeRequest request) {
        var start = accounts.beginUpgrade(RequestContext.getUserId(), request == null ? null : request.targetLevelId(),
                request == null ? null : request.paymentMethod(), request == null ? null : request.channelCode(),
                request == null ? null : request.device());
        if (start.paymentId() == null) {
            return ApiResponse.success(Map.of("transaction_id", start.transactionId(), "status", start.status(),
                    "amount", start.amount(), "account_type", start.type()));
        }
        Map<String, Object> result = new LinkedHashMap<>(payments.dispatchPayment(start.paymentId()));
        result.put("transaction_id", start.transactionId());
        return ApiResponse.success(result);
    }

    @GetMapping("/transactions/{id}")
    public ApiResponse<?> transaction(@PathVariable UUID id) {
        return ApiResponse.success(accounts.transaction(RequestContext.getUserId(), id));
    }

    @PostMapping("/transactions/{id}/refresh")
    public ApiResponse<?> refresh(@PathVariable UUID id) {
        accounts.transaction(RequestContext.getUserId(), id);
        payments.refreshAccountPayment(id);
        return ApiResponse.success(accounts.transaction(RequestContext.getUserId(), id));
    }

    @GetMapping("/price-check")
    public ApiResponse<?> priceCheck(@RequestParam(name = "product_id") UUID productId,
                                     @RequestParam(name = "spec_id", required = false) UUID specId) {
        return ApiResponse.success(accounts.priceCheck(RequestContext.getUserId(), productId, specId));
    }
}
