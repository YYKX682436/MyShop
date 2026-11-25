package com.myshop.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myshop.common.PageResult;
import com.myshop.constant.AccountPaymentStatus;
import com.myshop.constant.AccountPaymentType;
import com.myshop.constant.BalanceTransactionType;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.UserRole;
import com.myshop.entity.AccountPaymentTransaction;
import com.myshop.entity.BalanceTransaction;
import com.myshop.entity.PaymentAttempt;
import com.myshop.entity.Product;
import com.myshop.entity.ProductSpec;
import com.myshop.entity.Subsite;
import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.response.BalanceTransactionResponse;
import com.myshop.payment.PaymentNextAction;
import com.myshop.repository.AccountPaymentTransactionRepository;
import com.myshop.repository.BalanceTransactionRepository;
import com.myshop.repository.ProductRepository;
import com.myshop.repository.ProductSpecRepository;
import com.myshop.repository.PaymentAttemptRepository;
import com.myshop.repository.SiteConfigRepository;
import com.myshop.repository.SubsiteRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserAccountService {
    private static final BigDecimal DEFAULT_LEVEL_PRICE = new BigDecimal("0.00");
    private static final BigDecimal DEFAULT_RECHARGE_MIN = new BigDecimal("10.00");
    private static final BigDecimal DEFAULT_RECHARGE_MAX = new BigDecimal("5000.00");

    private final UserRepository users;
    private final UserLevelRepository levels;
    private final AccountPaymentTransactionRepository accountTransactions;
    private final PaymentAttemptRepository paymentAttempts;
    private final BalanceTransactionRepository balanceTransactions;
    private final ProductRepository products;
    private final ProductSpecRepository productSpecs;
    private final SiteConfigRepository siteConfigs;
    private final SubsiteRepository subsites;
    private final PricingService pricing;
    private final SubsitePricingService subsitePricing;
    private final AccountUpgradePolicy upgradePolicy;
    private final AccountPaymentCompletionService completion;
    private final PaymentService payments;
    private final ObjectMapper objectMapper;

    public record UpgradeOption(UUID levelId, String name, int upgradeRank,
                                BigDecimal levelPrice, BigDecimal amount,
                                boolean publicPriceEnabled) { }

    public record AccountOverview(boolean admin, boolean rechargeEnabled,
                                  BigDecimal rechargeMinAmount, BigDecimal rechargeMaxAmount,
                                  BigDecimal balance, String currentLevelName,
                                  int currentUpgradeRank, BigDecimal currentLevelPrice,
                                  boolean hasHigherLevel, boolean upgradeAvailable,
                                  List<UpgradeOption> upgrades) { }

    public record PaymentStart(UUID transactionId, UUID paymentId, AccountPaymentStatus status,
                               BigDecimal amount, AccountPaymentType type) { }

    public record ProductLevelPrice(UUID levelId, String levelName, BigDecimal price,
                                    BigDecimal saving) { }

    public record ProductPriceCheck(UUID productId, String productName, UUID specId,
                                    String specName, String currentLevelName,
                                    BigDecimal currentPrice, List<ProductLevelPrice> levels) { }

    @Transactional(readOnly = true)
    public AccountOverview overview(UUID userId) {
        User user = requireStoreUser(userId, true);
        boolean admin = user.getRole() != UserRole.USER;
        BigDecimal minimum = rechargeLimit("recharge_min_amount", DEFAULT_RECHARGE_MIN);
        BigDecimal maximum = rechargeLimit("recharge_max_amount", DEFAULT_RECHARGE_MAX);
        if (admin) return new AccountOverview(true, false, minimum, maximum, null,
                null, 0, null, false, false, List.of());

        SiteContext site = siteContextFor(user, true);
        if (site.owner()) return new AccountOverview(false, rechargeEnabled(), minimum, maximum,
                user.getBalance(), levelName(user.getUserLevelId()), currentRank(user.getUserLevelId()),
                null, false, false, List.of());
        UserLevel current = levels.findById(user.getUserLevelId()).filter(UserLevel::isEnabled).orElse(null);
        if (current == null) return new AccountOverview(false, rechargeEnabled(), minimum, maximum,
                user.getBalance(), null, 0, null, false, false, List.of());
        BigDecimal currentPrice = upgradePolicy.price(site.id(), current);
        List<UserLevel> all = levels.findAllOrdered().stream()
                .sorted(Comparator.comparingInt(UserLevel::getUpgradeRank)).toList();
        boolean hasHigher = all.stream().anyMatch(level -> level.isEnabled()
                && level.getUpgradeRank() > current.getUpgradeRank());
        List<UpgradeOption> options = upgradeOptions(current, site.id(), currentPrice, all);
        return new AccountOverview(false, rechargeEnabled(), minimum, maximum, user.getBalance(),
                current.getName(), current.getUpgradeRank(), currentPrice, hasHigher,
                !options.isEmpty(), options);
    }

    @Transactional
    public PaymentStart beginRecharge(UUID userId, BigDecimal amount,
                                      String channelCode, String device) {
        User user = requireStoreUser(userId, true);
        SiteContext site = siteContextFor(user, true);
        if (user.getRole() != UserRole.USER) throw bad("管理员无需充值");
        if (!rechargeEnabled()) throw bad("余额充值暂未开放");
        BigDecimal requested = normalizedAmount(amount);
        BigDecimal minimum = rechargeLimit("recharge_min_amount", DEFAULT_RECHARGE_MIN);
        BigDecimal maximum = rechargeLimit("recharge_max_amount", DEFAULT_RECHARGE_MAX);
        if (requested.compareTo(minimum) < 0 || requested.compareTo(maximum) > 0)
            throw bad("充值金额须在 " + minimum.toPlainString() + " 至 " + maximum.toPlainString() + " 元之间");
        if (channelCode == null || channelCode.isBlank()) throw bad("请选择在线支付方式");

        AccountPaymentTransaction transaction = new AccountPaymentTransaction();
        transaction.setUserId(userId);
        transaction.setSubsiteId(site.id());
        transaction.setSubsiteOwnerIdSnapshot(site.ownerId());
        transaction.setType(AccountPaymentType.RECHARGE);
        transaction.setPaymentMethod(OrderPaymentMethod.ONLINE);
        transaction.setAmount(requested);
        transaction.setStatus(AccountPaymentStatus.PENDING);
        transaction.setChannelCodeSnapshot(channelCode.trim());
        transaction.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        accountTransactions.saveAndFlush(transaction);
        Map<String, Object> intent = payments.prepareAccountPayment(transaction.getId(), channelCode.trim(), device);
        return new PaymentStart(transaction.getId(), (UUID) intent.get("payment_id"),
                AccountPaymentStatus.PENDING, requested, AccountPaymentType.RECHARGE);
    }

    @Transactional
    public PaymentStart beginUpgrade(UUID userId, UUID targetLevelId,
                                     OrderPaymentMethod method, String channelCode, String device) {
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> bad("用户不存在"));
        SiteContext site = siteContextFor(user, false);
        if (user.getRole() != UserRole.USER || user.getIsDeleted() != 0) throw bad("当前账号不能自主升级");
        List<UserLevel> all = levels.findAllForUpdate();
        Map<UUID, UserLevel> byId = new LinkedHashMap<>();
        all.forEach(level -> byId.put(level.getId(), level));
        UserLevel current = byId.get(user.getUserLevelId());
        UserLevel target = byId.get(targetLevelId);
        if (current == null || !current.isEnabled() || target == null || !target.isEnabled())
            throw bad("当前或目标等级不可用");
        BigDecimal currentPrice = upgradePolicy.price(site.id(), current);
        BigDecimal targetPrice = upgradePolicy.price(site.id(), target);
        BigDecimal amount = upgradeAmount(current, target, currentPrice, targetPrice, site.id());
        if (method != OrderPaymentMethod.ONLINE && method != OrderPaymentMethod.BALANCE)
            throw bad("支付方式无效");

        expireOldUpgradeRequests(userId);
        List<AccountPaymentTransaction> pending = accountTransactions.findByUserIdAndTypeAndStatusOrderByCreatedAtDesc(
                userId, AccountPaymentType.LEVEL_UPGRADE, AccountPaymentStatus.PENDING);
        if (!pending.isEmpty()) {
            AccountPaymentTransaction existing = pending.get(0);
            if (method == OrderPaymentMethod.ONLINE && existing.getPaymentMethod() == method
                    && targetLevelId.equals(existing.getTargetLevelId())
                    && java.util.Objects.equals(channelCode, existing.getChannelCodeSnapshot())) {
                Map<String, Object> intent = payments.prepareAccountPayment(existing.getId(), channelCode, device);
                return new PaymentStart(existing.getId(), (UUID) intent.get("payment_id"),
                        existing.getStatus(), existing.getAmount(), existing.getType());
            }
            throw bad("已有一笔等级升级付款正在处理中");
        }

        AccountPaymentTransaction transaction = new AccountPaymentTransaction();
        transaction.setUserId(userId);
        transaction.setSubsiteId(site.id());
        transaction.setSubsiteOwnerIdSnapshot(site.ownerId());
        transaction.setType(AccountPaymentType.LEVEL_UPGRADE);
        transaction.setPaymentMethod(method);
        transaction.setAmount(amount);
        transaction.setStatus(AccountPaymentStatus.PENDING);
        transaction.setCurrentLevelId(current.getId());
        transaction.setTargetLevelId(target.getId());
        transaction.setCurrentLevelRankSnapshot(current.getUpgradeRank());
        transaction.setTargetLevelRankSnapshot(target.getUpgradeRank());
        transaction.setCurrentLevelPriceSnapshot(currentPrice);
        transaction.setTargetLevelPriceSnapshot(targetPrice);
        if (amount.signum() == 0) {
            transaction.setPaymentMethod(OrderPaymentMethod.BALANCE);
            transaction.setExpiresAt(null);
            accountTransactions.saveAndFlush(transaction);
            completion.completePaid(transaction);
            return new PaymentStart(transaction.getId(), null, transaction.getStatus(), amount, transaction.getType());
        }
        if (method == OrderPaymentMethod.ONLINE) {
            if (channelCode == null || channelCode.isBlank()) throw bad("请选择在线支付方式");
            transaction.setChannelCodeSnapshot(channelCode.trim());
            transaction.setExpiresAt(LocalDateTime.now().plusMinutes(30));
            accountTransactions.saveAndFlush(transaction);
            Map<String, Object> intent = payments.prepareAccountPayment(transaction.getId(), channelCode.trim(), device);
            return new PaymentStart(transaction.getId(), (UUID) intent.get("payment_id"),
                    transaction.getStatus(), amount, transaction.getType());
        }
        if (user.getBalance() == null || user.getBalance().compareTo(amount) < 0)
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE, "余额不足，可切换在线支付");
        transaction.setExpiresAt(null);
        accountTransactions.saveAndFlush(transaction);
        completion.completePaid(transaction);
        return new PaymentStart(transaction.getId(), null, transaction.getStatus(), amount, transaction.getType());
    }

    @Transactional(readOnly = true)
    public PageResult<BalanceTransactionResponse> balanceTransactions(UUID userId, int page, int pageSize) {
        User user = requireStoreUser(userId, true);
        if (user.getRole() != UserRole.USER) return PageResult.of(List.of(), 1, 20, 0);
        siteContextFor(user, true);
        int p = Math.max(0, page - 1);
        int s = Math.max(1, Math.min(pageSize, 100));
        Page<BalanceTransaction> result = balanceTransactions.findByUserIdOrderByCreatedAtDescIdDesc(
                userId, PageRequest.of(p, s));
        List<BalanceTransactionResponse> items = result.getContent().stream().map(this::toResponse).toList();
        return PageResult.of(result, items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> balanceTransactionLocation(UUID userId, UUID transactionId, int pageSize) {
        User user = requireStoreUser(userId, true);
        if (user.getRole() != UserRole.USER) throw bad("管理员无需查询个人余额流水");
        siteContextFor(user, true);
        BalanceTransaction transaction = balanceTransactions.findById(transactionId)
                .filter(value -> value.getUserId().equals(userId))
                .orElseThrow(() -> bad("余额流水不存在"));
        int boundedSize = Math.max(1, Math.min(pageSize, 100));
        long newer = balanceTransactions.countNewerThanForUser(userId, transaction.getCreatedAt(), transaction.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transaction", toResponse(transaction));
        result.put("page", Math.toIntExact(newer / boundedSize) + 1);
        return result;
    }

    @Transactional
    public Map<String, Object> transaction(UUID userId, UUID transactionId) {
        User user = requireStoreUser(userId, true);
        if (user.getRole() != UserRole.USER) throw bad("管理员无需查询个人账户交易");
        SiteContext site = siteContextFor(user, true);
        AccountPaymentTransaction transaction = accountTransactions.findByIdForUpdate(transactionId)
                .filter(value -> value.getUserId().equals(userId))
                .orElseThrow(() -> bad("账户交易不存在"));
        if (!java.util.Objects.equals(site.id(), transaction.getSubsiteId()))
            throw bad("账户交易不属于当前站点");
        if (transaction.getStatus() == AccountPaymentStatus.PENDING && transaction.getExpiresAt() != null
                && !transaction.getExpiresAt().isAfter(LocalDateTime.now())) {
            transaction.setStatus(AccountPaymentStatus.EXPIRED);
            accountTransactions.save(transaction);
        }
        return transactionView(transaction);
    }

    @Transactional(readOnly = true)
    public ProductPriceCheck priceCheck(UUID userId, UUID productId, UUID specId) {
        User user = requireStoreUser(userId, false);
        SiteContext site = siteContextFor(user, false);
        if (user.getRole() != UserRole.USER || site.owner()) throw bad("当前账号不能查询升级价格");
        UserLevel current = levels.findById(user.getUserLevelId()).filter(UserLevel::isEnabled)
                .orElseThrow(() -> bad("当前等级不可用"));
        Product product = products.findById(productId)
                .filter(candidate -> candidate.isEnabled() && candidate.getIsDeleted() == 0)
                .orElseThrow(() -> bad("商品不存在或不可查询"));
        if (site.site() != null && subsitePricing.isHidden(site.id(), productId))
            throw bad("商品不存在或不可查询");
        List<ProductSpec> visibleSpecs = productSpecs.findByProductIdAndIsDeletedOrderBySortOrderAsc(productId, 0)
                .stream().filter(ProductSpec::isVisible).toList();
        ProductSpec spec = null;
        if (product.isSpecEnabled()) {
            if (specId == null) throw bad("请选择商品规格");
            spec = visibleSpecs.stream().filter(value -> value.getId().equals(specId)).findFirst()
                    .orElseThrow(() -> bad("商品规格不可查询"));
        } else if (specId != null) throw bad("该商品没有规格");

        ProductSpec selectedSpec = spec;
        BigDecimal currentPrice = actualProductPrice(site.site(), product, selectedSpec, current, userId);
        List<UserLevel> all = levels.findAllOrdered().stream()
                .sorted(Comparator.comparingInt(UserLevel::getUpgradeRank)).toList();
        BigDecimal currentLevelPrice = upgradePolicy.price(site.id(), current);
        List<ProductLevelPrice> results = upgradeOptions(current, site.id(), currentLevelPrice, all).stream()
                .filter(option -> option.publicPriceEnabled())
                .map(option -> {
                    UserLevel target = all.stream().filter(level -> level.getId().equals(option.levelId())).findFirst().orElseThrow();
                    BigDecimal actual = actualProductPrice(site.site(), product, selectedSpec, target, userId);
                    return new ProductLevelPrice(target.getId(), target.getName(), actual, currentPrice.subtract(actual));
                }).toList();
        return new ProductPriceCheck(product.getId(), product.getTitle(), selectedSpec == null ? null : selectedSpec.getId(),
                selectedSpec == null ? null : selectedSpec.getName(), current.getName(), currentPrice, results);
    }

    private BigDecimal actualProductPrice(Subsite site, Product product, ProductSpec spec,
                                          UserLevel level, UUID userId) {
        return site == null ? pricing.resolveForUser(product, spec, level, userId).price()
                : subsitePricing.resolve(site, product, spec, level, userId).unitPrice();
    }

    private List<UpgradeOption> upgradeOptions(UserLevel current, UUID siteId, BigDecimal currentPrice,
                                               List<UserLevel> all) {
        if (currentPrice == null) return List.of();
        List<UpgradeOption> result = new ArrayList<>();
        for (UserLevel target : all) {
            if (!target.isEnabled() || target.getUpgradeRank() <= current.getUpgradeRank()
                    || !upgradePolicy.selfUpgradeEnabled(siteId, target)) continue;
            BigDecimal targetPrice = upgradePolicy.price(siteId, target);
            if (targetPrice == null || targetPrice.compareTo(currentPrice) < 0) continue;
            result.add(new UpgradeOption(target.getId(), target.getName(), target.getUpgradeRank(), targetPrice,
                    targetPrice.subtract(currentPrice), upgradePolicy.publicPriceEnabled(siteId, target)));
        }
        return List.copyOf(result);
    }

    private BigDecimal upgradeAmount(UserLevel current, UserLevel target, BigDecimal currentPrice,
                                     BigDecimal targetPrice, UUID siteId) {
        if (target.getUpgradeRank() <= current.getUpgradeRank()
                || !upgradePolicy.selfUpgradeEnabled(siteId, target)
                || currentPrice == null || targetPrice == null || targetPrice.compareTo(currentPrice) < 0)
            throw bad("目标等级暂不可升级");
        return targetPrice.subtract(currentPrice).setScale(2, RoundingMode.UNNECESSARY);
    }

    private void expireOldUpgradeRequests(UUID userId) {
        List<AccountPaymentTransaction> pending = accountTransactions.findByUserIdAndTypeAndStatusOrderByCreatedAtDesc(
                userId, AccountPaymentType.LEVEL_UPGRADE, AccountPaymentStatus.PENDING);
        LocalDateTime now = LocalDateTime.now();
        for (AccountPaymentTransaction item : pending) {
            if (item.getExpiresAt() != null && !item.getExpiresAt().isAfter(now)) {
                item.setStatus(AccountPaymentStatus.EXPIRED);
                accountTransactions.save(item);
            }
        }
    }

    private Map<String, Object> transactionView(AccountPaymentTransaction transaction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", transaction.getId());
        result.put("type", transaction.getType());
        result.put("status", transaction.getStatus());
        result.put("payment_method", transaction.getPaymentMethod());
        result.put("amount", transaction.getAmount());
        result.put("channel_code", transaction.getChannelCodeSnapshot());
        result.put("current_level_id", transaction.getCurrentLevelId());
        result.put("target_level_id", transaction.getTargetLevelId());
        result.put("current_level_name", levelName(transaction.getCurrentLevelId()));
        result.put("target_level_name", levelName(transaction.getTargetLevelId()));
        result.put("recovery_reason", transaction.getRecoveryReason());
        result.put("created_at", transaction.getCreatedAt());
        result.put("completed_at", transaction.getCompletedAt());
        List<PaymentAttempt> attempts = paymentAttempts.findByAccountPaymentTransactionIdOrderByCreatedAtDesc(
                transaction.getId());
        PaymentAttempt latest = attempts.isEmpty() ? null : attempts.get(0);
        if (latest != null) {
            result.put("payment_id", latest.getId());
            result.put("payment_status", latest.getStatus());
            result.put("creation_status", latest.getCreationStatus());
            result.put("payment_type", latest.getPaymentType());
            result.put("next_action", deserializeAction(latest.getNextAction()));
            result.put("expires_at", latest.getExpiresAt());
            result.put("remaining_seconds", latest.getExpiresAt() == null ? null
                    : Math.max(0, java.time.Duration.between(LocalDateTime.now(), latest.getExpiresAt()).getSeconds()));
        }
        return result;
    }

    private PaymentNextAction deserializeAction(String value) {
        if (value == null || value.isBlank()) return null;
        try { return objectMapper.readValue(value, PaymentNextAction.class); }
        catch (Exception ignored) { return null; }
    }

    private BalanceTransactionResponse toResponse(BalanceTransaction transaction) {
        String operator = transaction.getOperatorId() == null ? null
                : users.findById(transaction.getOperatorId()).map(User::getUsername).orElse(null);
        return new BalanceTransactionResponse(transaction.getId(), transaction.getType(), transaction.getAmount(),
                transaction.getDelta(), transaction.getBalanceBefore(), transaction.getBalanceAfter(),
                transaction.getReason(), transaction.getOperatorId(), operator, transaction.getOrderId(),
                transaction.getCreatedAt());
    }

    private User requireStoreUser(UUID userId, boolean allowStationOwner) {
        User user = userId == null ? null : users.findById(userId).orElse(null);
        if (user == null || user.getIsDeleted() != 0) throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录账号不存在或已被禁用");
        if (user.getRole() != UserRole.USER && user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SUPER_ADMIN)
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号无权访问");
        SiteContext site = siteContextFor(user, allowStationOwner);
        if (site.site() != null && site.owner() && !allowStationOwner)
            throw bad("分站站长不能在自己的分站中自主升级");
        return user;
    }

    private SiteContext siteContextFor(User user, boolean allowStationOwner) {
        Subsite site = com.myshop.context.SiteRequestContext.getSite();
        if (site == null) {
            if (user.getSubsiteId() != null) throw bad("当前账号不属于主站");
            return new SiteContext(null, null, false);
        }
        if (!site.isEnabled()) throw bad("当前分站暂不可用");
        boolean owner = site.getOwnerId().equals(user.getId());
        if (!owner && !site.getId().equals(user.getSubsiteId())) throw bad("用户不属于当前分站");
        if (owner && !allowStationOwner) throw bad("分站站长不能在自己的分站中自主升级");
        return new SiteContext(site, site.getOwnerId(), owner);
    }

    private record SiteContext(Subsite site, UUID ownerId, boolean owner) {
        UUID id() { return site == null ? null : site.getId(); }
    }

    private boolean rechargeEnabled() {
        return siteConfigs.findByConfigKey("recharge_enabled")
                .map(value -> Boolean.parseBoolean(value.getConfigValue())).orElse(false);
    }

    private BigDecimal rechargeLimit(String key, BigDecimal fallback) {
        try {
            return siteConfigs.findByConfigKey(key).map(value -> new BigDecimal(value.getConfigValue()))
                    .filter(value -> value.signum() > 0 && value.scale() <= 2).map(value -> value.setScale(2))
                    .orElse(fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private BigDecimal normalizedAmount(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw bad("金额无效");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException exception) { throw bad("金额最多保留两位小数"); }
    }

    private String levelName(UUID levelId) {
        return levelId == null ? null : levels.findById(levelId).map(UserLevel::getName).orElse(null);
    }

    private int currentRank(UUID levelId) {
        return levelId == null ? 0 : levels.findById(levelId).map(UserLevel::getUpgradeRank).orElse(0);
    }

    private BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
