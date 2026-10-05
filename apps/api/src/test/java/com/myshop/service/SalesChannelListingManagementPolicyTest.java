package com.myshop.service;

import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelConvergenceCutoverState;
import com.myshop.entity.SalesChannelListing;
import com.myshop.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void legacyIdentityMutationIsBlockedAfterActivationAndInvalidatesPendingCertification() {
        for (SalesChannelConvergenceCutoverState state : new SalesChannelConvergenceCutoverState[] {
                SalesChannelConvergenceCutoverState.ACTIVE, SalesChannelConvergenceCutoverState.PAUSED,
        }) {
            SalesChannelListing listing = new SalesChannelListing();
            listing.certifyConvergence(UUID.randomUUID());
            listing.activateConvergence(java.time.LocalDateTime.now());
            if (state == SalesChannelConvergenceCutoverState.PAUSED) {
                listing.pauseConvergence(java.time.LocalDateTime.now());
            }
            assertThrows(BusinessException.class,
                    () -> SalesChannelListingManagementPolicy.requireLegacyIdentityMutationAllowed(listing));
            assertEquals(state, listing.getConvergenceCutoverState());
        }

        SalesChannelListing certified = new SalesChannelListing();
        certified.certifyConvergence(UUID.randomUUID());
        SalesChannelListingManagementPolicy.requireLegacyIdentityMutationAllowed(certified);
        assertEquals(SalesChannelConvergenceCutoverState.UNCERTIFIED,
                certified.getConvergenceCutoverState());
        assertNull(certified.getConvergenceCertificationId());
    }
}
