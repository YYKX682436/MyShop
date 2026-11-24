package com.myshop.service;

import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.repository.UserLevelRepository;
import com.myshop.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAvatarServiceTest {
    @Mock UserRepository users;
    @Mock UserLevelRepository levels;
    @Mock ImageUploadService images;
    @Mock MediaAssetRegistry assets;
    @Mock MediaMaintenanceService maintenance;

    @AfterEach void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test void accountReferenceChangesInTransactionAndOldAssetRetiresOnlyAfterCommit() {
        UUID id = UUID.randomUUID(); User user = user(id); String previous = user.getAvatarUrl();
        var file = new MockMultipartFile("file", "new.png", "image/png", new byte[]{1});
        var uploaded = new ImageUploadService.UploadedImage("/api/uploads/avatars/new.png", "avatars/new.png");
        var references = new MediaAssetRegistry.LockedReferences(Map.of());
        when(images.uploadAvatar(file, id)).thenReturn(uploaded);
        when(assets.lockAvatar(uploaded.url(), id)).thenReturn(references);
        when(users.findByIdForUpdate(id)).thenReturn(java.util.Optional.of(user));
        when(users.save(user)).thenReturn(user);
        TransactionSynchronizationManager.initSynchronization();
        var response = service().uploadAvatar(id, file);
        assertEquals(uploaded.url(), response.getAvatarUrl());
        verify(assets).replaceReferences("AVATAR", id, references, "avatar_url");
        verifyNoInteractions(maintenance);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
        verify(maintenance).retireUnreferencedAvatar(previous, id);
    }

    @Test void rollbackNeverRetiresThePreviousAvatar() {
        UUID id = UUID.randomUUID(); User user = user(id);
        var file = new MockMultipartFile("file", "new.png", "image/png", new byte[]{1});
        var uploaded = new ImageUploadService.UploadedImage("/api/uploads/avatars/new.png", "avatars/new.png");
        when(images.uploadAvatar(file, id)).thenReturn(uploaded);
        when(assets.lockAvatar(uploaded.url(), id)).thenReturn(new MediaAssetRegistry.LockedReferences(Map.of()));
        when(users.findByIdForUpdate(id)).thenReturn(java.util.Optional.of(user));
        when(users.save(user)).thenReturn(user);
        TransactionSynchronizationManager.initSynchronization();
        service().uploadAvatar(id, file);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(maintenance);
    }

    @Test void cleanupErrorAfterCommitDoesNotInvalidateCommittedAccountChange() {
        UUID id = UUID.randomUUID(); User user = user(id); String previous = user.getAvatarUrl();
        var file = new MockMultipartFile("file", "new.png", "image/png", new byte[]{1});
        var uploaded = new ImageUploadService.UploadedImage("/api/uploads/avatars/new.png", "avatars/new.png");
        when(images.uploadAvatar(file, id)).thenReturn(uploaded);
        when(assets.lockAvatar(uploaded.url(), id)).thenReturn(new MediaAssetRegistry.LockedReferences(Map.of()));
        when(users.findByIdForUpdate(id)).thenReturn(java.util.Optional.of(user));
        when(users.save(user)).thenReturn(user);
        doThrow(new IllegalStateException("cleanup failed")).when(maintenance).retireUnreferencedAvatar(previous, id);
        TransactionSynchronizationManager.initSynchronization();
        service().uploadAvatar(id, file);
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit());
    }

    private UserAvatarService service() { return new UserAvatarService(users, levels, images, assets, maintenance); }
    private static User user(UUID id) {
        User user = new User(); user.setId(id); user.setUsername("buyer"); user.setEmail("buyer@example.com");
        user.setRole(UserRole.USER); user.setUserNo(10000L); user.setAvatarUrl("/api/uploads/avatars/old.png"); return user;
    }
}
