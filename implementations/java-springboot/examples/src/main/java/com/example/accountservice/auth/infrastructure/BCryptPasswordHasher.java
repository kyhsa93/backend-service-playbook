package com.example.accountservice.auth.infrastructure;

import com.example.accountservice.auth.application.service.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The BCrypt-based implementation of PasswordHasher (a Technical Service interface). In this
 * codebase the Service annotation means an Application Service that orchestrates a use case, so
 * this class — a technical implementation in the infrastructure layer — uses the Component
 * annotation instead (the same rule as
 * account/infrastructure/notification/NotificationServiceImpl).
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private static final int STRENGTH = 12;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(STRENGTH);

    @Override
    public String hash(String plainPassword) {
        return encoder.encode(plainPassword);
    }

    @Override
    public boolean verify(String plainPassword, String passwordHash) {
        return encoder.matches(plainPassword, passwordHash);
    }
}
