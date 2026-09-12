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
        user.setAvatarUrl(uploaded.url());

        try {
            User saved = userRepository.save(user);
            cleanupPreviousAvatar(previousAvatarUrl);
            return toProfile(saved);
        } catch (RuntimeException exception) {
            imageUploadService.deleteManagedAvatar(uploaded.url());
            throw exception;
        }
    }

    @Transactional
    public UserProfileResponse deleteAvatar(UUID userId) {
        User user = findUser(userId);
        String previousAvatarUrl = user.getAvatarUrl();
        user.setAvatarUrl(null);
        User saved = userRepository.save(user);
        cleanupPreviousAvatar(previousAvatarUrl);
        return toProfile(saved);
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
        if (!imageUploadService.deleteManagedAvatar(previousAvatarUrl)) {
            if (previousAvatarUrl != null) {
                log.warn("Previous managed avatar cleanup was skipped or failed");
            }
        }
    }
}
