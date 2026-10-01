package com.myshop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelGenerationState;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.constant.StockMode;
import com.myshop.constant.ErrorCode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelOperation;
import com.myshop.exception.BusinessException;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelOperationRepository;
import com.myshop.saleschannel.model.DesiredListingSnapshot;
import com.myshop.saleschannel.model.SalesChannelDtos.PlatformAttribute;
import com.myshop.saleschannel.model.SalesChannelDtos.SkuRow;
import com.myshop.saleschannel.model.SalesChannelDtos.Specification;
import com.myshop.saleschannel.model.SalesChannelDtos.SpecificationValue;
import com.myshop.saleschannel.xianyu.model.XianyuProviderConfig;
import com.myshop.service.impl.SalesChannelOperationServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Builds one canonical, immutable target snapshot for every sales-channel revision. */
@Service
@RequiredArgsConstructor
public class SalesChannelDesiredStatePlanner {

    private static final int MAX_PLATFORM_STOCK = 999_999;

    private final ObjectMapper objectMapper;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelListingGenerationRepository generationRepository;
    private final SalesChannelOperationRepository operationRepository;
    private final ProductInventoryBatchService inventoryBatchService;
    private final SalesChannelOperationServiceImpl operationService;

    @Transactional
    public SalesChannelListing saveConfig(UUID listingId, XianyuProviderConfig config) {
        if (config == null) throw new IllegalArgumentException("Xianyu provider config is required");
        config.validateForSave();
        SalesChannelListing listing = lockListing(listingId);
        requireNoCandidate(listing);
        listing.setProviderConfig(objectMapper.convertValue(config, Map.class));
        return listingRepository.saveAndFlush(listing);
    }

    @Transactional
    public SalesChannelListing setEnabled(UUID listingId, boolean enabled) {
        SalesChannelListing listing = lockListing(listingId);
        requireNoCandidate(listing);
        if (!SalesChannelListingManagementPolicy.providerWritesAllowed(listing)) {
            if (enabled) {
                SalesChannelListingManagementPolicy.requireProviderWritesAllowed(listing);
            }
            listing.setEnabled(false);
            return listingRepository.saveAndFlush(listing);
        }
        if (enabled) preflightForEnable(listing);
        listing.setEnabled(enabled);
        return planLocked(listing);
    }

    private void requireNoCandidate(SalesChannelListing listing) {
        if (generationRepository.findCandidateByListingId(listing.getId()).isPresent()) {
            throw new IllegalStateException("REPLACEMENT_PENDING: candidate generation activation is required first");
        }
    }

    private void preflightForEnable(SalesChannelListing listing) {
        SalesChannelAccount account = loadAccount(listing);
        Product product = loadProduct(listing);
        DesiredListingSnapshot snapshot = buildSnapshot(listing, account, product,
                SalesChannelListingDesiredState.ONLINE);
        List<String> missing = new ArrayList<>();
        if (blank(snapshot.title())) missing.add("title");
        if (blank(snapshot.description())) missing.add("description");
        if (snapshot.price() == null || snapshot.price().signum() <= 0) missing.add("price");
        if (snapshot.sourceImageUrls() == null || snapshot.sourceImageUrls().stream().allMatch(this::blank)) {
            missing.add("sourceImages");
        }
        if (blank(snapshot.address())) missing.add("address");
        if (blank(snapshot.platformCategoryId())) missing.add("platformCategoryId");
        if (blank(snapshot.platformChannelCategoryId())) missing.add("platformChannelCategoryId");
        if (blank(snapshot.platformChannelCategoryName())) missing.add("platformChannelCategoryName");
        if (blank(snapshot.platformTbCategoryId())) missing.add("platformTbCategoryId");
        if (!account.isEnabled()) missing.add("accountEnabled");
        if (account.getStatus() != com.myshop.constant.SalesChannelAccountStatus.CONNECTED
                || account.getLastVerifiedAt() == null) missing.add("accountVerification");
        if (!Arrays.asList("FISH_SHOP", "PERSONAL").contains(accountType(account))) {
            missing.add("accountType");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Cannot enable listing; missing required fields: " + String.join(", ", missing),
                    Map.of("missingFields", List.copyOf(missing)));
        }
    }

    @Transactional
    public SalesChannelListing refresh(UUID listingId) {
        SalesChannelListing listing = lockListing(listingId);
        if (!SalesChannelListingManagementPolicy.providerWritesAllowed(listing)) {
            return listing;
        }
        return planLocked(listing);
    }

    @Transactional
    public void retry(UUID listingId) {
        SalesChannelListing listing = lockListing(listingId);
        SalesChannelListingManagementPolicy.requireProviderWritesAllowed(listing);
        if ("EXTERNAL_ITEM_ID_CONFLICT".equals(listing.getLastErrorCode())) {
            SalesChannelOperation target = operationRepository
                    .findBySalesChannelListingIdOrderByCreatedAtAsc(listingId).stream()
                    .filter(operation -> operation.getOperationType() != SalesChannelOperationType.RECONCILE)
                    .filter(operation -> operation.getStatus() == SalesChannelOperationStatus.SUCCEEDED)
                    .filter(operation -> operation.getResult() != null
                            && (operation.getResult().get("item_id") != null
                            || operation.getResult().get("external_item_id") != null))
                    .reduce((earlier, later) -> later)
                    .orElseThrow(() -> new IllegalStateException(
                            "External item identity conflict needs manual review before retry"));
            operationService.requestReconciliation(target.getId());
            return;
        }
        if (operationRepository.hasUnresolvedUnknown(listingId)) {
            SalesChannelOperation target = operationRepository.findLatestUnresolvedUnknown(listingId)
                    .orElseThrow(() -> new IllegalStateException("UNKNOWN operation must be reconciled before retry"));
            operationService.requestReconciliation(target.getId());
            return;
        }
        if (listing.getStatus() == SalesChannelListingStatus.UNKNOWN) {
            throw new IllegalStateException("UNKNOWN operations must be reconciled before retry");
        }
        if (listing.getStatus() == SalesChannelListingStatus.REPLACEMENT_PENDING) {
            throw new IllegalStateException("REPLACEMENT_PENDING requires local candidate activation");
        }
        SalesChannelAccount account = loadAccount(listing);
        Product product = loadProduct(listing);
        SalesChannelListingDesiredState desiredState = effectiveDesiredState(listing, account, product);
        DesiredListingSnapshot snapshot = buildSnapshot(listing, account, product, desiredState);
        listing.setDesiredState(desiredState);
        if (listing.getDesiredFingerprint() == null) {
            listing.setDesiredFingerprint(fingerprint(snapshot, listing.getProviderConfig()));
        }
        listing.incrementDesiredRevision();
        operationRepository.supersedePendingBeforeRevision(
                listing.getId(), listing.getDesiredRevision(), SalesChannelOperationStatus.PENDING,
                SalesChannelOperationStatus.SUPERSEDED, LocalDateTime.now());
        listingRepository.saveAndFlush(listing);
        returnQueue(listing, account, snapshot, desiredState, fingerprint(snapshot, listing.getProviderConfig()));
    }

    private SalesChannelListing planLocked(SalesChannelListing listing) {
        if (generationRepository.findCandidateByListingId(listing.getId()).isPresent()) {
            // A candidate freezes the provider write target.  Preserve a
            // permanent mapping error so a later refresh cannot hide the
            // required manual review; a normal pending candidate remains in
            // the explicit replacement-pending state.
            if (listing.getStatus() != SalesChannelListingStatus.ERROR
                    || !"REPLACEMENT_MAPPING_ERROR".equals(listing.getLastErrorCode())) {
                listing.setStatus(SalesChannelListingStatus.REPLACEMENT_PENDING);
            }
            return listingRepository.saveAndFlush(listing);
        }
        SalesChannelAccount account = loadAccount(listing);
        Product product = loadProduct(listing);
        SalesChannelListingDesiredState desiredState = effectiveDesiredState(listing, account, product);
        DesiredListingSnapshot snapshot = buildSnapshot(listing, account, product, desiredState);
        String nextFingerprint = fingerprint(snapshot, listing.getProviderConfig());
        boolean changed = !nextFingerprint.equals(listing.getDesiredFingerprint())
                || listing.getDesiredState() != desiredState;

        if (changed) {
            listing.setDesiredState(desiredState);
            listing.setDesiredFingerprint(nextFingerprint);
            listing.incrementDesiredRevision();
            operationRepository.supersedePendingBeforeRevision(
                    listing.getId(), listing.getDesiredRevision(), SalesChannelOperationStatus.PENDING,
                    SalesChannelOperationStatus.SUPERSEDED, LocalDateTime.now());
        }

        listingRepository.saveAndFlush(listing);
        if ("EXTERNAL_ITEM_ID_CONFLICT".equals(listing.getLastErrorCode())) return listing;
        if (changed) {
            returnQueue(listing, account, snapshot, desiredState, nextFingerprint);
        } else {
            ensureDeferredCurrentRevisionQueued(listing, account, snapshot, desiredState, nextFingerprint);
        }
        return listing;
    }

    private void returnQueue(SalesChannelListing listing, SalesChannelAccount account,
                             DesiredListingSnapshot snapshot, SalesChannelListingDesiredState desiredState,
                             String fingerprint) {
        if (!SalesChannelListingManagementPolicy.providerWritesAllowed(listing)) return;
        if (desiredState == SalesChannelListingDesiredState.OFFLINE) {
            boolean hasGeneration = generationRepository.findCurrentByListingId(listing.getId()).isPresent();
            if (!hasGeneration) {
                listing.setStatus(SalesChannelListingStatus.OFFLINE);
                listing.advanceAppliedRevision(listing.getDesiredRevision());
                listing.setAppliedFingerprint(fingerprint);
                listing.setLastErrorCode(null);
                listing.setLastErrorMessage(null);
                return;
            }
            if (listing.getStatus() == SalesChannelListingStatus.OFFLINE) {
                listing.advanceAppliedRevision(listing.getDesiredRevision());
                listing.setAppliedFingerprint(fingerprint);
                listing.setLastErrorCode(null);
                listing.setLastErrorMessage(null);
                return;
            }
            Map<String, Object> payload = payload(snapshot, fingerprint);
            operationService.request(listing.getId(), SalesChannelOperationType.OFFLINE, payload);
            return;
        }

        String accountType = accountType(account);
        if (!"FISH_SHOP".equals(accountType) && !"PERSONAL".equals(accountType)) {
            listing.setStatus(SalesChannelListingStatus.ERROR);
            listing.setLastErrorCode("UNSUPPORTED_ACCOUNT_CAPABILITY");
            listing.setLastErrorMessage("Verify the Xianyu account before enabling automated listing operations");
            return;
        }
        boolean hasCurrent = generationRepository.findCurrentByListingId(listing.getId()).isPresent();
        boolean hasCandidate = generationRepository.findCandidateByListingId(listing.getId()).isPresent();
        boolean hasNoGeneration = !hasCurrent;
        if (hasCandidate) {
            listing.setStatus(SalesChannelListingStatus.REPLACEMENT_PENDING);
            listing.setLastErrorCode(null);
            listing.setLastErrorMessage(null);
            return;
        }
        if (hasNoGeneration) {
            SalesChannelOperation inFlightPublish = operationRepository
                    .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(
                            listing.getId(), listing.getDesiredRevision()).stream()
                    .filter(operation -> operation.getOperationType() == SalesChannelOperationType.PUBLISH)
                    .filter(operation -> operation.getStatus() == SalesChannelOperationStatus.PENDING
                            || operation.getStatus() == SalesChannelOperationStatus.RUNNING
                            || operation.getStatus() == SalesChannelOperationStatus.UNKNOWN)
                    .findFirst()
                    .orElse(null);
            if (inFlightPublish != null) {
                if (inFlightPublish.getStatus() == SalesChannelOperationStatus.UNKNOWN) {
                    // UNKNOWN is a read-only reconciliation barrier. Re-requesting
                    // reconciliation is idempotent while one is already pending/running.
                    operationService.requestReconciliation(inFlightPublish.getId());
                }
                return;
            }
            operationService.request(listing.getId(), SalesChannelOperationType.PUBLISH,
                    payload(snapshot, fingerprint));
            return;
        }

        if (listing.getStatus() == SalesChannelListingStatus.OFFLINE) {
            Map<String, Object> replacementPayload = payload(snapshot, fingerprint);
            replacementPayload.put("replacement_of_item_id", generationRepository
                    .findCurrentByListingId(listing.getId())
                    .orElseThrow().getExternalItemId());
            operationService.request(listing.getId(), SalesChannelOperationType.REPUBLISH, replacementPayload);
            listing.setStatus(SalesChannelListingStatus.REPUBLISHING);
            return;
        }
        if (listing.getStatus() == SalesChannelListingStatus.DRAFT) {
            listing.setStatus(SalesChannelListingStatus.ERROR);
            listing.setLastErrorCode("PROVIDER_STATE_REQUIRED");
            listing.setLastErrorMessage("A current provider generation is not in a writable online state");
            return;
        }
        if ("PERSONAL".equals(accountType)) {
            listing.setStatus(SalesChannelListingStatus.ERROR);
            listing.setLastErrorCode("UNSUPPORTED_ACCOUNT_CAPABILITY");
            listing.setLastErrorMessage("Personal accounts do not support automatic listing edits or price and stock sync");
            return;
        }
        operationService.request(listing.getId(), SalesChannelOperationType.SYNC, payload(snapshot, fingerprint));
    }

    private void ensureDeferredCurrentRevisionQueued(SalesChannelListing listing, SalesChannelAccount account,
                                                      DesiredListingSnapshot snapshot,
                                                      SalesChannelListingDesiredState desiredState,
                                                      String fingerprint) {
        if ("PERSONAL".equals(accountType(account))
                && generationRepository.findCurrentByListingId(listing.getId()).isPresent()
                && desiredState == SalesChannelListingDesiredState.ONLINE
                && listing.getAppliedRevision() < listing.getDesiredRevision()) {
            operationRepository.supersedePendingSyncAtRevision(listing.getId(), listing.getDesiredRevision(),
                    SalesChannelOperationStatus.PENDING, SalesChannelOperationStatus.SUPERSEDED,
                    LocalDateTime.now());
            listing.setStatus(SalesChannelListingStatus.ERROR);
            listing.setLastErrorCode("UNSUPPORTED_ACCOUNT_CAPABILITY");
            listing.setLastErrorMessage("Personal accounts do not support automatic listing edits or price and stock sync");
            return;
        }
        if (listing.getLastErrorCode() != null
                && (listing.getLastErrorCode().equals("UNSUPPORTED_ACCOUNT_CAPABILITY")
                || listing.getLastErrorCode().equals("PROVIDER_STATE_REQUIRED"))) return;
        boolean hasCurrentOperation = operationRepository
                .findBySalesChannelListingIdAndDesiredRevisionOrderByCreatedAtAsc(
                        listing.getId(), listing.getDesiredRevision()).stream()
                .anyMatch(operation -> operation.getOperationType() != SalesChannelOperationType.RECONCILE
                        && operation.getStatus() != SalesChannelOperationStatus.SUPERSEDED);
        if (!hasCurrentOperation && listing.getAppliedRevision() < listing.getDesiredRevision()) {
            returnQueue(listing, account, snapshot, desiredState, fingerprint);
        }
    }

    private boolean hasOperation(UUID listingId, SalesChannelOperationType type, SalesChannelOperationStatus status) {
        return operationRepository.existsBySalesChannelListingIdAndOperationTypeAndStatus(listingId, type, status);
    }

    private Map<String, Object> payload(DesiredListingSnapshot snapshot, String fingerprint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("desired_snapshot", objectMapper.convertValue(snapshot, Map.class));
        payload.put("desired_fingerprint", fingerprint);
        return payload;
    }

    private SalesChannelListingDesiredState effectiveDesiredState(SalesChannelListing listing,
                                                                   SalesChannelAccount account, Product product) {
        return listing.isEnabled() && account.isEnabled() && product.isEnabled() && product.getIsDeleted() == 0
                ? SalesChannelListingDesiredState.ONLINE : SalesChannelListingDesiredState.OFFLINE;
    }

    private DesiredListingSnapshot buildSnapshot(SalesChannelListing listing, SalesChannelAccount account,
                                                 Product product, SalesChannelListingDesiredState desiredState) {
        XianyuProviderConfig config;
        try {
            config = objectMapper.convertValue(listing.getProviderConfig(), XianyuProviderConfig.class);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Stored Xianyu provider config is invalid", exception);
        }
        config.validateForSave();

        List<ProductSpec> specs = productSpecRepository
                .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0);
        boolean useSpecs = product.isSpecEnabled() && !specs.isEmpty() && "FISH_SHOP".equals(accountType(account));
        Map<PriceTarget, ProductInventoryBatchService.StockSnapshot> inventory = inventoryBatchService.load(
                List.of(product), Map.of(product.getId(), specs));

        String title = truncate(blank(config.titleOverride()) ? product.getTitle() : config.titleOverride().trim(), 200);
        String description = truncate(blank(config.descriptionOverride())
                ? firstNonBlank(product.getDescription(), product.getDetailMd(), product.getTitle())
                : config.descriptionOverride().trim(), 1500);
        List<String> imageUrls = config.imageUrls().isEmpty()
                ? (blank(product.getCoverUrl()) ? List.of() : List.of(product.getCoverUrl().trim()))
                : config.imageUrls().stream().map(String::trim).filter(value -> !value.isBlank()).toList();

        int quantity;
        List<Specification> specifications = List.of();
        List<SkuRow> skuRows = List.of();
        if (useSpecs) {
            List<SpecificationValue> values = new ArrayList<>();
            List<SkuRow> skuValues = new ArrayList<>();
            long total = 0;
            for (ProductSpec spec : specs) {
                ProductInventoryBatchService.StockSnapshot stock = inventory.get(
                        new PriceTarget(product.getId(), spec.getId()));
                int available = stockQuantity(product, stock, config.unlimitedStockQuantity(), config.stockCapPerSku());
                total += available;
                values.add(new SpecificationValue(spec.getName(), null));
                skuValues.add(new SkuRow(Map.of("规格", spec.getName()),
                        price(spec.getRetailPrice(), config), available));
            }
            quantity = clamp(total);
            specifications = List.of(new Specification("规格", values, false));
            skuRows = List.copyOf(skuValues);
        } else {
            ProductInventoryBatchService.StockSnapshot stock = inventory.get(
                    new PriceTarget(product.getId(), null));
            quantity = stockQuantity(product, stock, config.unlimitedStockQuantity(), config.stockCapPerSku());
        }

        List<PlatformAttribute> attributes = config.platformAttributes().stream().map(attribute ->
                new PlatformAttribute(attribute.propertyId(), attribute.propertyName(), attribute.valueId(),
                        attribute.valueName(), attribute.text(), attribute.properties())).toList();
        BigDecimal retail = product.getRetailPrice() == null ? BigDecimal.ZERO : product.getRetailPrice();
        return new DesiredListingSnapshot(account.getProviderAccountId(), accountType(account),
                desiredState.name(), title, description, price(retail, config), null,
                imageUrls, config.platformCategoryId(), config.platformCategoryName(),
                config.platformChannelCategoryId(), config.platformChannelCategoryName(),
                config.platformLeafId(), config.platformTbCategoryId(), attributes, quantity,
                specifications, skuRows, config.address(), blank(config.addressExpectedText())
                        && !blank(config.address()) ? config.address() : config.addressExpectedText(),
                config.deliveryMethod(), config.shippingMethod(), config.supportPickup(), config.postage());
    }

    private int stockQuantity(Product product, ProductInventoryBatchService.StockSnapshot stock, int unlimited,
                              Integer stockCapPerSku) {
        if (product.getStockMode() == StockMode.UNLIMITED || (stock != null && stock.unlimited())) {
            return clamp(Math.max(0, unlimited));
        }
        int realAvailable = clamp(stock == null || stock.available() == null ? 0 : stock.available());
        return stockCapPerSku == null ? realAvailable : Math.min(realAvailable, stockCapPerSku);
    }

    private int clamp(long quantity) {
        return (int) Math.max(0, Math.min(MAX_PLATFORM_STOCK, quantity));
    }

    private BigDecimal price(BigDecimal retail, XianyuProviderConfig config) {
        BigDecimal value = switch (config.priceMode()) {
            case PRODUCT_RETAIL -> retail;
            case FIXED -> config.fixedPrice();
            case MARKUP_PERCENT -> retail.multiply(BigDecimal.ONE.add(
                    config.markupPercent().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
        };
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String fingerprint(DesiredListingSnapshot snapshot, Map<String, Object> providerConfig) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.set("snapshot", canonicalize(objectMapper.valueToTree(snapshot)));
            root.set("providerConfig", canonicalize(objectMapper.valueToTree(providerConfig)));
            byte[] canonical = objectMapper.writeValueAsBytes(root);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint Xianyu desired state", exception);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, field) -> sorted.set(key, canonicalize(field)));
            return sorted;
        }
        if (value.isArray()) {
            var array = objectMapper.createArrayNode();
            value.forEach(child -> array.add(canonicalize(child)));
            return array;
        }
        return value;
    }

    private String accountType(SalesChannelAccount account) {
        Object type = account.getCapabilities().get("account_type");
        return type == null ? "UNKNOWN" : type.toString().toUpperCase();
    }

    private SalesChannelAccount loadAccount(SalesChannelListing listing) {
        return accountRepository.findById(listing.getSalesChannelAccountId())
                .orElseThrow(() -> new IllegalStateException("Sales channel account is missing"));
    }

    private Product loadProduct(SalesChannelListing listing) {
        return productRepository.findById(listing.getProductId())
                .orElseThrow(() -> new BusinessException(com.myshop.constant.ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
    }

    private SalesChannelListing lockListing(UUID listingId) {
        return listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new IllegalArgumentException("Sales channel listing does not exist"));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value.trim();
        return "";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
