package com.myshop.controller;

import com.myshop.common.ApiResponse;
import com.myshop.common.PageResult;
import org.springframework.data.domain.PageRequest;
import com.myshop.service.ProductListSort;
import com.myshop.service.SubsitePricingService;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.UserLevel;
import com.myshop.constant.ErrorCode;
import com.myshop.context.RequestContext;
import com.myshop.entity.Product;
import com.myshop.entity.Subsite;
import com.myshop.exception.BusinessException;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.SubsiteRepository;
import com.myshop.repository.UserLevelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Pricing configuration is always scoped to the authenticated site owner. */
@RestController
@RequestMapping("/subsite/pricing")
@RequiredArgsConstructor
public class SubsitePricingController {
    private final SubsiteRepository sites;
    private final ProductRepository products;
    private final ProductSpecRepository specs;
    private final UserLevelRepository levels;
    private final JdbcTemplate jdbc;
    private final SubsitePricingService subsitePricing;

    public record PriceEntry(UUID specId, UUID userLevelId, BigDecimal price) {}
    public record ProductPricing(boolean hidden, List<PriceEntry> prices) {}
    public record LevelMarkup(UUID userLevelId, BigDecimal markupRate) {}
    public record LevelOption(UUID id, String name) {}
    public record SpecOption(UUID id, String name, BigDecimal retailPrice, BigDecimal supplyPrice, List<String> underpricedLevels) {}
    public record ProductOption(UUID id, String title, boolean specEnabled, BigDecimal retailPrice, BigDecimal supplyPrice,
                                List<String> underpricedLevels, List<SpecOption> specs) {}

    @GetMapping("/levels/available")
    @Transactional(readOnly = true)
    public ApiResponse<List<LevelOption>> availableLevels() {
        ownedSite();
        return ApiResponse.success(levels.findAllOrdered().stream().filter(l -> l.isEnabled()).map(l -> new LevelOption(l.getId(), l.getName())).toList());
    }

    @GetMapping("/products")
    @Transactional(readOnly = true)
    public ApiResponse<PageResult<ProductOption>> availableProducts(@RequestParam(defaultValue = "1") int page,
                                                                     @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        Subsite site = ownedSite();
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new BusinessException(ErrorCode.BAD_REQUEST, "分页参数无效");
        var results = products.findPublicProducts(null, PageRequest.of(page - 1, pageSize, ProductListSort.resolve("sort_weight", "desc")));
        List<UserLevel> activeLevels = levels.findAllOrdered().stream().filter(UserLevel::isEnabled).toList();
        var rows = results.getContent().stream().map(p -> {
            var visibleSpecs = specs.findByProductIdAndIsDeletedOrderBySortOrderAsc(p.getId(), 0).stream().filter(ProductSpec::isVisible).toList();
            var specRows = visibleSpecs.stream().map(spec -> {
                var warning = warning(site, p, spec, activeLevels);
                return new SpecOption(spec.getId(), spec.getName(), spec.getRetailPrice(), warning.supplyPrice(), warning.underpricedLevels());
            }).toList();
            var warning = p.isSpecEnabled() ? null : warning(site, p, null, activeLevels);
            return new ProductOption(p.getId(), p.getTitle(), p.isSpecEnabled(), p.getRetailPrice(),
                    warning == null ? null : warning.supplyPrice(), warning == null ? List.of() : warning.underpricedLevels(), specRows);
        }).toList();
        return ApiResponse.success(PageResult.of(results, rows));
    }

    private record PriceWarning(BigDecimal supplyPrice, List<String> underpricedLevels) {}

    private PriceWarning warning(Subsite site, Product product, ProductSpec spec, List<UserLevel> activeLevels) {
        var guest = subsitePricing.resolve(site, product, spec, null, null);
        var warnings = new java.util.ArrayList<String>();
        if (guest.unitPrice().signum() <= 0 || guest.unitPrice().compareTo(guest.supplyPrice()) < 0) warnings.add("游客");
        for (UserLevel level : activeLevels) {
            var price = subsitePricing.resolve(site, product, spec, level, null);
            if ((product.getStockMode() == com.myshop.constant.StockMode.SUPPLIER && price.unitPrice().signum() == 0)
                    || price.unitPrice().compareTo(price.supplyPrice()) < 0) warnings.add(level.getName());
        }
        return new PriceWarning(guest.supplyPrice(), List.copyOf(warnings));
    }

    private Subsite ownedSite() {
        UUID userId = RequestContext.getUserId();
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        return sites.findByOwnerId(userId).orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "尚未开通分站"));
    }

    @GetMapping("/products/{productId}")
    @Transactional(readOnly = true)
    public ApiResponse<ProductPricing> getProduct(@PathVariable UUID productId) {
        Subsite site = ownedSite();
        if (products.findById(productId).filter(p -> p.getIsDeleted() == 0).isEmpty())
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在");
        boolean hidden = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM subsite_hidden_products WHERE subsite_id = ? AND product_id = ?)", Boolean.class, site.getId(), productId));
        List<PriceEntry> prices = jdbc.query("SELECT spec_id, user_level_id, price FROM subsite_product_prices WHERE subsite_id = ? AND product_id = ?",
                (rs, n) -> new PriceEntry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3)), site.getId(), productId);
        return ApiResponse.success(new ProductPricing(hidden, prices));
    }

    @PutMapping("/products/{productId}")
    @Transactional
    public ApiResponse<ProductPricing> updateProduct(@PathVariable UUID productId, @RequestBody ProductPricing request) {
        Subsite site = ownedSite();
        Product product = products.findByIdForUpdate(productId).filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));
        if (request == null || request.prices() == null || request.prices().size() > 1000)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "定价配置无效");
        var seen = new HashSet<String>();
        for (PriceEntry row : request.prices()) {
            if (row == null || (product.isSpecEnabled() != (row.specId() != null)))
                throw new BusinessException(ErrorCode.BAD_REQUEST, "规格与商品不匹配");
            if (row.specId() != null && specs.findById(row.specId()).filter(s -> s.getProductId().equals(productId) && s.getIsDeleted() == 0 && s.isVisible()).isEmpty())
                throw new BusinessException(ErrorCode.BAD_REQUEST, "规格不存在");
            if (row.userLevelId() != null && levels.findById(row.userLevelId()).filter(l -> l.isEnabled()).isEmpty())
                throw new BusinessException(ErrorCode.BAD_REQUEST, "用户等级无效");
            validateMoney(row.price());
            if (!seen.add(row.specId() + ":" + row.userLevelId())) throw new BusinessException(ErrorCode.BAD_REQUEST, "定价重复");
        }
        // Preserve prices for hidden/deleted specifications and disabled levels. They may
        // become available again; a partial editor must never silently discard them.
        jdbc.update("DELETE FROM subsite_product_prices p WHERE p.subsite_id = ? AND p.product_id = ? "
                + "AND ((? = false AND p.spec_id IS NULL) OR (? = true AND EXISTS "
                + "(SELECT 1 FROM product_specs s WHERE s.id = p.spec_id AND s.product_id = p.product_id AND s.is_deleted = 0 AND s.is_visible = true))) "
                + "AND (p.user_level_id IS NULL OR EXISTS (SELECT 1 FROM user_levels l WHERE l.id = p.user_level_id AND l.enabled = true))",
                site.getId(), productId, product.isSpecEnabled(), product.isSpecEnabled());
        for (PriceEntry row : request.prices()) jdbc.update("INSERT INTO subsite_product_prices(subsite_id, product_id, spec_id, user_level_id, price) VALUES (?, ?, ?, ?, ?)", site.getId(), productId, row.specId(), row.userLevelId(), row.price());
        jdbc.update("DELETE FROM subsite_hidden_products WHERE subsite_id = ? AND product_id = ?", site.getId(), productId);
        if (request.hidden()) jdbc.update("INSERT INTO subsite_hidden_products(subsite_id, product_id) VALUES (?, ?)", site.getId(), productId);
        return ApiResponse.success(request);
    }

    @GetMapping("/levels")
    @Transactional(readOnly = true)
    public ApiResponse<List<LevelMarkup>> getLevels() {
        Subsite site = ownedSite();
        return ApiResponse.success(jdbc.query("SELECT user_level_id, markup_rate FROM subsite_level_markups WHERE subsite_id = ?",
                (rs, n) -> new LevelMarkup(rs.getObject(1, UUID.class), rs.getBigDecimal(2)), site.getId()));
    }

    @PutMapping("/levels")
    @Transactional
    public ApiResponse<List<LevelMarkup>> updateLevels(@RequestBody List<LevelMarkup> rows) {
        Subsite site = ownedSite();
        if (rows == null || rows.size() > 1000) throw new BusinessException(ErrorCode.BAD_REQUEST, "等级定价无效");
        var seen = new HashSet<UUID>();
        for (LevelMarkup row : rows) {
            if (row == null || row.userLevelId() == null || !seen.add(row.userLevelId()) || levels.findById(row.userLevelId()).filter(l -> l.isEnabled()).isEmpty()
                    || row.markupRate() == null || row.markupRate().signum() < 0 || row.markupRate().compareTo(new BigDecimal("99999.9999")) > 0)
                throw new BusinessException(ErrorCode.BAD_REQUEST, "等级定价无效");
            try { row.markupRate().setScale(4, RoundingMode.UNNECESSARY); }
            catch (ArithmeticException e) { throw new BusinessException(ErrorCode.BAD_REQUEST, "加价率最多支持四位小数"); }
        }
        jdbc.update("DELETE FROM subsite_level_markups m WHERE m.subsite_id = ? AND EXISTS (SELECT 1 FROM user_levels l WHERE l.id = m.user_level_id AND l.enabled = true)", site.getId());
        for (LevelMarkup row : rows) jdbc.update("INSERT INTO subsite_level_markups(subsite_id, user_level_id, markup_rate) VALUES (?, ?, ?)", site.getId(), row.userLevelId(), row.markupRate());
        return ApiResponse.success(rows);
    }

    private void validateMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(new BigDecimal("99999999.99")) > 0)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "售价无效");
        try { amount.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw new BusinessException(ErrorCode.BAD_REQUEST, "金额最多支持两位小数"); }
    }
}
