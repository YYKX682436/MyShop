package com.myshop.service.impl;

import com.myshop.constant.ErrorCode;
import com.myshop.constant.PricingType;
import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.request.UserLevelAssignmentRequest;
import com.myshop.model.request.UserLevelMigrationRequest;
import com.myshop.model.request.UserLevelStatusRequest;
import com.myshop.model.request.UserLevelUpsertRequest;
import com.myshop.model.response.UserLevelResponse;
import com.myshop.repository.ProductLevelPriceOverrideRepository;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import com.myshop.service.OperationLogService;
import com.myshop.service.UserLevelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserLevelServiceImpl implements UserLevelService {

    private final UserLevelRepository userLevelRepository;
    private final UserRepository userRepository;
    private final ProductLevelPriceOverrideRepository overrideRepository;
    private final OperationLogService operationLogService;

    @Override
    @Transactional(readOnly = true)
    public List<UserLevelResponse> list() {
        return userLevelRepository.findAllOrdered().stream()
                .map(level -> toResponse(level, userRepository.countByUserLevelId(level.getId())))
                .toList();
    }

    @Override
    @Transactional
    public UserLevelResponse create(UserLevelUpsertRequest request) {
        boolean defaultRequested = request != null && Boolean.TRUE.equals(request.isDefault());
        List<UserLevel> lockedLevels = userLevelRepository.findAllForUpdate();
        UserLevel level = new UserLevel();
        apply(level, request, false, lockedLevels);
        if (defaultRequested) {
            if (!level.isEnabled()) {
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "默认等级必须启用");
            }
            prepareDefault(level, lockedLevels);
            clearDefaults(lockedLevels);
            level.setDefault(true);
        }
        List<UserLevel> candidate = new java.util.ArrayList<>(lockedLevels);
        candidate.add(level);
        validateUpgradeConfiguration(candidate);
        UserLevel saved = userLevelRepository.saveAndFlush(level);
        log("user_level.create", saved.getId(), "创建用户等级：" + saved.getName());
        return toResponse(saved, 0);
    }

    @Override
    @Transactional
    public UserLevelResponse update(UUID id, UserLevelUpsertRequest request) {
        boolean defaultRequested = request != null && Boolean.TRUE.equals(request.isDefault());
        List<UserLevel> lockedLevels = userLevelRepository.findAllForUpdate();
        UserLevel level = requireLockedLevel(lockedLevels, id);
        UserLevel duplicate = findNamed(request == null ? null : request.name());
        if (duplicate != null && !duplicate.getId().equals(id)) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "用户等级名称已存在");
        }
        boolean wasDefault = level.isDefault();
        apply(level, request, true, lockedLevels);
        if (wasDefault && !level.isEnabled()) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "默认等级不能停用");
        }
        if (wasDefault && !level.isDefault()) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "请先设置其他等级为默认等级");
        }
        if (level.isDefault()) {
            level.setEnabled(true);
            if (defaultRequested) {
                prepareDefault(level, lockedLevels);
                clearDefaults(lockedLevels);
                level.setDefault(true);
            }
        }
        validateUpgradeConfiguration(lockedLevels);
        UserLevel saved = userLevelRepository.saveAndFlush(level);
        log("user_level.update", id, "更新用户等级：" + saved.getName());
        return toResponse(saved, userRepository.countByUserLevelId(id));
    }

    @Override
    @Transactional
    public UserLevelResponse setStatus(UUID id, UserLevelStatusRequest request) {
        UserLevel level = lockLevel(id);
        boolean enabled = request != null && Boolean.TRUE.equals(request.enabled());
        if (!enabled) {
            if (level.isDefault()) {
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "默认等级不能停用");
            }
            if (userRepository.countByUserLevelId(id) > 0) {
                throw new BusinessException(ErrorCode.USER_LEVEL_HAS_USERS, "该等级仍有用户，请先迁移用户");
            }
        }
        level.setEnabled(enabled);
        UserLevel saved = userLevelRepository.saveAndFlush(level);
        log("user_level.status", id, "用户等级状态改为：" + (enabled ? "启用" : "停用"));
        return toResponse(saved, userRepository.countByUserLevelId(id));
    }

    @Override
    @Transactional
    public UserLevelResponse setDefault(UUID id) {
        List<UserLevel> lockedLevels = userLevelRepository.findAllForUpdate();
        UserLevel level = requireLockedLevel(lockedLevels, id);
        if (!level.isEnabled()) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "停用等级不能设为默认");
        }
        prepareDefault(level, lockedLevels);
        clearDefaults(lockedLevels);
        level.setDefault(true);
        level.setEnabled(true);
        level.setUpgradePrice(BigDecimal.ZERO.setScale(2));
        level.setSelfUpgradeEnabled(false);
        level.setPublicPriceEnabled(false);
        validateUpgradeConfiguration(lockedLevels);
        UserLevel saved = userLevelRepository.saveAndFlush(level);
        log("user_level.default", id, "设置默认用户等级：" + saved.getName());
        return toResponse(saved, userRepository.countByUserLevelId(id));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        UserLevel level = lockLevel(id);
        long users = userRepository.countByUserLevelId(id);
        if (level.isDefault()) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "默认等级不能删除");
        }
        if (users > 0) {
            throw new BusinessException(ErrorCode.USER_LEVEL_HAS_USERS, "该等级仍有用户，请先迁移用户");
        }
        overrideRepository.deleteByUserLevelId(id);
        userLevelRepository.delete(level);
        log("user_level.delete", id, "删除用户等级：" + level.getName());
    }

    @Override
    @Transactional
    public int migrate(UUID sourceLevelId, UserLevelMigrationRequest request) {
        if (sourceLevelId == null || request == null || request.targetLevelId() == null
                || sourceLevelId.equals(request.targetLevelId())) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "迁移目标等级无效");
        }
        // Level locks always precede user locks. Use unsigned/string UUID
        // order to match findAllForUpdate, including reverse migrations.
        List<UserLevel> lockedLevels = List.of(sourceLevelId, request.targetLevelId()).stream()
                .sorted(Comparator.comparing(UUID::toString)).map(this::lockLevel).toList();
        UserLevel source = requireLockedLevel(lockedLevels, sourceLevelId);
        UserLevel target = requireLockedLevel(lockedLevels, request.targetLevelId());
        if (!target.isEnabled()) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "不能迁移到停用等级");
        }
        int moved = userRepository.moveUsersToLevel(source.getId(), target.getId());
        log("user_level.migrate", source.getId(), "整级迁移：" + source.getName()
                + " -> " + target.getName() + "，用户数：" + moved);
        return moved;
    }

    @Override
    @Transactional
    public UserLevelResponse assignUser(UUID userId, UserLevelAssignmentRequest request) {
        if (request == null || request.userLevelId() == null) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "用户等级不能为空");
        }
        UserLevel target = lockLevel(request.userLevelId());
        if (!target.isEnabled()) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "停用等级不能分配");
        }
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        if (user.getRole() != UserRole.USER) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "管理员账号不能分配普通用户等级");
        }
        UUID previous = user.getUserLevelId();
        user.setUserLevelId(target.getId());
        userRepository.saveAndFlush(user);
        if (!target.getId().equals(previous)) {
            logUser("user.change_level", userId, "用户换级：" + String.valueOf(previous)
                    + " -> " + target.getName());
        }
        return toResponse(target, userRepository.countByUserLevelId(target.getId()));
    }

    private void apply(UserLevel level, UserLevelUpsertRequest request, boolean update,
                       List<UserLevel> lockedLevels) {
        if (request == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "用户等级参数不能为空");
        String name = request.name() == null ? null : request.name().trim();
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "用户等级名称不能为空且不能超过 100 个字符");
        }
        UserLevel duplicate = findNamed(name);
        if (duplicate != null && (!update || !duplicate.getId().equals(level.getId()))) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "用户等级名称已存在");
        }
        PricingType type = request.pricingType();
        if (type == null) throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "定价类型不能为空");
        BigDecimal markup = request.markupRate();
        if (type == PricingType.RETAIL) {
            if (markup != null) throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "RETAIL 等级不能填写加价率");
        } else {
            if (markup == null || markup.signum() < 0) {
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "COST_MARKUP 等级必须填写非负加价率");
            }
            try {
                markup = markup.setScale(4, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException exception) {
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "加价率最多支持 4 位小数");
            }
        }
        level.setName(name);
        level.setPricingType(type);
        level.setMarkupRate(markup);
        if (request.enabled() != null) level.setEnabled(request.enabled());
        if (request.isDefault() != null) level.setDefault(request.isDefault());
        if (request.sortOrder() != null) level.setSortOrder(request.sortOrder());
        level.setRemark(request.remark() == null ? null : request.remark().trim());
        boolean upgradeSettingsSupplied = request.upgradeRank() != null
                || request.selfUpgradeEnabled() != null || request.publicPriceEnabled() != null;
        if (request.upgradeRank() != null) level.setUpgradeRank(request.upgradeRank());
        else if (!update) level.setUpgradeRank(lockedLevels.stream().mapToInt(UserLevel::getUpgradeRank)
                .max().orElse(-100) + 100);
        if (request.selfUpgradeEnabled() != null) level.setSelfUpgradeEnabled(request.selfUpgradeEnabled());
        else if (!update) level.setSelfUpgradeEnabled(false);
        if (request.publicPriceEnabled() != null) level.setPublicPriceEnabled(request.publicPriceEnabled());
        else if (!update) level.setPublicPriceEnabled(false);
        if (!update || upgradeSettingsSupplied) level.setUpgradePrice(normalizeUpgradePrice(request.upgradePrice()));
        if (level.isDefault()) {
            if (request.upgradePrice() != null && request.upgradePrice().signum() != 0)
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "默认等级价格固定为 0");
            level.setUpgradePrice(BigDecimal.ZERO.setScale(2));
            level.setSelfUpgradeEnabled(false);
            level.setPublicPriceEnabled(false);
        }
    }

    private BigDecimal normalizeUpgradePrice(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() < 0 || value.compareTo(new BigDecimal("9999999999.99")) > 0)
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "等级开通价格无效");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "等级开通价格最多保留两位小数");
        }
    }

    private void prepareDefault(UserLevel target, List<UserLevel> all) {
        int minimum = all.stream().filter(level -> !level.getId().equals(target.getId()))
                .mapToInt(UserLevel::getUpgradeRank).min().orElse(target.getUpgradeRank());
        if (minimum < Integer.MIN_VALUE + 100)
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "无法为默认等级分配更低的等级顺序");
        target.setUpgradeRank(minimum - 100);
        target.setUpgradePrice(BigDecimal.ZERO.setScale(2));
        target.setSelfUpgradeEnabled(false);
        target.setPublicPriceEnabled(false);
        all.stream().filter(UserLevel::isDefault).filter(level -> !level.getId().equals(target.getId()))
                .forEach(level -> level.setUpgradePrice(null));
    }

    private void validateUpgradeConfiguration(List<UserLevel> all) {
        List<UserLevel> ordered = all.stream().sorted(Comparator.comparingInt(UserLevel::getUpgradeRank)).toList();
        java.util.Set<Integer> ranks = new java.util.HashSet<>();
        BigDecimal lastConfiguredPrice = null;
        for (int index = 0; index < ordered.size(); index++) {
            UserLevel level = ordered.get(index);
            if (!ranks.add(level.getUpgradeRank()))
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "等级高低顺序不能重复");
            if (level.isDefault() && index != 0)
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "默认等级必须排在最低等级");
            if (level.isDefault() && (level.getUpgradePrice() == null
                    || level.getUpgradePrice().compareTo(BigDecimal.ZERO) != 0
                    || level.isSelfUpgradeEnabled() || level.isPublicPriceEnabled()))
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "默认等级价格必须为 0，且不能开放自主升级或价格查询");
            if (level.isSelfUpgradeEnabled() && level.getUpgradePrice() == null)
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "开放自主升级前必须配置等级价格");
            if (level.isPublicPriceEnabled() && (!level.isSelfUpgradeEnabled() || level.getUpgradePrice() == null))
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "公开价格仅适用于已开放自主升级的等级");
            if (level.getUpgradePrice() != null) {
                if (lastConfiguredPrice != null && level.getUpgradePrice().compareTo(lastConfiguredPrice) < 0)
                    throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "较高等级价格不能低于较低等级价格");
                lastConfiguredPrice = level.getUpgradePrice();
            }
        }
    }

    private UserLevel lockLevel(UUID id) {
        return userLevelRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_LEVEL_NOT_FOUND, "用户等级不存在"));
    }

    private UserLevel requireLockedLevel(List<UserLevel> levels, UUID id) {
        return levels.stream().filter(level -> level.getId().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_LEVEL_NOT_FOUND, "用户等级不存在"));
    }

    private UserLevel findNamed(String name) {
        return name == null ? null : userLevelRepository.findByNameIgnoreCase(name.trim()).orElse(null);
    }

    private void clearDefaults(List<UserLevel> lockedLevels) {
        lockedLevels.forEach(level -> {
            if (level.isDefault()) {
                level.setDefault(false);
                userLevelRepository.save(level);
            }
        });
        // The partial unique index is immediate. Clear the old flag before
        // Hibernate can INSERT/UPDATE the new default, all in this transaction.
        userLevelRepository.flush();
    }

    private UserLevelResponse toResponse(UserLevel level, long userCount) {
        return UserLevelResponse.from(level, userCount);
    }

    private void log(String action, UUID targetId, String detail) {
        operationLogService.logCurrentRequest(action, "USER_LEVEL", targetId.toString(), detail);
    }

    private void logUser(String action, UUID targetId, String detail) {
        operationLogService.logCurrentRequest(action, "USER", targetId.toString(), detail);
    }
}
