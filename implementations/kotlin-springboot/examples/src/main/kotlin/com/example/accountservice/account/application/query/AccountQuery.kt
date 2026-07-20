package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.Account
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.Transaction
import com.example.accountservice.account.domain.TransactionFindQuery

/**
 * 읽기 전용 포트 — Query Service가 의존하는 좁은 인터페이스.
 *
 * root `cqrs-pattern.md`가 규정하는 `<Domain>Query` 네이밍·배치(application/query/)를 따른다.
 * 쓰기 모델([com.example.accountservice.account.domain.AccountRepository])과 분리해, Query Service가
 * save/deleteAccount 같은 쓰기 메서드에 접근하지 못하도록 컴파일 타임에 강제한다. 실제 구현체
 * (AccountRepositoryImpl)는 두 인터페이스를 모두 구현하지만, 각 Service는 자신에게 필요한
 * 인터페이스 타입으로만 주입받는다.
 *
 * 조회 메서드는 AccountRepository(쓰기 모델)와 정확히 같은 시그니처(`findAccounts`/`findTransactions`)를
 * 재사용한다 — root `repository-pattern.md`의 `find<Noun>s` 통일 규칙이 Query 포트에도 그대로
 * 적용된다(PaymentQuery가 PaymentRepository의 `PaymentFindQuery`를 그대로 재사용하는 것과 동일한
 * 패턴). 단건 조회는 전용 메서드를 두지 않고 `AccountFindQuery(take = 1, ...)` + `.firstOrNull()`로
 * 처리한다(`GetAccountService`/`GetTransactionsService` 참고).
 */
interface AccountQuery {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>

    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>
}
