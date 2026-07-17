package com.example.accountservice.auth.domain;

/**
 * Credential Aggregate의 쓰기용 Repository 계약(domain 소유). 읽기 전용 조회는 별도의
 * application/query/CredentialQuery 인터페이스로 분리한다(cqrs-pattern.md 참고). Credential은 생성(sign-up) 이후
 * 수정되지 않는 불변 레코드이므로 save 외의 쓰기 메서드는 없다.
 */
public interface CredentialRepository {
    void saveCredential(Credential credential);
}
