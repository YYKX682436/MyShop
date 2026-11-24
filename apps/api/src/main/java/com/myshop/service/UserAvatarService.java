package com.myshop.service;

import com.myshop.constant.ErrorCode;
import com.myshop.entity.User;
import com.myshop.entity.UserLevel;
import com.myshop.exception.BusinessException;
import com.myshop.model.response.UserProfileResponse;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.Objects;
import java.util.Map;
import java.util.Set;

/** Avatar assets are owned by one account and retired only after the account change commits. */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAvatarService {
    private final UserRepository userRepository;
    private final UserLevelRepository userLevelRepository;
    private final ImageUploadService imageUploadService;
    private final MediaAssetRegistry assets;
    private final MediaMaintenanceService maintenance;

    @Transactional
    public UserProfileResponse uploadAvatar(UUID userId, MultipartFile file) {
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "Not logged in or session expired");
        ImageUploadService.UploadedImage uploaded = imageUploadService.uploadAvatar(file, userId);
        var references = assets.lockAvatar(uploaded.url(), userId);
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        String previous = user.getAvatarUrl();
        user.setAvatarUrl(uploaded.url());
        User saved = userRepository.save(user);
        assets.replaceReferences("AVATAR", userId, references, "avatar_url");
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Avatar persistence requires an active transaction");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { maintenance.retireUnreferencedAvatar(previous, userId); }
                catch (RuntimeException failure) { log.warn("Previous avatar cleanup needs retry: {}", failure.getMessage()); }
            }
        });
        UserLevel level = saved.getUserLevelId() == null ? null : userLevelRepository.findById(saved.getUserLevelId()).orElse(null);
        UserProfileResponse result = UserProfileResponse.from(saved, level);
        result.setImageProcessingReason(uploaded.reason());
        return result;
    }

    /** Switches one existing account only if its current URL still matches the optimization snapshot. */
    @Transactional
    public boolean applyOptimizedAvatar(UUID userId, UUID sourceAssetId, String expectedPreviousUrl,
                                        ImageUploadService.UploadedImage uploaded) {
        if (userId == null || uploaded == null) return false;
        var lockedAssets = assets.lockAvatarOptimization(sourceAssetId, expectedPreviousUrl, uploaded, userId);
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || !lockedAssets.sourceUsable() || !Objects.equals(user.getAvatarUrl(), expectedPreviousUrl)) return false;
        String previous = user.getAvatarUrl();
        user.setAvatarUrl(uploaded.url());
        userRepository.save(user);
        var references = new MediaAssetRegistry.LockedReferences(Map.of("avatar_url", Set.of(uploaded.assetId())));
        assets.replaceReferences("AVATAR", userId, references, "avatar_url");
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Avatar persistence requires an active transaction");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { maintenance.retireUnreferencedAvatar(previous, userId); }
                catch (RuntimeException failure) { log.warn("Previous avatar cleanup needs retry: {}", failure.getMessage()); }
            }
        });
        return true;
    }
}
