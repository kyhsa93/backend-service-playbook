package com.example.accountservice.auth.infrastructure;

import com.example.accountservice.auth.application.service.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * PasswordHasher(Technical Service 인터페이스)의 BCrypt 기반 구현체. 이 코드베이스에서 Service 애노테이션은 유스케이스를 조율하는
 * Application Service를 뜻하므로, infrastructure 레이어의 기술 구현체인 이 클래스는 그 대신 Component 애노테이션을 사용한다
 * (account/infrastructure/notification/NotificationServiceImpl과 동일한 규칙).
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
