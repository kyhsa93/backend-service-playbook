package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.Transaction

/**
 * 읽기 전용 포트 — Query Service가 의존하는 좁은 인터페이스.
 *
 * root `cqrs-pattern.md`가 규정하는 `<Domain>Query` 네이밍·배치(application/query/)를 따른다.
 * 쓰기 모델([com.example.accountservice.account.domain.AccountRepository])과 분리해, Query Service가
 * save/deleteAccount 같은 쓰기 메서드에 접근하지 못하도록 컴파일 타임에 강제한다. 실제 구현체
 * (AccountRepositoryImpl)는 두 인터페이스를 모두 구현하지만, 각 Service는 자신에게 필요한
 * 인터페이스 타입으로만 주입받는다.
 */
interface AccountQuery {
    fun findByAccountIdAndOwnerId(
        accountId: String,
        ownerId: String,
    ): Account?

    fun findTransactions(
        accountId: String,
        page: Int,
        take: Int,
    ): List<Transaction>

    fun countTransactions(accountId: String): Long
}
