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

/** Account-scoped avatar lifecycle. The caller supplies only RequestContext's user id. */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAvatarService {

    private final UserRepository userRepository;
    private final UserLevelRepository userLevelRepository;
    private final ImageUploadService imageUploadService;

    @Transactional
    public UserProfileResponse uploadAvatar(UUID userId, MultipartFile file) {
        User user = findUser(userId);
        String previousAvatarUrl = user.getAvatarUrl();
        ImageUploadService.UploadedImage uploaded = imageUploadService.uploadAvatar(file);
        boolean cleanupRegistered = false;

        try {
            requireActiveTransactionSynchronization();
            user.setAvatarUrl(uploaded.url());
            User saved = userRepository.save(user);
            registerFileCleanup(previousAvatarUrl, uploaded.url());
            cleanupRegistered = true;
            return toProfile(saved);
        } catch (RuntimeException exception) {
            // Once the synchronization is registered, afterCompletion owns the
            // rollback cleanup. Before that point the new file must be removed
            // immediately, while the previous file remains untouched.
            if (!cleanupRegistered) {
                cleanupNewAvatar(uploaded.url());
            }
            throw exception;
        }
    }

    private void requireActiveTransactionSynchronization() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Avatar persistence requires an active transaction");
        }
    }

    private void registerFileCleanup(String previousAvatarUrl, String uploadedAvatarUrl) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // The database now points at the new file. A cleanup failure
                // must be observable but must not turn a committed update into
                // a false failure or attempt a database rollback.
                cleanupPreviousAvatar(previousAvatarUrl);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    // The save may have failed during flush/commit. The old
                    // URL is still authoritative, so only remove the new file.
                    cleanupNewAvatar(uploadedAvatarUrl);
                }
            }
        });
    }

    private User findUser(UUID userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Not logged in or session expired");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    private UserProfileResponse toProfile(User user) {
        UserLevel level = user.getUserLevelId() == null ? null
                : userLevelRepository.findById(user.getUserLevelId()).orElse(null);
        return UserProfileResponse.from(user, level);
    }

    private void cleanupPreviousAvatar(String previousAvatarUrl) {
        if (previousAvatarUrl == null) {
            return;
        }
        try {
            if (!imageUploadService.deleteManagedAvatar(previousAvatarUrl)) {
                log.warn("Previous managed avatar cleanup was skipped or failed");
            }
        } catch (RuntimeException exception) {
            log.warn("Previous managed avatar cleanup failed");
        }
    }

    private void cleanupNewAvatar(String uploadedAvatarUrl) {
        try {
            if (!imageUploadService.deleteManagedAvatar(uploadedAvatarUrl)) {
                log.warn("Newly uploaded avatar cleanup was skipped or failed");
            }
        } catch (RuntimeException exception) {
            log.warn("Newly uploaded avatar cleanup failed");
        }
    }
}
