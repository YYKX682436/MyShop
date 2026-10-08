package com.myshop.saleschannel.convergence;

import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.EnsureListingRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.OperationObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.EnsureListingOperationObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.AdoptExistingItemRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.SendDeliveryRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ConfirmDeliveryRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverReadiness;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverInspection;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.CutoverInspectionRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryValidationRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryValidationResponse;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPrepareRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryArmRequest;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPermitObservation;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.RecoveryPermitProof;

/** Exact S2.1 port to the Agent-owned v2 durable listing command boundary. */
public interface XianyuConvergenceClient {

    EnsureListingOperationObservation ensureListing(EnsureListingRequest request);

    default EnsureListingOperationObservation ensureListing(EnsureListingRequest request,
                                                            RecoveryPermitProof recoveryProof) {
        if (recoveryProof == null) return ensureListing(request);
        throw new UnsupportedOperationException("Scoped recovery is not implemented by this client");
    }

    EnsureListingOperationObservation observeOperation(String requestId);

    OperationObservation adoptExistingItem(AdoptExistingItemRequest request);

    OperationObservation sendDelivery(SendDeliveryRequest request);

    OperationObservation confirmDelivery(ConfirmDeliveryRequest request);

    CutoverReadiness agentReadiness();

    CutoverInspection inspectListing(CutoverInspectionRequest request);

    default RecoveryValidationResponse validateRecovery(RecoveryValidationRequest request) {
        throw new UnsupportedOperationException("Scoped recovery is not implemented by this client");
    }

    default RecoveryPermitObservation prepareRecovery(RecoveryPrepareRequest request) {
        throw new UnsupportedOperationException("Scoped recovery is not implemented by this client");
    }

    default RecoveryPermitObservation armRecovery(RecoveryArmRequest request) {
        throw new UnsupportedOperationException("Scoped recovery is not implemented by this client");
    }

    default RecoveryPermitObservation activateRecovery(RecoveryArmRequest request) {
        throw new UnsupportedOperationException("Scoped recovery is not implemented by this client");
    }

    default RecoveryPermitObservation cancelRecovery(RecoveryArmRequest request) {
        throw new UnsupportedOperationException("Scoped recovery is not implemented by this client");
    }

    default RecoveryPermitObservation observeRecovery(String permitId) {
        throw new UnsupportedOperationException("Scoped recovery is not implemented by this client");
    }
}
