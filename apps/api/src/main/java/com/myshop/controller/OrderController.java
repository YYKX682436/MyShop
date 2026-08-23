package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.config.ClientIpResolver;
import com.myshop.context.RequestContext;
import com.myshop.service.DeliverService;
import com.myshop.service.OrderService;
import com.myshop.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final DeliverService deliverService;
    private final PaymentService paymentService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping
    public ApiResponse<?> createOrder(@RequestBody Map<String, Object> request,
                                      @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
                                      HttpServletRequest httpRequest) {
        return ApiResponse.success(orderService.createDirectOrder(
                request, RequestContext.getUserId(), clientIpResolver.resolve(httpRequest), sessionToken));
    }

    @PostMapping("/from-cart")
    public ApiResponse<?> createCartOrder(@RequestBody Map<String, Object> request,
                                          @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
                                          HttpServletRequest httpRequest) {
        return ApiResponse.success(orderService.createCartOrder(
                request, RequestContext.getUserId(), clientIpResolver.resolve(httpRequest), sessionToken));
    }

    @GetMapping("/{id}/status")
    public ApiResponse<?> getOrderStatus(@PathVariable UUID id,
                                         @RequestParam(value = "lookup_token", required = false) String lookupToken) {
        return ApiResponse.success(orderService.getOrderStatus(id, lookupToken, RequestContext.getUserId()));
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<?> refreshOrderStatus(@PathVariable UUID id,
                                             @RequestParam(value = "lookup_token", required = false) String lookupToken) {
        return ApiResponse.success(orderService.refreshOrderStatus(id, lookupToken, RequestContext.getUserId()));
    }

    @PostMapping("/query")
    public ApiResponse<?> queryOrders(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(deliverService.queryOrders(request, RequestContext.getUserId()));
    }

    @PostMapping("/deliver")
    public ApiResponse<?> deliverOrders(@RequestBody Map<String, Object> request) {
        return ApiResponse.success(deliverService.deliverOrders(request, RequestContext.getUserId()));
    }

    @GetMapping("/{id}/export")
    public void exportCardKeys(@PathVariable UUID id,
                               @RequestParam(value = "lookup_token", required = false) String lookupToken,
                               HttpServletResponse response) throws Exception {
        String content = deliverService.exportCardKeys(id, lookupToken, RequestContext.getUserId());
        response.setContentType("text/plain; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=card-keys-" + id + ".txt");
        response.getWriter().write(content);
    }

    @PostMapping("/{id}/repay")
    public ApiResponse<?> repayOrder(@PathVariable UUID id,
                                     @RequestBody(required = false) Map<String, String> body) {
        String device = body != null ? body.get("device") : null;
        String lookupToken = body != null ? body.get("lookup_token") : null;
        return ApiResponse.success(paymentService.repay(id, device, RequestContext.getUserId(), lookupToken));
    }
}
