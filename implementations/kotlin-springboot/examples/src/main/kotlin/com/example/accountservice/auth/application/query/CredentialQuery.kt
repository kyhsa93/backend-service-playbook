package com.example.accountservice.auth.application.query

import com.example.accountservice.auth.domain.Credential
import com.example.accountservice.auth.domain.CredentialFindQuery

/**
 * Credential 읽기 전용 포트 — 아이디 중복 확인(SignUpService)과 비밀번호 검증을 위한
 * 해시 조회(SignInService) 모두 이 인터페이스로 수행한다. SignInService는 자격증명을
 * 저장하지 않으므로(순수 조회 후 검증) 쓰기 모델 [com.example.accountservice.auth.domain.CredentialRepository]에는
 * 접근하지 않는다.
 *
 * 조회 메서드는 root `repository-pattern.md`의 `find<Noun>s` 통일 규칙을 따른다 — 단건 조회
 * 전용 메서드(`findByUserId` 등)를 따로 두지 않고 `CredentialFindQuery(take = 1, userId = ...)` +
 * `.first.firstOrNull()`로 처리한다(AccountQuery/CardQuery/PaymentQuery와 동일한 패턴).
 */
interface CredentialQuery {
    fun findCredentials(query: CredentialFindQuery): Pair<List<Credential>, Long>
}
