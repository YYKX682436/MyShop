package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.PricingSource;
import com.myshop.constant.SalesChannelOrderDeliveryStatus;
import com.myshop.constant.SalesChannelOrderIngestStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingGeneration;
import com.myshop.entity.SalesChannelListingGenerationSpec;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.exception.UnmanagedSalesChannelItemException;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingGenerationRepository;
import com.myshop.repository.SalesChannelListingGenerationSpecRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.saleschannel.model.SalesChannelDtos.AgentOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Imports already-paid provider orders without reusing the storefront checkout path. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SalesChannelOrderImportService {

    private final ObjectMapper objectMapper;
    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelListingGenerationRepository generationRepository;
    private final SalesChannelListingGenerationSpecRepository generationSpecRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final SalesChannelOrderRepository channelOrderRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SalesChannelOrderTerminalStatusService terminalStatusService;
    private final AutoFulfillmentService autoFulfillmentService;

    @Transactional
    public SalesChannelOrder importOrder(SalesChannelAccount account, AgentOrder external) {
        account = accountRepository.findByIdForUpdate(account.getId())
                .orElseThrow(() -> new IllegalArgumentException("Sales-channel account does not exist"));
        if (external == null || external.externalOrderNo() == null || external.externalOrderNo().isBlank()) {
            throw new IllegalArgumentException("External order number is required");
        }
        IdentityResolution identity = resolveListingIdentity(account, external);
        SalesChannelListingGeneration generation = identity.generation();
        SalesChannelListing listing = identity.listing();
        rejectUnmanagedItem(account, external, listing);
        String fingerprint = fingerprint(account.getProvider(), null, external);
        SalesChannelOrder row = channelOrderRepository
                .findByProviderAndSalesChannelAccountIdAndExternalOrderNo(
                        account.getProvider(), account.getId(), external.externalOrderNo())
                .orElse(null);
        if (row != null) {
            return updateExisting(row, external, fingerprint);
        }

        row = new SalesChannelOrder();
        row.setProvider(account.getProvider());
        row.setSalesChannelAccountId(account.getId());
        if (generation != null) {
            row.setSalesChannelListingId(generation.getSalesChannelListingId());
            row.setSalesChannelListingGenerationId(generation.getId());
        }
        row.setExternalOrderNo(external.externalOrderNo());
        row.setExternalItemId(external.itemId());
        row.setExternalSkuId(blankToNull(external.externalSkuId()));
        row.setExternalSpecName(blankToNull(external.specName()));
        row.setExternalSpecValue(blankToNull(external.specValue()));
        row.setQuantity(normalizeQuantity(external.quantity()));
        row.setAmount(parseAmount(external.amount()));
        row.setCurrency(normalizeCurrency(external.currency()));
        row.setExternalStatus(normalizeStatus(external.status()));
        row.setSourceUpdatedAt(sourceTime(external.updatedAt()));
        row.setSourceFingerprint(fingerprint);
        row.setIngestStatus(SalesChannelOrderIngestStatus.DISCOVERED);
        row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.NOT_READY);
        channelOrderRepository.saveAndFlush(row);

        if (!"pending_ship".equals(row.getExternalStatus())) {
            row.setIngestStatus(SalesChannelOrderIngestStatus.UNSUPPORTED);
            row.setLastErrorCode(alreadyFulfilled(row.getExternalStatus())
                    ? "EXTERNAL_ALREADY_FULFILLED" : "EXTERNAL_ORDER_NOT_PENDING_SHIP");
            row.setLastErrorMessage("Phase 4 only imports pending_ship as an automatic fulfillment entry point");
            return row;
        }
        return mapAndCreate(account, external, row, identity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistImportFailure(SalesChannelAccount account, AgentOrder external,
                                     String code, String message) {
        if (external == null || external.externalOrderNo() == null || external.externalOrderNo().isBlank()) return;
        account = accountRepository.findByIdForUpdate(account.getId())
                .orElseThrow(() -> new IllegalArgumentException("Sales-channel account does not exist"));
        IdentityResolution identity = resolveListingIdentity(account, external);
        SalesChannelListingGeneration generation = identity.generation();
        SalesChannelListing listing = identity.listing();
        if (isUnmanagedItem(listing)) {
            logIgnoredUnmanagedItem(account, external);
            return;
        }
        SalesChannelOrder row = channelOrderRepository
                .findByProviderAndSalesChannelAccountIdAndExternalOrderNo(
                        account.getProvider(), account.getId(), external.externalOrderNo())
                .orElseGet(SalesChannelOrder::new);
        row.setProvider(account.getProvider());
        row.setSalesChannelAccountId(account.getId());
        row.setExternalOrderNo(external.externalOrderNo());
        row.setExternalItemId(external.itemId() == null ? "UNKNOWN" : external.itemId());
        if (generation != null) {
            row.setSalesChannelListingId(generation.getSalesChannelListingId());
            row.setSalesChannelListingGenerationId(generation.getId());
        }
        row.setExternalSkuId(blankToNull(external.externalSkuId()));
        row.setExternalSpecName(blankToNull(external.specName()));
        row.setExternalSpecValue(blankToNull(external.specValue()));
        row.setQuantity(normalizeQuantity(external.quantity()));
        row.setAmount(safeAmount(external.amount()));
        row.setCurrency(normalizeCurrency(external.currency()));
        row.setExternalStatus(normalizeStatus(external.status()));
        row.setSourceUpdatedAt(sourceTime(external.updatedAt()));
        row.setSourceFingerprint(safeFailureFingerprint(account, external));
        row.setIngestStatus(SalesChannelOrderIngestStatus.MAPPING_ERROR);
        row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.NOT_READY);
        row.setLastErrorCode(code);
        row.setLastErrorMessage(message);
        channelOrderRepository.save(row);
    }

    private SalesChannelOrder updateExisting(SalesChannelOrder row, AgentOrder external, String fingerprint) {
        if (!Objects.equals(row.getSourceFingerprint(), fingerprint)) {
            row.setIngestStatus(SalesChannelOrderIngestStatus.SOURCE_CONFLICT);
            row.setLastErrorCode("SOURCE_FINGERPRINT_CHANGED");
            row.setLastErrorMessage("The same external order number changed immutable source facts");
            return channelOrderRepository.save(row);
        }
        row.setExternalStatus(normalizeStatus(external.status()));
        row.setSourceUpdatedAt(sourceTime(external.updatedAt()));
        terminalStatusService.handle(row.getId(), row.getOrderId(), row.getExternalStatus());
        return channelOrderRepository.save(row);
    }

    private SalesChannelOrder mapAndCreate(SalesChannelAccount account, AgentOrder external,
                                           SalesChannelOrder row, IdentityResolution identity) {
        if (identity.errorCode() != null) {
            return mappingError(row, identity.errorCode(), identity.errorMessage());
        }
        SalesChannelListing listing = identity.listing();
        SalesChannelListingGeneration generation = identity.generation();
        if (listing == null) {
            return mappingError(row, "LISTING_NOT_FOUND", "No MyShop listing matches the provider item");
        }
        Product product = productRepository.findById(listing.getProductId()).orElse(null);
        if (product == null || product.getIsDeleted() != 0) {
            return mappingError(row, "PRODUCT_NOT_FOUND", "The provider item is not mapped to an active product");
        }
        row.setSalesChannelListingId(listing.getId());
        row.setSalesChannelListingGenerationId(generation == null ? null : generation.getId());
        boolean autoRequested = SalesChannelListingManagementPolicy.autoFulfillmentAllowed(listing)
                && listing.isAutoFulfillmentEnabled();
        row.setAutoFulfillmentRequested(autoRequested);
        if (autoRequested && (!"AUTO".equalsIgnoreCase(product.getDeliveryType())
                || product.getStockMode() != com.myshop.constant.StockMode.CARD_KEY)) {
            return mappingError(row, "AUTO_FULFILLMENT_GATE", "Listing auto fulfillment requires AUTO + CARD_KEY");
        }

        SpecResolution specResolution = resolveSpec(product, generation, external);
        if (specResolution.errorCode() != null) {
            return mappingError(row, specResolution.errorCode(), specResolution.errorMessage());
        }
        ProductSpec spec = specResolution.spec();
        if (product.isSpecEnabled() && spec == null && hasExternalSpec(external)) {
            return mappingError(row, "SPEC_MAPPING_ERROR", "The provider SKU/spec has no unique exact mapping");
        }
        if (product.isSpecEnabled() && spec == null && hasActiveSpecs(product.getId())) {
            return mappingError(row, "SPEC_MAPPING_ERROR", "A specification is required but no exact mapping was found");
        }
        if (!product.isSpecEnabled() && hasExternalSpec(external)) {
            return mappingError(row, "SPEC_MAPPING_ERROR", "An unspec'd product cannot accept a provider SKU/spec");
        }
        BigDecimal costPriceSnapshot = spec == null ? product.getCostPrice() : spec.getCostPrice();
        if (costPriceSnapshot == null) {
            return mappingError(row, "COST_PRICE_SNAPSHOT_UNAVAILABLE",
                    "A valid MyShop cost price snapshot is required before importing the order");
        }
        row.setIngestStatus(SalesChannelOrderIngestStatus.IMPORTED);
        row.setDeliveryStatus(autoRequested
                ? SalesChannelOrderDeliveryStatus.FULFILLMENT_PENDING
                : SalesChannelOrderDeliveryStatus.NOT_READY);
        Order order = createOrder(external, product, spec);
        orderRepository.saveAndFlush(order);
        OrderItem item = createOrderItem(order, external, product, spec, costPriceSnapshot);
        orderItemRepository.saveAndFlush(item);
        row.setOrderId(order.getId());
        channelOrderRepository.save(row);
        triggerAfterCommit(row, order.getId());
        return row;
    }

    private SalesChannelListingGeneration resolveGeneration(SalesChannelAccount account, AgentOrder external) {
        String externalItemId = external == null ? null : blankToNull(external.itemId());
        if (externalItemId == null) return null;
        return generationRepository
                .findBySalesChannelAccountIdAndExternalItemId(account.getId(), externalItemId)
                .orElse(null);
    }

    private IdentityResolution resolveListingIdentity(SalesChannelAccount account, AgentOrder external) {
        String stableListingKey = external == null ? null : external.listingKey();
        SalesChannelListingGeneration legacyGeneration = resolveGeneration(account, external);
        if (stableListingKey == null) {
            SalesChannelListing legacyListing = legacyGeneration == null ? null
                    : listingRepository.findById(legacyGeneration.getSalesChannelListingId()).orElse(null);
            return legacyListing == null
                    ? new IdentityResolution(null, legacyGeneration, "LISTING_NOT_FOUND",
                    "No exact provider item generation maps to a MyShop listing")
                    : new IdentityResolution(legacyListing, legacyGeneration, null, null);
        }

        UUID listingId = canonicalUuid(stableListingKey);
        if (listingId == null) {
            return new IdentityResolution(null, null, "LISTING_KEY_INVALID",
                    "listing_key must be a canonical MyShop listing UUID");
        }
        SalesChannelListing listing = listingRepository.findById(listingId).orElse(null);
        if (listing == null) {
            return new IdentityResolution(null, null, "LISTING_NOT_FOUND",
                    "listing_key does not identify a local MyShop listing");
        }
        if (!account.getId().equals(listing.getSalesChannelAccountId())) {
            return new IdentityResolution(null, null, "LISTING_ACCOUNT_MISMATCH",
                    "listing_key belongs to another sales-channel account");
        }
        if (legacyGeneration != null
                && !listingId.equals(legacyGeneration.getSalesChannelListingId())) {
            return new IdentityResolution(listing, null, "LISTING_IDENTITY_CONFLICT",
                    "Stable listing_key conflicts with the legacy external item projection");
        }
        return new IdentityResolution(listing, legacyGeneration, null, null);
    }

    private void rejectUnmanagedItem(SalesChannelAccount account, AgentOrder external,
                                     SalesChannelListing listing) {
        if (!isUnmanagedItem(listing)) return;
        throw new UnmanagedSalesChannelItemException(account.getId(), external.itemId(), external.externalOrderNo());
    }

    private boolean isUnmanagedItem(SalesChannelListing listing) {
        return listing != null && !SalesChannelListingManagementPolicy.orderImportAllowed(listing);
    }

    private void logIgnoredUnmanagedItem(SalesChannelAccount account, AgentOrder external) {
        log.info("sales_channel_order_ignored_unmanaged_item accountId={} externalItemId={} externalOrderNo={} reason=LISTING_MANAGEMENT_MODE",
                account.getId(), external.itemId(), external.externalOrderNo());
    }

    private Order createOrder(AgentOrder external, Product product, ProductSpec spec) {
        BigDecimal amount = parseAmount(external.amount());
        LocalDateTime paidAt = parseTime(external.placedAt());
        if (paidAt == null) paidAt = parseTime(external.updatedAt());
        if (paidAt == null) paidAt = LocalDateTime.now();
        Order order = new Order();
        order.setUserId(null);
        order.setTotalAmount(amount);
        order.setActualAmount(amount);
        order.setStatus(OrderStatus.PAID);
        order.setOrderType(OrderType.SALES_CHANNEL);
        order.setPaymentMethod(null);
        order.setPaymentChannelCode("XIANYU");
        order.setExpiresAt(null);
        order.setPaidAt(paidAt);
        order.setIdempotencyKey("XIANYU_ORDER:" + external.accountId() + ":" + external.externalOrderNo());
        order.setIdempotencyFingerprint(fingerprint(SalesChannelProvider.XIANYU, null, external));
        return order;
    }

    private OrderItem createOrderItem(Order order, AgentOrder external, Product product, ProductSpec spec,
                                      BigDecimal costPriceSnapshot) {
        int quantity = normalizeQuantity(external.quantity());
        BigDecimal amount = parseAmount(external.amount());
        BigDecimal unit = amount.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setProductId(product.getId());
        item.setProductNo(product.getProductNo());
        item.setProductTitle(product.getTitle());
        item.setProductCoverUrl(product.getCoverUrl());
        item.setSpecId(spec == null ? null : spec.getId());
        item.setSpecName(spec == null ? null : spec.getName());
        item.setQuantity(quantity);
        item.setUnitPrice(unit);
        item.setCostPriceSnapshot(costPriceSnapshot);
        item.setRetailPriceSnapshot(spec == null ? product.getRetailPrice() : spec.getRetailPrice());
        item.setPricingSource(PricingSource.SALES_CHANNEL);
        item.setSubtotal(amount);
        item.setDeliveryType(product.getDeliveryType());
        item.setCustomerInputs(List.of());
        item.setStockMode(product.getStockMode());
        item.setFulfillmentStatus(FulfillmentStatus.PENDING);
        return item;
    }

    private SpecResolution resolveSpec(Product product, SalesChannelListingGeneration generation, AgentOrder external) {
        if (!product.isSpecEnabled()) return new SpecResolution(null, null, null);
        String stableSkuKey = external == null ? null : external.skuKey();
        if (stableSkuKey != null) {
            UUID specId = canonicalUuid(stableSkuKey);
            if (specId == null) {
                return new SpecResolution(null, "SKU_KEY_INVALID", "sku_key must be a canonical ProductSpec UUID");
            }
            ProductSpec stableSpec = productSpecRepository.findById(specId)
                    .filter(candidate -> candidate.getProductId().equals(product.getId())
                            && candidate.getIsDeleted() == 0)
                    .orElse(null);
            if (stableSpec == null) {
                return new SpecResolution(null, "SKU_KEY_NOT_FOUND",
                        "sku_key does not identify a non-deleted specification for this product");
            }
            String externalSkuId = blankToNull(external.externalSkuId());
            if (generation != null) {
                List<SalesChannelListingGenerationSpec> legacyMatches = generationSpecRepository
                        .findBySalesChannelListingGenerationIdOrderByCreatedAtAsc(generation.getId()).stream()
                        .filter(mapping -> externalSkuId != null
                                ? externalSkuId.equals(mapping.getExternalSkuId())
                                : Objects.equals(blankToNull(external.specName()),
                                        stringValue(mapping.getProviderMapping().get("provider_spec_name")))
                                && Objects.equals(blankToNull(external.specValue()),
                                        stringValue(mapping.getProviderMapping().get("provider_spec_value"))))
                        .toList();
                if (!legacyMatches.isEmpty() && (legacyMatches.size() != 1
                        || !specId.equals(legacyMatches.getFirst().getProductSpecId()))) {
                    return new SpecResolution(null, "SKU_IDENTITY_CONFLICT",
                            "Stable sku_key conflicts with the legacy external SKU projection");
                }
            }
            return new SpecResolution(stableSpec, null, null);
        }
        if (generation == null) return new SpecResolution(null, null, null);
        List<SalesChannelListingGenerationSpec> mappings = generationSpecRepository
                .findBySalesChannelListingGenerationIdOrderByCreatedAtAsc(generation.getId());
        List<SalesChannelListingGenerationSpec> matches;
        String sku = blankToNull(external.externalSkuId());
        if (sku != null) {
            matches = mappings.stream().filter(mapping -> sku.equals(mapping.getExternalSkuId())).toList();
        } else {
            String name = blankToNull(external.specName());
            String value = blankToNull(external.specValue());
            matches = mappings.stream().filter(mapping -> {
                Map<String, Object> provider = mapping.getProviderMapping();
                return Objects.equals(name, stringValue(provider.get("provider_spec_name")))
                        && Objects.equals(value, stringValue(provider.get("provider_spec_value")));
            }).toList();
        }
        if (matches.size() != 1) return new SpecResolution(null, null, null);
        ProductSpec legacySpec = productSpecRepository.findById(matches.get(0).getProductSpecId())
                .filter(candidate -> candidate.getProductId().equals(product.getId()) && candidate.getIsDeleted() == 0)
                .orElse(null);
        return new SpecResolution(legacySpec, null, null);
    }

    private boolean hasActiveSpecs(UUID productId) {
        return !productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(productId, 0).isEmpty();
    }

    private SalesChannelOrder mappingError(SalesChannelOrder row, String code, String message) {
        row.setIngestStatus(SalesChannelOrderIngestStatus.MAPPING_ERROR);
        row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.NOT_READY);
        row.setLastErrorCode(code);
        row.setLastErrorMessage(message);
        return channelOrderRepository.save(row);
    }

    private void triggerAfterCommit(SalesChannelOrder row, UUID orderId) {
        if (!row.isAutoFulfillmentRequested()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { autoFulfillmentService.trigger(orderId); }
            });
        } else {
            autoFulfillmentService.trigger(orderId);
        }
    }

    private String fingerprint(SalesChannelProvider provider, UUID accountId, AgentOrder external) {
        Map<String, Object> facts = new TreeMap<>();
        facts.put("provider", provider == null ? null : provider.name());
        facts.put("account_id", accountId == null ? external.accountId() : accountId.toString());
        facts.put("external_order_no", external.externalOrderNo());
        facts.put("item_id", external.itemId());
        facts.put("listing_key", external.listingKey());
        facts.put("sku_key", external.skuKey());
        facts.put("external_sku_id", external.externalSkuId());
        facts.put("spec_name", external.specName());
        facts.put("spec_value", external.specValue());
        facts.put("quantity", normalizeQuantity(external.quantity()));
        facts.put("amount", parseAmount(external.amount()).setScale(2, RoundingMode.HALF_UP).toPlainString());
        facts.put("currency", normalizeCurrency(external.currency()));
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(facts);
            return HexDigest.sha256(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint external order", exception);
        }
    }

    private String normalizeStatus(String value) {
        String normalized = value == null ? "unknown" : value.trim().toLowerCase();
        return Set.of("pending_payment", "pending_ship", "shipped", "completed", "cancelled", "refunding", "refunded")
                .contains(normalized) ? normalized : "unknown";
    }

    private boolean alreadyFulfilled(String status) { return "shipped".equals(status) || "completed".equals(status); }
    private boolean hasExternalSpec(AgentOrder external) {
        return (external.skuKey() != null && !external.skuKey().isBlank())
                || blankToNull(external.externalSkuId()) != null
                || blankToNull(external.specName()) != null || blankToNull(external.specValue()) != null;
    }

    private UUID canonicalUuid(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) return null;
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record IdentityResolution(SalesChannelListing listing, SalesChannelListingGeneration generation,
                                      String errorCode, String errorMessage) { }

    private record SpecResolution(ProductSpec spec, String errorCode, String errorMessage) { }
    private int normalizeQuantity(Integer quantity) { return quantity == null || quantity < 1 ? 1 : quantity; }
    private BigDecimal parseAmount(String value) {
        try { return new BigDecimal(value == null ? "0" : value).setScale(2, RoundingMode.HALF_UP); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("External order amount is invalid"); }
    }
    private BigDecimal safeAmount(String value) {
        try { return parseAmount(value); } catch (RuntimeException ignored) { return BigDecimal.ZERO.setScale(2); }
    }
    private String safeFailureFingerprint(SalesChannelAccount account, AgentOrder external) {
        try { return fingerprint(account.getProvider(), null, external); }
        catch (RuntimeException ignored) {
            return HexDigest.sha256((external.externalOrderNo() + ":" + external.itemId()).getBytes(StandardCharsets.UTF_8));
        }
    }
    private String normalizeCurrency(String currency) { return (currency == null || currency.isBlank() ? "CNY" : currency.trim()).toUpperCase(); }
    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value.replace("Z", "+00:00")).toLocalDateTime(); }
        catch (RuntimeException first) {
            try { return LocalDateTime.parse(value); } catch (RuntimeException ignored) { return null; }
        }
    }
    private LocalDateTime sourceTime(String value) {
        LocalDateTime parsed = parseTime(value);
        return parsed == null ? LocalDateTime.now() : parsed;
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }

    private static final class HexDigest {
        private static String sha256(byte[] bytes) {
            try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
        }
    }
}
