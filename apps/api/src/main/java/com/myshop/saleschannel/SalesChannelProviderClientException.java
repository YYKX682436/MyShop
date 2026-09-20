package com.myshop.saleschannel;

/** A local client failure with an explicit retry safety classification. */
public final class SalesChannelProviderClientException extends RuntimeException {

    public enum Disposition {
        SAFE_FAILURE,
        UNKNOWN
    }

    private final Disposition disposition;
    private final String errorCode;

    public SalesChannelProviderClientException(Disposition disposition, String errorCode, String message) {
        super(message);
        this.disposition = disposition;
        this.errorCode = errorCode;
    }

    public SalesChannelProviderClientException(Disposition disposition, String errorCode, String message,
                                               Throwable cause) {
        super(message, cause);
        this.disposition = disposition;
        this.errorCode = errorCode;
    }

    public Disposition getDisposition() {
        return disposition;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
