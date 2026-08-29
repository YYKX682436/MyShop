package com.myshop.config;

import com.myshop.constant.UserRole;
import com.myshop.entity.User;
import com.myshop.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Optional first-admin bootstrap. It only runs when all three explicit
 * environment variables are supplied; there is no built-in credential.
 */
@Slf4j
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String email;
    private final String password;

    public BootstrapAdminRunner(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${bootstrap.admin.username:}") String username,
            @Value("${bootstrap.admin.email:}") String email,
            @Value("${bootstrap.admin.password:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isBlank() && email.isBlank() && password.isBlank()) {
            log.info("Admin bootstrap skipped; set BOOTSTRAP_ADMIN_USERNAME/EMAIL/PASSWORD for first setup.");
            return;
        }
        if (username.isBlank() || email.isBlank() || password.length() < 12) {
            throw new IllegalStateException("Bootstrap admin requires username, email, and a password of at least 12 characters");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByUsername(username) || userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            log.info("Admin bootstrap skipped because the configured account already exists.");
            return;
        }

        User admin = new User();
        admin.setUsername(username);
        admin.setEmail(normalizedEmail);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRole(UserRole.ADMIN);
        userRepository.save(admin);
        log.info("Created the explicitly configured bootstrap administrator '{}'.", username);
    }
}
