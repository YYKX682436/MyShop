package com.myshop.exception;

import lombok.Getter;

/** A safe, user-visible failure for the exact sales-channel specification gate. */
@Getter
public final class SalesChannelSpecMappingException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public SalesChannelSpecMappingException(String code, String message) {
        this(code, message, false);
    }

    public SalesChannelSpecMappingException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }
}
