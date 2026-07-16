package com.example.accountservice.auth.domain

/**
 * Credential 쓰기 모델 포트 — 저장만 담당한다.
 *
 * 읽기(아이디 중복 확인, 비밀번호 검증을 위한 조회)는
 * [com.example.accountservice.auth.application.query.CredentialQuery]로 분리한다(cqrs-pattern.md).
 * 실제 구현체(CredentialRepositoryImpl)는 두 인터페이스를 모두 구현하지만, 각 Service는
 * 자신에게 필요한 인터페이스 타입으로만 주입받는다 — SignInService는 조회만 하므로
 * CredentialQuery만 의존하고 이 쓰기 포트에는 접근할 수 없다.
 */
interface CredentialRepository {
    fun saveCredential(credential: Credential)
}
