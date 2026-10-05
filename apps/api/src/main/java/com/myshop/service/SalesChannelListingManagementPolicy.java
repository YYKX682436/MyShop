package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.entity.SalesChannelListing;
import com.myshop.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * Central policy for the three ownership/control-plane modes.
 *
 * Keep provider-write, order-intake, and auto-fulfillment decisions here so a
 * caller cannot accidentally turn a UI-only LINKED_ONLY guard into a provider
 * side effect when a listing is TAKEOVER_PENDING.
 */
public final class SalesChannelListingManagementPolicy {

    private SalesChannelListingManagementPolicy() { }

    public static boolean providerWritesAllowed(SalesChannelListing listing) {
        return listing != null && providerWritesAllowed(listing.getManagementMode());
    }

    public static boolean providerWritesAllowed(SalesChannelListingManagementMode mode) {
        return mode == SalesChannelListingManagementMode.MANAGED;
    }

    public static boolean autoFulfillmentAllowed(SalesChannelListing listing) {
        return listing != null && autoFulfillmentAllowed(listing.getManagementMode());
    }

    public static boolean autoFulfillmentAllowed(SalesChannelListingManagementMode mode) {
        return mode == SalesChannelListingManagementMode.MANAGED;
    }

    public static boolean orderImportAllowed(SalesChannelListing listing) {
        return listing != null && orderImportAllowed(listing.getManagementMode());
    }

    public static boolean orderImportAllowed(SalesChannelListingManagementMode mode) {
        return mode == SalesChannelListingManagementMode.MANAGED
                || mode == SalesChannelListingManagementMode.TAKEOVER_PENDING;
    }

    public static void requireProviderWritesAllowed(SalesChannelListing listing) {
        if (!providerWritesAllowed(listing)) {
            throw new BusinessException(ErrorCode.LINKED_ITEM_NOT_MANAGED,
                    "该闲鱼商品尚未进入 MyShop 管理，禁止平台写操作", HttpStatus.CONFLICT);
        }
    }
}
