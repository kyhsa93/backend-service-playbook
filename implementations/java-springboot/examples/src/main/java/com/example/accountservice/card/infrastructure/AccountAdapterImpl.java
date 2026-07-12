package com.example.accountservice.card.infrastructure;

import com.example.accountservice.account.application.query.AccountQuery;
import com.example.accountservice.account.domain.AccountFindQuery;
import com.example.accountservice.account.domain.AccountStatus;
import com.example.accountservice.card.application.adapter.AccountAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link AccountAdapter}의 구현체(ACL). Account BC가 노출한 읽기 인터페이스
 * ({@link AccountQuery})를 주입받아 호출하고, Account BC의 모델을 Card BC가 쓰는 최소
 * 형태({@link AccountAdapter.AccountView})로 번역한다. Account의 Repository/도메인 객체를
 * Card의 Application·Domain 레이어로 노출하지 않는 유일한 경계 지점이다.
 *
 * <p>Account의 읽기 인터페이스는 "계좌 없음"을 예외가 아니라 {@code Optional.empty}로 표현하므로,
 * 그 신호를 Card가 이해하는 {@code Optional.empty}로 그대로 번역한다 — Account의 예외 타입이
 * Card 도메인으로 누수되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AccountAdapterImpl implements AccountAdapter {

    private final AccountQuery accountQuery;

    @Override
    public Optional<AccountView> findAccount(String accountId, String ownerId) {
        return accountQuery.findAccounts(new AccountFindQuery(0, 1, accountId, ownerId, null))
                .accounts().stream().findFirst()
                .map(account -> new AccountView(account.getAccountId(), account.getStatus() == AccountStatus.ACTIVE));
    }
}
