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
        UserLevel level = new UserLevel();
        apply(level, request, false);
        if (Boolean.TRUE.equals(request == null ? null : request.isDefault())) {
            if (!level.isEnabled()) {
                throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "默认等级必须启用");
            }
            setAllDefaultsFalse();
            level.setDefault(true);
        }
        UserLevel saved = userLevelRepository.saveAndFlush(level);
        log("user_level.create", saved.getId(), "创建用户等级：" + saved.getName());
        return toResponse(saved, 0);
    }

    @Override
    @Transactional
    public UserLevelResponse update(UUID id, UserLevelUpsertRequest request) {
        UserLevel level = lockLevel(id);
        UserLevel duplicate = findNamed(request == null ? null : request.name());
        if (duplicate != null && !duplicate.getId().equals(id)) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "用户等级名称已存在");
        }
        boolean wasDefault = level.isDefault();
        apply(level, request, true);
        if (wasDefault && !level.isEnabled()) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "默认等级不能停用");
        }
        if (wasDefault && !level.isDefault()) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "请先设置其他等级为默认等级");
        }
        if (level.isDefault()) {
            level.setEnabled(true);
            clearOtherDefaults(id);
        }
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
        UserLevel level = lockLevel(id);
        if (!level.isEnabled()) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "停用等级不能设为默认");
        }
        setAllDefaultsFalse();
        level.setDefault(true);
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
        if (request == null || request.targetLevelId() == null
                || sourceLevelId.equals(request.targetLevelId())) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "迁移目标等级无效");
        }
        UserLevel source = lockLevel(sourceLevelId);
        UserLevel target = lockLevel(request.targetLevelId());
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
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        if (user.getRole() != UserRole.USER) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "管理员账号不能分配普通用户等级");
        }
        UserLevel target = lockLevel(request.userLevelId());
        if (!target.isEnabled()) {
            throw new BusinessException(ErrorCode.USER_LEVEL_INVALID, "停用等级不能分配");
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

    private void apply(UserLevel level, UserLevelUpsertRequest request, boolean update) {
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
    }

    private UserLevel lockLevel(UUID id) {
        return userLevelRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_LEVEL_NOT_FOUND, "用户等级不存在"));
    }

    private UserLevel findNamed(String name) {
        return name == null ? null : userLevelRepository.findByNameIgnoreCase(name.trim()).orElse(null);
    }

    private void setAllDefaultsFalse() {
        userLevelRepository.findAllOrdered().forEach(level -> {
            if (level.isDefault()) {
                level.setDefault(false);
                userLevelRepository.save(level);
            }
        });
    }

    private void clearOtherDefaults(UUID id) {
        userLevelRepository.findAllOrdered().forEach(level -> {
            if (!level.getId().equals(id) && level.isDefault()) {
                level.setDefault(false);
                userLevelRepository.save(level);
            }
        });
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
