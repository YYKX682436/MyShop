package com.myshop.service;

import com.myshop.constant.AccountPaymentStatus;
import com.myshop.constant.AccountPaymentType;
import com.myshop.constant.BalanceTransactionType;
import com.myshop.constant.ErrorCode;
import com.myshop.constant.OrderPaymentMethod;
import com.myshop.constant.UserRole;
import com.myshop.entity.AccountPaymentTransaction;
import com.myshop.entity.BalanceTransaction;
import com.myshop.entity.Subsite;
import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.repository.AccountPaymentTransactionRepository;
import com.myshop.repository.BalanceTransactionRepository;
import com.myshop.repository.SubsiteRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Applies a confirmed account payment atomically with its ledger and station revenue. */
@Service
@RequiredArgsConstructor
public class AccountPaymentCompletionService {
    private final AccountPaymentTransactionRepository accountTransactions;
    private final UserRepository users;
    private final UserLevelRepository levels;
    private final BalanceTransactionRepository balanceTransactions;
    private final SubsiteRepository subsites;
    private final AccountUpgradePolicy upgradePolicy;

    @Transactional(propagation = Propagation.MANDATORY)
    public AccountPaymentStatus completePaid(AccountPaymentTransaction transaction) {
        if (transaction == null) return AccountPaymentStatus.FAILED;
        if (transaction.getStatus() == AccountPaymentStatus.COMPLETED
                || transaction.getStatus() == AccountPaymentStatus.RECOVERED) return transaction.getStatus();

        List<UUID> lockIds = new java.util.ArrayList<>();
        lockIds.add(transaction.getUserId());
        if (transaction.getSubsiteOwnerIdSnapshot() != null
                && !transaction.getSubsiteOwnerIdSnapshot().equals(transaction.getUserId())) {
            lockIds.add(transaction.getSubsiteOwnerIdSnapshot());
        }
        lockIds.sort(Comparator.naturalOrder());
        Map<UUID, User> lockedUsers = new HashMap<>();
        for (UUID id : lockIds) users.findByIdForUpdate(id).ifPresent(user -> lockedUsers.put(id, user));
        User customer = lockedUsers.get(transaction.getUserId());

        if (transaction.getType() == AccountPaymentType.RECHARGE) {
            if (customer == null) return transaction.getStatus();
            credit(customer, transaction, "在线充值到账");
            transaction.setStatus(AccountPaymentStatus.COMPLETED);
            transaction.setCompletedAt(LocalDateTime.now());
            accountTransactions.save(transaction);
            return transaction.getStatus();
        }

        Map<UUID, UserLevel> levelById = new HashMap<>();
        for (UserLevel level : levels.findAllForUpdate()) levelById.put(level.getId(), level);
        if (transaction.getSubsiteId() != null) {
            // Site owners edit their rules while holding this same row lock.
            subsites.findByIdForUpdate(transaction.getSubsiteId());
        }
        UserLevel source = levelById.get(transaction.getCurrentLevelId());
        UserLevel target = levelById.get(transaction.getTargetLevelId());
        String failure = upgradeFailure(transaction, customer, source, target);
        if (failure != null) {
            if (transaction.getPaymentMethod() == OrderPaymentMethod.BALANCE)
                throw new BusinessException(ErrorCode.BAD_REQUEST, failure);
            if (customer == null) return transaction.getStatus();
            credit(customer, transaction, "等级升级未完成，金额已转入余额");
            transaction.setStatus(AccountPaymentStatus.RECOVERED);
            transaction.setRecoveryReason(failure);
            transaction.setCompletedAt(LocalDateTime.now());
            accountTransactions.save(transaction);
            return transaction.getStatus();
        }

        BigDecimal before = money(customer.getBalance());
        BigDecimal after = before;
        if (transaction.getPaymentMethod() == OrderPaymentMethod.BALANCE) {
            if (before.compareTo(transaction.getAmount()) < 0) {
                throw new IllegalStateException("Balance changed before level upgrade settlement");
            }
            after = before.subtract(transaction.getAmount());
            customer.setBalance(after);
        }
        customer.setUserLevelId(target.getId());
        users.save(customer);

        BalanceTransaction purchase = new BalanceTransaction();
        purchase.setUserId(customer.getId());
        purchase.setType(BalanceTransactionType.LEVEL_UPGRADE);
        purchase.setAmount(transaction.getAmount());
        purchase.setDelta(transaction.getPaymentMethod() == OrderPaymentMethod.BALANCE
                ? transaction.getAmount().negate() : BigDecimal.ZERO.setScale(2));
        purchase.setBalanceBefore(before);
        purchase.setBalanceAfter(after);
        purchase.setReason("等级升级：" + source.getName() + " → " + target.getName()
                + (transaction.getAmount().signum() == 0 ? "（免费升级）"
                : transaction.getPaymentMethod() == OrderPaymentMethod.BALANCE ? "（余额支付）" : "（在线支付）"));
        purchase.setOperatorId(customer.getId());
        purchase.setAccountPaymentTransactionId(transaction.getId());
        balanceTransactions.save(purchase);

        if (transaction.getSubsiteId() != null) {
            User owner = lockedUsers.get(transaction.getSubsiteOwnerIdSnapshot());
            if (owner == null) throw new IllegalStateException("分站站长账户不存在，无法结算升级收益");
            if (transaction.getAmount().signum() > 0)
                creditSubsiteOwner(owner, transaction, customer, source, target);
        }

        transaction.setStatus(AccountPaymentStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        accountTransactions.save(transaction);
        return transaction.getStatus();
    }

    private String upgradeFailure(AccountPaymentTransaction transaction, User customer,
                                  UserLevel source, UserLevel target) {
        if (customer == null) return "用户账户不存在，付款已保留待处理";
        if (customer.getRole() != UserRole.USER || customer.getIsDeleted() != 0)
            return "用户状态已变化，付款已转入余额";
        if (!java.util.Objects.equals(customer.getUserLevelId(), transaction.getCurrentLevelId()))
            return "付款期间当前等级已变化，付款已转入余额";
        if (source == null || target == null || !source.isEnabled() || !target.isEnabled())
            return "目标等级已停用，付款已转入余额";
        if (source.getUpgradeRank() != transaction.getCurrentLevelRankSnapshot()
                || target.getUpgradeRank() != transaction.getTargetLevelRankSnapshot()
                || target.getUpgradeRank() <= source.getUpgradeRank())
            return "等级顺序已变化，付款已转入余额";
        if (transaction.getSubsiteId() == null) {
            if (!upgradePolicy.selfUpgradeEnabled(null, target)) return "目标等级已关闭自主升级，付款已转入余额";
        } else {
            Subsite site = subsites.findById(transaction.getSubsiteId()).orElse(null);
            if (site == null || !java.util.Objects.equals(customer.getSubsiteId(), transaction.getSubsiteId())
                    || !java.util.Objects.equals(site.getOwnerId(), transaction.getSubsiteOwnerIdSnapshot()))
                return "分站归属已变化，付款已转入余额";
            if (!upgradePolicy.selfUpgradeEnabled(transaction.getSubsiteId(), target))
                return "分站已关闭目标等级自主升级，付款已转入余额";
        }
        BigDecimal currentPrice = upgradePolicy.price(transaction.getSubsiteId(), source);
        BigDecimal targetPrice = upgradePolicy.price(transaction.getSubsiteId(), target);
        if (currentPrice == null || targetPrice == null
                || currentPrice.compareTo(transaction.getCurrentLevelPriceSnapshot()) != 0
                || targetPrice.compareTo(transaction.getTargetLevelPriceSnapshot()) != 0
                || targetPrice.subtract(currentPrice).compareTo(transaction.getAmount()) != 0)
            return "等级价格已变化，付款已转入余额";
        return null;
    }

    private void credit(User user, AccountPaymentTransaction transaction, String reason) {
        BigDecimal before = money(user.getBalance());
        BigDecimal after = before.add(transaction.getAmount());
        user.setBalance(after);
        users.save(user);
        BalanceTransaction ledger = new BalanceTransaction();
        ledger.setUserId(user.getId());
        ledger.setType(BalanceTransactionType.RECHARGE);
        ledger.setAmount(transaction.getAmount());
        ledger.setDelta(transaction.getAmount());
        ledger.setBalanceBefore(before);
        ledger.setBalanceAfter(after);
        ledger.setReason(reason);
        ledger.setOperatorId(user.getId());
        ledger.setAccountPaymentTransactionId(transaction.getId());
        balanceTransactions.save(ledger);
    }

    private void creditSubsiteOwner(User owner, AccountPaymentTransaction transaction,
                                   User customer, UserLevel source, UserLevel target) {
        if (owner.getId().equals(customer.getId())) return;
        BigDecimal before = money(owner.getBalance());
        BigDecimal after = before.add(transaction.getAmount());
        owner.setBalance(after);
        users.save(owner);
        BalanceTransaction revenue = new BalanceTransaction();
        revenue.setUserId(owner.getId());
        revenue.setType(BalanceTransactionType.SUBSITE_UPGRADE_PROFIT);
        revenue.setAmount(transaction.getAmount());
        revenue.setDelta(transaction.getAmount());
        revenue.setBalanceBefore(before);
        revenue.setBalanceAfter(after);
        revenue.setReason("分站等级升级收益：" + source.getName() + " → " + target.getName());
        revenue.setOperatorId(customer.getId());
        revenue.setAccountPaymentTransactionId(transaction.getId());
        balanceTransactions.save(revenue);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value;
    }
}
