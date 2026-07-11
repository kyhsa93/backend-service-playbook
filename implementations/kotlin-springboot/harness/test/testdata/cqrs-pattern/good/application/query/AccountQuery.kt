package com.example.accountservice.account.application.query

/**
 * 읽기 전용 Query 인터페이스. 쓰기 모델 AccountRepository와 분리되어 있다 —
 * 이 주석 안의 "Repository" 언급은 실제 코드 의존이 아니므로 cqrs-pattern 규칙이 잡지 않아야 한다.
 */
interface AccountQuery {
    fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account?
}
