package com.myshop.service;

import com.myshop.constant.SalesChannelListingManagementMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesChannelListingManagementPolicyTest {

    @Test
    void ownershipModesHaveOneCentralizedProviderAndOrderContract() {
        assertTrue(SalesChannelListingManagementPolicy.providerWritesAllowed(
                SalesChannelListingManagementMode.MANAGED));
        assertTrue(SalesChannelListingManagementPolicy.autoFulfillmentAllowed(
                SalesChannelListingManagementMode.MANAGED));
        assertTrue(SalesChannelListingManagementPolicy.orderImportAllowed(
                SalesChannelListingManagementMode.MANAGED));

        for (SalesChannelListingManagementMode mode : new SalesChannelListingManagementMode[] {
                SalesChannelListingManagementMode.LINKED_ONLY,
                SalesChannelListingManagementMode.TAKEOVER_PENDING,
        }) {
            assertFalse(SalesChannelListingManagementPolicy.providerWritesAllowed(mode));
            assertFalse(SalesChannelListingManagementPolicy.autoFulfillmentAllowed(mode));
        }
        assertFalse(SalesChannelListingManagementPolicy.orderImportAllowed(
                SalesChannelListingManagementMode.LINKED_ONLY));
        assertTrue(SalesChannelListingManagementPolicy.orderImportAllowed(
                SalesChannelListingManagementMode.TAKEOVER_PENDING));
    }
}
