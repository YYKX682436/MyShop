package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.CardKeyStatus;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.FulfillmentStatus;
import com.myshop.constant.OrderStatus;
import com.myshop.constant.OrderType;
import com.myshop.constant.PricingSource;
import com.myshop.constant.SalesChannelOrderDeliveryStatus;
import com.myshop.constant.SalesChannelOrderIngestStatus;
import com.myshop.constant.SalesChannelProvider;
import com.myshop.entity.CardKey;
import com.myshop.entity.Order;
import com.myshop.entity.OrderItem;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelAccount;
import com.myshop.entity.SalesChannelListing;
import com.myshop.entity.SalesChannelListingSpec;
import com.myshop.entity.SalesChannelOrder;
import com.myshop.exception.BusinessException;
import com.myshop.repository.CardKeyRepository;
import com.myshop.repository.OrderItemRepository;
import com.myshop.repository.OrderRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SalesChannelAccountRepository;
import com.myshop.repository.SalesChannelListingRepository;
import com.myshop.repository.SalesChannelListingSpecRepository;
import com.myshop.repository.SalesChannelOrderRepository;
import com.myshop.saleschannel.model.SalesChannelDtos.AgentOrder;
import lombok.RequiredArgsConstructor;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Imports already-paid provider orders without reusing the storefront checkout path. */
@Service
@RequiredArgsConstructor
public class SalesChannelOrderImportService {

    private final ObjectMapper objectMapper;
    private final SalesChannelAccountRepository accountRepository;
    private final SalesChannelListingRepository listingRepository;
    private final SalesChannelListingSpecRepository listingSpecRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final SalesChannelOrderRepository channelOrderRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final AutoFulfillmentService autoFulfillmentService;

    @Transactional
    public SalesChannelOrder importOrder(SalesChannelAccount account, AgentOrder external) {
        account = accountRepository.findByIdForUpdate(account.getId())
                .orElseThrow(() -> new IllegalArgumentException("Sales-channel account does not exist"));
        if (external == null || external.externalOrderNo() == null || external.externalOrderNo().isBlank()) {
            throw new IllegalArgumentException("External order number is required");
        }
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
        return mapAndCreate(account, external, row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistImportFailure(SalesChannelAccount account, AgentOrder external,
                                     String code, String message) {
        if (external == null || external.externalOrderNo() == null || external.externalOrderNo().isBlank()) return;
        account = accountRepository.findByIdForUpdate(account.getId())
                .orElseThrow(() -> new IllegalArgumentException("Sales-channel account does not exist"));
        SalesChannelOrder row = channelOrderRepository
                .findByProviderAndSalesChannelAccountIdAndExternalOrderNo(
                        account.getProvider(), account.getId(), external.externalOrderNo())
                .orElseGet(SalesChannelOrder::new);
        row.setProvider(account.getProvider());
        row.setSalesChannelAccountId(account.getId());
        row.setExternalOrderNo(external.externalOrderNo());
        row.setExternalItemId(external.itemId() == null ? "UNKNOWN" : external.itemId());
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
        handleExternalTerminalStatus(row);
        return channelOrderRepository.save(row);
    }

    private SalesChannelOrder mapAndCreate(SalesChannelAccount account, AgentOrder external,
                                           SalesChannelOrder row) {
        SalesChannelListing listing = listingRepository
                .findBySalesChannelAccountIdAndExternalItemId(account.getId(), external.itemId())
                .orElse(null);
        if (listing == null) {
            return mappingError(row, "LISTING_NOT_FOUND", "No MyShop listing matches the provider item");
        }
        Product product = productRepository.findById(listing.getProductId()).orElse(null);
        if (product == null || product.getIsDeleted() != 0) {
            return mappingError(row, "PRODUCT_NOT_FOUND", "The provider item is not mapped to an active product");
        }
        row.setSalesChannelListingId(listing.getId());
        boolean autoRequested = listing.isAutoFulfillmentEnabled();
        row.setAutoFulfillmentRequested(autoRequested);
        if (autoRequested && (!"AUTO".equalsIgnoreCase(product.getDeliveryType())
                || product.getStockMode() != com.myshop.constant.StockMode.CARD_KEY)) {
            return mappingError(row, "AUTO_FULFILLMENT_GATE", "Listing auto fulfillment requires AUTO + CARD_KEY");
        }

        ProductSpec spec = resolveSpec(product, listing, external);
        if (product.isSpecEnabled() && spec == null && hasExternalSpec(external)) {
            return mappingError(row, "SPEC_MAPPING_ERROR", "The provider SKU/spec has no unique exact mapping");
        }
        if (product.isSpecEnabled() && spec == null && hasActiveSpecs(product.getId())) {
            return mappingError(row, "SPEC_MAPPING_ERROR", "A specification is required but no exact mapping was found");
        }
        if (!product.isSpecEnabled() && hasExternalSpec(external)) {
            return mappingError(row, "SPEC_MAPPING_ERROR", "An unspec'd product cannot accept a provider SKU/spec");
        }
        row.setIngestStatus(SalesChannelOrderIngestStatus.IMPORTED);
        row.setDeliveryStatus(autoRequested
                ? SalesChannelOrderDeliveryStatus.FULFILLMENT_PENDING
                : SalesChannelOrderDeliveryStatus.NOT_READY);
        Order order = createOrder(external, product, spec);
        orderRepository.saveAndFlush(order);
        OrderItem item = createOrderItem(order, external, product, spec);
        orderItemRepository.saveAndFlush(item);
        row.setOrderId(order.getId());
        channelOrderRepository.save(row);
        triggerAfterCommit(row, order.getId());
        return row;
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

    private OrderItem createOrderItem(Order order, AgentOrder external, Product product, ProductSpec spec) {
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
        item.setCostPriceSnapshot(spec == null ? product.getCostPrice() : spec.getCostPrice());
        item.setRetailPriceSnapshot(spec == null ? product.getRetailPrice() : spec.getRetailPrice());
        item.setPricingSource(PricingSource.SALES_CHANNEL);
        item.setSubtotal(amount);
        item.setDeliveryType(product.getDeliveryType());
        item.setCustomerInputs(List.of());
        item.setStockMode(product.getStockMode());
        item.setFulfillmentStatus(FulfillmentStatus.PENDING);
        return item;
    }

    private ProductSpec resolveSpec(Product product, SalesChannelListing listing, AgentOrder external) {
        if (!product.isSpecEnabled()) return null;
        List<SalesChannelListingSpec> mappings = listingSpecRepository
                .findBySalesChannelListingIdOrderByCreatedAtAsc(listing.getId());
        List<SalesChannelListingSpec> matches;
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
        if (matches.size() != 1) return null;
        return productSpecRepository.findById(matches.get(0).getProductSpecId())
                .filter(candidate -> candidate.getProductId().equals(product.getId()) && candidate.getIsDeleted() == 0)
                .orElse(null);
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

    private void handleExternalTerminalStatus(SalesChannelOrder row) {
        if (!Set.of("cancelled", "refunded", "refunding").contains(row.getExternalStatus()) || row.getOrderId() == null) return;
        List<CardKey> keys = cardKeyRepository.findByOrderIdOrderByCreatedAtAsc(row.getOrderId());
        boolean allocated = keys.stream().anyMatch(key -> key.getStatus() == CardKeyStatus.SOLD);
        if (allocated) {
            row.setDeliveryStatus(SalesChannelOrderDeliveryStatus.REVIEW_REQUIRED);
            row.setLastErrorCode("EXTERNAL_REFUND_AFTER_FULFILLMENT");
            row.setLastErrorMessage("Provider order was refunded/cancelled after a CardKey was sold; no automatic reclaim");
            return;
        }
        orderRepository.findById(row.getOrderId()).ifPresent(order -> {
            order.setStatus(OrderStatus.REFUNDED);
            order.setRefundedAt(LocalDateTime.now());
            orderRepository.save(order);
            orderItemRepository.findByOrderId(order.getId()).forEach(item -> {
                item.setFulfillmentStatus(FulfillmentStatus.CANCELLED);
                orderItemRepository.save(item);
            });
        });
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
        return blankToNull(external.externalSkuId()) != null || blankToNull(external.specName()) != null || blankToNull(external.specValue()) != null;
    }
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
