package com.myshop.service;

import com.myshop.constant.SalesChannelGenerationState;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.entity.Product;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingGenerationSpecRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesChannelGenerationActivationServiceTest {

    @Mock private SalesChannelOperationRepository operationRepository;
    @Mock private SalesChannelListingRepository listingRepository;
    @Mock private SalesChannelListingGenerationRepository generationRepository;
    @Mock private SalesChannelListingGenerationSpecRepository generationSpecRepository;
    @Mock private ProductSpecRepository productSpecRepository;
    @Mock private SalesChannelAccountRepository accountRepository;
    @Mock private ProductRepository productRepository;
    @Mock private SalesChannelSpecMappingValidator mappingValidator;
    @InjectMocks private SalesChannelGenerationActivationService service;

    @Test
    void replacementActivationRetiresSourceAndMakesCandidateCurrentAtomically() {
        UUID listingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();

        SalesChannelListing listing = new SalesChannelListing();
        listing.setId(listingId);
        listing.setSalesChannelAccountId(accountId);
        listing.setProductId(productId);
        listing.setManagementMode(SalesChannelListingManagementMode.MANAGED);
        listing.setDesiredState(SalesChannelListingDesiredState.ONLINE);
        listing.setDesiredFingerprint("fp-2");
        listing.incrementDesiredRevision();
        listing.setStatus(SalesChannelListingStatus.REPLACEMENT_PENDING);

        SalesChannelListingGeneration source = new SalesChannelListingGeneration(
                listingId, accountId, 1, "item-old", SalesChannelGenerationState.CURRENT, "LEGACY_MIGRATION");
        source.setId(sourceId);
        SalesChannelListingGeneration candidate = new SalesChannelListingGeneration(
                listingId, accountId, 2, "item-new", SalesChannelGenerationState.CANDIDATE, "REPUBLISH");
        candidate.setId(candidateId);
        candidate.setReplacesGenerationId(sourceId);

        SalesChannelOperation operation = new SalesChannelOperation(listingId, sourceId,
                SalesChannelOperationType.REPUBLISH, 1, "republish-key",
                Map.of("desired_fingerprint", "fp-2", "replacement_of_item_id", "item-old"));
        operation.setId(operationId);
        operation.setResultGenerationId(candidateId);
        operation.start(java.time.LocalDateTime.now());
        operation.succeed(Map.of("item_id", "item-new"), java.time.LocalDateTime.now());

        SalesChannelAccount account = new SalesChannelAccount();
        account.setId(accountId);
        Product product = new Product();
        product.setId(productId);
        product.setCostPrice(new BigDecimal("1.00"));

        when(operationRepository.findById(operationId)).thenReturn(Optional.of(operation));
        when(operationRepository.findByIdForUpdate(operationId)).thenReturn(Optional.of(operation));
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingRepository.findByIdForUpdate(listingId)).thenReturn(Optional.of(listing));
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(generationRepository.findByIdForUpdate(candidateId)).thenReturn(Optional.of(candidate));
        when(generationRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        when(productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(productId, 0))
                .thenReturn(List.of());
        when(generationSpecRepository.findBySalesChannelListingGenerationIdForUpdate(candidateId))
                .thenReturn(List.of());

        assertTrue(service.activate(operationId));

        assertEquals(SalesChannelGenerationState.RETIRED, source.getGenerationState());
        assertEquals(SalesChannelGenerationState.CURRENT, candidate.getGenerationState());
        assertEquals(SalesChannelListingStatus.ONLINE, listing.getStatus());
        assertEquals(1, listing.getAppliedRevision());
        assertEquals("fp-2", listing.getAppliedFingerprint());
    }
}
