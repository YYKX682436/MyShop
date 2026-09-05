package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.entity.PaymentChannel;
import com.myshop.model.response.PublicPaymentChannelResponse;
import com.myshop.repository.PaymentChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/payment-channels")
@RequiredArgsConstructor
public class PaymentChannelController {

    private final PaymentChannelRepository paymentChannelRepository;

    @GetMapping
    public ApiResponse<List<PublicPaymentChannelResponse>> listEnabledChannels() {
        List<PublicPaymentChannelResponse> channels = paymentChannelRepository
                .findByEnabledAndIsDeletedOrderBySortOrderAsc(true, 0)
                .stream()
                // Phase 1 storefront is intentionally limited to the two
                // supported mainland payment types. Future providers can
                // still participate by declaring alipay/wxpay channels.
                .filter(channel -> "alipay".equalsIgnoreCase(channel.getPaymentType())
                        || "wxpay".equalsIgnoreCase(channel.getPaymentType()))
                .map(this::toPublicResponse)
                .toList();
        return ApiResponse.success(channels);
    }

    private PublicPaymentChannelResponse toPublicResponse(PaymentChannel channel) {
        return new PublicPaymentChannelResponse(
                channel.getId(),
                channel.getChannelCode(),
                channel.getChannelName(),
                channel.getPaymentHint(),
                channel.getBadgeText(),
                channel.getPaymentType());
    }
}
