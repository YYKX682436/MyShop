package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.UserRole;
import com.myshop.config.SubsiteDomainRules;
import com.myshop.context.SiteRequestContext;
import com.myshop.entity.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.myshop.exception.BusinessException;
import com.myshop.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SubsiteManagementService {
    private final SubsiteRepository sites;
    private final SubsiteSettingsRepository settings;
    private final UserRepository users;
    private final UserLevelRepository levels;
    private final JdbcTemplate jdbc;
    private final BalanceTransactionRepository balanceTransactions;
    private final ImageUploadService imageUploadService;
    private MediaAssetRegistry mediaAssets;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMediaAssets(MediaAssetRegistry value) { this.mediaAssets = value; }
    @Value("${app.public-site-url:http://localhost:3000}") private String publicSiteUrl;
    @Value("${subsites.reserved-prefixes:mc,www,api,admin,mail,kf,jc,dl,openlist,myshop-candidate,tp}") private String reservedPrefixes;

    public record Settings(boolean selfServiceEnabled, BigDecimal openingPrice, UUID supplyLevelId, List<UUID> allowedLevelIds) {}
    public record OpenRequest(UUID ownerId, String prefix, String name) {}
    public record SiteUpdate(String name, String faviconUrl) {}
    public record TransferRequest(UUID userId, UUID levelId) {}
    public record StatusRequest(boolean enabled) {}
    public record LevelRequest(UUID levelId) {}
    public record LevelUpgradeRuleRequest(UUID userLevelId, boolean selfUpgradeEnabled,
                                          BigDecimal upgradePrice, boolean publicPriceEnabled) {}
    public record LevelUpgradeRuleView(UUID userLevelId, String name, int upgradeRank,
                                       @JsonProperty("is_default") boolean isDefault,
                                       boolean levelEnabled, boolean platformPricePublic,
                                       boolean selfUpgradeEnabled, BigDecimal upgradePrice,
                                       boolean publicPriceEnabled) {}

    @Transactional(readOnly = true)
    public Settings getSettings() {
        SubsiteSettings value = settings.findById(1).orElseThrow();
        return new Settings(value.isSelfServiceEnabled(), value.getOpeningPrice(), value.getSupplyLevelId(),
                jdbc.queryForList("select level_id from subsite_allowed_levels order by level_id", UUID.class));
    }

    @Transactional
    public Settings updateSettings(Settings request) {
        if (request == null || request.openingPrice() == null || request.allowedLevelIds() == null)
            throw bad("开通配置不完整");
        BigDecimal price;
        try { price = request.openingPrice().setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw bad("开通价格最多两位小数"); }
        if (price.signum() < 0 || price.compareTo(new BigDecimal("9999999999.99")) > 0) throw bad("开通价格无效");
        Set<UUID> ids = new HashSet<>(request.allowedLevelIds());
        if (ids.contains(null) || ids.size() != request.allowedLevelIds().size()) throw bad("允许等级无效");
        if (request.supplyLevelId() != null) requireLevel(request.supplyLevelId());
        for (UUID id : ids) requireLevel(id);
        if (request.selfServiceEnabled() && (request.supplyLevelId() == null || ids.isEmpty()))
            throw bad("请先选择供货等级和允许开通的等级");
        SubsiteSettings value = settings.lockSettings().orElseThrow();
        value.setSelfServiceEnabled(request.selfServiceEnabled()); value.setOpeningPrice(price);
        value.setSupplyLevelId(request.supplyLevelId()); settings.save(value);
        jdbc.update("delete from subsite_allowed_levels");
        for (UUID id : ids) jdbc.update("insert into subsite_allowed_levels(level_id) values (?)", id);
        return getSettings();
    }

    @Transactional
    public Map<String, Object> open(UUID operatorId, OpenRequest request, boolean admin) {
        if (SiteRequestContext.getSite() != null) throw bad("仅可在主站开通分站");
        if (request == null) throw bad("开通信息不完整");
        String prefix = request.prefix() == null ? "" : request.prefix().trim().toLowerCase(Locale.ROOT);
        String name = text(request.name(), 100, true);
        if (!prefix.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw bad("域名前缀无效");
        SubsiteDomainRules domainRules = SubsiteDomainRules.fromPublicSiteUrl(publicSiteUrl);
        String mainHost = domainRules.mainHost();
        if (Set.of("mc", "www", "api", "admin", "mail", "kf", "jc", "dl", "openlist", "myshop-candidate", "tp").contains(prefix)
                || prefix.equals(mainHost.split("\\.")[0]) || Arrays.stream(reservedPrefixes.split(","))
                .anyMatch(s -> s.trim().equalsIgnoreCase(prefix))) throw bad("域名前缀已保留");
        SubsiteSettings config = settings.lockSettings().orElseThrow();
        UUID ownerId = admin ? request.ownerId() : operatorId;
        if (ownerId == null) throw bad("请选择站长");
        User owner = users.findByIdForUpdate(ownerId).orElseThrow(() -> bad("站长不存在"));
        if (owner.getRole() != UserRole.USER || owner.getIsDeleted() != 0 || owner.getSubsiteId() != null)
            throw bad("站长必须为有效的主站用户");
        Subsite existing = sites.findByOwnerId(ownerId).orElse(null);
        if (existing != null) {
            if (existing.getPrefix().equals(prefix) && existing.getName().equals(name)) return view(existing);
            throw bad("该用户已开通分站");
        }
        if (config.getSupplyLevelId() == null) throw bad("请先由管理员选择分站供货等级");
        requireLevel(config.getSupplyLevelId());
        if (!admin) {
            if (!config.isSelfServiceEnabled()) throw bad("暂未开放自助开通");
            if (owner.getUserLevelId() != null) requireLevel(owner.getUserLevelId());
            if (owner.getUserLevelId() == null || jdbc.queryForObject(
                    "select count(*) from subsite_allowed_levels where level_id = ?", Long.class, owner.getUserLevelId()) == 0)
                throw bad("当前等级不能开通分站");
        }
        if (sites.existsByPrefix(prefix)) throw bad("域名前缀已被使用");
        BigDecimal amount = admin ? BigDecimal.ZERO.setScale(2) : config.getOpeningPrice();
        BigDecimal before = owner.getBalance();
        if (before == null || before.compareTo(amount) < 0) throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE, "余额不足，无法开通分站");
        BigDecimal after = before.subtract(amount);
        Subsite site = new Subsite(); site.setOwnerId(ownerId); site.setPrefix(prefix); site.setName(name);
        sites.saveAndFlush(site);
        owner.setBalance(after); users.save(owner);
        UUID ledgerId = null;
        if (amount.signum() > 0) {
            BalanceTransaction ledger = new BalanceTransaction();
            ledger.setUserId(ownerId); ledger.setType(com.myshop.constant.BalanceTransactionType.SUBSITE_OPENING);
            ledger.setAmount(amount); ledger.setDelta(amount.negate()); ledger.setBalanceBefore(before);
            ledger.setBalanceAfter(after); ledger.setReason("开通分站：" + prefix); ledger.setOperatorId(operatorId);
            ledgerId = balanceTransactions.saveAndFlush(ledger).getId();
        }
        jdbc.update("insert into subsite_opening_records(subsite_id, owner_id, amount, balance_before, balance_after, operator_id, balance_transaction_id) values (?,?,?,?,?,?,?)",
                site.getId(), ownerId, amount, before, after, operatorId, ledgerId);
        return view(site);
    }

    @Transactional(readOnly = true)
    public Map<String,Object> opening(UUID userId) {
        SubsiteSettings config = settings.findById(1).orElseThrow();
        User user = userId == null ? null : users.findById(userId).orElse(null);
        boolean eligible = SiteRequestContext.getSite() == null && config.isSelfServiceEnabled()
                && config.getSupplyLevelId() != null && user != null && user.getRole() == UserRole.USER
                && user.getIsDeleted() == 0 && user.getSubsiteId() == null && user.getUserLevelId() != null
                && sites.findByOwnerId(user.getId()).isEmpty()
                && jdbc.queryForObject("select count(*) from subsite_allowed_levels where level_id = ?", Long.class, user.getUserLevelId()) > 0
                && levels.findById(config.getSupplyLevelId()).filter(UserLevel::isEnabled).isPresent();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("enabled", config.isSelfServiceEnabled());
        result.put("opening_price", config.getOpeningPrice());
        result.put("eligible", eligible);
        result.put("domain_suffix", SubsiteDomainRules.fromPublicSiteUrl(publicSiteUrl).domainSuffix());
        return result;
    }
    @Transactional(readOnly = true)
    public Map<String,Object> mine(UUID ownerId) { return sites.findByOwnerId(ownerId).map(this::view).orElse(null); }
    @Transactional(readOnly = true)
    public List<Map<String,Object>> list() { return sites.findAll().stream().map(site -> {
        Map<String,Object> row = view(site);
        User owner = users.findById(site.getOwnerId()).orElse(null);
        row.put("owner_username", owner == null ? null : owner.getUsername());
        row.put("owner_user_no", owner == null ? null : owner.getUserNo());
        row.putAll(jdbc.queryForMap("""
                select count(*) as order_count,
                  coalesce(sum(subsite_profit) filter (where subsite_profit_state = 'SETTLED'), 0) as settled_profit
                from orders where subsite_id = ? and subsite_owner_id_snapshot = ?
                """, site.getId(),site.getOwnerId()));
        return row;
    }).toList(); }
    @Transactional
    public Map<String,Object> status(UUID id, boolean enabled) {
        Subsite site = sites.findByIdForUpdate(id).orElseThrow(() -> bad("分站不存在"));
        site.setEnabled(enabled); return view(sites.save(site));
    }
    @Transactional
    public Map<String,Object> updateMine(UUID ownerId, SiteUpdate request) {
        if (request == null) throw bad("站点设置无效");
        String favicon = text(request.faviconUrl(),500,false);
        var references = mediaAssets == null ? null : mediaAssets.lockExistingSiteIcon(favicon);
        Subsite site = sites.findByOwnerIdForUpdate(ownerId).orElseThrow(() -> bad("尚未开通分站"));
        if (mediaAssets != null) mediaAssets.verifyHistoricalSiteIconReferences(site.getFaviconUrl(), references);
        if (favicon != null && !favicon.equals(site.getFaviconUrl())) throw bad("请通过上传设置网站图标");
        site.setName(text(request.name(),100,true));
        site.setFaviconUrl(favicon);
        var saved = sites.save(site);
        if (mediaAssets != null) mediaAssets.replaceReferences("SUBSITE", saved.getId(), references, "favicon_url");
        return view(saved);
    }
    @Transactional
    public Map<String,Object> uploadFavicon(UUID ownerId, org.springframework.web.multipart.MultipartFile file) {
        if (!sites.existsByOwnerId(ownerId)) throw bad("尚未开通分站");
        var uploaded = imageUploadService.uploadSiteFavicon(file);
        var references = mediaAssets == null ? null : mediaAssets.lockSiteIcon(uploaded.url());
        Subsite site = sites.findByOwnerIdForUpdate(ownerId).orElseThrow(() -> bad("尚未开通分站"));
        site.setFaviconUrl(uploaded.url());
        var saved = sites.save(site);
        if (mediaAssets != null) mediaAssets.replaceReferences("SUBSITE", saved.getId(), references, "favicon_url");
        Map<String,Object> result = view(saved);
        result.put("image_processing_reason", uploaded.reason());
        return result;
    }
    @Transactional
    public void transfer(UUID siteId, TransferRequest request) {
        if (request == null || request.userId() == null || request.levelId() == null) throw bad("移交信息不完整");
        requireLevel(request.levelId());
        User user = users.findByIdForUpdate(request.userId()).orElseThrow(() -> bad("用户不存在"));
        if (user.getRole() != UserRole.USER || !Objects.equals(user.getSubsiteId(), siteId)) throw bad("用户不属于该分站");
        if (user.getAuthVersion() == Integer.MAX_VALUE) throw new IllegalStateException("User auth version overflow");
        user.setSubsiteId(null); user.setUserLevelId(request.levelId()); user.setAuthVersion(user.getAuthVersion()+1); users.save(user);
    }
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Map<String,Object>> customers(UUID ownerId, int page, int size) {
        Subsite site = sites.findByOwnerId(ownerId).orElseThrow(() -> bad("尚未开通分站"));
        return customersBySite(site.getId(), page, size);
    }
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Map<String,Object>> customersBySite(UUID siteId, int page, int size) {
        if (!sites.existsById(siteId)) throw bad("分站不存在");
        return users.findBySubsiteIdOrderByCreatedAtDesc(siteId,
                org.springframework.data.domain.PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 100))))
                .map(user -> {
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("id",user.getId()); item.put("user_no",user.getUserNo());
                    item.put("username",user.getUsername()); item.put("user_level_id",user.getUserLevelId());
                    item.put("created_at",user.getCreatedAt());
                    return item;
                });
    }
    @Transactional
    public void changeCustomerLevel(UUID ownerId, UUID customerId, LevelRequest request) {
        Subsite site = sites.findByOwnerId(ownerId).orElseThrow(() -> bad("尚未开通分站"));
        if (request == null || request.levelId() == null || ownerId.equals(customerId)) throw bad("用户等级设置无效");
        requireLevel(request.levelId());
        User user = users.findByIdForUpdate(customerId).orElseThrow(() -> bad("用户不存在"));
        if (user.getRole() != UserRole.USER || !Objects.equals(user.getSubsiteId(), site.getId())) throw bad("用户不属于该分站");
        user.setUserLevelId(request.levelId()); users.save(user);
    }

    @Transactional(readOnly = true)
    public List<LevelUpgradeRuleView> levelUpgradeRules(UUID ownerId) {
        Subsite site = sites.findByOwnerId(ownerId).orElseThrow(() -> bad("尚未开通分站"));
        return levels.findAllOrdered().stream().sorted(Comparator.comparingInt(UserLevel::getUpgradeRank))
                .filter(UserLevel::isEnabled).map(level -> {
                    BigDecimal localPrice = null;
                    boolean selfEnabled = false;
                    boolean publicEnabled = false;
                    if (level.isDefault()) localPrice = BigDecimal.ZERO.setScale(2);
                    else {
                        List<Map<String, Object>> rows = jdbc.queryForList("""
                                SELECT self_upgrade_enabled, upgrade_price, public_price_enabled
                                FROM subsite_level_upgrade_rules WHERE subsite_id = ? AND user_level_id = ?
                                """, site.getId(), level.getId());
                        if (!rows.isEmpty()) {
                            Map<String, Object> row = rows.get(0);
                            selfEnabled = Boolean.TRUE.equals(row.get("self_upgrade_enabled"));
                            localPrice = (BigDecimal) row.get("upgrade_price");
                            publicEnabled = Boolean.TRUE.equals(row.get("public_price_enabled"));
                        }
                    }
                    return new LevelUpgradeRuleView(level.getId(), level.getName(), level.getUpgradeRank(),
                            level.isDefault(), level.isEnabled(), level.isPublicPriceEnabled(), selfEnabled, localPrice,
                            publicEnabled && level.isPublicPriceEnabled());
                }).toList();
    }

    @Transactional
    public List<LevelUpgradeRuleView> updateLevelUpgradeRules(UUID ownerId,
                                                               List<LevelUpgradeRuleRequest> request) {
        if (request == null) throw bad("等级升级规则不能为空");
        Subsite site = sites.findByOwnerIdForUpdate(ownerId).orElseThrow(() -> bad("尚未开通分站"));
        Map<UUID, UserLevel> available = levels.findAllOrdered().stream()
                .filter(UserLevel::isEnabled).collect(java.util.stream.Collectors.toMap(UserLevel::getId, level -> level));
        Map<UUID, LevelUpgradeRuleRequest> updates = new LinkedHashMap<>();
        for (LevelUpgradeRuleRequest rule : request) {
            if (rule == null || rule.userLevelId() == null || updates.putIfAbsent(rule.userLevelId(), rule) != null)
                throw bad("等级升级规则无效");
            UserLevel level = available.get(rule.userLevelId());
            if (level == null) throw bad("等级不存在或已停用");
            if (level.isDefault()) {
                if (rule.selfUpgradeEnabled() || rule.publicPriceEnabled()
                        || rule.upgradePrice() != null && rule.upgradePrice().signum() != 0)
                    throw bad("默认等级价格固定为 0，不能开放自主升级或价格查询");
                continue;
            }
            BigDecimal price = rule.upgradePrice() == null ? null : normalizeUpgradePrice(rule.upgradePrice());
            if (rule.selfUpgradeEnabled() && price == null) throw bad("开放自主升级前必须配置价格");
            if (rule.publicPriceEnabled() && (!rule.selfUpgradeEnabled() || price == null
                    || !level.isPublicPriceEnabled()))
                throw bad("主站隐藏的等级价格不能在分站公开");
        }

        Map<UUID, BigDecimal> effectivePrices = new HashMap<>();
        Map<UUID, Boolean> effectiveEnabled = new HashMap<>();
        Map<UUID, Boolean> effectivePublic = new HashMap<>();
        for (UserLevel level : available.values()) {
            if (level.isDefault()) {
                effectivePrices.put(level.getId(), BigDecimal.ZERO.setScale(2));
                effectiveEnabled.put(level.getId(), false);
                effectivePublic.put(level.getId(), false);
                continue;
            }
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT self_upgrade_enabled, upgrade_price, public_price_enabled
                    FROM subsite_level_upgrade_rules WHERE subsite_id = ? AND user_level_id = ?
                    """, site.getId(), level.getId());
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                effectivePrices.put(level.getId(), (BigDecimal) row.get("upgrade_price"));
                effectiveEnabled.put(level.getId(), Boolean.TRUE.equals(row.get("self_upgrade_enabled")));
                effectivePublic.put(level.getId(), Boolean.TRUE.equals(row.get("public_price_enabled")));
            }
        }
        for (LevelUpgradeRuleRequest rule : updates.values()) {
            if (available.get(rule.userLevelId()).isDefault()) continue;
            effectivePrices.put(rule.userLevelId(), rule.upgradePrice() == null ? null : normalizeUpgradePrice(rule.upgradePrice()));
            effectiveEnabled.put(rule.userLevelId(), rule.selfUpgradeEnabled());
            effectivePublic.put(rule.userLevelId(), rule.publicPriceEnabled());
        }
        BigDecimal lastConfiguredPrice = null;
        for (UserLevel level : available.values().stream().sorted(Comparator.comparingInt(UserLevel::getUpgradeRank)).toList()) {
            BigDecimal price = effectivePrices.get(level.getId());
            if (Boolean.TRUE.equals(effectiveEnabled.get(level.getId())) && price == null)
                throw bad("开放自主升级前必须配置价格");
            if (Boolean.TRUE.equals(effectivePublic.get(level.getId()))
                    && (!Boolean.TRUE.equals(effectiveEnabled.get(level.getId())) || !level.isPublicPriceEnabled()))
                throw bad("主站隐藏的等级价格不能在分站公开");
            if (price != null && lastConfiguredPrice != null && price.compareTo(lastConfiguredPrice) < 0)
                throw bad("较高等级价格不能低于较低等级价格");
            if (price != null) lastConfiguredPrice = price;
        }

        for (LevelUpgradeRuleRequest rule : updates.values()) {
            UserLevel level = available.get(rule.userLevelId());
            if (level.isDefault()) continue;
            BigDecimal price = rule.upgradePrice() == null ? null : normalizeUpgradePrice(rule.upgradePrice());
            jdbc.update("""
                    INSERT INTO subsite_level_upgrade_rules
                        (subsite_id, user_level_id, self_upgrade_enabled, upgrade_price, public_price_enabled, updated_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (subsite_id, user_level_id) DO UPDATE SET
                        self_upgrade_enabled = EXCLUDED.self_upgrade_enabled,
                        upgrade_price = EXCLUDED.upgrade_price,
                        public_price_enabled = EXCLUDED.public_price_enabled,
                        updated_at = CURRENT_TIMESTAMP
                    """, site.getId(), rule.userLevelId(), rule.selfUpgradeEnabled(), price,
                    rule.publicPriceEnabled());
        }
        return levelUpgradeRules(ownerId);
    }

    private BigDecimal normalizeUpgradePrice(BigDecimal value) {
        try {
            BigDecimal amount = value.setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() < 0 || amount.compareTo(new BigDecimal("9999999999.99")) > 0)
                throw bad("等级开通价格无效");
            return amount;
        } catch (ArithmeticException exception) {
            throw bad("等级开通价格最多保留两位小数");
        }
    }
    @Transactional(readOnly = true)
    public Map<String,Object> overview(UUID ownerId) {
        Subsite site = sites.findByOwnerId(ownerId).orElseThrow(() -> bad("尚未开通分站"));
        return jdbc.queryForMap("""
                select count(*) as order_count,
                  coalesce(sum(subsite_profit) filter (where status = 'PAID' and subsite_profit_state is null), 0) as estimated_profit,
                  coalesce(sum(subsite_profit) filter (where subsite_profit_state = 'PENDING'), 0) as pending_profit,
                  coalesce(sum(subsite_profit) filter (where subsite_profit_state = 'SETTLED'), 0) as settled_profit
                from orders where subsite_id = ? and subsite_owner_id_snapshot = ?
                """, site.getId(), ownerId);
    }
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Map<String,Object>> orders(UUID ownerId, int page, int size) {
        Subsite site = sites.findByOwnerId(ownerId).orElseThrow(() -> bad("尚未开通分站"));
        int p = Math.max(0,page), s = Math.max(1,Math.min(size,100));
        Long count = jdbc.queryForObject("select count(*) from orders where subsite_id = ? and subsite_owner_id_snapshot = ?", Long.class, site.getId(),ownerId);
        var rows = jdbc.queryForList("""
                select id, order_no, actual_amount, status, created_at, paid_at, delivered_at,
                  subsite_profit, subsite_profit_state, subsite_profit_canceled_at, subsite_profit_settled_at, subsite_profit_reversed_at
                from orders where subsite_id = ? and subsite_owner_id_snapshot = ?
                order by created_at desc, id desc limit ? offset ?
                """,site.getId(),ownerId,s,(long)p*s);
        return new org.springframework.data.domain.PageImpl<>(rows, org.springframework.data.domain.PageRequest.of(p,s), count == null ? 0 : count);
    }
    public Map<String,Object> view(Subsite s) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id",s.getId()); result.put("owner_id",s.getOwnerId()); result.put("prefix",s.getPrefix());
        result.put("name",s.getName()); result.put("enabled",s.isEnabled()); result.put("favicon_url",s.getFaviconUrl());
        result.put("url", SubsiteDomainRules.fromPublicSiteUrl(publicSiteUrl).subsiteUrl(s.getPrefix()));
        result.put("user_count", jdbc.queryForObject("select count(*) from users where subsite_id = ?", Long.class, s.getId()));
        return result;
    }
    private void requireLevel(UUID id) { if (levels.findById(id).filter(UserLevel::isEnabled).isEmpty()) throw bad("用户等级不存在或已停用"); }
    private String text(String value,int max,boolean required) {
        String v=value==null ? "" : value.trim();
        if ((required && v.isEmpty()) || v.length()>max) throw bad("站点设置无效");
        return v.isEmpty() ? null : v;
    }
    private BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST,message); }
}
