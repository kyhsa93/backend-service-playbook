package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountsWithCount;
import com.example.accountservice.account.domain.TransactionsWithCount;

/**
 * Query Service 전용 읽기 인터페이스. 쓰기용 {@code AccountRepository}(domain)와 분리된 좁은 계약이다. Query Service는 이
 * 인터페이스만 의존해야 한다 — {@code saveAccount}/{@code delete} 같은 쓰기 메서드를 노출하지 않는다 (cqrs-pattern.md 참고).
 */
public interface AccountQuery {
    AccountsWithCount findAccounts(AccountFindQuery query);

    TransactionsWithCount findTransactions(String accountId, int page, int take);
}
