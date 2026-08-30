package com.myshop.config;

import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Explicit bootstrap for the ordinary first administrator and the one fixed
 * super administrator. Plaintext passwords are read only from environment
 * properties, never persisted or logged, and an existing account is never
 * reset on a later restart.
 */
@Slf4j
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String email;
    private final String password;
    private final String superAdminUsername;
    private final String superAdminPassword;

    private static final String FIXED_SUPER_ADMIN_USERNAME = "muchen-admin";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,20}$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^[!-~]{6,72}$");

    @Autowired
    public BootstrapAdminRunner(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${bootstrap.admin.username:}") String username,
            @Value("${bootstrap.admin.email:}") String email,
            @Value("${bootstrap.admin.password:}") String password,
            @Value("${bootstrap.super-admin.username:}") String superAdminUsername,
            @Value("${bootstrap.super-admin.password:}") String superAdminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.email = email;
        this.password = password;
        this.superAdminUsername = superAdminUsername;
        this.superAdminPassword = superAdminPassword;
    }

    /** Kept for focused unit tests and callers that only exercise normal admin bootstrap. */
    public BootstrapAdminRunner(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                String username, String email, String password) {
        this(userRepository, passwordEncoder, username, email, password, "", "");
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        bootstrapOrdinaryAdmin();
        bootstrapSuperAdmin();
    }

    private void bootstrapOrdinaryAdmin() {
        if (username.isBlank() && email.isBlank() && password.isBlank()) {
            return;
        }
        if (username.isBlank() || email.isBlank() || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new IllegalStateException("Bootstrap admin requires username, email, and a 6-72 character ASCII visible password");
        }
        String normalizedUsername = username.trim();
        if (!USERNAME_PATTERN.matcher(normalizedUsername).matches()) {
            throw new IllegalStateException("Bootstrap admin username must be 3-20 characters using English letters, numbers, underscores, and hyphens");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByUsernameIgnoreCase(normalizedUsername)
                || userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            log.info("Admin bootstrap skipped because the configured account already exists.");
            return;
        }

        User admin = new User();
        admin.setUsername(normalizedUsername);
        admin.setEmail(normalizedEmail);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRole(UserRole.ADMIN);
        userRepository.save(admin);
        log.info("Created the explicitly configured bootstrap administrator '{}'.", normalizedUsername);
    }

    private void bootstrapSuperAdmin() {
        if (superAdminUsername.isBlank() && superAdminPassword.isBlank()) {
            return;
        }
        if (superAdminUsername.isBlank() || superAdminPassword.isBlank()) {
            throw new IllegalStateException("Super administrator bootstrap requires both username and password");
        }
        String normalizedUsername = superAdminUsername.trim();
        if (!FIXED_SUPER_ADMIN_USERNAME.equals(normalizedUsername)) {
            throw new IllegalStateException("Super administrator username must be the configured fixed account name");
        }
        if (!PASSWORD_PATTERN.matcher(superAdminPassword).matches()) {
            throw new IllegalStateException("Super administrator password must be 6-72 ASCII visible characters");
        }

        User configured = userRepository.findByUsernameIgnoreCase(normalizedUsername).orElse(null);
        long existingSuperAdmins = userRepository.countByRole(UserRole.SUPER_ADMIN);
        if (existingSuperAdmins > 0) {
            if (configured == null || configured.getRole() != UserRole.SUPER_ADMIN) {
                throw new IllegalStateException("A different super administrator already exists; refusing to promote or replace it");
            }
            if (configured.getIsDeleted() == 1) {
                throw new IllegalStateException("The fixed super administrator is disabled");
            }
            log.info("Super administrator bootstrap skipped because the fixed account already exists.");
            return;
        }
        if (configured != null) {
            throw new IllegalStateException("The fixed super administrator username is already used by another account");
        }

        User superAdmin = new User();
        superAdmin.setUsername(normalizedUsername);
        superAdmin.setEmail(null);
        superAdmin.setPasswordHash(passwordEncoder.encode(superAdminPassword));
        superAdmin.setRole(UserRole.SUPER_ADMIN);
        superAdmin.setUserNo(null);
        userRepository.save(superAdmin);
        log.info("Created the explicitly configured fixed super administrator.");
    }
}
