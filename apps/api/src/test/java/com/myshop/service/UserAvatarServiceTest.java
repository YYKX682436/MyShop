package com.myshop.service;

import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import com.myshop.model.response.UserProfileResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAvatarServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserLevelRepository userLevelRepository;
    @Mock
    private ImageUploadService imageUploadService;

    @Test
    void replacingAvatarUpdatesOnlyTheCurrentUserAndCleansThePreviousManagedFile() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        String previous = "/api/uploads/avatars/avatar-11111111-1111-1111-1111-111111111111.png";
        user.setAvatarUrl(previous);
        MockMultipartFile file = new MockMultipartFile("file", "new.png", "image/png", new byte[]{1});
        ImageUploadService.UploadedImage uploaded = new ImageUploadService.UploadedImage(
                "/api/uploads/avatars/avatar-22222222-2222-2222-2222-222222222222.png",
                "avatars/avatar-22222222-2222-2222-2222-222222222222.png");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(imageUploadService.uploadAvatar(file)).thenReturn(uploaded);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(imageUploadService.deleteManagedAvatar(previous)).thenReturn(true);

        UserProfileResponse result = new UserAvatarService(
                userRepository, userLevelRepository, imageUploadService).uploadAvatar(userId, file);

        assertEquals(uploaded.url(), result.getAvatarUrl());
        assertEquals(uploaded.url(), user.getAvatarUrl());
        verify(userRepository).save(user);
        verify(imageUploadService).deleteManagedAvatar(previous);
    }

    @Test
    void deletingAvatarClearsTheUrlAndCleansThePreviousManagedFile() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        String previous = "/api/uploads/avatars/avatar-33333333-3333-3333-3333-333333333333.jpg";
        user.setAvatarUrl(previous);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(imageUploadService.deleteManagedAvatar(previous)).thenReturn(true);

        UserProfileResponse result = new UserAvatarService(
                userRepository, userLevelRepository, imageUploadService).deleteAvatar(userId);

        assertNull(result.getAvatarUrl());
        assertNull(user.getAvatarUrl());
        verify(userRepository).save(user);
        verify(imageUploadService).deleteManagedAvatar(previous);
    }

    private static User user(UUID id) {
        User user = new User();
        user.setId(id);
        user.setUsername("buyer");
        user.setEmail("buyer@example.com");
        user.setRole(UserRole.USER);
        user.setUserNo(10000L);
        return user;
    }
}
