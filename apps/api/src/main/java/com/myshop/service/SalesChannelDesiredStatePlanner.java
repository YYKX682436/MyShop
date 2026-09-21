package com.myshop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myshop.constant.SalesChannelListingDesiredState;
import com.myshop.constant.SalesChannelListingStatus;
import com.myshop.constant.SalesChannelOperationStatus;
import com.myshop.constant.SalesChannelOperationType;
import com.myshop.constant.StockMode;
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
    private final SalesChannelOperationRepository operationRepository;
    private final ProductInventoryBatchService inventoryBatchService;
    private final SalesChannelOperationServiceImpl operationService;

    @Transactional
    public SalesChannelListing saveConfig(UUID listingId, XianyuProviderConfig config) {
        if (config == null) throw new IllegalArgumentException("Xianyu provider config is required");
        config.validateForSave();
        SalesChannelListing listing = lockListing(listingId);
        listing.setProviderConfig(objectMapper.convertValue(config, Map.class));
        return listingRepository.saveAndFlush(listing);
    }

    @Transactional
    public SalesChannelListing setEnabled(UUID listingId, boolean enabled) {
        SalesChannelListing listing = lockListing(listingId);
        listing.setEnabled(enabled);
        return planLocked(listing);
    }

    @Transactional
    public SalesChannelListing refresh(UUID listingId) {
        return planLocked(lockListing(listingId));
    }

    @Transactional
    public void retry(UUID listingId) {
        SalesChannelListing listing = lockListing(listingId);
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
        if (listing.getStatus() == SalesChannelListingStatus.RELIST_REQUIRED) {
            throw new IllegalStateException("RELIST_REQUIRED needs a separately designed relist flow");
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
        if (desiredState == SalesChannelListingDesiredState.OFFLINE) {
            if (listing.getExternalItemId() == null) {
                listing.setStatus(SalesChannelListingStatus.OFFLINE);
                return;
            }
            if (listing.getStatus() == SalesChannelListingStatus.OFFLINE) {
                listing.advanceAppliedRevision(listing.getDesiredRevision());
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
        if (listing.getExternalItemId() == null) {
            if (hasOperation(listing.getId(), SalesChannelOperationType.PUBLISH,
                    SalesChannelOperationStatus.UNKNOWN)) return;
            boolean publishRunning = hasOperation(listing.getId(), SalesChannelOperationType.PUBLISH,
                    SalesChannelOperationStatus.RUNNING);
            if (publishRunning && "PERSONAL".equals(accountType)) return;
            SalesChannelOperationType type = publishRunning
                    ? SalesChannelOperationType.SYNC : SalesChannelOperationType.PUBLISH;
            operationService.request(listing.getId(), type, payload(snapshot, fingerprint));
            return;
        }

        if (listing.getStatus() == SalesChannelListingStatus.OFFLINE) {
            listing.setStatus(SalesChannelListingStatus.RELIST_REQUIRED);
            listing.setLastErrorCode("RELIST_REQUIRED");
            listing.setLastErrorMessage("The current Agent contract does not prove that this offline item can be safely restored");
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
                && listing.getExternalItemId() != null
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
                || listing.getLastErrorCode().equals("RELIST_REQUIRED"))) return;
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
                int available = stockQuantity(product, stock, config.unlimitedStockQuantity());
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
            quantity = "PERSONAL".equals(accountType(account)) ? 1
                    : stockQuantity(product, stock, config.unlimitedStockQuantity());
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
                specifications, skuRows, config.address(), config.addressExpectedText(),
                config.deliveryMethod(), config.shippingMethod(), config.supportPickup(), config.postage());
    }

    private int stockQuantity(Product product, ProductInventoryBatchService.StockSnapshot stock, int unlimited) {
        if (product.getStockMode() == StockMode.UNLIMITED || (stock != null && stock.unlimited())) {
            return clamp(Math.max(0, unlimited));
        }
        return clamp(stock == null || stock.available() == null ? 0 : stock.available());
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
