package com.myshop.service;

import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import com.myshop.model.response.UserProfileResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void replacingAvatarDefersPreviousFileCleanupUntilAfterCommit() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        String previous = "/api/uploads/avatars/avatar-11111111-1111-1111-1111-111111111111.png";
        user.setAvatarUrl(previous);
        MockMultipartFile file = validPng();
        ImageUploadService.UploadedImage uploaded = uploaded("avatar-22222222-2222-2222-2222-222222222222.png");
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        when(imageUploadService.uploadAvatar(file)).thenReturn(uploaded);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(imageUploadService.deleteManagedAvatar(any())).thenReturn(true);

        TransactionSynchronizationManager.initSynchronization();
        UserProfileResponse result = service().uploadAvatar(userId, file);

        assertEquals(uploaded.url(), result.getAvatarUrl());
        verify(userRepository).save(user);
        verify(imageUploadService, never()).deleteManagedAvatar(previous);

        TransactionSynchronization synchronization = synchronization();
        synchronization.afterCommit();

        verify(imageUploadService).deleteManagedAvatar(previous);
        verify(imageUploadService, never()).deleteManagedAvatar(uploaded.url());
    }

    @Test
    void rollbackCleanupRemovesOnlyTheNewFileAndLeavesPreviousFileUntouched() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        String previous = "/api/uploads/avatars/avatar-33333333-3333-3333-3333-333333333333.jpg";
        user.setAvatarUrl(previous);
        MockMultipartFile file = validPng();
        ImageUploadService.UploadedImage uploaded = uploaded("avatar-44444444-4444-4444-4444-444444444444.png");
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        when(imageUploadService.uploadAvatar(file)).thenReturn(uploaded);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(imageUploadService.deleteManagedAvatar(any())).thenReturn(true);

        TransactionSynchronizationManager.initSynchronization();
        service().uploadAvatar(userId, file);

        synchronization().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(imageUploadService).deleteManagedAvatar(uploaded.url());
        verify(imageUploadService, never()).deleteManagedAvatar(previous);
    }

    @Test
    void saveFailureCleansNewFileWithoutTouchingPreviousFile() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        String previous = "/api/uploads/avatars/avatar-55555555-5555-5555-5555-555555555555.webp";
        user.setAvatarUrl(previous);
        MockMultipartFile file = validPng();
        ImageUploadService.UploadedImage uploaded = uploaded("avatar-66666666-6666-6666-6666-666666666666.png");
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        when(imageUploadService.uploadAvatar(file)).thenReturn(uploaded);
        when(userRepository.save(any(User.class))).thenThrow(new IllegalStateException("save failed"));
        when(imageUploadService.deleteManagedAvatar(uploaded.url())).thenReturn(true);

        TransactionSynchronizationManager.initSynchronization();
        assertThrows(IllegalStateException.class, () -> service().uploadAvatar(userId, file));

        verify(imageUploadService).deleteManagedAvatar(uploaded.url());
        verify(imageUploadService, never()).deleteManagedAvatar(previous);
    }

    @Test
    void previousFileCleanupFailureAfterCommitDoesNotTurnCommittedUpdateIntoFailure() {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        String previous = "/api/uploads/avatars/avatar-77777777-7777-7777-7777-777777777777.jpg";
        user.setAvatarUrl(previous);
        MockMultipartFile file = validPng();
        ImageUploadService.UploadedImage uploaded = uploaded("avatar-88888888-8888-8888-8888-888888888888.png");
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
        when(imageUploadService.uploadAvatar(file)).thenReturn(uploaded);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(imageUploadService.deleteManagedAvatar(previous)).thenThrow(new IllegalStateException("cleanup failed"));

        TransactionSynchronizationManager.initSynchronization();
        UserProfileResponse result = service().uploadAvatar(userId, file);

        assertDoesNotThrow(() -> synchronization().afterCommit());
        assertEquals(uploaded.url(), result.getAvatarUrl());
        verify(imageUploadService).deleteManagedAvatar(previous);
        verify(imageUploadService, never()).deleteManagedAvatar(uploaded.url());
    }

    private UserAvatarService service() {
        return new UserAvatarService(userRepository, userLevelRepository, imageUploadService);
    }

    private TransactionSynchronization synchronization() {
        return TransactionSynchronizationManager.getSynchronizations().get(0);
    }

    private static ImageUploadService.UploadedImage uploaded(String filename) {
        return new ImageUploadService.UploadedImage(
                "/api/uploads/avatars/" + filename,
                "avatars/" + filename);
    }

    private static MockMultipartFile validPng() {
        return new MockMultipartFile(
                "file", "new.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
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
