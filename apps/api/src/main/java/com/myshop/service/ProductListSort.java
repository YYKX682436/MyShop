package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The public/admin product list exposes a fixed sort vocabulary. Keeping the
 * mapping here prevents request parameters from becoming arbitrary JPA paths.
 */
public final class ProductListSort {

    private ProductListSort() {
    }

    public static Sort resolve(String sortBy, String sortDirection) {
        String requestedBy = sortBy == null || sortBy.isBlank()
                ? "sort_weight" : sortBy.trim().toLowerCase(Locale.ROOT);
        String property = switch (requestedBy) {
            case "sort_weight" -> "sortWeight";
            case "created_at" -> "createdAt";
            case "product_no" -> "productNo";
            case "retail_price" -> "retailPrice";
            case "cost_price" -> "costPrice";
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的商品排序字段");
        };

        String requestedDirection = sortDirection == null || sortDirection.isBlank()
                ? "desc" : sortDirection.trim().toLowerCase(Locale.ROOT);
        Sort.Direction direction = switch (requestedDirection) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的商品排序方向");
        };

        List<Sort.Order> orders = new ArrayList<>();
        orders.add(new Sort.Order(direction, property));
        if (!"createdAt".equals(property)) {
            orders.add(Sort.Order.desc("createdAt"));
        }
        if (!"id".equals(property)) {
            orders.add(Sort.Order.asc("id"));
        }
        return Sort.by(orders);
    }
}
