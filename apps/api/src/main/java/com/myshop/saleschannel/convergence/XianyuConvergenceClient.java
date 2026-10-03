package com.myshop.saleschannel.convergence;

import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.AdoptExistingItemRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ConfirmDeliveryRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.EnsureListingRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.SendDeliveryRequest;

/**
 * Target Agent port. Deliberately has no implementation until the Agent v2
 * ledger and ownership bindings are deployed and proven in staging.
 */
public interface XianyuConvergenceClient {

    OperationObservation ensureListing(EnsureListingRequest request);

    OperationObservation observeOperation(String requestId);

    OperationObservation adoptExistingItem(AdoptExistingItemRequest request);

    OperationObservation sendDelivery(SendDeliveryRequest request);

    OperationObservation confirmDelivery(ConfirmDeliveryRequest request);
}
