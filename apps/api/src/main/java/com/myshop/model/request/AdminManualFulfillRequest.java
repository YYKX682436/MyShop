package com.myshop.model.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Whole-order manual fulfillment confirmation. The item set is checked again
 * under the order lock so a stale browser cannot partially fulfill an order.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AdminManualFulfillRequest(
        @NotNull(message = "必须明确确认完成发货")
        Boolean confirmed,
        @NotEmpty(message = "至少选择一个待人工发货订单项")
        List<@Valid Item> items
) {

    public static final int MAX_CUSTOMER_CONTENT_LENGTH = 20_000;

    /**
     * Keep operation logs useful without ever copying the customer content
     * into an audit record.
     */
    @JsonIgnore
    public String auditDetail() {
        if (items == null) {
            return "完成手动发货，订单项=0，填写发货内容=0/0";
        }
        String itemIds = items.stream()
                .map(item -> item == null || item.orderItemId() == null
                        ? "null" : item.orderItemId().toString())
                .collect(Collectors.joining(","));
        long contentCount = items.stream()
                .filter(item -> item != null
                        && item.customerDeliveryContent() != null
                        && !item.customerDeliveryContent().isBlank())
                .count();
        return "完成手动发货，订单项=" + itemIds
                + "，填写发货内容=" + contentCount + "/" + items.size();
    }

    public record Item(
            @NotNull(message = "订单项不能为空")
            @JsonProperty("order_item_id")
            UUID orderItemId,
            @Size(max = MAX_CUSTOMER_CONTENT_LENGTH, message = "发货内容不能超过 20000 个字符")
            @JsonProperty("customer_delivery_content")
            String customerDeliveryContent
    ) {
    }
}
