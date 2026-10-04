package com.myshop.saleschannel.convergence;

import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.EnsureListingRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.EnsureListingOperationObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.AdoptExistingItemRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.SendDeliveryRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ConfirmDeliveryRequest;

/** Exact S2.1 port to the Agent-owned v2 durable listing command boundary. */
public interface XianyuConvergenceClient {

    EnsureListingOperationObservation ensureListing(EnsureListingRequest request);

    EnsureListingOperationObservation observeOperation(String requestId);

    OperationObservation adoptExistingItem(AdoptExistingItemRequest request);

    OperationObservation sendDelivery(SendDeliveryRequest request);

    OperationObservation confirmDelivery(ConfirmDeliveryRequest request);
}
