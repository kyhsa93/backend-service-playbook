package com.example.accountservice.auth.application.service;

/**
 * A Technical Service interface for password hashing (see domain-service.md). It is defined in the
 * shape the caller (the Application layer) needs, and does not expose details of the actual hashing
 * algorithm (BCrypt, etc.). The implementation lives in the infrastructure/ layer
 * (BCryptPasswordHasher).
 */
public interface PasswordHasher {

    String hash(String plainPassword);

    boolean verify(String plainPassword, String passwordHash);
}
