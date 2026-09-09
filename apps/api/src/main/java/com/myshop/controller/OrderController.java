package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.config.ClientIpResolver;
import com.myshop.context.RequestContext;
import com.myshop.model.request.RepayOrderRequest;
import com.myshop.service.OrderAccessService;
import com.myshop.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderAccessService orderAccessService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping
    public ApiResponse<?> createOrder(@RequestBody Map<String, Object> request,
                                      @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
                                      HttpServletRequest httpRequest) {
        return ApiResponse.success(orderService.createDirectOrder(
                request, RequestContext.getUserId(), clientIpResolver.resolve(httpRequest), sessionToken));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> getOrder(@PathVariable UUID id,
                                   @RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
        return ApiResponse.success(orderAccessService.getOrder(id, RequestContext.getUserId(), sessionToken));
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<?> refreshOrder(@PathVariable UUID id,
                                       @RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
        return ApiResponse.success(orderAccessService.refreshOrder(id, RequestContext.getUserId(), sessionToken));
    }

    @GetMapping("/{id}/export")
    public void exportCardKeys(@PathVariable UUID id,
                               @RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
                               HttpServletResponse response) throws Exception {
        String content = orderAccessService.exportCardKeys(id, RequestContext.getUserId(), sessionToken);
        response.setContentType("text/plain; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=card-keys.txt");
        response.getWriter().write(content);
    }

    @PostMapping("/{id}/repay")
    public ApiResponse<?> repayOrder(@PathVariable UUID id,
                                     @RequestBody(required = false) RepayOrderRequest request,
                                     @RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
        return ApiResponse.success(orderAccessService.repayOrder(
                id, request == null ? null : request.device(), RequestContext.getUserId(), sessionToken));
    }
}
