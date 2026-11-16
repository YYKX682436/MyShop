package com.myshop.config;

import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.repository.UserRepository;
import com.myshop.repository.UserNumberSequenceRepository;
import com.myshop.entity.UserNumberSequence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapAdminRunnerTest {

    @Mock
    private UserRepository userRepository;
    @Mock private UserNumberSequenceRepository userNumberSequenceRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(userNumberSequenceRepository.findByIdForUpdate(1))
                .thenReturn(new UserNumberSequence());
    }

    @Test
    void createsFixedSuperAdminFromEnvironmentWithoutEmbeddingThePassword() throws Exception {
        when(userRepository.findByUsernameIgnoreCase("muchen-admin")).thenReturn(Optional.empty());
        when(userRepository.countByRole(UserRole.SUPER_ADMIN)).thenReturn(0L);
        when(passwordEncoder.encode("valid1")).thenReturn("bcrypt-hash");

        BootstrapAdminRunner runner = new BootstrapAdminRunner(userRepository, userNumberSequenceRepository, passwordEncoder,
                "", "", "", "muchen-admin", "valid1");
        runner.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User created = captor.getValue();
        assertEquals("muchen-admin", created.getUsername());
        assertEquals(UserRole.SUPER_ADMIN, created.getRole());
        assertEquals("bcrypt-hash", created.getPasswordHash());
        assertTrue(created.getEmail() == null);
        assertTrue(created.getUserNo() == null);
    }

    @Test
    void existingFixedSuperAdminIsIdempotentAndItsPasswordIsNeverReset() throws Exception {
        User existing = new User();
        existing.setUsername("muchen-admin");
        existing.setRole(UserRole.SUPER_ADMIN);
        existing.setIsDeleted(0);
        existing.setPasswordHash("existing-hash");
        when(userRepository.findByUsernameIgnoreCase("muchen-admin")).thenReturn(Optional.of(existing));
        when(userRepository.countByRole(UserRole.SUPER_ADMIN)).thenReturn(1L);

        BootstrapAdminRunner runner = new BootstrapAdminRunner(userRepository, userNumberSequenceRepository, passwordEncoder,
                "", "", "", "muchen-admin", "valid1");
        runner.run(null);

        assertEquals("existing-hash", existing.getPasswordHash());
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void sixCharacterOrdinaryAdminPasswordIsAccepted() throws Exception {
        when(userRepository.existsByUsernameIgnoreCase("first_admin-1")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("first@example.com")).thenReturn(false);
        when(passwordEncoder.encode("abc123")).thenReturn("ordinary-hash");

        BootstrapAdminRunner runner = new BootstrapAdminRunner(userRepository, userNumberSequenceRepository, passwordEncoder,
                "first_admin-1", "FIRST@EXAMPLE.COM", "abc123");
        runner.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(UserRole.ADMIN, captor.getValue().getRole());
        assertEquals("first@example.com", captor.getValue().getEmail());
        assertEquals("ordinary-hash", captor.getValue().getPasswordHash());
    }

    @Test
    void ordinaryAdminBootstrapRejectsUsernameOutsideUserRule() {
        BootstrapAdminRunner runner = new BootstrapAdminRunner(userRepository, userNumberSequenceRepository, passwordEncoder,
                "first.admin", "first@example.com", "abc123");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> runner.run(null));

        assertTrue(exception.getMessage().contains("3-20 characters"));
        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void fixedUsernameAndPasswordConfigurationMustBeValid() {
        BootstrapAdminRunner wrongUsername = new BootstrapAdminRunner(userRepository, userNumberSequenceRepository, passwordEncoder,
                "", "", "", "another-admin", "valid1");
        IllegalStateException usernameException = assertThrows(IllegalStateException.class,
                () -> wrongUsername.run(null));
        assertTrue(usernameException.getMessage().contains("fixed account name"));

        BootstrapAdminRunner partialPassword = new BootstrapAdminRunner(userRepository, userNumberSequenceRepository, passwordEncoder,
                "", "", "", "muchen-admin", "short");
        IllegalStateException passwordException = assertThrows(IllegalStateException.class,
                () -> partialPassword.run(null));
        assertTrue(passwordException.getMessage().contains("6-72"));
    }

    @Test
    void ordinaryBootstrapDoesNotClaimAnExistingUsersPhoneAsItsUsername() throws Exception {
        when(userRepository.existsByPhone("13800138000")).thenReturn(true);
        BootstrapAdminRunner runner = new BootstrapAdminRunner(userRepository, userNumberSequenceRepository,
                passwordEncoder, "13800138000", "bootstrap@example.test", "abc123");

        runner.run(null);

        var locks = org.mockito.Mockito.inOrder(userNumberSequenceRepository, userRepository);
        locks.verify(userNumberSequenceRepository).findByIdForUpdate(1);
        locks.verify(userRepository).existsByUsernameIgnoreCase("13800138000");
        verify(userRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }
}
