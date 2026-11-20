package com.myshop.supplier;

import com.myshop.constant.ErrorCode;
import com.myshop.exception.BusinessException;
import java.util.Map;

/** Messages never include remote bodies, credentials or request headers. */
public class SupplierException extends BusinessException {
    private final String kind;
    public SupplierException(String kind, String message) {
        super(ErrorCode.BAD_REQUEST, message, Map.of("supplier_error", kind));
        this.kind = kind;
    }
    public String kind() { return kind; }
}
