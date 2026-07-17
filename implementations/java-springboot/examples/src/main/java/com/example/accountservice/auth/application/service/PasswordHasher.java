package com.example.accountservice.auth.application.service;

/**
 * 비밀번호 해싱을 위한 Technical Service 인터페이스(domain-service.md 참고). 호출하는 쪽(Application 레이어)이 필요로 하는 형태로
 * 정의하며, 실제 해싱 알고리즘(BCrypt 등)에 대한 세부사항은 노출하지 않는다. 구현체는 infrastructure/ 레이어에 위치한다
 * (BCryptPasswordHasher).
 */
public interface PasswordHasher {

    String hash(String plainPassword);

    boolean verify(String plainPassword, String passwordHash);
}
