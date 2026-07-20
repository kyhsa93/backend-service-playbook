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

/**
 * root `repository-pattern.md`의 `find<Noun>s` 통일 규칙에 맞춰 정의한다 —
 * [com.example.accountservice.auth.application.query.CredentialQuery]가 이 타입을 재사용해
 * `findCredentials`를 정의한다(AccountFindQuery/PaymentFindQuery와 동일한 패턴). userId는
 * 유일 키이므로 결과가 최대 1건이지만, 다른 Find Query와 동일하게 page/take를 갖는다.
 */
data class CredentialFindQuery(
    val page: Int,
    val take: Int,
    val userId: String? = null,
)
