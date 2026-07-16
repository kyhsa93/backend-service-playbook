package com.example.accountservice.auth.application.query;

import com.example.accountservice.auth.domain.CredentialFindQuery;
import com.example.accountservice.auth.domain.CredentialsWithCount;

/**
 * Credential의 읽기 전용 조회 계약(cqrs-pattern.md의 AccountQuery/CardQuery와 동일한 역할).
 * SignUpService(아이디 중복 확인)와 SignInService(비밀번호 검증을 위한 저장된 해시 조회)
 * 두 Command Service 모두 이 Query만 사용한다 — Credential은 신규 생성 후 수정되지 않으므로
 * 두 유스케이스 모두 쓰기용 CredentialRepository가 아니라 읽기 전용 Query로 충분하다.
 */
public interface CredentialQuery {
    CredentialsWithCount findCredentials(CredentialFindQuery query);
}
