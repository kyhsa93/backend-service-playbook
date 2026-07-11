package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.Account;

import java.util.Optional;

/**
 * Query Service 전용 읽기 인터페이스. 쓰기용 {@code AccountRepository}(domain)와 분리된 좁은 계약이다.
 * 이 Javadoc은 일부러 쓰기용 인터페이스 이름을 문서화 목적으로 언급한다 — 실제 코드(주석 제외)에는
 * Repository 참조가 없으므로 규칙을 통과해야 한다.
 */
public interface AccountQuery {
    Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId);
}
