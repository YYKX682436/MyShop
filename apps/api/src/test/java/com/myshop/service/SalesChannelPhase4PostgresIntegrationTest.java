package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.SalesChannelAccountStatus;
import com.myshop.constant.SalesChannelGenerationState;
import com.myshop.constant.SalesChannelDeliveryOperationStatus;
import com.myshop.constant.SalesChannelDeliveryOperationType;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingManagementMode;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOrderDeliveryStatus;
import com.myshop.constant.SalesChannelOrderIngestStatus;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelDeliveryOperation;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelListingGenerationSpec;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.entity.SalesChannelOrderCursor;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.entity.SalesChannelRecoveryPermit;
import com.myshop.exception.BusinessException;
import com.myshop.exception.SalesChannelSpecMappingException;
import com.myshop.exception.UnmanagedSalesChannelItemException;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelDeliveryOperationRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingGenerationSpecRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.repository.SalesChannelOrderCursorRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.repository.SalesChannelRecoveryPermitRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.service.impl.AutoFulfillmentServiceImpl;
import com.myshop.service.impl.FulfillmentTransactionService;
import com.myshop.saleschannel.SalesChannelProviderClient;
import com.myshop.saleschannel.SalesChannelProviderClientException;
import com.myshop.saleschannel.SalesChannelWorker;
import com.myshop.saleschannel.convergence.XianyuConvergenceClient;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts;
import com.myshop.saleschannel.model.SalesChannelDtos.AgentOrder;
import com.myshop.saleschannel.model.SalesChannelDtos.CatalogItem;
import com.myshop.saleschannel.model.SalesChannelDtos.DeliveryReconcileData;
import com.myshop.saleschannel.model.SalesChannelDtos.IntegrationEnvelope;
import com.myshop.saleschannel.model.SalesChannelDtos.OrderChangesData;
import com.myshop.saleschannel.model.SalesChannelDtos.OrderCursor;
import com.myshop.saleschannel.model.SalesChannelDtos.Outcome;
import com.myshop.saleschannel.model.SalesChannelDtos.ListingSpecMappingsData;
import com.myshop.saleschannel.model.SalesChannelDtos.ItemsQueryData;
import com.myshop.saleschannel.model.SalesChannelDtos.OwnershipClaimData;
import com.myshop.saleschannel.model.SalesChannelDtos.OwnershipClaimCommand;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSpecification;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSpecificationValue;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecMappingSkuRow;
import com.myshop.utils.CardContentParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.myshop.service.impl.SalesChannelOperationServiceImpl;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.util.TreeMap;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL-only Phase 4 invariants. Testcontainers skips this class when a
 * Docker daemon is unavailable; compilation still protects the production
 * service/repository contract.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class SalesChannelPhase4PostgresIntegrationTest {

    private int nextTestUnitIndex;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SalesChannelAccountRepository accountRepository;

    @Autowired
    private SalesChannelListingRepository listingRepository;

    @Autowired
    private SalesChannelListingGenerationRepository generationRepository;

    @Autowired
    private SalesChannelListingGenerationSpecRepository generationSpecRepository;

    @Autowired
    private SalesChannelOrderRepository channelOrderRepository;

    @Autowired
    private SalesChannelOrderCursorRepository cursorRepository;

    @Autowired
    private SalesChannelOperationRepository operationRepository;

    @Autowired
    private SalesChannelRecoveryPermitRepository recoveryPermitRepository;

    @Autowired
    private SalesChannelOperationClaimService operationClaimService;

    @Autowired
    private SalesChannelOperationServiceImpl salesChannelOperationService;

    @Autowired
    private SalesChannelAdminService salesChannelAdminService;

    @Autowired
    private SalesChannelDeliveryOperationRepository deliveryOperationRepository;

    @Autowired
    private SalesChannelOrderImportService importService;

    @Autowired
    private SalesChannelSpecMappingSyncService specMappingSyncService;

    @Autowired
    private XianyuItemBindingTransactionService itemBindingTransactionService;

    @Autowired
    private XianyuItemTakeoverService takeoverService;

    @Autowired
    private FulfillmentTransactionService fulfillmentService;

    @Autowired
    private SalesChannelDeliveryPlanner deliveryPlanner;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private AutoFulfillmentServiceImpl autoFulfillmentService;

    @Autowired
    private SalesChannelDeliveryClaimService deliveryClaimService;

    @Autowired
    private SalesChannelDeliveryRecoveryService deliveryRecoveryService;

    @Autowired
    private SalesChannelDeliveryOperationStateService deliveryStateService;

    @Autowired
    private CardKeyRepository cardKeyRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Test
    void scopedFailedEnsureClaimWaitsForGoAndFencesConcurrentWorkersWithoutBlockingLegacyRecovery() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        String fingerprint = "d".repeat(64);
        String oldItem = "phase4-recovery-old-" + UUID.randomUUID();
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("scoped-recovery");
        SalesChannelListing listing = new SalesChannelListing();
        listing.setSalesChannelAccountId(account.getId());
        listing.setProductId(productId);
        listing.setEnabled(true);
        listing.setAutoFulfillmentEnabled(false);
        listing.setDesiredState(SalesChannelListingDesiredState.ONLINE);
        listing.setStatus(SalesChannelListingStatus.ERROR);
        listing.setManagementMode(SalesChannelListingManagementMode.MANAGED);
        listing.setProviderConfig(Map.of());
        for (int index = 0; index < 10; index++) listing.incrementDesiredRevision();
        listing.setDesiredFingerprint(fingerprint);
        listing = listingRepository.saveAndFlush(listing);
        UUID listingId = listing.getId();
        String requestId = "phase4-scoped-recovery-" + UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account_id", account.getProviderAccountId());
        payload.put("listing_key", listingId.toString().toLowerCase());
        payload.put("revision", 10L);
        payload.put("desired_state", "ONLINE");
        payload.put("desired_fingerprint", fingerprint);
        payload.put("desired_snapshot", Map.of("schema_version", 1));
        SalesChannelOperation failed = new SalesChannelOperation(
                listingId, SalesChannelOperationType.ENSURE_LISTING, 10L, requestId, payload);
        failed.start(now.minusMinutes(1));
        failed.fail("PROVIDER_PRECALL_FAILURE", "No provider attempt started", now);
        operationRepository.saveAndFlush(failed);

        UUID certificationId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into sales_channel_cutover_certifications
                    (id, sales_channel_listing_id, desired_revision, desired_state, desired_fingerprint,
                     current_external_item_id, agent_state_digest, requires_adoption, bootstrap_operation_id,
                     evidence, created_at)
                values (?, ?, 10, 'ONLINE', ?, ?, ?, false, ?, '{}'::jsonb, ?)
                """, certificationId, listing.getId(), fingerprint, oldItem, "e".repeat(64), failed.getId(), now);
        listing.certifyConvergence(certificationId);
        listing.activateConvergence(now.minusMinutes(1));
        listing.pauseConvergence(now);
        listing = listingRepository.saveAndFlush(listing);
        SalesChannelListingGeneration current = new SalesChannelListingGeneration(
                listing.getId(), account.getId(), 9L, oldItem,
                SalesChannelGenerationState.CURRENT, "LEGACY_MIGRATION");
        current.makeCurrent(now.minusDays(1));
        generationRepository.saveAndFlush(current);
        SalesChannelRecoveryPermit permit = new SalesChannelRecoveryPermit(
                1L, listingId, failed.getId(), 15L, account.getProviderAccountId(),
                requestId, 10L, fingerprint, oldItem, "operator-a", "prove scoped worker fencing",
                Map.of("operation_status", "FAILED", "provider_attempt_count", 0), now);
        permit.arm(Map.of("operation_status", "FAILED"), now);

        UUID listingA = createRecoveryQueueListing(account, insertProduct(false), "A");
        UUID listingC = createRecoveryQueueListing(account, insertProduct(false), "C");
        UUID listingUnknown = createRecoveryQueueListing(account, insertProduct(false), "UNKNOWN");
        SalesChannelOperation untouchedA = pendingEnsure(listingA, "phase4-a-" + UUID.randomUUID(), 0, now);
        SalesChannelOperation untouchedC = pendingEnsure(listingC, "phase4-c-" + UUID.randomUUID(), 0, now);
        SalesChannelOperation attemptedUnknown = pendingEnsure(
                listingUnknown, "phase4-unknown-" + UUID.randomUUID(), 1, now);

        failed.reopenFailedForRecovery(now);
        operationRepository.saveAndFlush(failed);
        permit.activate("operator-a", now, Map.of("GO", "pending"));
        permit = recoveryPermitRepository.saveAndFlush(permit);
        UUID permitId = permit.getId();
        operationRepository.saveAllAndFlush(List.of(untouchedA, untouchedC, attemptedUnknown));

        List<UUID> beforeGoCandidates = operationClaimService.selectReadyIds(now.plusSeconds(1), 100);
        assertTrue(beforeGoCandidates.contains(attemptedUnknown.getId()),
                "ordinary attempted UNKNOWN recovery remains claimable before scoped GO");
        assertFalse(beforeGoCandidates.contains(failed.getId()),
                "before the Agent GO acknowledgement, the failed command is not claimable");
        assertFalse(beforeGoCandidates.contains(untouchedA.getId()));
        assertFalse(beforeGoCandidates.contains(untouchedC.getId()));
        assertEquals(SalesChannelOperationStatus.PENDING,
                operationRepository.findById(failed.getId()).orElseThrow().getStatus());
        assertEquals("GO_PENDING", recoveryPermitRepository.findById(permitId).orElseThrow().getState());

        permit = recoveryPermitRepository.findById(permitId).orElseThrow();
        permit.completeGo(now.plusSeconds(2));
        recoveryPermitRepository.saveAndFlush(permit);
        assertEquals(List.of(failed.getId()), operationClaimService.selectReadyIds(now.plusSeconds(3), 1),
                "batch_size=1 still selects only the permit-bound command");

        assertPermitMutationRejected(permitId, failed.getId(), "request_id", "wrong-request-id", requestId);
        assertPermitMutationRejected(permitId, failed.getId(), "myshop_operation_id",
                attemptedUnknown.getId(), failed.getId());
        assertPermitMutationRejected(permitId, failed.getId(), "sales_channel_listing_id", listingA, listingId);
        assertPermitMutationRejected(permitId, failed.getId(), "account_id", "wrong-account", account.getProviderAccountId());
        assertPermitMutationRejected(permitId, failed.getId(), "desired_revision", 9L, 10L);
        assertPermitMutationRejected(permitId, failed.getId(), "desired_fingerprint", "f".repeat(64), fingerprint);
        assertPermitMutationRejected(permitId, failed.getId(), "expected_old_item_id", "wrong-item", oldItem);
        assertPermitMutationRejected(permitId, failed.getId(), "generation", 0L, 1L);
        assertPermitMutationRejected(permitId, failed.getId(), "execution_fence", 1L, 0L);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(() -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("claim race did not start");
                return salesChannelOperationService.markRunning(failed.getId());
            });
            Future<Boolean> second = workers.submit(() -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("claim race did not start");
                return salesChannelOperationService.markRunning(failed.getId());
            });
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            start.countDown();
            List<Boolean> claims = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(1, claims.stream().filter(Boolean::booleanValue).count());
        } finally {
            workers.shutdownNow();
        }
        SalesChannelOperation running = operationRepository.findById(failed.getId()).orElseThrow();
        assertEquals(SalesChannelOperationStatus.RUNNING, running.getStatus());
        assertEquals(2, running.getAttemptCount());
        assertEquals("EXECUTING", recoveryPermitRepository.findById(permitId).orElseThrow().getState());
        assertTrue(operationClaimService.selectReadyIds(now.plusSeconds(4), 100).contains(attemptedUnknown.getId()),
                "ordinary attempted UNKNOWN recovery remains claimable while the scoped operation is running");
        assertFalse(salesChannelOperationService.markRunning(untouchedA.getId()),
                "markRunning independently fences an unattempted A command");
        assertFalse(salesChannelOperationService.markRunning(untouchedC.getId()),
                "markRunning independently fences an unattempted C command");
        assertTrue(salesChannelOperationService.markRunning(attemptedUnknown.getId()),
                "the pre-existing attempted UNKNOWN command keeps its recovery path");
        SalesChannelOperation unknownRunning = operationRepository.findById(attemptedUnknown.getId()).orElseThrow();
        assertTrue(unknownRunning.reschedule("AGENT_REPLAY_REQUIRED", "retain the original uncertain request",
                now.plusSeconds(5)));
        operationRepository.saveAndFlush(unknownRunning);

        running = operationRepository.findById(failed.getId()).orElseThrow();
        assertTrue(running.succeed(Map.of("external_item_id", "mock-recovery-item"), now.plusSeconds(5)));
        operationRepository.saveAndFlush(running);
        SalesChannelRecoveryPermit terminalPermit = recoveryPermitRepository.findById(permitId).orElseThrow();
        terminalPermit.terminal("SUCCEEDED", now.plusSeconds(5));
        recoveryPermitRepository.saveAndFlush(terminalPermit);
        List<UUID> afterTerminalCandidates = operationClaimService.selectReadyIds(now.plusSeconds(6), 100);
        assertTrue(afterTerminalCandidates.contains(attemptedUnknown.getId()),
                "terminal scoped mode still permits prior attempted UNKNOWN reconciliation");
        assertFalse(afterTerminalCandidates.contains(untouchedA.getId()));
        assertFalse(afterTerminalCandidates.contains(untouchedC.getId()));
        assertEquals(SalesChannelOperationStatus.PENDING, operationRepository.findById(untouchedA.getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelOperationStatus.PENDING, operationRepository.findById(untouchedC.getId()).orElseThrow().getStatus());
    }

    @Test
    void verifiedQrReauthenticationPreservesPostgresAccountRowAndCurrentItemBinding() {
        String providerAccountId = "pg-qr-reauth-" + UUID.randomUUID();
        UUID loginIntentId = UUID.randomUUID();
        UUID loginSessionId = UUID.randomUUID();
        String itemId = "qr-reauth-current-item";
        UUID productId = insertProduct(false);
        LocalDateTime beforeVerified = LocalDateTime.now().minusMinutes(2);

        SalesChannelAccount account = new SalesChannelAccount();
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setProviderAccountId(providerAccountId);
        account.setDisplayName("Existing QR account");
        account.setStatus(SalesChannelAccountStatus.CONNECTED);
        account.setEnabled(true);
        account.setCapabilities(Map.of("account_type", "PERSONAL"));
        account.setLastLoginAt(beforeVerified);
        account = accountRepository.saveAndFlush(account);
        UUID accountRowId = account.getId();
        SalesChannelListing listing = listing(account, productId, itemId, false);
        SalesChannelListingGeneration originalGeneration = generationRepository
                .findCurrentByListingId(listing.getId()).orElseThrow();
        UUID originalGenerationId = originalGeneration.getId();

        var startedData = new com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStartData(
                loginIntentId.toString(), loginSessionId.toString(), "data:image/png;base64,AA==", 300);
        when(providerClient.startQrReauthentication(any())).thenReturn(new IntegrationEnvelope<>(
                "start-request", Outcome.SUCCEEDED, startedData, null));
        var cookiePersisted = new com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStatusData(
                "COOKIE_PERSISTED", loginIntentId.toString(), loginSessionId.toString(),
                providerAccountId, false, null, null, null);
        when(providerClient.getQrLoginStatus(any())).thenReturn(new IntegrationEnvelope<>(
                "poll-request", Outcome.SUCCEEDED, cookiePersisted, null));
        var capabilities = new com.myshop.saleschannel.model.SalesChannelDtos.AccountCapabilities(
                "PERSONAL", true, true, false, true, false);
        when(providerClient.verifyAccount(providerAccountId)).thenReturn(new IntegrationEnvelope<>(
                "capability-request", Outcome.SUCCEEDED,
                new com.myshop.saleschannel.model.SalesChannelDtos.CapabilityData(capabilities), null));
        var providerVerified = new com.myshop.saleschannel.model.SalesChannelDtos.QrLoginStatusData(
                "PROVIDER_AUTH_VERIFIED", loginIntentId.toString(), loginSessionId.toString(),
                providerAccountId, false, null, null, null);
        when(providerClient.completeQrLoginVerification(any())).thenReturn(new IntegrationEnvelope<>(
                "verification-request", Outcome.SUCCEEDED, providerVerified, null));

        var started = salesChannelAdminService.startQrReauthentication(
                accountRowId, UUID.randomUUID().toString(), loginIntentId.toString(), "pg-admin");
        var completed = salesChannelAdminService.getQrReauthenticationStatus(
                accountRowId, UUID.randomUUID().toString(), loginSessionId.toString(),
                loginIntentId.toString(), "pg-admin");

        assertEquals(loginIntentId.toString(), started.loginIntentId());
        assertEquals(loginSessionId.toString(), started.loginSessionId());
        assertEquals("PROVIDER_AUTH_VERIFIED", completed.status());
        assertEquals(providerAccountId, completed.accountId());

        SalesChannelAccount persistedAccount = accountRepository.findById(accountRowId).orElseThrow();
        SalesChannelListing persistedListing = listingRepository.findById(listing.getId()).orElseThrow();
        SalesChannelListingGeneration persistedGeneration = generationRepository
                .findCurrentByListingId(listing.getId()).orElseThrow();
        assertEquals(accountRowId, persistedAccount.getId());
        assertEquals(providerAccountId, persistedAccount.getProviderAccountId());
        assertTrue(persistedAccount.getLastLoginAt().isAfter(beforeVerified));
        assertTrue(persistedAccount.getLastVerifiedAt().isAfter(beforeVerified));
        assertEquals(listing.getId(), persistedListing.getId());
        assertEquals(originalGenerationId, persistedGeneration.getId());
        assertEquals(itemId, persistedGeneration.getExternalItemId());
        assertEquals(SalesChannelGenerationState.CURRENT, persistedGeneration.getGenerationState());

        org.mockito.ArgumentCaptor<com.myshop.saleschannel.model.SalesChannelDtos.QrLoginReauthenticationStartCommand>
                startCommand = org.mockito.ArgumentCaptor.forClass(
                        com.myshop.saleschannel.model.SalesChannelDtos.QrLoginReauthenticationStartCommand.class);
        org.mockito.ArgumentCaptor<com.myshop.saleschannel.model.SalesChannelDtos.QrLoginVerificationCommand>
                verificationCommand = org.mockito.ArgumentCaptor.forClass(
                        com.myshop.saleschannel.model.SalesChannelDtos.QrLoginVerificationCommand.class);
        verify(providerClient).startQrReauthentication(startCommand.capture());
        verify(providerClient).getQrLoginStatus(any());
        verify(providerClient).verifyAccount(providerAccountId);
        verify(providerClient).completeQrLoginVerification(verificationCommand.capture());
        assertEquals(providerAccountId, startCommand.getValue().targetAccountId());
        assertEquals(loginIntentId.toString(), startCommand.getValue().loginIntentId());
        assertEquals("pg-admin", startCommand.getValue().operatorIdentity());
        assertTrue(verificationCommand.getValue().verified());
        assertEquals(loginIntentId.toString(), verificationCommand.getValue().loginIntentId());
        org.mockito.Mockito.verifyNoMoreInteractions(providerClient);
    }

    @Test
    void exactFailedEnsureGoIsIdempotentAndRecoversAfterAgentCommitResponseLoss() throws Exception {
        UUID listingId = UUID.fromString("c827bfa4-abfe-47f9-8d60-f2d1c6972fcd");
        UUID operationId = UUID.fromString("7369460a-45a7-49d0-9cf5-85d98eff4199");
        String accountId = "2215747465381";
        String requestId = "0153b2a7-fbd7-48b8-b52c-0ea2379ddf26";
        String fingerprint = "def5d3cbd69714b2cd8b3e87e5fc472631e46150b7bcd2c778a23f59b7989446";
        String oldItem = "1084013851108";
        LocalDateTime now = LocalDateTime.now();
        UUID productId = insertProduct(false);

        SalesChannelAccount account = new SalesChannelAccount();
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setProviderAccountId(accountId);
        account.setDisplayName("Frozen B account");
        account.setStatus(SalesChannelAccountStatus.CONNECTED);
        account.setEnabled(true);
        account.setCapabilities(Map.of("publish", true));
        account.setLastVerifiedAt(now);
        account = accountRepository.saveAndFlush(account);

        XianyuConvergenceContracts.ListingSnapshot snapshot = new XianyuConvergenceContracts.ListingSnapshot(
                1, listingId.toString().toLowerCase(), "Frozen B item", "recovery acceptance",
                "1.00", 0,
                List.of(new XianyuConvergenceContracts.ListingImage(
                        "sha256:" + "0".repeat(64), "https://example.invalid/b-cover.jpg", "0".repeat(64))),
                new XianyuConvergenceContracts.ListingCategory("category", "Category", "channel", "Channel",
                        "leaf", "tb-category"),
                new XianyuConvergenceContracts.ListingAddress(null, null, null, null, null,
                        "Shanghai", null, "Shanghai"),
                List.of(), new XianyuConvergenceContracts.ListingShipping("free", false, null),
                List.of(), List.of());
        Map<String, Object> snapshotPayload = objectMapper.convertValue(snapshot, Map.class);
        Map<String, Object> frozenPayload = new LinkedHashMap<>();
        frozenPayload.put("account_id", accountId);
        frozenPayload.put("listing_key", listingId.toString().toLowerCase());
        frozenPayload.put("revision", 10L);
        frozenPayload.put("desired_state", "ONLINE");
        frozenPayload.put("desired_fingerprint", fingerprint);
        frozenPayload.put("desired_snapshot", snapshotPayload);

        jdbcTemplate.update("""
                insert into sales_channel_listings
                    (id, created_at, updated_at, sales_channel_account_id, product_id, management_mode,
                     is_enabled, auto_fulfillment_enabled, desired_state, status, desired_revision,
                     applied_revision, provider_config, desired_fingerprint, convergence_cutover_state)
                values (?, ?, ?, ?, ?, 'MANAGED', true, false, 'ONLINE', 'ERROR', 10, 0,
                        '{}'::jsonb, ?, 'UNCERTIFIED')
                """, listingId, now, now, account.getId(), productId, fingerprint);
        jdbcTemplate.update("""
                insert into sales_channel_operations
                    (id, created_at, updated_at, sales_channel_listing_id, operation_type, status,
                     desired_revision, idempotency_key, payload, attempt_count, started_at, finished_at,
                     last_error_code, last_error_message)
                values (?, ?, ?, ?, 'ENSURE_LISTING', 'FAILED', 10, ?, ?::jsonb, 1, ?, ?,
                        'PROVIDER_PRECALL_FAILURE', 'No provider attempt started')
                """, operationId, now, now, listingId, requestId,
                objectMapper.writeValueAsString(frozenPayload), now.minusMinutes(1), now);
        UUID certificationId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into sales_channel_cutover_certifications
                    (id, sales_channel_listing_id, desired_revision, desired_state, desired_fingerprint,
                     current_external_item_id, agent_state_digest, requires_adoption, bootstrap_operation_id,
                     evidence, created_at)
                values (?, ?, 10, 'ONLINE', ?, ?, ?, false, ?, '{}'::jsonb, ?)
                """, certificationId, listingId, fingerprint, oldItem, "e".repeat(64), operationId, now);
        jdbcTemplate.update("""
                update sales_channel_listings
                set convergence_certification_id = ?, convergence_cutover_state = 'PAUSED',
                    convergence_activated_at = ?, convergence_paused_at = ?,
                    convergence_adopted_external_item_id = ?, convergence_adopted_at = ?
                where id = ?
                """, certificationId, now.minusMinutes(5), now, oldItem, now.minusMinutes(4), listingId);
        SalesChannelListingGeneration current = new SalesChannelListingGeneration(
                listingId, account.getId(), 9L, oldItem, SalesChannelGenerationState.CURRENT, "LEGACY_MIGRATION");
        current.setActivatedAt(now.minusDays(1));
        generationRepository.saveAndFlush(current);

        SalesChannelOperation historical = operationRepository.findById(operationId).orElseThrow();
        assertEquals(requestId, historical.getIdempotencyKey());
        assertEquals(java.util.Set.of("account_id", "listing_key", "revision", "desired_state",
                        "desired_fingerprint", "desired_snapshot"), historical.getPayload().keySet());
        assertFalse(historical.getPayload().containsKey("request_id"));

        SalesChannelDesiredStatePlanner planner = org.mockito.Mockito.mock(SalesChannelDesiredStatePlanner.class);
        when(planner.computeCurrentIntent(listingId)).thenReturn(new SalesChannelDesiredStatePlanner.ComputedDesiredIntent(
                account, null, SalesChannelListingDesiredState.ONLINE, snapshot, fingerprint));
        XianyuConvergenceClient agent = org.mockito.Mockito.mock(XianyuConvergenceClient.class);
        AtomicReference<XianyuConvergenceContracts.RecoveryPermitObservation> remotePermit = new AtomicReference<>();
        AtomicBoolean loseFirstActivationResponse = new AtomicBoolean(true);
        when(agent.validateRecovery(any())).thenReturn(new XianyuConvergenceContracts.RecoveryValidationResponse(
                true, 15L, requestId, "a".repeat(64), Map.of("provider_attempt_count", 0)));
        when(agent.prepareRecovery(any())).thenAnswer(invocation -> {
            XianyuConvergenceContracts.RecoveryPrepareRequest request = invocation.getArgument(0);
            var prepared = recoveryObservation(request.permitId(), request.generation(), "PREPARED",
                    request.accountId(), request.listingKey(), request.myshopOperationId(), 15L,
                    request.requestId(), request.desiredRevision(), request.desiredFingerprint(),
                    request.expectedOldItemId());
            remotePermit.set(prepared);
            return prepared;
        });
        when(agent.armRecovery(any())).thenAnswer(invocation -> {
            XianyuConvergenceContracts.RecoveryArmRequest request = invocation.getArgument(0);
            var armed = recoveryObservation(request.permitId(), request.generation(), "ARMED",
                    request.accountId(), request.listingKey(), request.myshopOperationId(), 15L,
                    request.requestId(), request.desiredRevision(), request.desiredFingerprint(),
                    request.expectedOldItemId());
            remotePermit.set(armed);
            return armed;
        });
        when(agent.observeRecovery(any())).thenAnswer(invocation -> remotePermit.get());
        when(agent.activateRecovery(any())).thenAnswer(invocation -> {
            XianyuConvergenceContracts.RecoveryArmRequest request = invocation.getArgument(0);
            var active = recoveryObservation(request.permitId(), request.generation(), "ACTIVE",
                    request.accountId(), request.listingKey(), request.myshopOperationId(), 15L,
                    request.requestId(), request.desiredRevision(), request.desiredFingerprint(),
                    request.expectedOldItemId());
            remotePermit.set(active); // Simulate Agent's DB commit before its response is lost.
            if (loseFirstActivationResponse.getAndSet(false)) {
                throw new IllegalStateException("simulated response loss after Agent commit");
            }
            return active;
        });

        SalesChannelFailedEnsureRecoveryService service = new SalesChannelFailedEnsureRecoveryService(
                listingRepository, accountRepository, generationRepository, operationRepository,
                recoveryPermitRepository, planner, agent, objectMapper, transactionManager);
        assertTrue(service.plan(listingId, operationId).eligible());
        assertEquals(SalesChannelOperationStatus.FAILED,
                operationRepository.findById(operationId).orElseThrow().getStatus());
        assertFalse(operationClaimService.selectReadyIds(now.plusSeconds(1), 100).contains(operationId));
        assertFalse(salesChannelOperationService.markRunning(operationId),
                "a FAILED historical command cannot be recovered without its permit");

        var prepared = service.prepare(listingId, operationId, "operator-r2", "recover only frozen B command");
        assertEquals("PREPARED", prepared.state());
        var armed = service.arm(prepared.permitId());
        assertEquals("ARMED", armed.state());
        assertFalse(operationClaimService.selectReadyIds(now.plusSeconds(1), 100).contains(operationId));

        var wrongRemote = recoveryObservation(prepared.permitId().toString(), prepared.generation(), "ARMED",
                "wrong-account", listingId.toString(), operationId.toString(), 15L,
                requestId, 10L, fingerprint, oldItem);
        remotePermit.set(wrongRemote);
        assertThrows(IllegalStateException.class, () -> service.go(prepared.permitId(), "operator-r2"));
        assertEquals("ARMED", recoveryPermitRepository.findById(prepared.permitId()).orElseThrow().getState());
        assertEquals(SalesChannelOperationStatus.FAILED,
                operationRepository.findById(operationId).orElseThrow().getStatus());

        remotePermit.set(recoveryObservation(prepared.permitId().toString(), prepared.generation(), "ARMED",
                accountId, listingId.toString(), operationId.toString(), 15L,
                requestId, 10L, fingerprint, oldItem));
        assertThrows(IllegalStateException.class, () -> service.go(prepared.permitId(), "operator-r2"));
        assertEquals("GO_PENDING", recoveryPermitRepository.findById(prepared.permitId()).orElseThrow().getState());
        SalesChannelOperation goPendingOperation = operationRepository.findById(operationId).orElseThrow();
        assertEquals(SalesChannelOperationStatus.PENDING, goPendingOperation.getStatus());
        assertEquals(1, goPendingOperation.getAttemptCount());
        assertEquals(requestId, goPendingOperation.getIdempotencyKey());
        assertFalse(operationClaimService.selectReadyIds(LocalDateTime.now().plusSeconds(1), 100).contains(operationId));
        assertFalse(salesChannelOperationService.markRunning(operationId),
                "the local command stays unclaimable until Agent confirms GO");

        var active = service.go(prepared.permitId(), "operator-r2");
        assertEquals("ACTIVE", active.state());
        assertEquals(List.of(operationId), operationClaimService.selectReadyIds(LocalDateTime.now().plusSeconds(1), 1));
        var duplicateGo = service.go(prepared.permitId(), "operator-r2");
        assertEquals("ACTIVE", duplicateGo.state());
        assertEquals(1, count("select count(*) from sales_channel_operations where sales_channel_listing_id = ?",
                listingId), "duplicate GO retains the original operation instead of creating a successor");
        verify(agent, org.mockito.Mockito.times(1)).activateRecovery(any());
        verify(agent, never()).ensureListing(any());

        AtomicInteger ensureCalls = new AtomicInteger();
        when(agent.ensureListing(any(), any())).thenAnswer(invocation -> {
            XianyuConvergenceContracts.EnsureListingRequest request = invocation.getArgument(0);
            // APPLYING preserves the durable request while Agent resolves its unknown provider outcome.
            return new XianyuConvergenceContracts.EnsureListingOperationObservation(
                    request.requestId(), request.listingKey(), request.revision(),
                    ensureCalls.getAndIncrement() == 0
                            ? XianyuConvergenceContracts.OperationState.APPLYING
                            : XianyuConvergenceContracts.OperationState.SUCCEEDED,
                    ensureCalls.get() == 1 ? null : "mock-recovered-item", null);
        });
        SalesChannelWorker worker = new SalesChannelWorker(operationClaimService, operationRepository,
                listingRepository, generationRepository, accountRepository, salesChannelOperationService,
                agent, planner, objectMapper, 10, 300_000L);
        worker.runBatch();
        SalesChannelOperation replayPending = operationRepository.findById(operationId).orElseThrow();
        assertEquals(SalesChannelOperationStatus.PENDING, replayPending.getStatus());
        assertEquals(requestId, replayPending.getIdempotencyKey());
        assertEquals("RECOVERY_REQUIRED", recoveryPermitRepository.findById(prepared.permitId()).orElseThrow().getState());
        assertEquals(1L, recoveryPermitRepository.findById(prepared.permitId()).orElseThrow().getExecutionFence());
        jdbcTemplate.update("update sales_channel_operations set next_attempt_at = ? where id = ?",
                LocalDateTime.now().minusSeconds(1), operationId);
        worker.runBatch();

        var ensureRequests = org.mockito.ArgumentCaptor.forClass(XianyuConvergenceContracts.EnsureListingRequest.class);
        var recoveryProofs = org.mockito.ArgumentCaptor.forClass(XianyuConvergenceContracts.RecoveryPermitProof.class);
        verify(agent, org.mockito.Mockito.times(2)).ensureListing(ensureRequests.capture(), recoveryProofs.capture());
        assertEquals(List.of(requestId, requestId), ensureRequests.getAllValues().stream()
                .map(XianyuConvergenceContracts.EnsureListingRequest::requestId).toList());
        assertEquals(prepared.permitId().toString().toLowerCase(),
                recoveryProofs.getAllValues().get(0).permitId());
        assertEquals(prepared.generation(), recoveryProofs.getAllValues().get(0).generation());
        assertEquals(1L, recoveryProofs.getAllValues().get(0).executionFence());
        assertEquals(prepared.permitId().toString().toLowerCase(),
                recoveryProofs.getAllValues().get(1).permitId());
        assertEquals(prepared.generation(), recoveryProofs.getAllValues().get(1).generation());
        assertEquals(2L, recoveryProofs.getAllValues().get(1).executionFence());
        assertEquals(SalesChannelOperationStatus.SUCCEEDED,
                operationRepository.findById(operationId).orElseThrow().getStatus());
        assertEquals("TERMINAL", recoveryPermitRepository.findById(prepared.permitId()).orElseThrow().getState());
        worker.runBatch();
        verify(agent, org.mockito.Mockito.times(2)).ensureListing(any(), any());
        assertEquals(1, count("select count(*) from sales_channel_operations where sales_channel_listing_id = ?",
                listingId), "terminal success does not create or execute another ENSURE");

        SalesChannelOperation completed = operationRepository.findById(operationId).orElseThrow();
        SalesChannelRecoveryPermit terminal = recoveryPermitRepository.findById(prepared.permitId()).orElseThrow();
        assertEquals(requestId, completed.getIdempotencyKey());
        assertEquals("TERMINAL", terminal.getState());

        when(agent.agentReadiness()).thenReturn(new XianyuConvergenceContracts.CutoverReadiness(1, true, false));
        assertThrows(IllegalStateException.class,
                () -> service.cancel(prepared.permitId(), "operator-close", "close after gates are disabled"));
        verify(agent, never()).cancelRecovery(any());

        when(agent.agentReadiness()).thenReturn(new XianyuConvergenceContracts.CutoverReadiness(1, false, false));
        AtomicBoolean loseFirstCancelResponse = new AtomicBoolean(true);
        when(agent.cancelRecovery(any())).thenAnswer(invocation -> {
            XianyuConvergenceContracts.RecoveryArmRequest request = invocation.getArgument(0);
            var cancelled = recoveryObservation(request.permitId(), request.generation(), "CANCELLED",
                    request.accountId(), request.listingKey(), request.myshopOperationId(), 15L,
                    request.requestId(), request.desiredRevision(), request.desiredFingerprint(),
                    request.expectedOldItemId());
            remotePermit.set(cancelled);
            if (loseFirstCancelResponse.getAndSet(false)) {
                throw new IllegalStateException("simulated response loss after Agent cancellation commit");
            }
            return cancelled;
        });
        assertThrows(IllegalStateException.class,
                () -> service.cancel(prepared.permitId(), "operator-close", "close after gates are disabled"));
        assertEquals("CANCEL_PENDING", recoveryPermitRepository.findById(prepared.permitId()).orElseThrow().getState(),
                "Agent cancellation response loss leaves MyShop scoped and fail closed");
        assertTrue(recoveryPermitRepository.existsByStateIn(SalesChannelRecoveryPermitRepository.SCOPED_STATES));
        var cancelled = service.cancel(prepared.permitId(), "operator-close", "close after gates are disabled");
        assertEquals("CANCELLED", cancelled.state());
        assertEquals("operator-close", ((Map<?, ?>) cancelled.preconditions().get("cancellation")).get("operator"));
        assertEquals("close after gates are disabled",
                ((Map<?, ?>) cancelled.preconditions().get("cancellation")).get("reason"));
        verify(agent, org.mockito.Mockito.times(2)).cancelRecovery(any());
        assertFalse(recoveryPermitRepository.existsByStateIn(SalesChannelRecoveryPermitRepository.SCOPED_STATES));
    }

    private XianyuConvergenceContracts.RecoveryPermitObservation recoveryObservation(
            String permitId, long generation, String state, String accountId, String listingKey,
            String operationId, long agentOperationId, String requestId, long revision,
            String fingerprint, String oldItem) {
        return new XianyuConvergenceContracts.RecoveryPermitObservation(
                permitId, generation, state, accountId, listingKey, operationId, agentOperationId,
                requestId, revision, fingerprint, oldItem, "operator-r2", "recover only frozen B command",
                Map.of("provider_attempt_count", 0), state);
    }

    @MockitoBean
    private SalesChannelProviderClient providerClient;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private com.myshop.service.EmailService emailService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("jwt.secret", () -> "integration-test-secret-with-at-least-64-bytes-and-random-looking-value-2026");
        registry.add("mail.enabled", () -> false);
        registry.add("bootstrap.admin.username", () -> "");
        registry.add("bootstrap.admin.email", () -> "");
        registry.add("bootstrap.admin.password", () -> "");
        registry.add("sales-channel.worker.enabled", () -> false);
        registry.add("sales-channel.order-sync.enabled", () -> false);
        registry.add("sales-channel.delivery.worker-enabled", () -> false);
    }

    @Test
    void takeoverUnknownLeavesPendingAndSameClaimRetryFinalizesWithoutOperationOrRevisionMutation() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("takeover-recovery");
        String itemId = "item-takeover-recovery";
        SalesChannelListing listing = linkedOnlyListing(account, productId, itemId);
        long revision = listing.getDesiredRevision();
        String desiredFingerprint = listing.getDesiredFingerprint();

        when(providerClient.queryItems(any())).thenReturn(new IntegrationEnvelope<>(
                "catalog-read", Outcome.SUCCEEDED,
                new ItemsQueryData(List.of(new CatalogItem(account.getProviderAccountId(), itemId,
                        "takeover item", "10.00", 1, "online", 0, false, true, null, null)),
                        1, 100, 1L, 1), null));
        when(providerClient.inspectItemSpecMappings(any())).thenReturn(new IntegrationEnvelope<>(
                "spec-read", Outcome.SUCCEEDED,
                new ListingSpecMappingsData(itemId, List.of(), List.of(), true), null));
        AtomicInteger claimAttempts = new AtomicInteger();
        when(providerClient.claimOwnership(any())).thenAnswer(invocation -> {
            OwnershipClaimCommand command = invocation.getArgument(0, OwnershipClaimCommand.class);
            if (claimAttempts.getAndIncrement() == 0) {
                return new IntegrationEnvelope<>(command.requestId(), Outcome.UNKNOWN, null, null);
            }
            return new IntegrationEnvelope<>(command.requestId(), Outcome.SUCCEEDED,
                    new OwnershipClaimData(account.getProviderAccountId(), itemId,
                            "MYSHOP", "ALREADY_CLAIMED"), null);
        });

        var pending = takeoverService.takeover(account.getId(), itemId);

        assertEquals("TAKEOVER_PENDING", pending.result());
        SalesChannelListing afterPending = listingRepository.findById(listing.getId()).orElseThrow();
        assertEquals(SalesChannelListingManagementMode.TAKEOVER_PENDING, afterPending.getManagementMode());
        assertEquals(revision, afterPending.getDesiredRevision());
        assertEquals(desiredFingerprint, afterPending.getDesiredFingerprint());
        assertEquals(0, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId()).size());

        var completed = takeoverService.takeover(account.getId(), itemId);

        assertEquals("TAKEOVER_COMPLETED", completed.result());
        SalesChannelListing finalListing = listingRepository.findById(listing.getId()).orElseThrow();
        assertEquals(SalesChannelListingManagementMode.MANAGED, finalListing.getManagementMode());
        assertEquals(SalesChannelListingStatus.DRAFT, finalListing.getStatus());
        assertEquals(SalesChannelListingDesiredState.OFFLINE, finalListing.getDesiredState());
        assertFalse(finalListing.isEnabled());
        assertFalse(finalListing.isAutoFulfillmentEnabled());
        assertEquals(revision, finalListing.getDesiredRevision());
        assertEquals(desiredFingerprint, finalListing.getDesiredFingerprint());
        assertEquals(0, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId()).size());
        verify(providerClient, org.mockito.Mockito.times(2)).claimOwnership(any());
    }

    @Test
    void takeoverPersistsExactSpecMappingsBeforeClaimAndImportsTheMappedSku() {
        UUID productId = insertProduct(true);
        UUID monthId = insertProductSpec(productId, "月卡");
        UUID yearId = insertProductSpec(productId, "年卡");
        UUID lifetimeId = insertProductSpec(productId, "永久卡");
        SalesChannelAccount account = account("takeover-spec-mapping");
        String itemId = "item-takeover-spec-mapping";
        SalesChannelListing listing = linkedOnlyListing(account, productId, itemId);
        insertListingSpec(listing, monthId);
        insertListingSpec(listing, yearId);
        insertListingSpec(listing, lifetimeId);

        when(providerClient.queryItems(any())).thenReturn(new IntegrationEnvelope<>(
                "catalog-read", Outcome.SUCCEEDED,
                new ItemsQueryData(List.of(new CatalogItem(account.getProviderAccountId(), itemId,
                        "takeover spec item", "20.00", 13, "online", 13, true, true, null, null)),
                        1, 100, 1L, 1), null));
        when(providerClient.inspectItemSpecMappings(any())).thenReturn(new IntegrationEnvelope<>(
                "spec-read", Outcome.SUCCEEDED,
                new ListingSpecMappingsData(itemId,
                        List.of(new SpecMappingSpecification("规格", List.of(
                                new SpecMappingSpecificationValue("月卡"),
                                new SpecMappingSpecificationValue("年卡"),
                                new SpecMappingSpecificationValue("永久卡")))),
                        List.of(
                                new SpecMappingSkuRow("sku-lifetime", Map.of("规格", "永久卡")),
                                new SpecMappingSkuRow("sku-month", Map.of("规格", "月卡")),
                                new SpecMappingSkuRow("sku-year", Map.of("规格", "年卡"))), true), null));
        when(providerClient.claimOwnership(any())).thenAnswer(invocation -> {
            OwnershipClaimCommand command = invocation.getArgument(0, OwnershipClaimCommand.class);
            assertEquals(SalesChannelListingManagementMode.TAKEOVER_PENDING.name(),
                    scalar("select management_mode from sales_channel_listings where id = ?",
                            String.class, listing.getId()));
            UUID generationId = generationRepository.findCurrentByListingId(listing.getId()).orElseThrow().getId();
            assertEquals(3, count("select count(*) from sales_channel_listing_generation_specs "
                    + "where sales_channel_listing_generation_id = ? and external_sku_id is not null "
                    + "and provider_mapping ->> 'provider_spec_name' = '规格'", generationId));
            assertEquals("sku-month", scalar("select external_sku_id from sales_channel_listing_generation_specs "
                    + "where sales_channel_listing_generation_id = ? and product_spec_id = ?", String.class,
                    generationId, monthId));
            assertEquals("月卡", scalar("select provider_mapping ->> 'provider_spec_value' "
                    + "from sales_channel_listing_generation_specs where sales_channel_listing_generation_id = ? and product_spec_id = ?",
                    String.class, generationId, monthId));
            assertEquals("sku-year", scalar("select external_sku_id from sales_channel_listing_generation_specs "
                    + "where sales_channel_listing_generation_id = ? and product_spec_id = ?", String.class,
                    generationId, yearId));
            assertEquals("sku-lifetime", scalar("select external_sku_id from sales_channel_listing_generation_specs "
                    + "where sales_channel_listing_generation_id = ? and product_spec_id = ?", String.class,
                    generationId, lifetimeId));
            return new IntegrationEnvelope<>(command.requestId(), Outcome.SUCCEEDED,
                    new OwnershipClaimData(account.getProviderAccountId(), itemId, "MYSHOP", "CLAIMED"), null);
        });

        var completed = takeoverService.takeover(account.getId(), itemId);

        assertEquals("TAKEOVER_COMPLETED", completed.result());
        assertEquals(SalesChannelListingManagementMode.MANAGED,
                listingRepository.findById(listing.getId()).orElseThrow().getManagementMode());
        SalesChannelOrder imported = importService.importOrder(account,
                order(account, "xy-takeover-spec-mapping", itemId, "sku-year", "年卡", 1, "32.00"));
        assertEquals(SalesChannelOrderIngestStatus.IMPORTED, imported.getIngestStatus());
        assertEquals(yearId, scalar("select spec_id from order_items where order_id = ?", UUID.class,
                imported.getOrderId()));
        verify(providerClient, org.mockito.Mockito.times(1)).inspectItemSpecMappings(any());
        verify(providerClient, org.mockito.Mockito.times(1)).claimOwnership(any());
    }

    @Test
    void managedClaimRejectsWrongItemIdentity() {
        assertManagedClaimResponseRejected((expectedAccountId, expectedItemId) ->
                new OwnershipClaimData(expectedAccountId, "wrong-item", "MYSHOP", "ALREADY_CLAIMED"));
    }

    @Test
    void managedClaimRejectsWrongAccountIdentity() {
        assertManagedClaimResponseRejected((expectedAccountId, expectedItemId) ->
                new OwnershipClaimData("wrong-account", expectedItemId, "MYSHOP", "ALREADY_CLAIMED"));
    }

    @Test
    void managedClaimRejectsNonMyShopOwner() {
        assertManagedClaimResponseRejected((expectedAccountId, expectedItemId) ->
                new OwnershipClaimData(expectedAccountId, expectedItemId,
                        "OTHER_INTEGRATION", "ALREADY_CLAIMED"));
    }

    @Test
    void managedClaimRejectsUnknownResult() {
        assertManagedClaimResponseRejected((expectedAccountId, expectedItemId) ->
                new OwnershipClaimData(expectedAccountId, expectedItemId, "MYSHOP", "UNKNOWN"));
    }

    @Test
    void takeoverSpecMismatchFailsBeforeClaimAndKeepsLinkedOnly() {
        UUID productId = insertProduct(true);
        UUID productSpecId = insertProductSpec(productId, "规格");
        SalesChannelAccount account = account("takeover-spec-mismatch");
        String itemId = "item-takeover-spec-mismatch";
        SalesChannelListing listing = linkedOnlyListing(account, productId, itemId);
        insertListingSpec(listing, productSpecId);

        when(providerClient.queryItems(any())).thenReturn(new IntegrationEnvelope<>(
                "catalog-read", Outcome.SUCCEEDED,
                new ItemsQueryData(List.of(new CatalogItem(account.getProviderAccountId(), itemId,
                        "spec item", "10.00", 2, "online", 2, true, true, null, null)),
                        1, 100, 1L, 1), null));
        when(providerClient.inspectItemSpecMappings(any())).thenReturn(new IntegrationEnvelope<>(
                "spec-read", Outcome.SUCCEEDED,
                new ListingSpecMappingsData(itemId,
                        List.of(
                                new SpecMappingSpecification("颜色", List.of(
                                        new SpecMappingSpecificationValue("黑"))),
                                new SpecMappingSpecification("尺码", List.of(
                                        new SpecMappingSpecificationValue("大")))),
                        List.of(), false), null));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> takeoverService.takeover(account.getId(), itemId));

        assertEquals(ErrorCode.TAKEOVER_NOT_ALLOWED, failure.getCode());
        assertEquals(SalesChannelListingManagementMode.LINKED_ONLY,
                listingRepository.findById(listing.getId()).orElseThrow().getManagementMode());
        assertEquals(0, operationRepository.findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId()).size());
        verify(providerClient, never()).claimOwnership(any());
    }

    @Test
    void takeoverMissingCostFailsBeforeAnyAgentClaim() {
        // The production schema requires root cost_price for spec-disabled products.
        // Use a spec-enabled product with no active specs instead: this is the
        // database-valid representation of an order-unready spec product, and it
        // exercises the same fail-closed cost/spec preflight without weakening a
        // production constraint merely to make the test fixture invalid.
        UUID productId = insertProduct(true);
        jdbcTemplate.update("update products set cost_price = null where id = ?", productId);
        SalesChannelAccount account = account("takeover-cost-missing");
        String itemId = "item-takeover-cost-missing";
        SalesChannelListing listing = linkedOnlyListing(account, productId, itemId);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> takeoverService.takeover(account.getId(), itemId));

        assertEquals(ErrorCode.TAKEOVER_NOT_ORDER_READY, failure.getCode());
        assertEquals(SalesChannelListingManagementMode.LINKED_ONLY,
                listingRepository.findById(listing.getId()).orElseThrow().getManagementMode());
        verify(providerClient, never()).queryItems(any());
        verify(providerClient, never()).claimOwnership(any());
    }

    @Test
    void replayAndQuantityThreeCreateOneOrderAndExactlyThreeSoldKeys() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("replay");
        SalesChannelListing listing = listing(account, productId, "item-replay", true);
        insertCard(productId, null, "replay-card-1");
        insertCard(productId, null, "replay-card-2");
        insertCard(productId, null, "replay-card-3");
        insertCard(productId, null, "replay-card-4");

        AgentOrder external = order(account, "xy-replay-1", "item-replay", null, null, 3, "60.00");
        SalesChannelOrder first = importService.importOrder(account, external);
        SalesChannelOrder second = importService.importOrder(account, external);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, count("select count(*) from sales_channel_orders where external_order_no = ?", "xy-replay-1"));
        assertEquals(1, count("select count(*) from orders where id = ?", first.getOrderId()));
        assertEquals("SALES_CHANNEL", scalar("select order_type from orders where id = ?", String.class, first.getOrderId()));
        assertNull(scalar("select payment_method from orders where id = ?", String.class, first.getOrderId()));
        assertEquals("XIANYU", scalar("select payment_channel_code from orders where id = ?", String.class, first.getOrderId()));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, first.getOrderId()));
        assertEquals(3, count("select count(*) from card_keys where order_id = ? and status = 'SOLD'", first.getOrderId()));
        assertEquals(3, scalar("select quantity from order_items where order_id = ?", Integer.class, first.getOrderId()));
        assertEquals(listing.getId(), first.getSalesChannelListingId());
    }

    @Test
    void linkedOnlyPendingShipOrderIsIgnoredBeforeAnyOrderDomainMutation() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("linked-only-pending");
        linkedOnlyListing(account, productId, "item-linked-only-pending");
        UUID cardId = insertCard(productId, null, "linked-only-pending-card");
        AgentOrder external = order(account, "xy-linked-only-pending", "item-linked-only-pending",
                null, null, 1, "10.00");

        UnmanagedSalesChannelItemException ignored = assertThrows(UnmanagedSalesChannelItemException.class,
                () -> importService.importOrder(account, external));

        assertEquals(account.getId(), ignored.getAccountId());
        assertEquals("item-linked-only-pending", ignored.getExternalItemId());
        assertEquals("xy-linked-only-pending", ignored.getExternalOrderNo());
        assertEquals(0, count("select count(*) from sales_channel_orders where external_order_no = ?",
                "xy-linked-only-pending"));
        assertEquals(0, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-linked-only-pending"));
        assertEquals(0, count("select count(*) from order_items where order_id in "
                + "(select id from orders where idempotency_key = ?)",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-linked-only-pending"));
        assertEquals(1, count("select count(*) from card_keys where id = ? and status = 'AVAILABLE'", cardId));
    }

    @Test
    void linkedOnlyNonPendingShipOrderIsIgnoredBeforeUnsupportedRowCreation() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("linked-only-completed");
        linkedOnlyListing(account, productId, "item-linked-only-completed");
        AgentOrder external = new AgentOrder(account.getProviderAccountId(), "xy-linked-only-completed",
                "completed", "item-linked-only-completed", null, null, null, 1, "10.00", "CNY",
                "2026-09-22T00:00:00Z", "2026-09-22T00:00:01Z");

        assertThrows(UnmanagedSalesChannelItemException.class, () -> importService.importOrder(account, external));
        assertEquals(0, count("select count(*) from sales_channel_orders where external_order_no = ?",
                "xy-linked-only-completed"));
        assertEquals(0, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-linked-only-completed"));
    }

    @Test
    void persistImportFailureIsFailClosedForLinkedOnlyItem() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("linked-only-failure");
        linkedOnlyListing(account, productId, "item-linked-only-failure");
        AgentOrder external = order(account, "xy-linked-only-failure", "item-linked-only-failure",
                null, null, 1, "10.00");

        importService.persistImportFailure(account, external, "IMPORT_ERROR", "should not persist");

        assertEquals(0, count("select count(*) from sales_channel_orders where external_order_no = ?",
                "xy-linked-only-failure"));
        assertEquals(0, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-linked-only-failure"));
    }

    @Test
    void takeoverPendingOrderIsImportedButCannotRequestFulfillmentOrConsumeCards() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("takeover-pending-order");
        SalesChannelListing listing = pendingListing(account, productId, "item-takeover-pending-order", true);
        UUID cardId = insertCard(productId, null, "takeover-pending-card");

        SalesChannelOrder imported = importService.importOrder(account,
                order(account, "xy-takeover-pending-order", "item-takeover-pending-order",
                        null, null, 1, "10.00"));

        assertEquals(SalesChannelOrderIngestStatus.IMPORTED, imported.getIngestStatus());
        assertFalse(imported.isAutoFulfillmentRequested());
        assertEquals(SalesChannelOrderDeliveryStatus.NOT_READY, imported.getDeliveryStatus());
        assertEquals(0, count("select count(*) from sales_channel_delivery_operations where sales_channel_order_id = ?",
                imported.getId()));
        assertEquals(1, count("select count(*) from card_keys where id = ? and status = 'AVAILABLE'", cardId));
        assertEquals(SalesChannelListingManagementMode.TAKEOVER_PENDING,
                listingRepository.findById(listing.getId()).orElseThrow().getManagementMode());
    }

    @Test
    void persistImportFailureRecordsTakeoverPendingOrderFacts() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("takeover-pending-failure");
        pendingListing(account, productId, "item-takeover-pending-failure", false);
        AgentOrder external = order(account, "xy-takeover-pending-failure",
                "item-takeover-pending-failure", null, null, 1, "10.00");

        importService.persistImportFailure(account, external, "IMPORT_ERROR", "spec mapping unavailable");

        assertEquals(1, count("select count(*) from sales_channel_orders where external_order_no = ?",
                "xy-takeover-pending-failure"));
        assertEquals("IMPORT_ERROR", scalar("select last_error_code from sales_channel_orders "
                + "where external_order_no = ?", String.class, "xy-takeover-pending-failure"));
    }

    @Test
    void workerSkipsLinkedOnlyOrderAndImportsManagedOrderFromSamePage() {
        UUID linkedProductId = insertProduct(false);
        UUID pendingProductId = insertProduct(false);
        UUID managedProductId = insertProduct(false);
        SalesChannelAccount account = account("linked-only-worker-page");
        account.setOrderSyncEnabled(true);
        accountRepository.saveAndFlush(account);
        linkedOnlyListing(account, linkedProductId, "item-linked-only-worker");
        pendingListing(account, pendingProductId, "item-takeover-pending-worker", true);
        listing(account, managedProductId, "item-managed-worker", false);

        SalesChannelOrderCursor cursor = new SalesChannelOrderCursor();
        cursor.setProvider(SalesChannelProvider.XIANYU);
        cursor.setSalesChannelAccountId(account.getId());
        cursor.setCursorUpdatedAt(LocalDateTime.of(2026, 9, 25, 0, 0));
        cursor.setCursorId(1L);
        cursor.setInitializedAt(LocalDateTime.of(2026, 9, 25, 0, 0));
        cursorRepository.saveAndFlush(cursor);

        AgentOrder linkedOrder = order(account, "xy-linked-only-worker", "item-linked-only-worker",
                null, null, 1, "10.00");
        AgentOrder pendingOrder = order(account, "xy-takeover-pending-worker", "item-takeover-pending-worker",
                null, null, 1, "10.50");
        AgentOrder managedOrder = order(account, "xy-managed-worker", "item-managed-worker",
                null, null, 1, "11.00");
        when(providerClient.getOrderChanges(any())).thenReturn(new IntegrationEnvelope<>(
                "order-page-linked-only-worker", Outcome.SUCCEEDED,
                new OrderChangesData(List.of(linkedOrder, pendingOrder, managedOrder),
                        new OrderCursor("2026-09-25T00:00:01Z", 2L), false), null));

        new SalesChannelOrderSyncWorker(accountRepository, cursorRepository, providerClient, importService).poll();

        assertEquals(0, count("select count(*) from sales_channel_orders where external_order_no = ?",
                "xy-linked-only-worker"));
        assertEquals(1, count("select count(*) from sales_channel_orders where external_order_no = ?",
                "xy-managed-worker"));
        assertEquals(1, count("select count(*) from sales_channel_orders where external_order_no = ?",
                "xy-takeover-pending-worker"));
        assertFalse(channelOrderRepository.findByProviderAndSalesChannelAccountIdAndExternalOrderNo(
                SalesChannelProvider.XIANYU, account.getId(), "xy-takeover-pending-worker")
                .orElseThrow().isAutoFulfillmentRequested());
        assertEquals(0, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-linked-only-worker"));
        assertEquals(1, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-managed-worker"));
        assertEquals(2L, scalar("select cursor_id from sales_channel_order_cursors "
                + "where sales_channel_account_id = ?", Long.class, account.getId()));
    }

    @Test
    void shortageLeavesPaidAndRetryAfterRestockConsumesOnlyRequestedQuantity() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("shortage");
        listing(account, productId, "item-shortage", true);
        insertCard(productId, null, "shortage-card-1");
        insertCard(productId, null, "shortage-card-2");

        SalesChannelOrder row = importService.importOrder(
                account, order(account, "xy-shortage-1", "item-shortage", null, null, 3, "30.00"));

        assertEquals("PAID", scalar("select status from orders where id = ?", String.class, row.getOrderId()));
        assertEquals(0, count("select count(*) from card_keys where order_id = ?", row.getOrderId()));

        insertCard(productId, null, "shortage-card-3");
        fulfillmentService.fulfillOrder(row.getOrderId());

        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, row.getOrderId()));
        assertEquals(3, count("select count(*) from card_keys where order_id = ? and status = 'SOLD'", row.getOrderId()));
    }

    @Test
    void changedFingerprintIsSourceConflictAndDoesNotMutateImportedOrder() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("conflict");
        listing(account, productId, "item-conflict", false);

        importService.importOrder(account, order(account, "xy-conflict-1", "item-conflict", null, null, 1, "10.00"));
        SalesChannelOrder conflict = importService.importOrder(
                account, order(account, "xy-conflict-1", "item-conflict", null, null, 2, "20.00"));

        assertEquals("SOURCE_CONFLICT", conflict.getIngestStatus().name());
        assertEquals(1, count("select count(*) from sales_channel_orders where external_order_no = ?", "xy-conflict-1"));
        assertEquals(1, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-conflict-1"));
        assertEquals(1, scalar("select quantity from order_items where order_id = ?", Integer.class, conflict.getOrderId()));
    }

    @Test
    void concurrentFirstImportSerializesOnAccountAndCreatesOneOrder() throws Exception {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("concurrent");
        listing(account, productId, "item-concurrent", false);
        AgentOrder external = order(account, "xy-concurrent-1", "item-concurrent", null, null, 1, "10.00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SalesChannelOrder> first = executor.submit(() -> importAfter(start, ready, account, external));
            Future<SalesChannelOrder> second = executor.submit(() -> importAfter(start, ready, account, external));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(first.get(30, TimeUnit.SECONDS).getId(), second.get(30, TimeUnit.SECONDS).getId());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, count("select count(*) from sales_channel_orders where external_order_no = ?", "xy-concurrent-1"));
        assertEquals(1, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-concurrent-1"));
    }

    @Test
    void exactSkuMappingSelectsOnlyTheMappedSpecAndPlannerIsIdempotent() {
        UUID productId = insertProduct(true);
        UUID specId = insertProductSpec(productId, "月卡");
        SalesChannelAccount account = account("spec");
        SalesChannelListing listing = listing(account, productId, "item-spec", true);
        SalesChannelListingGenerationSpec mapping = new SalesChannelListingGenerationSpec();
        mapping.setSalesChannelListingGenerationId(
                generationRepository.findCurrentByListingId(listing.getId()).orElseThrow().getId());
        mapping.setProductSpecId(specId);
        mapping.setExternalSkuId("sku-month");
        mapping.setProviderMapping(Map.of("provider_spec_name", "规格", "provider_spec_value", "月卡"));
        generationSpecRepository.saveAndFlush(mapping);
        insertCard(productId, specId, "spec-card");

        SalesChannelOrder row = importService.importOrder(
                account, order(account, "xy-spec-1", "item-spec", "sku-month", "月卡", 1, "20.00"));
        deliveryPlanner.plan(row.getId());
        deliveryPlanner.plan(row.getId());

        assertEquals(specId, scalar("select spec_id from order_items where order_id = ?", UUID.class, row.getOrderId()));
        assertEquals("DELIVERED", scalar("select status from orders where id = ?", String.class, row.getOrderId()));
        List<SalesChannelDeliveryOperation> operations = deliveryOperationRepository
                .findBySalesChannelOrderIdOrderByCreatedAtAsc(row.getId());
        assertEquals(1, operations.stream().filter(operation -> "SEND_MESSAGE".equals(operation.getOperationType().name())).count());
        assertEquals(1, operations.stream().filter(operation -> specId != null && operation.getCardKeyId() != null).count());
        assertEquals(0, operations.stream().filter(operation -> operation.getResultJson() != null
                && operation.getResultJson().containsKey("content")).count());
    }

    @Test
    void stableListingAndSkuKeysImportWithoutAnyGenerationProjection() {
        UUID productId = insertProduct(true);
        UUID specId = insertProductSpec(productId, "stable sku");
        SalesChannelAccount account = account("stable-keys");
        SalesChannelListing listing = listingWithoutGeneration(account, productId);
        AgentOrder external = new AgentOrder(account.getProviderAccountId(), "xy-stable-keys",
                "pending_ship", "agent-owned-item", null, null, null, 1, "10.00", "CNY",
                "2026-09-22T00:00:00Z", "2026-09-22T00:00:01Z",
                listing.getId().toString().toLowerCase(), specId.toString().toLowerCase());

        SalesChannelOrder imported = importService.importOrder(account, external);

        assertEquals(SalesChannelOrderIngestStatus.IMPORTED, imported.getIngestStatus());
        assertEquals(listing.getId(), imported.getSalesChannelListingId());
        assertNull(imported.getSalesChannelListingGenerationId());
        assertEquals(specId, orderItemRepository.findByOrderId(imported.getOrderId()).getFirst().getSpecId());
    }

    @Test
    void stableListingKeyAccountAndLegacyItemConflictsFailClosed() {
        UUID productId = insertProduct(false);
        UUID secondProductId = insertProduct(false);
        SalesChannelAccount account = account("stable-key-conflict");
        SalesChannelListing first = listing(account, productId, "legacy-item-a", false);
        SalesChannelListing second = listing(account, secondProductId, "legacy-item-b", false);
        AgentOrder conflictingItem = new AgentOrder(account.getProviderAccountId(), "xy-stable-item-conflict",
                "pending_ship", "legacy-item-a", null, null, null, 1, "10.00", "CNY",
                "2026-09-22T00:00:00Z", "2026-09-22T00:00:01Z",
                second.getId().toString().toLowerCase(), null);

        SalesChannelOrder itemConflict = importService.importOrder(account, conflictingItem);

        assertEquals(SalesChannelOrderIngestStatus.MAPPING_ERROR, itemConflict.getIngestStatus());
        assertEquals("LISTING_IDENTITY_CONFLICT", itemConflict.getLastErrorCode());
        assertNull(itemConflict.getOrderId());

        SalesChannelAccount otherAccount = account("stable-key-other-account");
        SalesChannelListing otherListing = listingWithoutGeneration(otherAccount, productId);
        AgentOrder accountConflict = new AgentOrder(account.getProviderAccountId(), "xy-stable-account-conflict",
                "pending_ship", "unprojected-item", null, null, null, 1, "10.00", "CNY",
                "2026-09-22T00:00:00Z", "2026-09-22T00:00:01Z",
                otherListing.getId().toString().toLowerCase(), null);

        SalesChannelOrder accountMismatch = importService.importOrder(account, accountConflict);

        assertEquals(SalesChannelOrderIngestStatus.MAPPING_ERROR, accountMismatch.getIngestStatus());
        assertEquals("LISTING_ACCOUNT_MISMATCH", accountMismatch.getLastErrorCode());
        assertNull(accountMismatch.getOrderId());
        assertNotEquals(first.getId(), second.getId());
    }

    @Test
    void stableSkuKeyConflictWithLegacyExternalSkuProjectionFailsClosed() {
        UUID productId = insertProduct(true);
        UUID stableSpecId = insertProductSpec(productId, "stable mapping target");
        UUID legacySpecId = insertProductSpec(productId, "legacy mapping target");
        SalesChannelAccount account = account("stable-sku-conflict");
        SalesChannelListing listing = listing(account, productId, "stable-sku-item", false);
        SalesChannelListingGeneration generation = generationRepository
                .findCurrentByListingId(listing.getId()).orElseThrow();
        SalesChannelListingGenerationSpec legacyMapping = new SalesChannelListingGenerationSpec();
        legacyMapping.setSalesChannelListingGenerationId(generation.getId());
        legacyMapping.setProductSpecId(legacySpecId);
        legacyMapping.setExternalSkuId("provider-sku-legacy");
        generationSpecRepository.saveAndFlush(legacyMapping);
        AgentOrder external = new AgentOrder(account.getProviderAccountId(), "xy-stable-sku-conflict",
                "pending_ship", "stable-sku-item", "provider-sku-legacy", null, null, 1, "10.00", "CNY",
                "2026-09-22T00:00:00Z", "2026-09-22T00:00:01Z",
                listing.getId().toString().toLowerCase(), stableSpecId.toString().toLowerCase());

        SalesChannelOrder conflict = importService.importOrder(account, external);

        assertEquals(SalesChannelOrderIngestStatus.MAPPING_ERROR, conflict.getIngestStatus());
        assertEquals("SKU_IDENTITY_CONFLICT", conflict.getLastErrorCode());
        assertNull(conflict.getOrderId());

        UUID legacyNameValueSpecId = insertProductSpec(productId, "legacy name value mapping target");
        SalesChannelListingGenerationSpec legacyNameValueMapping = new SalesChannelListingGenerationSpec();
        legacyNameValueMapping.setSalesChannelListingGenerationId(generation.getId());
        legacyNameValueMapping.setProductSpecId(legacyNameValueSpecId);
        legacyNameValueMapping.setProviderMapping(Map.of(
                "provider_spec_name", "Provider color", "provider_spec_value", "Legacy blue"));
        generationSpecRepository.saveAndFlush(legacyNameValueMapping);
        AgentOrder nameValueConflict = new AgentOrder(account.getProviderAccountId(), "xy-stable-sku-name-conflict",
                "pending_ship", "stable-sku-item", null, "Provider color", "Legacy blue", 1,
                "10.00", "CNY", "2026-09-22T00:00:00Z", "2026-09-22T00:00:01Z",
                listing.getId().toString().toLowerCase(), stableSpecId.toString().toLowerCase());

        SalesChannelOrder legacyNameConflict = importService.importOrder(account, nameValueConflict);

        assertEquals(SalesChannelOrderIngestStatus.MAPPING_ERROR, legacyNameConflict.getIngestStatus());
        assertEquals("SKU_IDENTITY_CONFLICT", legacyNameConflict.getLastErrorCode());
        assertNull(legacyNameConflict.getOrderId());
    }

    @Test
    void oldGenerationOrderResolvesLogicalListingAndOldGenerationSkuAfterCurrentSwitch() {
        UUID productId = insertProduct(true);
        UUID oldSpecId = insertProductSpec(productId, "月卡");
        UUID currentSpecId = insertProductSpec(productId, "年卡");
        SalesChannelAccount account = account("old-generation-order");
        SalesChannelListing listing = listing(account, productId, "generation-item-a", false);
        SalesChannelListingGeneration generationOne = generationRepository
                .findCurrentByListingId(listing.getId()).orElseThrow();
        generationOne.setGenerationState(SalesChannelGenerationState.RETIRED);
        generationOne.setRetiredAt(LocalDateTime.now());
        generationRepository.saveAndFlush(generationOne);
        SalesChannelListingGeneration generationTwo = new SalesChannelListingGeneration(
                listing.getId(), account.getId(), 2L, "generation-item-b",
                SalesChannelGenerationState.CURRENT, "PUBLISH");
        generationTwo.setActivatedAt(LocalDateTime.now());
        generationRepository.saveAndFlush(generationTwo);

        SalesChannelListingGenerationSpec oldMapping = new SalesChannelListingGenerationSpec();
        oldMapping.setSalesChannelListingGenerationId(generationOne.getId());
        oldMapping.setProductSpecId(oldSpecId);
        oldMapping.setExternalSkuId("sku-old-month");
        oldMapping.setProviderMapping(Map.of("provider_spec_name", "规格", "provider_spec_value", "月卡"));
        generationSpecRepository.saveAndFlush(oldMapping);
        SalesChannelListingGenerationSpec currentMapping = new SalesChannelListingGenerationSpec();
        currentMapping.setSalesChannelListingGenerationId(generationTwo.getId());
        currentMapping.setProductSpecId(currentSpecId);
        currentMapping.setExternalSkuId("sku-current-year");
        currentMapping.setProviderMapping(Map.of("provider_spec_name", "规格", "provider_spec_value", "年卡"));
        generationSpecRepository.saveAndFlush(currentMapping);

        SalesChannelOrder imported = importService.importOrder(account,
                order(account, "xy-old-generation-1", "generation-item-a", "sku-old-month", "月卡", 1, "20.00"));

        assertEquals(SalesChannelOrderIngestStatus.IMPORTED, imported.getIngestStatus());
        assertEquals(listing.getId(), imported.getSalesChannelListingId());
        assertEquals(generationOne.getId(), imported.getSalesChannelListingGenerationId());
        assertEquals(oldSpecId, scalar("select spec_id from order_items where order_id = ?", UUID.class,
                imported.getOrderId()));
        assertEquals(generationTwo.getId(), generationRepository.findCurrentByListingId(listing.getId())
                .orElseThrow().getId());
    }

    @Test
    void specMappingBackfillIsExactAtomicIdempotentAndDoesNotCreateListingOperations() {
        UUID productId = insertProduct(true);
        UUID monthId = insertProductSpec(productId, "月卡");
        UUID yearId = insertProductSpec(productId, "年卡");
        UUID lifetimeId = insertProductSpec(productId, "永久卡");
        SalesChannelAccount account = account("spec-backfill");
        SalesChannelListing listing = listing(account, productId, "item-spec-backfill", false);
        insertListingSpec(listing, monthId);
        insertListingSpec(listing, yearId);
        insertListingSpec(listing, lifetimeId);

        long desiredRevision = listing.getDesiredRevision();
        long appliedRevision = listing.getAppliedRevision();
        int operationCount = count("select count(*) from sales_channel_operations where sales_channel_listing_id = ?",
                listing.getId());

        when(providerClient.getListingSpecMappings(any())).thenReturn(new IntegrationEnvelope<>(
                "provider-read",
                Outcome.SUCCEEDED,
                new ListingSpecMappingsData("item-spec-backfill",
                        List.of(new SpecMappingSpecification("规格", List.of(
                                new SpecMappingSpecificationValue("月卡"),
                                new SpecMappingSpecificationValue("年卡"),
                                new SpecMappingSpecificationValue("永久卡"),
                                new SpecMappingSpecificationValue("测试卡")))),
                        List.of(
                                new SpecMappingSkuRow(null, Map.of("规格", "永久卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "月卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "年卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "测试卡")))),
                null));

        SalesChannelSpecMappingException failure = assertThrows(SalesChannelSpecMappingException.class,
                () -> specMappingSyncService.sync(listing.getId()));
        assertEquals("SPEC_MAPPING_INCOMPLETE", failure.getCode());
        UUID generationId = generationRepository.findCurrentByListingId(listing.getId()).orElseThrow().getId();
        assertEquals(3, count("select count(*) from sales_channel_listing_generation_specs "
                + "where sales_channel_listing_generation_id = ? and provider_mapping = '{}'::jsonb and external_sku_id is null",
                generationId));
        assertEquals(desiredRevision, listingRepository.findById(listing.getId()).orElseThrow().getDesiredRevision());
        assertEquals(appliedRevision, listingRepository.findById(listing.getId()).orElseThrow().getAppliedRevision());
        assertEquals(operationCount, count("select count(*) from sales_channel_operations where sales_channel_listing_id = ?",
                listing.getId()));

        when(providerClient.getListingSpecMappings(any())).thenReturn(new IntegrationEnvelope<>(
                "provider-read",
                Outcome.SUCCEEDED,
                new ListingSpecMappingsData("item-spec-backfill",
                        List.of(new SpecMappingSpecification("规格", List.of(
                                new SpecMappingSpecificationValue("月卡"),
                                new SpecMappingSpecificationValue("年卡"),
                                new SpecMappingSpecificationValue("永久卡")))),
                        List.of(
                                new SpecMappingSkuRow(null, Map.of("规格", "永久卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "月卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "年卡")))),
                null));

        var first = specMappingSyncService.sync(listing.getId());
        assertEquals(3, first.matched());
        assertEquals("item-spec-backfill", first.itemId());

        Map<UUID, SalesChannelListingGenerationSpec> rows = generationSpecRepository
                .findBySalesChannelListingGenerationIdOrderByCreatedAtAsc(generationId).stream()
                .collect(java.util.stream.Collectors.toMap(SalesChannelListingGenerationSpec::getProductSpecId, row -> row));
        assertEquals(Map.of("provider_spec_name", "规格", "provider_spec_value", "月卡"),
                rows.get(monthId).getProviderMapping());
        assertEquals(Map.of("provider_spec_name", "规格", "provider_spec_value", "年卡"),
                rows.get(yearId).getProviderMapping());
        assertEquals(Map.of("provider_spec_name", "规格", "provider_spec_value", "永久卡"),
                rows.get(lifetimeId).getProviderMapping());
        assertNull(rows.get(monthId).getExternalSkuId());
        assertNull(rows.get(yearId).getExternalSkuId());
        assertNull(rows.get(lifetimeId).getExternalSkuId());
        assertEquals(desiredRevision, listingRepository.findById(listing.getId()).orElseThrow().getDesiredRevision());
        assertEquals(appliedRevision, listingRepository.findById(listing.getId()).orElseThrow().getAppliedRevision());
        assertEquals(operationCount, count("select count(*) from sales_channel_operations where sales_channel_listing_id = ?",
                listing.getId()));

        var second = specMappingSyncService.sync(listing.getId());
        assertEquals(first.mappings(), second.mappings());
        assertEquals(3, count("select count(*) from sales_channel_listing_generation_specs where sales_channel_listing_generation_id = ?",
                generationId));
        assertEquals(operationCount, count("select count(*) from sales_channel_operations where sales_channel_listing_id = ?",
                listing.getId()));
        verify(providerClient, org.mockito.Mockito.times(3)).getListingSpecMappings(any());
    }

    @Test
    void specMappingOrderImportUsesExactProviderValueAndRejectsFuzzyValue() {
        UUID productId = insertProduct(true);
        UUID monthId = insertProductSpec(productId, "月卡");
        UUID yearId = insertProductSpec(productId, "年卡");
        UUID lifetimeId = insertProductSpec(productId, "永久卡");
        SalesChannelAccount account = account("spec-order-backfill");
        SalesChannelListing listing = listing(account, productId, "item-spec-order-backfill", false);
        insertListingSpec(listing, monthId);
        insertListingSpec(listing, yearId);
        insertListingSpec(listing, lifetimeId);
        when(providerClient.getListingSpecMappings(any())).thenReturn(new IntegrationEnvelope<>(
                "provider-read",
                Outcome.SUCCEEDED,
                new ListingSpecMappingsData("item-spec-order-backfill",
                        List.of(new SpecMappingSpecification("规格", List.of(
                                new SpecMappingSpecificationValue("永久卡"),
                                new SpecMappingSpecificationValue("月卡"),
                                new SpecMappingSpecificationValue("年卡")))),
                        List.of(
                                new SpecMappingSkuRow(null, Map.of("规格", "永久卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "月卡")),
                                new SpecMappingSkuRow(null, Map.of("规格", "年卡")))),
                null));
        specMappingSyncService.sync(listing.getId());

        SalesChannelOrder month = importService.importOrder(account,
                order(account, "xy-spec-backfill-month", "item-spec-order-backfill", null, "月卡", 1, "20.00"));
        SalesChannelOrder year = importService.importOrder(account,
                order(account, "xy-spec-backfill-year", "item-spec-order-backfill", null, "年卡", 1, "20.00"));
        SalesChannelOrder lifetime = importService.importOrder(account,
                order(account, "xy-spec-backfill-lifetime", "item-spec-order-backfill", null, "永久卡", 1, "20.00"));

        assertEquals(monthId, scalar("select spec_id from order_items where order_id = ?", UUID.class, month.getOrderId()));
        assertEquals(yearId, scalar("select spec_id from order_items where order_id = ?", UUID.class, year.getOrderId()));
        assertEquals(lifetimeId, scalar("select spec_id from order_items where order_id = ?", UUID.class, lifetime.getOrderId()));

        SalesChannelOrder fuzzy = importService.importOrder(account,
                order(account, "xy-spec-backfill-fuzzy", "item-spec-order-backfill", null, "月", 1, "20.00"));
        assertEquals("MAPPING_ERROR", fuzzy.getIngestStatus().name());
        assertEquals("SPEC_MAPPING_ERROR", fuzzy.getLastErrorCode());
        assertNull(fuzzy.getOrderId());
    }

    @Test
    void wrongAccountCannotCrossMatchAnotherAccountListing() {
        UUID productId = insertProduct(false);
        SalesChannelAccount owner = account("owner");
        SalesChannelAccount wrongAccount = account("wrong");
        listing(owner, productId, "item-owned-by-owner", false);

        SalesChannelOrder row = importService.importOrder(wrongAccount,
                order(wrongAccount, "xy-cross-account-1", "item-owned-by-owner", null, null, 1, "10.00"));

        assertEquals("MAPPING_ERROR", row.getIngestStatus().name());
        assertEquals("LISTING_NOT_FOUND", row.getLastErrorCode());
        assertNull(row.getOrderId());
        assertEquals(0, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + wrongAccount.getProviderAccountId() + ":xy-cross-account-1"));
    }

    @Test
    void channelAutoFulfillmentSnapshotControlsGlobalRetryScheduler() {
        UUID disabledProductId = insertProduct(false);
        SalesChannelAccount disabledAccount = account("retry-disabled");
        listing(disabledAccount, disabledProductId, "item-retry-disabled", false);
        UUID disabledCardId = insertCard(disabledProductId, null, "retry-disabled-card");
        SalesChannelOrder disabled = importService.importOrder(disabledAccount,
                order(disabledAccount, "xy-retry-disabled", "item-retry-disabled", null, null, 1, "10.00"));

        UUID enabledProductId = insertProduct(false);
        SalesChannelAccount enabledAccount = account("retry-enabled");
        listing(enabledAccount, enabledProductId, "item-retry-enabled", true);
        SalesChannelOrder enabled = importService.importOrder(enabledAccount,
                order(enabledAccount, "xy-retry-enabled", "item-retry-enabled", null, null, 1, "10.00"));
        assertEquals(OrderStatus.PAID.name(), scalar("select status from orders where id = ?", String.class,
                enabled.getOrderId()));
        UUID enabledCardId = insertCard(enabledProductId, null, "retry-enabled-card");
        UUID normalProductId = insertProduct(false);
        UUID normalOrderId = insertNormalRetryOrder(normalProductId);
        UUID normalCardId = insertCard(normalProductId, null, "retry-normal-card");

        List<com.myshop.entity.Order> due = orderRepository.findPaidOrdersWithPendingAutoItems(
                PageRequest.of(0, 100));
        assertFalse(due.stream().anyMatch(candidate -> disabled.getOrderId().equals(candidate.getId())));
        assertTrue(due.stream().anyMatch(candidate -> enabled.getOrderId().equals(candidate.getId())));
        assertTrue(due.stream().anyMatch(candidate -> normalOrderId.equals(candidate.getId())));

        autoFulfillmentService.retryPendingPaidOrders();

        assertEquals(OrderStatus.PAID.name(), scalar("select status from orders where id = ?", String.class,
                disabled.getOrderId()));
        assertEquals(0, count("select count(*) from card_keys where id = ? and status = 'SOLD'", disabledCardId));
        assertEquals(OrderStatus.DELIVERED.name(), scalar("select status from orders where id = ?", String.class,
                enabled.getOrderId()));
        assertEquals(1, count("select count(*) from card_keys where id = ? and status = 'SOLD'", enabledCardId));
        assertEquals(OrderStatus.DELIVERED.name(), scalar("select status from orders where id = ?", String.class,
                normalOrderId));
        assertEquals(1, count("select count(*) from card_keys where id = ? and status = 'SOLD'", normalCardId));
    }

    @Test
    void deliveryDueQueryAndClaimRejectPermanentAndFutureFailures() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("due-query");
        listing(account, productId, "item-due-query", false);
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-due-query", "item-due-query", null, null, 1, "10.00"));

        SalesChannelDeliveryOperation permanent = deliveryOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.FAILED, null);
        SalesChannelDeliveryOperation future = deliveryOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.FAILED, LocalDateTime.now().plusMinutes(5));
        SalesChannelDeliveryOperation past = deliveryOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.FAILED, LocalDateTime.now().minusMinutes(5));
        List<SalesChannelDeliveryOperation> due = deliveryOperationRepository.findDue(
                List.of(SalesChannelDeliveryOperationStatus.PENDING, SalesChannelDeliveryOperationStatus.FAILED),
                LocalDateTime.now(), PageRequest.of(0, 100));

        assertFalse(due.stream().anyMatch(operation -> permanent.getId().equals(operation.getId())));
        assertFalse(due.stream().anyMatch(operation -> future.getId().equals(operation.getId())));
        assertTrue(due.stream().anyMatch(operation -> past.getId().equals(operation.getId())));
        assertTrue(deliveryClaimService.claim(permanent.getId()).isEmpty());
        assertTrue(deliveryClaimService.claim(future.getId()).isEmpty());
        assertTrue(deliveryClaimService.claim(past.getId()).isPresent());
    }

    @Test
    void maxAttemptsStopsRetryAndReconcileExhaustionRequiresReview() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("max-attempts");
        listing(account, productId, "item-max-attempts", false);
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-max-attempts", "item-max-attempts", null, null, 1, "10.00"));

        SalesChannelDeliveryOperation send = deliveryOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.RUNNING, null);
        send.setAttemptCount(8);
        deliveryOperationRepository.saveAndFlush(send);
        deliveryStateService.failed(send.getId(), "TEMPORARY_PROVIDER_ERROR", "retryable", true);
        SalesChannelDeliveryOperation storedSend = deliveryOperationRepository.findById(send.getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, storedSend.getStatus());
        assertNull(storedSend.getNextAttemptAt());
        assertEquals("DELIVERY_MAX_ATTEMPTS_EXCEEDED", storedSend.getLastErrorCode());
        assertEquals(SalesChannelOrderDeliveryStatus.FAILED,
                channelOrderRepository.findById(row.getId()).orElseThrow().getDeliveryStatus());
        assertTrue(deliveryClaimService.claim(send.getId()).isEmpty());

        SalesChannelDeliveryOperation reconcile = deliveryOperation(row,
                SalesChannelDeliveryOperationType.RECONCILE_MESSAGE,
                SalesChannelDeliveryOperationStatus.RUNNING, null);
        reconcile.setAttemptCount(8);
        deliveryOperationRepository.saveAndFlush(reconcile);
        deliveryStateService.failed(reconcile.getId(), "MESSAGE_UNKNOWN", "still unknown", true);
        assertEquals(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED,
                channelOrderRepository.findById(row.getId()).orElseThrow().getDeliveryStatus());
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED,
                deliveryOperationRepository.findById(reconcile.getId()).orElseThrow().getStatus());
        assertNull(deliveryOperationRepository.findById(reconcile.getId()).orElseThrow().getNextAttemptAt());
    }

    @Test
    void staleSendConfirmAndReconcileRecoverWithoutReplayingSideEffects() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("stale-recovery");
        listing(account, productId, "item-stale-recovery", false);
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-stale-recovery", "item-stale-recovery", null, null, 1, "10.00"));
        LocalDateTime old = LocalDateTime.now().minusMinutes(10);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);

        SalesChannelDeliveryOperation send = deliveryOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.RUNNING, null);
        send.setStartedAt(old);
        send.setAttemptCount(1);
        deliveryOperationRepository.saveAndFlush(send);

        SalesChannelDeliveryOperation confirm = deliveryOperation(row, SalesChannelDeliveryOperationType.CONFIRM_DELIVERY,
                SalesChannelDeliveryOperationStatus.RUNNING, null);
        confirm.setStartedAt(old);
        confirm.setAttemptCount(1);
        deliveryOperationRepository.saveAndFlush(confirm);

        SalesChannelDeliveryOperation reconcile = deliveryOperation(row,
                SalesChannelDeliveryOperationType.RECONCILE_MESSAGE,
                SalesChannelDeliveryOperationStatus.RUNNING, null);
        reconcile.setStartedAt(old);
        reconcile.setAttemptCount(1);
        reconcile.setResultJson(new LinkedHashMap<>(Map.of("target_request_id", send.getRequestId())));
        deliveryOperationRepository.saveAndFlush(reconcile);

        assertEquals(3, deliveryRecoveryService.recoverStaleRunning(cutoff, 100));

        SalesChannelDeliveryOperation storedSend = deliveryOperationRepository.findById(send.getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN, storedSend.getStatus());
        SalesChannelDeliveryOperation sendReconcile = deliveryOperationRepository
                .findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(row.getId(),
                        SalesChannelDeliveryOperationType.RECONCILE_MESSAGE).stream()
                .filter(operation -> send.getRequestId().equals(
                        String.valueOf(operation.getResultJson().get("target_request_id"))))
                .findFirst().orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.PENDING, sendReconcile.getStatus());

        SalesChannelDeliveryOperation storedConfirm = deliveryOperationRepository.findById(confirm.getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN, storedConfirm.getStatus());
        SalesChannelDeliveryOperation confirmReconcile = deliveryOperationRepository
                .findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(row.getId(),
                        SalesChannelDeliveryOperationType.RECONCILE_CONFIRM).stream()
                .findFirst().orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.PENDING, confirmReconcile.getStatus());

        SalesChannelDeliveryOperation storedReconcile = deliveryOperationRepository.findById(reconcile.getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.PENDING, storedReconcile.getStatus());
        assertEquals(reconcile.getRequestId(), storedReconcile.getRequestId());
        assertNotNull(storedReconcile.getNextAttemptAt());
    }

    @Test
    void plannerPreservesMessageUnknownProjection() {
        UnknownMessageFixture fixture = unknownMessageFixture("planner-unknown");

        deliveryPlanner.plan(fixture.row().getId());

        assertEquals(SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN,
                channelOrderRepository.findById(fixture.row().getId()).orElseThrow().getDeliveryStatus());
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN,
                deliveryOperationRepository.findById(fixture.send().getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelDeliveryOperationStatus.PENDING,
                deliveryOperationRepository.findById(fixture.reconcile().getId()).orElseThrow().getStatus());
    }

    @Test
    void reconcileUnknownRetryPreservesProjectionAndNeverSendsAgain() {
        UnknownMessageFixture fixture = unknownMessageFixture("reconcile-unknown");
        when(providerClient.reconcileDeliveryUnit(any())).thenReturn(
                reconcileResponse(fixture.send().getRequestId(), Outcome.UNKNOWN, false));

        deliveryWorker().process(fixture.reconcile());

        SalesChannelDeliveryOperation reconcile = deliveryOperationRepository
                .findById(fixture.reconcile().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, reconcile.getStatus());
        assertNotNull(reconcile.getNextAttemptAt());
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN,
                deliveryOperationRepository.findById(fixture.send().getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN,
                channelOrderRepository.findById(fixture.row().getId()).orElseThrow().getDeliveryStatus());

        deliveryPlanner.plan(fixture.row().getId());
        assertEquals(SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN,
                channelOrderRepository.findById(fixture.row().getId()).orElseThrow().getDeliveryStatus());
        verify(providerClient, never()).sendDeliveryUnit(any());
    }

    @Test
    void retryableFailedMessageReconciliationRestoresPendingAndKeepsRequestId() {
        UnknownMessageFixture fixture = unknownMessageFixture("reconcile-retryable");
        String requestId = fixture.send().getRequestId();
        when(providerClient.reconcileDeliveryUnit(any())).thenReturn(
                reconcileResponse(requestId, Outcome.FAILED, true));

        deliveryWorker().process(fixture.reconcile());

        SalesChannelDeliveryOperation target = deliveryOperationRepository
                .findById(fixture.send().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, target.getStatus());
        assertNotNull(target.getNextAttemptAt());
        assertEquals(requestId, target.getRequestId());
        assertEquals(SalesChannelOrderDeliveryStatus.MESSAGE_PENDING,
                channelOrderRepository.findById(fixture.row().getId()).orElseThrow().getDeliveryStatus());

        target.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        deliveryOperationRepository.saveAndFlush(target);
        SalesChannelDeliveryOperation claimed = deliveryClaimService.claim(target.getId()).orElseThrow();
        assertEquals(requestId, claimed.getRequestId());
        verify(providerClient, never()).sendDeliveryUnit(any());
    }

    @Test
    void retryableFailedConfirmReconciliationRestoresPendingAndKeepsRequestId() {
        UnknownConfirmFixture fixture = unknownConfirmFixture("reconcile-retryable-confirm");
        String requestId = fixture.target().getRequestId();
        when(providerClient.reconcileConfirmDelivery(any())).thenReturn(
                reconcileResponse(requestId, Outcome.FAILED, true));

        deliveryWorker().process(fixture.reconcile());

        SalesChannelDeliveryOperation target = deliveryOperationRepository
                .findById(fixture.target().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, target.getStatus());
        assertNotNull(target.getNextAttemptAt());
        assertEquals(requestId, target.getRequestId());
        assertEquals(SalesChannelOrderDeliveryStatus.CONFIRM_PENDING,
                channelOrderRepository.findById(fixture.row().getId()).orElseThrow().getDeliveryStatus());

        target.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        deliveryOperationRepository.saveAndFlush(target);
        SalesChannelDeliveryOperation claimed = deliveryClaimService.claim(target.getId()).orElseThrow();
        assertEquals(requestId, claimed.getRequestId());
        verify(providerClient, never()).confirmDelivery(any());
    }

    @Test
    void permanentFailedMessageAndConfirmReconciliationProjectFailedAndStopRetry() {
        UnknownMessageFixture message = unknownMessageFixture("reconcile-permanent-message");
        UnknownConfirmFixture confirm = unknownConfirmFixture("reconcile-permanent-confirm");
        when(providerClient.reconcileDeliveryUnit(any())).thenReturn(
                reconcileResponse(message.send().getRequestId(), Outcome.FAILED, false));
        when(providerClient.reconcileConfirmDelivery(any())).thenReturn(
                reconcileResponse(confirm.target().getRequestId(), Outcome.FAILED, false));

        SalesChannelDeliveryWorker worker = deliveryWorker();
        worker.process(message.reconcile());
        worker.process(confirm.reconcile());

        SalesChannelDeliveryOperation messageTarget = deliveryOperationRepository
                .findById(message.send().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, messageTarget.getStatus());
        assertNull(messageTarget.getNextAttemptAt());
        assertEquals(SalesChannelOrderDeliveryStatus.FAILED,
                channelOrderRepository.findById(message.row().getId()).orElseThrow().getDeliveryStatus());
        assertTrue(deliveryOperationRepository.findDue(
                List.of(SalesChannelDeliveryOperationStatus.PENDING, SalesChannelDeliveryOperationStatus.FAILED),
                LocalDateTime.now(), PageRequest.of(0, 100)).stream()
                .noneMatch(operation -> messageTarget.getId().equals(operation.getId())));

        SalesChannelDeliveryOperation confirmTarget = deliveryOperationRepository
                .findById(confirm.target().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, confirmTarget.getStatus());
        assertNull(confirmTarget.getNextAttemptAt());
        assertEquals(SalesChannelOrderDeliveryStatus.FAILED,
                channelOrderRepository.findById(confirm.row().getId()).orElseThrow().getDeliveryStatus());
        verify(providerClient, never()).sendDeliveryUnit(any());
        verify(providerClient, never()).confirmDelivery(any());
    }

    @Test
    void staleReconciliationAtMaxAttemptsBecomesReviewWithoutChangingUnknownTarget() {
        UnknownMessageFixture message = unknownMessageFixture("stale-reconcile-max-message");
        UnknownConfirmFixture confirm = unknownConfirmFixture("stale-reconcile-max-confirm");
        LocalDateTime old = LocalDateTime.now().minusMinutes(10);

        prepareStaleReconcileAtMax(message.reconcile(), message.send().getRequestId(), old);
        prepareStaleReconcileAtMax(confirm.reconcile(), confirm.target().getRequestId(), old);

        assertEquals(2, deliveryRecoveryService.recoverStaleRunning(
                LocalDateTime.now().minusMinutes(5), 100));

        SalesChannelDeliveryOperation messageReconcile = deliveryOperationRepository
                .findById(message.reconcile().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, messageReconcile.getStatus());
        assertNull(messageReconcile.getNextAttemptAt());
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN,
                deliveryOperationRepository.findById(message.send().getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED,
                channelOrderRepository.findById(message.row().getId()).orElseThrow().getDeliveryStatus());

        SalesChannelDeliveryOperation confirmReconcile = deliveryOperationRepository
                .findById(confirm.reconcile().getId()).orElseThrow();
        assertEquals(SalesChannelDeliveryOperationStatus.FAILED, confirmReconcile.getStatus());
        assertNull(confirmReconcile.getNextAttemptAt());
        assertEquals(SalesChannelDeliveryOperationStatus.UNKNOWN,
                deliveryOperationRepository.findById(confirm.target().getId()).orElseThrow().getStatus());
        assertEquals(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED,
                channelOrderRepository.findById(confirm.row().getId()).orElseThrow().getDeliveryStatus());
        verify(providerClient, never()).reconcileDeliveryUnit(any());
        verify(providerClient, never()).reconcileConfirmDelivery(any());
    }

    @Test
    void plannerAndRecoveryCannotOverwriteReviewProjection() {
        UnknownMessageFixture fixture = unknownMessageFixture("review-sticky");
        SalesChannelOrder row = channelOrderRepository.findById(fixture.row().getId()).orElseThrow();
        row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED);
        row.setLastErrorCode("MANUAL_REVIEW");
        channelOrderRepository.saveAndFlush(row);

        deliveryPlanner.plan(row.getId());
        deliveryRecoveryService.recoverStaleRunning(LocalDateTime.now().minusMinutes(5), 100);
        deliveryPlanner.plan(row.getId());

        SalesChannelOrder stored = channelOrderRepository.findById(row.getId()).orElseThrow();
        assertEquals(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED, stored.getDeliveryStatus());
        assertEquals("MANUAL_REVIEW", stored.getLastErrorCode());
    }

    @Test
    void stableCardKeyUnitMappingSurvivesRepeatedPlanningAndReload() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("stable-units");
        listing(account, productId, "item-stable-units", true);
        insertCard(productId, null, "stable-card-1");
        insertCard(productId, null, "stable-card-2");
        insertCard(productId, null, "stable-card-3");
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-stable-units", "item-stable-units", null, null, 3, "30.00"));

        deliveryPlanner.plan(row.getId());
        Map<UUID, Integer> first = deliveryOperationRepository
                .findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(row.getId(),
                        SalesChannelDeliveryOperationType.SEND_MESSAGE).stream()
                .collect(java.util.stream.Collectors.toMap(SalesChannelDeliveryOperation::getCardKeyId,
                        SalesChannelDeliveryOperation::getUnitIndex));
        entityManager.clear();
        deliveryPlanner.plan(row.getId());
        deliveryPlanner.plan(row.getId());
        Map<UUID, Integer> second = deliveryOperationRepository
                .findBySalesChannelOrderIdAndOperationTypeOrderByUnitIndexAsc(row.getId(),
                        SalesChannelDeliveryOperationType.SEND_MESSAGE).stream()
                .collect(java.util.stream.Collectors.toMap(SalesChannelDeliveryOperation::getCardKeyId,
                        SalesChannelDeliveryOperation::getUnitIndex));

        assertEquals(3, first.size());
        assertEquals(first, second);
        assertEquals(3, second.size());
    }

    @Test
    void terminalStatusWithSameImmutableFingerprintIsHandledAndNotConflicted() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("terminal-fingerprint");
        listing(account, productId, "item-terminal-fingerprint", false);
        AgentOrder pending = order(account, "xy-terminal-fingerprint", "item-terminal-fingerprint",
                null, null, 1, "10.00");
        SalesChannelOrder row = importService.importOrder(account, pending);
        AgentOrder refunded = new AgentOrder(account.getProviderAccountId(), pending.externalOrderNo(), "refunded",
                pending.itemId(), pending.externalSkuId(), pending.specName(), pending.specValue(), pending.quantity(),
                pending.amount(), pending.currency(), pending.placedAt(), "2026-09-22T00:00:02Z");

        SalesChannelOrder updated = importService.importOrder(account, refunded);

        assertEquals(row.getId(), updated.getId());
        assertEquals("IMPORTED", updated.getIngestStatus().name());
        assertEquals("refunded", updated.getExternalStatus());
        assertEquals(OrderStatus.REFUNDED.name(), scalar("select status from orders where id = ?", String.class,
                row.getOrderId()));
        assertEquals("CANCELLED", scalar("select fulfillment_status from order_items where order_id = ?",
                String.class, row.getOrderId()));
        assertEquals(0, count("select count(*) from sales_channel_orders where ingest_status = 'SOURCE_CONFLICT' and id = ?",
                row.getId()));
    }

    @Test
    void preS23ImportedOrderKeepsFingerprintAcrossStableIdentityEnrichment() throws Exception {
        UUID productId = insertProduct(true);
        UUID specId = insertProductSpec(productId, "legacy spec");
        SalesChannelAccount account = account("historical-fingerprint-enrichment");
        SalesChannelListing listing = listing(account, productId, "historical-enrichment-item", false);
        SalesChannelListingGeneration generation = generationRepository
                .findCurrentByListingId(listing.getId()).orElseThrow();
        SalesChannelListingGenerationSpec mapping = new SalesChannelListingGenerationSpec();
        mapping.setSalesChannelListingGenerationId(generation.getId());
        mapping.setProductSpecId(specId);
        mapping.setExternalSkuId("provider-sku");
        mapping.setProviderMapping(Map.of("provider_spec_name", "规格", "provider_spec_value", "Blue"));
        generationSpecRepository.saveAndFlush(mapping);
        AgentOrder original = order(account, "xy-historical-enrichment", "historical-enrichment-item",
                "provider-sku", "Blue", 1, "20.00");
        SalesChannelOrder imported = importService.importOrder(account, original);
        String storedHistoricalFingerprint = historicalFingerprint(original, null, null);
        imported.setSourceFingerprint(storedHistoricalFingerprint);
        channelOrderRepository.saveAndFlush(imported);
        AgentOrder enriched = new AgentOrder(original.accountId(), original.externalOrderNo(), "refunded",
                original.itemId(), original.externalSkuId(), original.specName(), original.specValue(),
                original.quantity(), original.amount(), original.currency(), original.placedAt(),
                "2026-09-22T00:00:02Z", listing.getId().toString().toLowerCase(),
                specId.toString().toLowerCase());

        SalesChannelOrder updated = importService.importOrder(account, enriched);

        assertEquals(storedHistoricalFingerprint, updated.getSourceFingerprint());
        assertEquals(imported.getId(), updated.getId());
        assertEquals(imported.getOrderId(), updated.getOrderId());
        assertEquals(SalesChannelOrderIngestStatus.IMPORTED, updated.getIngestStatus());
        assertEquals("refunded", updated.getExternalStatus());
        assertNotEquals("SOURCE_FINGERPRINT_CHANGED", updated.getLastErrorCode());
        assertEquals(1, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-historical-enrichment"));
    }

    @Test
    void historicalMappingErrorRecoversInPlaceFromExactStableKeysExactlyOnce() {
        UUID productId = insertProduct(true);
        UUID specId = insertProductSpec(productId, "recoverable spec");
        SalesChannelAccount account = account("mapping-error-enrichment");
        SalesChannelListing listing = listing(account, productId, "mapping-error-item", false);
        SalesChannelListingGeneration generation = generationRepository
                .findCurrentByListingId(listing.getId()).orElseThrow();
        AgentOrder oldEvent = order(account, "xy-mapping-error-enrichment", "mapping-error-item",
                "provider-sku-recover", "Blue", 1, "20.00");
        SalesChannelOrder failed = importService.importOrder(account, oldEvent);
        assertEquals(SalesChannelOrderIngestStatus.MAPPING_ERROR, failed.getIngestStatus());
        assertNull(failed.getOrderId());

        SalesChannelListingGenerationSpec mapping = new SalesChannelListingGenerationSpec();
        mapping.setSalesChannelListingGenerationId(generation.getId());
        mapping.setProductSpecId(specId);
        mapping.setExternalSkuId("provider-sku-recover");
        mapping.setProviderMapping(Map.of("provider_spec_name", "规格", "provider_spec_value", "Blue"));
        generationSpecRepository.saveAndFlush(mapping);
        AgentOrder enriched = new AgentOrder(oldEvent.accountId(), oldEvent.externalOrderNo(), oldEvent.status(),
                oldEvent.itemId(), oldEvent.externalSkuId(), oldEvent.specName(), oldEvent.specValue(),
                oldEvent.quantity(), oldEvent.amount(), oldEvent.currency(), oldEvent.placedAt(), oldEvent.updatedAt(),
                listing.getId().toString().toLowerCase(), specId.toString().toLowerCase());

        SalesChannelOrder recovered = importService.importOrder(account, enriched);
        SalesChannelOrder replay = importService.importOrder(account, enriched);

        assertEquals(failed.getId(), recovered.getId());
        assertEquals(failed.getId(), replay.getId());
        assertEquals(SalesChannelOrderIngestStatus.IMPORTED, recovered.getIngestStatus());
        assertNotNull(recovered.getOrderId());
        assertEquals(recovered.getOrderId(), replay.getOrderId());
        assertEquals(specId, orderItemRepository.findByOrderId(recovered.getOrderId()).getFirst().getSpecId());
        assertEquals(1, count("select count(*) from sales_channel_orders where external_order_no = ?",
                "xy-mapping-error-enrichment"));
        assertEquals(1, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-mapping-error-enrichment"));
    }

    @Test
    void stableListingIdentityConflictOnImportedOrderFailsClosedWithoutDuplicate() {
        UUID productId = insertProduct(false);
        UUID otherProductId = insertProduct(false);
        SalesChannelAccount account = account("imported-listing-conflict");
        SalesChannelListing listing = listing(account, productId, "imported-listing-conflict-item", false);
        SalesChannelListing other = listingWithoutGeneration(account, otherProductId);
        AgentOrder original = order(account, "xy-imported-listing-conflict",
                "imported-listing-conflict-item", null, null, 1, "10.00");
        SalesChannelOrder imported = importService.importOrder(account, original);
        AgentOrder enriched = new AgentOrder(original.accountId(), original.externalOrderNo(), original.status(),
                original.itemId(), original.externalSkuId(), original.specName(), original.specValue(),
                original.quantity(), original.amount(), original.currency(), original.placedAt(), original.updatedAt(),
                other.getId().toString().toLowerCase(), null);

        SalesChannelOrder conflict = importService.importOrder(account, enriched);

        assertEquals(imported.getId(), conflict.getId());
        assertEquals(imported.getOrderId(), conflict.getOrderId());
        assertEquals(SalesChannelOrderIngestStatus.MAPPING_ERROR, conflict.getIngestStatus());
        assertEquals("LISTING_IDENTITY_CONFLICT", conflict.getLastErrorCode());
        assertEquals(listing.getId(), conflict.getSalesChannelListingId());
        assertEquals(1, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-imported-listing-conflict"));
    }

    @Test
    void stableSkuIdentityConflictOnImportedSpecOrderFailsClosedWithoutDuplicate() {
        UUID productId = insertProduct(true);
        UUID originalSpecId = insertProductSpec(productId, "persisted spec");
        UUID changedSpecId = insertProductSpec(productId, "enriched wrong spec");
        SalesChannelAccount account = account("imported-sku-conflict");
        SalesChannelListing listing = listingWithoutGeneration(account, productId);
        AgentOrder original = new AgentOrder(account.getProviderAccountId(), "xy-imported-sku-conflict",
                "pending_ship", "stable-provider-item", null, null, null, 1, "10.00", "CNY",
                "2026-09-22T00:00:00Z", "2026-09-22T00:00:01Z",
                listing.getId().toString().toLowerCase(), originalSpecId.toString().toLowerCase());
        SalesChannelOrder imported = importService.importOrder(account, original);
        AgentOrder enriched = new AgentOrder(original.accountId(), original.externalOrderNo(), original.status(),
                original.itemId(), original.externalSkuId(), original.specName(), original.specValue(),
                original.quantity(), original.amount(), original.currency(), original.placedAt(), original.updatedAt(),
                listing.getId().toString().toLowerCase(), changedSpecId.toString().toLowerCase());

        SalesChannelOrder conflict = importService.importOrder(account, enriched);

        assertEquals(imported.getId(), conflict.getId());
        assertEquals(imported.getOrderId(), conflict.getOrderId());
        assertEquals(SalesChannelOrderIngestStatus.MAPPING_ERROR, conflict.getIngestStatus());
        assertEquals("SKU_IDENTITY_CONFLICT", conflict.getLastErrorCode());
        assertEquals(originalSpecId, orderItemRepository.findByOrderId(imported.getOrderId()).getFirst().getSpecId());
        assertEquals(1, count("select count(*) from orders where idempotency_key = ?",
                "XIANYU_ORDER:" + account.getProviderAccountId() + ":xy-imported-sku-conflict"));
    }

    @Test
    void itemAndExternalSkuChangesRemainImmutableSourceFingerprintConflicts() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("immutable-source-facts");
        listing(account, productId, "immutable-source-item", false);
        AgentOrder original = order(account, "xy-immutable-item-change", "immutable-source-item",
                null, null, 1, "10.00");
        SalesChannelOrder first = importService.importOrder(account, original);
        AgentOrder changedItem = new AgentOrder(original.accountId(), original.externalOrderNo(), original.status(),
                "changed-provider-item", original.externalSkuId(), original.specName(), original.specValue(),
                original.quantity(), original.amount(), original.currency(), original.placedAt(), original.updatedAt());
        assertEquals("SOURCE_FINGERPRINT_CHANGED", importService.importOrder(account, changedItem).getLastErrorCode());

        AgentOrder skuOriginal = order(account, "xy-immutable-sku-change", "immutable-source-item",
                "provider-sku-old", "Blue", 1, "10.00");
        SalesChannelOrder second = importService.importOrder(account, skuOriginal);
        AgentOrder changedSku = new AgentOrder(skuOriginal.accountId(), skuOriginal.externalOrderNo(),
                skuOriginal.status(), skuOriginal.itemId(), "provider-sku-new", skuOriginal.specName(),
                skuOriginal.specValue(), skuOriginal.quantity(), skuOriginal.amount(), skuOriginal.currency(),
                skuOriginal.placedAt(), skuOriginal.updatedAt());
        assertEquals("SOURCE_FINGERPRINT_CHANGED", importService.importOrder(account, changedSku).getLastErrorCode());
        assertEquals(first.getId(), channelOrderRepository.findByProviderAndSalesChannelAccountIdAndExternalOrderNo(
                SalesChannelProvider.XIANYU, account.getId(), original.externalOrderNo()).orElseThrow().getId());
        assertEquals(second.getId(), channelOrderRepository.findByProviderAndSalesChannelAccountIdAndExternalOrderNo(
                SalesChannelProvider.XIANYU, account.getId(), skuOriginal.externalOrderNo()).orElseThrow().getId());
    }

    @Test
    void managementTransitionIsBlockedWhilePossiblySentEnsureIsInFlight() {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("mode-transition-in-flight");
        SalesChannelListing listing = listingWithoutGeneration(account, productId);
        SalesChannelOperation ensure = new SalesChannelOperation(listing.getId(),
                com.myshop.constant.SalesChannelOperationType.ENSURE_LISTING, 0,
                "mode-transition-in-flight-" + UUID.randomUUID(), Map.of());
        assertTrue(ensure.start(LocalDateTime.now().minusMinutes(1)));
        assertTrue(ensure.reschedule("AGENT_APPLYING", "retry exact command", LocalDateTime.now().minusSeconds(1)));
        operationRepository.saveAndFlush(ensure);

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> itemBindingTransactionService.link(account.getId(), "transition-item", productId));

        assertEquals(ErrorCode.CONVERGENCE_OPERATION_IN_FLIGHT, rejected.getCode());
        assertEquals(SalesChannelListingManagementMode.MANAGED,
                listingRepository.findById(listing.getId()).orElseThrow().getManagementMode());
        assertEquals(SalesChannelOperationStatus.PENDING,
                operationRepository.findById(ensure.getId()).orElseThrow().getStatus());
    }

    @Test
    void terminalRefundAndFulfillmentConcurrencyNeverProducesRefundedSold() throws Exception {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("refund-race");
        listing(account, productId, "item-refund-race", false);
        insertCard(productId, null, "refund-race-card-1");
        insertCard(productId, null, "refund-race-card-2");
        insertCard(productId, null, "refund-race-card-3");
        AgentOrder pending = order(account, "xy-refund-race", "item-refund-race", null, null, 3, "30.00");
        SalesChannelOrder row = importService.importOrder(account, pending);
        AgentOrder refunded = new AgentOrder(account.getProviderAccountId(), pending.externalOrderNo(), "refunded",
                pending.itemId(), pending.externalSkuId(), pending.specName(), pending.specValue(), pending.quantity(),
                pending.amount(), pending.currency(), pending.placedAt(), "2026-09-22T00:00:02Z");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> refund = executor.submit(() -> {
                ready.countDown();
                await(start);
                importService.importOrder(account, refunded);
            });
            Future<?> fulfill = executor.submit(() -> {
                ready.countDown();
                await(start);
                fulfillmentService.fulfillOrder(row.getOrderId());
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            refund.get(30, TimeUnit.SECONDS);
            fulfill.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        String status = scalar("select status from orders where id = ?", String.class, row.getOrderId());
        int sold = count("select count(*) from card_keys where order_id = ? and status = 'SOLD'", row.getOrderId());
        assertFalse(OrderStatus.REFUNDED.name().equals(status) && sold > 0,
                "a refunded order must never retain SOLD CardKeys");
        if (OrderStatus.REFUNDED.name().equals(status)) {
            assertEquals(0, sold);
        } else {
            assertEquals(OrderStatus.DELIVERED.name(), status);
            assertEquals(3, sold);
            assertEquals(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED,
                    channelOrderRepository.findById(row.getId()).orElseThrow().getDeliveryStatus());
        }
    }

    private UnknownMessageFixture unknownMessageFixture(String suffix) {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account(suffix);
        listing(account, productId, "item-" + suffix, false);
        UUID cardId = insertCard(productId, null, "card-" + suffix);
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-" + suffix, "item-" + suffix, null, null, 1, "10.00"));
        fulfillmentService.fulfillOrder(row.getOrderId());

        String sendRequestId = "XIANYU_DELIVERY:" + row.getId() + ":0";
        SalesChannelDeliveryOperation send = persistOperation(row,
                SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.UNKNOWN, 0, cardId, sendRequestId,
                new LinkedHashMap<>());
        SalesChannelDeliveryOperation reconcile = persistOperation(row,
                SalesChannelDeliveryOperationType.RECONCILE_MESSAGE,
                SalesChannelDeliveryOperationStatus.PENDING, 0, null,
                "XIANYU_RECONCILE_MESSAGE:" + row.getId() + ":0",
                new LinkedHashMap<>(Map.of("target_request_id", sendRequestId)));
        row = channelOrderRepository.findById(row.getId()).orElseThrow();
        row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.MESSAGE_UNKNOWN);
        channelOrderRepository.saveAndFlush(row);
        return new UnknownMessageFixture(row, send, reconcile);
    }

    private UnknownConfirmFixture unknownConfirmFixture(String suffix) {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account(suffix);
        listing(account, productId, "item-" + suffix, false);
        UUID cardId = insertCard(productId, null, "card-" + suffix);
        SalesChannelOrder row = importService.importOrder(account,
                order(account, "xy-" + suffix, "item-" + suffix, null, null, 1, "10.00"));
        fulfillmentService.fulfillOrder(row.getOrderId());

        String sendRequestId = "XIANYU_DELIVERY:" + row.getId() + ":0";
        persistOperation(row, SalesChannelDeliveryOperationType.SEND_MESSAGE,
                SalesChannelDeliveryOperationStatus.SUCCEEDED, 0, cardId, sendRequestId,
                new LinkedHashMap<>(Map.of("status", "sent")));
        String confirmRequestId = "XIANYU_CONFIRM:" + row.getId();
        SalesChannelDeliveryOperation target = persistOperation(row,
                SalesChannelDeliveryOperationType.CONFIRM_DELIVERY,
                SalesChannelDeliveryOperationStatus.UNKNOWN, null, null, confirmRequestId,
                new LinkedHashMap<>());
        SalesChannelDeliveryOperation reconcile = persistOperation(row,
                SalesChannelDeliveryOperationType.RECONCILE_CONFIRM,
                SalesChannelDeliveryOperationStatus.RUNNING, null, null,
                "XIANYU_RECONCILE_CONFIRM:" + row.getId(),
                new LinkedHashMap<>(Map.of("target_request_id", confirmRequestId)));
        row = channelOrderRepository.findById(row.getId()).orElseThrow();
        row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.CONFIRM_UNKNOWN);
        channelOrderRepository.saveAndFlush(row);
        return new UnknownConfirmFixture(row, target, reconcile);
    }

    private void prepareStaleReconcileAtMax(SalesChannelDeliveryOperation operation,
                                            String targetRequestId, LocalDateTime startedAt) {
        operation.setStatus(SalesChannelDeliveryOperationStatus.RUNNING);
        operation.setAttemptCount(8);
        operation.setStartedAt(startedAt);
        operation.setResultJson(new LinkedHashMap<>(Map.of("target_request_id", targetRequestId)));
        deliveryOperationRepository.saveAndFlush(operation);
    }

    private SalesChannelDeliveryOperation persistOperation(
            SalesChannelOrder row,
            SalesChannelDeliveryOperationType type,
            SalesChannelDeliveryOperationStatus status,
            Integer unitIndex,
            UUID cardKeyId,
            String requestId,
            Map<String, Object> result) {
        SalesChannelDeliveryOperation operation = new SalesChannelDeliveryOperation();
        operation.setSalesChannelOrderId(row.getId());
        operation.setOperationType(type);
        operation.setUnitIndex(unitIndex);
        operation.setCardKeyId(cardKeyId);
        operation.setRequestId(requestId);
        operation.setPayloadFingerprint("b".repeat(64));
        operation.setStatus(status);
        operation.setResultJson(result);
        return deliveryOperationRepository.saveAndFlush(operation);
    }

    private IntegrationEnvelope<DeliveryReconcileData> reconcileResponse(String targetRequestId,
                                                                           Outcome targetOutcome,
                                                                           boolean targetRetryable) {
        return new IntegrationEnvelope<>("reconcile-request", Outcome.SUCCEEDED,
                new DeliveryReconcileData(targetRequestId, targetOutcome, "DELIVERY",
                        targetRetryable, Map.of("status", targetOutcome.name().toLowerCase())), null);
    }

    private SalesChannelDeliveryWorker deliveryWorker() {
        return new SalesChannelDeliveryWorker(deliveryPlanner, deliveryOperationRepository,
                deliveryClaimService, deliveryRecoveryService, deliveryStateService,
                channelOrderRepository, orderRepository, accountRepository,
                cardKeyRepository, orderItemRepository, providerClient);
    }

    private record UnknownMessageFixture(SalesChannelOrder row,
                                         SalesChannelDeliveryOperation send,
                                         SalesChannelDeliveryOperation reconcile) { }

    private record UnknownConfirmFixture(SalesChannelOrder row,
                                         SalesChannelDeliveryOperation target,
                                         SalesChannelDeliveryOperation reconcile) { }

    private SalesChannelDeliveryOperation deliveryOperation(
            SalesChannelOrder row,
            SalesChannelDeliveryOperationType type,
            SalesChannelDeliveryOperationStatus status,
            LocalDateTime nextAttemptAt) {
        SalesChannelDeliveryOperation operation = new SalesChannelDeliveryOperation();
        operation.setSalesChannelOrderId(row.getId());
        operation.setOperationType(type);
        operation.setUnitIndex(type == SalesChannelDeliveryOperationType.SEND_MESSAGE
                || type == SalesChannelDeliveryOperationType.RECONCILE_MESSAGE ? nextTestUnitIndex++ : null);
        operation.setRequestId("TEST_DELIVERY:" + UUID.randomUUID());
        operation.setPayloadFingerprint("a".repeat(64));
        operation.setStatus(status);
        operation.setNextAttemptAt(nextAttemptAt);
        operation.setResultJson(new LinkedHashMap<>());
        return deliveryOperationRepository.saveAndFlush(operation);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency test did not start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency test interrupted", exception);
        }
    }

    private void assertManagedClaimResponseRejected(
            BiFunction<String, String, OwnershipClaimData> responseFactory) {
        UUID productId = insertProduct(false);
        SalesChannelAccount account = account("managed-identity");
        String itemId = "item-managed-identity";
        SalesChannelListing listing = linkedOnlyListing(account, productId, itemId);
        listing.setManagementMode(SalesChannelListingManagementMode.MANAGED);
        listing.setStatus(SalesChannelListingStatus.OFFLINE);
        listing.setDesiredState(SalesChannelListingDesiredState.OFFLINE);
        listing.setEnabled(false);
        listing.setAutoFulfillmentEnabled(false);
        listingRepository.saveAndFlush(listing);

        when(providerClient.claimOwnership(any())).thenAnswer(invocation -> {
            OwnershipClaimCommand command = invocation.getArgument(0, OwnershipClaimCommand.class);
            return new IntegrationEnvelope<>(command.requestId(), Outcome.SUCCEEDED,
                    responseFactory.apply(command.accountId(), command.itemId()), null);
        });

        assertThrows(SalesChannelProviderClientException.class,
                () -> takeoverService.takeover(account.getId(), itemId));

        SalesChannelListing unchanged = listingRepository.findById(listing.getId()).orElseThrow();
        assertEquals(SalesChannelListingManagementMode.MANAGED, unchanged.getManagementMode());
        assertEquals(SalesChannelListingStatus.OFFLINE, unchanged.getStatus());
        assertFalse(unchanged.isEnabled());
        assertFalse(unchanged.isAutoFulfillmentEnabled());
        verify(providerClient, org.mockito.Mockito.times(1)).claimOwnership(any());
    }

    private SalesChannelOrder importAfter(CountDownLatch start, CountDownLatch ready,
                                          SalesChannelAccount account, AgentOrder external) throws Exception {
        ready.countDown();
        if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("concurrency test did not start");
        return importService.importOrder(account, external);
    }

    private SalesChannelAccount account(String suffix) {
        SalesChannelAccount account = new SalesChannelAccount();
        account.setProvider(SalesChannelProvider.XIANYU);
        account.setProviderAccountId("phase4-account-" + suffix + "-" + UUID.randomUUID());
        account.setDisplayName("Phase 4 " + suffix);
        account.setStatus(SalesChannelAccountStatus.CONNECTED);
        account.setEnabled(true);
        account.setCapabilities(Map.of("account_type", "FISH_SHOP", "publish", true, "edit", true,
                "stock_sync", true, "sku", true, "video", false));
        return accountRepository.saveAndFlush(account);
    }

    private SalesChannelListing listing(SalesChannelAccount account, UUID productId,
                                        String externalItemId, boolean autoFulfillment) {
        SalesChannelListing listing = new SalesChannelListing();
        listing.setSalesChannelAccountId(account.getId());
        listing.setProductId(productId);
        listing.setEnabled(true);
        listing.setAutoFulfillmentEnabled(autoFulfillment);
        listing.setDesiredState(SalesChannelListingDesiredState.ONLINE);
        listing.setStatus(SalesChannelListingStatus.ONLINE);
        listing.setProviderConfig(Map.of());
        SalesChannelListing saved = listingRepository.saveAndFlush(listing);
        SalesChannelListingGeneration generation = new SalesChannelListingGeneration(
                saved.getId(), account.getId(), 1L, externalItemId,
                SalesChannelGenerationState.CURRENT, "LEGACY_MIGRATION");
        generation.setActivatedAt(LocalDateTime.now());
        generationRepository.saveAndFlush(generation);
        return saved;
    }

    private UUID createRecoveryQueueListing(SalesChannelAccount account, UUID productId, String suffix) {
        SalesChannelListing listing = new SalesChannelListing();
        listing.setSalesChannelAccountId(account.getId());
        listing.setProductId(productId);
        listing.setEnabled(true);
        listing.setDesiredState(SalesChannelListingDesiredState.ONLINE);
        listing.setStatus(SalesChannelListingStatus.ONLINE);
        listing.setProviderConfig(Map.of());
        listing.setDesiredFingerprint("a".repeat(64));
        return listingRepository.saveAndFlush(listing).getId();
    }

    private SalesChannelOperation pendingEnsure(UUID listingId, String requestId,
                                                int attemptedCount, LocalDateTime now) {
        SalesChannelOperation operation = new SalesChannelOperation(
                listingId, SalesChannelOperationType.ENSURE_LISTING, 1L, requestId,
                Map.of("revision", 1L, "listing_key", listingId.toString(), "desired_state", "ONLINE"));
        if (attemptedCount > 0) {
            assertTrue(operation.start(now.minusMinutes(1)));
            assertTrue(operation.reschedule("ENSURE_REPLAY_REQUIRED", "replay exact command", now.minusSeconds(1)));
        }
        return operation;
    }

    private SalesChannelListing linkedOnlyListing(SalesChannelAccount account, UUID productId,
                                                  String externalItemId) {
        SalesChannelListing listing = listing(account, productId, externalItemId, false);
        listing.setManagementMode(SalesChannelListingManagementMode.LINKED_ONLY);
        listing.setEnabled(false);
        listing.setAutoFulfillmentEnabled(false);
        listing.setDesiredState(SalesChannelListingDesiredState.OFFLINE);
        listing.setStatus(SalesChannelListingStatus.DRAFT);
        return listingRepository.saveAndFlush(listing);
    }

    private SalesChannelListing listingWithoutGeneration(SalesChannelAccount account, UUID productId) {
        SalesChannelListing listing = new SalesChannelListing();
        listing.setSalesChannelAccountId(account.getId());
        listing.setProductId(productId);
        listing.setEnabled(false);
        listing.setAutoFulfillmentEnabled(false);
        listing.setDesiredState(SalesChannelListingDesiredState.OFFLINE);
        listing.setStatus(SalesChannelListingStatus.DRAFT);
        listing.setProviderConfig(Map.of());
        return listingRepository.saveAndFlush(listing);
    }

    private SalesChannelListing pendingListing(SalesChannelAccount account, UUID productId,
                                                String externalItemId, boolean autoFulfillment) {
        SalesChannelListing listing = listing(account, productId, externalItemId, autoFulfillment);
        listing.setManagementMode(SalesChannelListingManagementMode.TAKEOVER_PENDING);
        listing.setEnabled(false);
        listing.setAutoFulfillmentEnabled(autoFulfillment);
        listing.setDesiredState(SalesChannelListingDesiredState.OFFLINE);
        listing.setStatus(SalesChannelListingStatus.DRAFT);
        return listingRepository.saveAndFlush(listing);
    }

    private SalesChannelListingGenerationSpec insertListingSpec(SalesChannelListing listing, UUID productSpecId) {
        SalesChannelListingGenerationSpec mapping = new SalesChannelListingGenerationSpec();
        mapping.setSalesChannelListingGenerationId(
                generationRepository.findCurrentByListingId(listing.getId()).orElseThrow().getId());
        mapping.setProductSpecId(productSpecId);
        mapping.setProviderMapping(Map.of());
        return generationSpecRepository.saveAndFlush(mapping);
    }

    private AgentOrder order(SalesChannelAccount account, String externalOrderNo,
                             String itemId, String sku, String specValue,
                             int quantity, String amount) {
        return new AgentOrder(account.getProviderAccountId(), externalOrderNo, "pending_ship", itemId,
                sku, specValue == null ? null : "规格", specValue, quantity, amount, "CNY",
                "2026-09-22T00:00:00Z", "2026-09-22T00:00:01Z");
    }

    private String historicalFingerprint(AgentOrder external, String listingKey, String skuKey) throws Exception {
        Map<String, Object> facts = new TreeMap<>();
        facts.put("provider", SalesChannelProvider.XIANYU.name());
        facts.put("account_id", external.accountId());
        facts.put("external_order_no", external.externalOrderNo());
        facts.put("item_id", external.itemId());
        facts.put("listing_key", listingKey);
        facts.put("sku_key", skuKey);
        facts.put("external_sku_id", external.externalSkuId());
        facts.put("spec_name", external.specName());
        facts.put("spec_value", external.specValue());
        facts.put("quantity", external.quantity() == null || external.quantity() < 1 ? 1 : external.quantity());
        facts.put("amount", new BigDecimal(external.amount()).setScale(2, RoundingMode.HALF_UP).toPlainString());
        facts.put("currency", external.currency() == null || external.currency().isBlank()
                ? "CNY" : external.currency().trim().toUpperCase());
        byte[] bytes = new ObjectMapper().writeValueAsBytes(facts);
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private UUID insertProduct(boolean specEnabled) {
        UUID productId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into product_categories (id, created_at, updated_at, name, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, 0, 0)
                """, categoryId, "Phase 4 category " + productId);
        jdbcTemplate.update("""
                insert into products (id, created_at, updated_at, title, product_no, retail_price, cost_price, category_id,
                    delivery_type, stock_mode, spec_enabled, is_enabled, is_deleted)
                values (?, current_timestamp, current_timestamp, 'Phase 4 product', nextval('product_no_sequence'),
                    10.00, 4.00, ?, 'AUTO', 'CARD_KEY', ?, true, 0)
                """, productId, categoryId, specEnabled);
        return productId;
    }

    private UUID insertProductSpec(UUID productId, String name) {
        UUID specId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into product_specs (id, created_at, updated_at, product_id, name, retail_price,
                    cost_price, is_visible, sort_order, is_deleted)
                values (?, current_timestamp, current_timestamp, ?, ?, 20.00, 8.00, true, 0, 0)
                """, specId, productId, name);
        return specId;
    }

    private UUID insertCard(UUID productId, UUID specId, String content) {
        UUID cardId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into card_keys (id, created_at, updated_at, product_id, spec_id, content_parts,
                    content_fingerprint, status)
                values (?, current_timestamp, current_timestamp, ?, ?, jsonb_build_array(?), ?, ?)
                """, cardId, productId, specId, content, CardContentParser.fingerprint(List.of(content)),
                CardKeyStatus.AVAILABLE.name());
        return cardId;
    }

    private UUID insertNormalRetryOrder(UUID productId) {
        UUID orderId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into orders (id, created_at, updated_at, actual_amount, total_amount, status,
                    order_type, payment_method, payment_channel_code)
                values (?, current_timestamp, current_timestamp, 10.00, 10.00, 'PAID',
                    'NORMAL', 'ONLINE', 'phase4-normal-channel')
                """, orderId);
        jdbcTemplate.update("""
                insert into order_items (id, created_at, updated_at, order_id, product_id, product_no,
                    product_title, quantity, unit_price, cost_price_snapshot, retail_price_snapshot,
                    pricing_source, subtotal, delivery_type, stock_mode, fulfillment_status)
                values (?, current_timestamp, current_timestamp, ?, ?,
                    (select product_no from products where id = ?), 'Phase 4 normal product', 1,
                    10.00, 4.00, 10.00, 'RETAIL', 10.00, 'AUTO', 'CARD_KEY', 'PENDING')
                """, itemId, orderId, productId, productId);
        return orderId;
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private void assertPermitMutationRejected(UUID permitId, UUID operationId, String column,
                                              Object invalidValue, Object originalValue) {
        assertEquals(1, jdbcTemplate.update("update sales_channel_recovery_permits set " + column + " = ? where id = ?",
                invalidValue, permitId));
        entityManager.clear();
        try {
            assertFalse(salesChannelOperationService.markRunning(operationId),
                    "recovery claim must reject mismatched permit " + column);
        } finally {
            assertEquals(1, jdbcTemplate.update("update sales_channel_recovery_permits set " + column + " = ? where id = ?",
                    originalValue, permitId));
            entityManager.clear();
        }
    }

    private <T> T scalar(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }
}
