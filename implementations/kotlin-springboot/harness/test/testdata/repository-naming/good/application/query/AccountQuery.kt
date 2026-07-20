package com.example.accountservice.account.application.query

/**
 * 쓰기 모델 AccountRepository와 분리된 읽기 전용 포트 — 이 주석 안의 "Repository" 언급은
 * 실제 코드 의존이 아니므로 다른 규칙들처럼 이 규칙도 주석은 무시해야 한다.
 */
interface AccountQuery {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>
}
