package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.constant.StockMode;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.SalesChannelListing;
import com.myshop.model.pricing.PriceTarget;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingAddress;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingCategory;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingImage;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingShipping;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingSnapshot;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingSpecification;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.ListingSku;
import com.myshop.saleschannel.convergence.XianyuConvergenceContracts.PlatformAttribute;
import com.myshop.saleschannel.xianyu.XianyuMediaMaterializer;
import com.myshop.saleschannel.xianyu.XianyuMediaMaterializer.MaterializedImage;
import com.myshop.saleschannel.xianyu.model.XianyuProviderConfig;
import com.myshop.service.ProductInventoryBatchService.StockSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Builds the provider-neutral, exact Xianyu listing write intent from MyShop data. */
@Service
@RequiredArgsConstructor
public class XianyuDesiredListingAssembler {

    private static final int MAX_STOCK = 999_999;
    private static final int MAX_IMAGES = 9;
    private static final BigDecimal MAX_POSTAGE = new BigDecimal("1000.00");

    private final ObjectMapper objectMapper;
    private final ProductSpecRepository productSpecRepository;
    private final ProductInventoryBatchService inventoryBatchService;
    private final XianyuMediaMaterializer mediaMaterializer;
    private final PaymentUrlService paymentUrlService;

    /** Assemble only. This component has no account, operation, or Agent client dependency. */
    @Transactional(readOnly = true, noRollbackFor = IllegalArgumentException.class)
    public ListingSnapshot assemble(SalesChannelListing listing, Product product) {
        Objects.requireNonNull(listing, "sales channel listing is required");
        Objects.requireNonNull(product, "product is required");
        if (listing.getId() == null) throw invalid("listing identity is missing");
        if (product.getId() == null) throw invalid("product identity is missing");

        XianyuProviderConfig config = readConfig(listing);
        config.validateForSave();
        List<ProductSpec> sellableSpecs = loadSellableSpecs(product);
        if (product.isSpecEnabled() && sellableSpecs.isEmpty()) {
            throw invalid("规格商品没有可售规格，请启用至少一个未删除且可见的规格");
        }

        Map<UUID, List<ProductSpec>> specsByProduct = Map.of(product.getId(), sellableSpecs);
        Map<PriceTarget, StockSnapshot> inventory = inventoryBatchService.load(List.of(product), specsByProduct);
        String title = title(config, product);
        String description = description(config, product);
        List<ListingImage> images = images(config, product);
        ListingCategory category = category(config);
        ListingAddress address = address(config);
        List<PlatformAttribute> attributes = attributes(config);
        ListingShipping shipping = shipping(config);

        List<ListingSpecification> specifications = List.of();
        List<ListingSku> skus = List.of();
        String price;
        int stock;
        if (product.isSpecEnabled()) {
            Set<String> specNames = new HashSet<>();
            List<String> values = new ArrayList<>(sellableSpecs.size());
            List<ListingSku> rows = new ArrayList<>(sellableSpecs.size());
            long totalStock = 0;
            BigDecimal minimumPrice = null;
            for (ProductSpec spec : sellableSpecs) {
                String name = requiredText(spec.getName(), "商品规格名称");
                if (!specNames.add(name)) throw invalid("存在重名的可售规格，无法构造唯一 SKU: " + name);
                BigDecimal skuPrice = finalPrice(spec.getRetailPrice(), config, "规格售价 " + name);
                int skuStock = stock(product, inventory.get(new PriceTarget(product.getId(), spec.getId())), config);
                if (totalStock > MAX_STOCK - (long) skuStock) {
                    throw invalid("规格库存总和超过闲鱼上限 999999，请调整各规格库存");
                }
                totalStock += skuStock;
                values.add(name);
                rows.add(new ListingSku(
                        spec.getId().toString().toLowerCase(Locale.ROOT),
                        Map.of("规格", name),
                        decimal(skuPrice),
                        skuStock));
                if (minimumPrice == null || skuPrice.compareTo(minimumPrice) < 0) minimumPrice = skuPrice;
            }
            specifications = List.of(new ListingSpecification("规格", List.copyOf(values)));
            skus = List.copyOf(rows);
            price = decimal(Objects.requireNonNull(minimumPrice));
            stock = (int) totalStock;
        } else {
            price = decimal(finalPrice(product.getRetailPrice(), config, "商品售价"));
            stock = stock(product, inventory.get(new PriceTarget(product.getId(), null)), config);
        }

        return new ListingSnapshot(
                1,
                listing.getId().toString().toLowerCase(Locale.ROOT),
                title,
                description,
                price,
                stock,
                images,
                category,
                address,
                attributes,
                shipping,
                specifications,
                skus);
    }

    private XianyuProviderConfig readConfig(SalesChannelListing listing) {
        try {
            Object raw = listing.getProviderConfig();
            return raw == null
                    ? objectMapper.convertValue(Map.of(), XianyuProviderConfig.class)
                    : objectMapper.convertValue(raw, XianyuProviderConfig.class);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Xianyu 配置无效，请检查标题、运费、分类和图片设置", exception);
        }
    }

    private List<ProductSpec> loadSellableSpecs(Product product) {
        if (!product.isSpecEnabled()) return List.of();
        List<ProductSpec> specs = productSpecRepository
                .findByProductIdInAndIsDeletedAndVisibleOrderByProductIdAscSortOrderAsc(
                        List.of(product.getId()), 0, true);
        if (specs == null) return List.of();
        return specs.stream()
                .sorted(Comparator.comparingInt(ProductSpec::getSortOrder)
                        .thenComparing(spec -> spec.getId().toString()))
                .toList();
    }

    private String title(XianyuProviderConfig config, Product product) {
        String source = nonblank(config.titleOverride()) ? config.titleOverride() : product.getTitle();
        String value = normalized(source);
        if (value.isEmpty()) throw invalid("商品标题不能为空");
        if (codePoints(value) > 200) throw invalid("商品标题超过 200 个 Unicode 字符，请填写标题覆盖内容");
        return value;
    }

    private String description(XianyuProviderConfig config, Product product) {
        String source = nonblank(config.descriptionOverride())
                ? config.descriptionOverride()
                : firstNonblank(product.getDescription(), product.getDetailMd(), product.getTitle());
        String value = Normalizer.normalize(source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFC).strip();
        if (value.isEmpty()) throw invalid("商品描述不能为空，请填写描述覆盖内容");
        if (codePoints(value) > 1500) throw invalid("商品描述超过 1500 个 Unicode 字符，请填写描述覆盖内容");
        return value;
    }

    private List<ListingImage> images(XianyuProviderConfig config, Product product) {
        List<String> sources;
        if (config.imageUrls() != null && !config.imageUrls().isEmpty()) {
            sources = config.imageUrls();
        } else if (nonblank(product.getCoverUrl())) {
            sources = List.of(product.getCoverUrl());
        } else {
            sources = List.of();
        }
        if (sources.isEmpty()) throw invalid("至少需要一张商品图片");
        if (sources.size() > MAX_IMAGES) throw invalid("商品图片不能超过 9 张");
        List<ListingImage> result = new ArrayList<>(sources.size());
        for (String rawSource : sources) {
            String source = requiredText(rawSource, "商品图片地址");
            String publicSource = publicImageUrl(source);
            final MaterializedImage materialized;
            try {
                materialized = mediaMaterializer.readImage(source);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("商品图片无法安全读取，请重新上传或更换图片地址", exception);
            }
            String digest = sha256(materialized.content());
            result.add(new ListingImage("sha256:" + digest, publicSource, digest));
        }
        return List.copyOf(result);
    }

    private String publicImageUrl(String source) {
        if (source.startsWith("/api/uploads/")) {
            String publicSite = paymentUrlService.publicSiteUrl();
            URI base = publicSiteUri(publicSite);
            URI absolute = base.resolve(source);
            return requireAbsoluteHttp(absolute).toASCIIString();
        }
        try {
            requireAbsoluteHttp(URI.create(source));
            return source;
        } catch (IllegalArgumentException exception) {
            throw invalid("商品图片地址必须是公开 HTTP(S) URL 或 /api/uploads/ 托管图片");
        }
    }

    private URI publicSiteUri(String value) {
        try {
            URI uri = URI.create(value);
            return requireAbsoluteHttp(uri);
        } catch (IllegalArgumentException exception) {
            throw invalid("公开站点地址配置无效，请检查 app.public-site-url");
        }
    }

    private URI requireAbsoluteHttp(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw invalid("图片来源必须是绝对 HTTP(S) URL，且不能包含账号凭据");
        }
        return uri;
    }

    private ListingCategory category(XianyuProviderConfig config) {
        List<String> missing = new ArrayList<>();
        if (!nonblank(config.platformCategoryId())) missing.add("platformCategoryId");
        if (!nonblank(config.platformChannelCategoryId())) missing.add("platformChannelCategoryId");
        if (!nonblank(config.platformChannelCategoryName())) missing.add("platformChannelCategoryName");
        if (!nonblank(config.platformTbCategoryId())) missing.add("platformTbCategoryId");
        if (!missing.isEmpty()) {
            throw invalid("闲鱼分类缺少必填配置项: " + String.join(", ", missing));
        }
        return new ListingCategory(
                requiredText(config.platformCategoryId(), "闲鱼末级分类 ID"),
                optionalText(config.platformCategoryName()),
                requiredText(config.platformChannelCategoryId(), "闲鱼频道分类 ID"),
                requiredText(config.platformChannelCategoryName(), "闲鱼频道分类名称"),
                optionalText(config.platformLeafId()),
                requiredText(config.platformTbCategoryId(), "闲鱼淘宝分类 ID"));
    }

    private ListingAddress address(XianyuProviderConfig config) {
        String address = optionalText(config.address());
        String expectedText = optionalText(config.addressExpectedText());
        if (address == null || expectedText == null) {
            throw invalid("闲鱼地址配置不完整，address 和 addressExpectedText 必须同时存在；请重新选择地址");
        }
        return new ListingAddress(null, null, null, null, null, address, null, expectedText);
    }

    private List<PlatformAttribute> attributes(XianyuProviderConfig config) {
        List<PlatformAttribute> result = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        Set<List<String>> identities = new HashSet<>();
        for (XianyuProviderConfig.PlatformAttributeConfig attribute : config.platformAttributes()) {
            if (attribute == null) throw invalid("闲鱼平台属性不能为空");
            if (attribute.properties() != null) throw invalid("平台属性 properties 不受支持，请重新选择属性");
            String id = requiredText(attribute.propertyId(), "平台属性 ID");
            String name = requiredText(attribute.propertyName(), "平台属性名称");
            if ("分类".equals(name)) throw invalid("分类必须通过闲鱼分类字段单独设置");
            String valueId = optionalText(attribute.valueId());
            String valueName = optionalText(attribute.valueName());
            String text = optionalText(attribute.text());
            if (valueName == null && text == null) throw invalid("平台属性必须选择选项或填写文本");
            String priorName = names.putIfAbsent(id, name);
            if (priorName != null && !priorName.equals(name)) throw invalid("同一平台属性 ID 的名称不一致");
            List<String> identity = List.of(id, empty(valueId), empty(valueName), empty(text));
            if (!identities.add(identity)) throw invalid("平台属性包含重复选项");
            result.add(new PlatformAttribute(id, name, valueId, valueName, text, null));
        }
        result.sort(Comparator.comparing(PlatformAttribute::propertyId)
                .thenComparing(attribute -> empty(attribute.valueId()))
                .thenComparing(attribute -> empty(attribute.valueName()))
                .thenComparing(attribute -> empty(attribute.text())));
        return List.copyOf(result);
    }

    private ListingShipping shipping(XianyuProviderConfig config) {
        String method = requiredText(config.shippingMethod(), "运费方式");
        if (!List.of("free", "distance", "fixed", "template", "none").contains(method)) {
            throw invalid("运费方式无效，请重新选择");
        }
        Boolean supportPickup = config.supportPickup();
        if (supportPickup == null) throw invalid("是否支持自提必须明确设置");
        String postage = null;
        if ("fixed".equals(method)) {
            BigDecimal value = config.postage();
            if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(MAX_POSTAGE) > 0) {
                throw invalid("一口价运费必须在 0.00 到 1000.00 元之间");
            }
            postage = decimal(value);
        }
        return new ListingShipping(method, supportPickup, postage);
    }

    private BigDecimal finalPrice(BigDecimal retailPrice, XianyuProviderConfig config, String field) {
        if (retailPrice == null) throw invalid(field + "缺失");
        BigDecimal value = switch (config.priceMode()) {
            case PRODUCT_RETAIL -> retailPrice;
            case FIXED -> config.fixedPrice();
            case MARKUP_PERCENT -> retailPrice.multiply(BigDecimal.ONE.add(
                    config.markupPercent().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)));
        };
        if (value == null) throw invalid(field + "价格规则配置不完整");
        value = value.setScale(2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) throw invalid(field + "必须大于 0 元");
        return value;
    }

    private int stock(Product product, StockSnapshot snapshot, XianyuProviderConfig config) {
        long available;
        if (product.getStockMode() == StockMode.UNLIMITED || snapshot != null && snapshot.unlimited()) {
            available = config.unlimitedStockQuantity();
        } else {
            available = snapshot == null || snapshot.available() == null ? 0 : snapshot.available();
        }
        available = Math.max(0, Math.min(MAX_STOCK, available));
        if (config.stockCapPerSku() != null) available = Math.min(available, config.stockCapPerSku());
        return (int) available;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash materialized listing image", exception);
        }
    }

    private String decimal(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private int codePoints(String value) { return value.codePointCount(0, value.length()); }
    private String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value.strip(), Normalizer.Form.NFC);
    }
    private boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private String optionalText(String value) {
        if (!nonblank(value)) return null;
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    }
    private String requiredText(String value, String field) {
        String result = optionalText(value);
        if (result == null) throw invalid(field + "不能为空");
        return result;
    }
    private String firstNonblank(String... values) {
        for (String value : values) if (nonblank(value)) return value;
        return "";
    }
    private String empty(String value) { return value == null ? "" : value; }
    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
