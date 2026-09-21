package com.myshop.saleschannel;

/** A local client failure with an explicit retry safety classification. */
public final class SalesChannelProviderClientException extends RuntimeException {

    public enum Disposition {
        SAFE_RETRYABLE,
        SAFE_PERMANENT,
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

    /** Compatibility constructor; new call sites should state retryability explicitly. */
    public SalesChannelProviderClientException(String errorCode, String message) {
        this(Disposition.SAFE_PERMANENT, errorCode, message);
    }

    public Disposition getDisposition() {
        return disposition;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
