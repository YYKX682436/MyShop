package com.myshop.exception;

import java.util.UUID;

/**
 * Expected input skip for provider orders whose item is only locally linked.
 * This must not be converted into an import failure or an order-domain row.
 */
public class UnmanagedSalesChannelItemException extends RuntimeException {

    private final UUID accountId;
    private final String externalItemId;
    private final String externalOrderNo;

    public UnmanagedSalesChannelItemException(UUID accountId, String externalItemId,
                                               String externalOrderNo) {
        super("The provider item is LINKED_ONLY and is not managed by MyShop");
        this.accountId = accountId;
        this.externalItemId = externalItemId;
        this.externalOrderNo = externalOrderNo;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getExternalItemId() {
        return externalItemId;
    }

    public String getExternalOrderNo() {
        return externalOrderNo;
    }
}
